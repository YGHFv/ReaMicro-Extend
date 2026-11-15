package com.reamicro.fix.cloud.webdav

import android.util.Base64
import android.os.SystemClock
import com.reamicro.fix.association.network.HttpRequestPolicy
import com.reamicro.fix.reload.TaskCancellation
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal data class WebDavCredentials(
    val url: String,
    val username: String,
    val password: String,
)

internal data class WebDavHttpResponse(
    val code: Int,
    val bodyString: String?,
    val headers: Map<String, String> = emptyMap(),
    val bodyBytes: ByteArray? = null,
)

internal data class AlistSearchEntry(
    val name: String,
    val path: String,
    val size: Long,
    val updatedAt: Long,
)

internal class WebDavHttpException(
    val code: Int,
    message: String,
) : IllegalStateException(message)

internal interface CleartextScope {
    fun <T> run(block: () -> T): T
}

internal class WebDavRemoteClient(
    private val classLoader: ClassLoader,
    private val log: (String) -> Unit,
    private val cleartextScope: CleartextScope,
) {
    private val alistTokenCache = ConcurrentHashMap<String, AlistTokenCache>()
    private val alistUnsupportedUntil = ConcurrentHashMap<String, Long>()
    private val alistCacheSalt = ByteArray(32).also(java.security.SecureRandom()::nextBytes)

    fun request(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        directory: Boolean = false,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebDavHttpResponse {
        val response = executeOkHttpRequest(credentials, method, path, directory, body, headers)
        return response.copy(bodyString = response.bodyString.orEmpty())
    }

    fun requestToFile(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        val response = executeOkHttpRequest(credentials, method, path, false, null, emptyMap(), outputFile, onProgress)
        if (response.code !in 200..299) {
            error("WebDAV $method failed: HTTP ${response.code}")
        }
    }

    fun ensureCollection(credentials: WebDavCredentials, path: String) {
        val normalized = normalizeWebDavPath(path)
        val created = request(credentials, "MKCOL", normalized, directory = true)
        if (created.code in 200..299) return
        if (created.code != 405) throw WebDavHttpException(created.code, "WebDAV MKCOL failed: HTTP ${created.code}")
        val existing = request(
            credentials, "PROPFIND", normalized, directory = true,
            body = WebDavMultistatus.COLLECTION_QUERY,
            headers = mapOf("Depth" to "0", "Content-Type" to "application/xml; charset=utf-8"),
        )
        if (existing.code !in 200..299) {
            throw WebDavHttpException(existing.code, "WebDAV collection check failed: HTTP ${existing.code}")
        }
        check(WebDavMultistatus.parse(existing.bodyString.orEmpty()).any {
            it.isDirectory && normalizeWebDavPath(pathFromHref(credentials.url, it.href)) == normalized
        }) { "WebDAV MKCOL 未创建目录，目标也不是已存在的目录" }
    }

    fun list(credentials: WebDavCredentials, path: String): List<WebDavListedResource> {
        TaskCancellation.checkCurrent()
        val current = normalizeWebDavPath(path.ifBlank { "/" })
        val response = request(
            credentials, "PROPFIND", current, directory = true,
            body = WebDavMultistatus.DIRECTORY_QUERY,
            headers = mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
        )
        if (response.code !in 200..299) {
            throw WebDavHttpException(response.code, "WebDAV PROPFIND failed: HTTP ${response.code}")
        }
        return WebDavMultistatus.parse(response.bodyString.orEmpty()).mapNotNull { resource ->
            TaskCancellation.checkCurrent()
            val entryPath = normalizeWebDavPath(pathFromHref(credentials.url, resource.href))
            if (entryPath == current || entryPath.substringBeforeLast('/').ifBlank { "/" } != current) {
                return@mapNotNull null
            }
            val name = resource.displayName.ifBlank { entryPath.substringAfterLast('/') }
            if (name.isBlank() || name.startsWith(".")) return@mapNotNull null
            WebDavListedResource(name, entryPath, resource.isDirectory, resource.size,
                parseWebDavDate(resource.lastModified), resource.href)
        }.distinctBy { it.path }
    }

    fun requestToFileByUrl(
        credentials: WebDavCredentials,
        absoluteUrl: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        val response = executeOkHttpRequest(
            credentials = credentials,
            method = "GET",
            path = absoluteUrl,
            directory = false,
            body = null,
            headers = emptyMap(),
            outputFile = outputFile,
            onProgress = onProgress,
            absoluteUrl = absoluteUrl,
        )
        if (response.code !in 200..299) {
            error("WebDAV GET failed: HTTP ${response.code}")
        }
    }

    fun putFile(
        credentials: WebDavCredentials,
        path: String,
        file: File,
        contentType: String,
    ): WebDavHttpResponse =
        executeOkHttpRequest(
            credentials = credentials,
            method = "PUT",
            path = path,
            directory = false,
            body = null,
            headers = mapOf(
                "Content-Type" to contentType,
                "Overwrite" to "T",
            ),
            requestBodyOverride = newOkHttpFileRequestBody(file, contentType),
        )

    fun tryAlistUploadFallback(
        credentials: WebDavCredentials,
        path: String,
        file: File,
        contentType: String,
    ): Boolean =
        runCatching {
            TaskCancellation.checkCurrent()
            val endpoint = alistApiEndpoint(credentials.url) ?: return false
            val cacheKey = alistCacheKey(endpoint, credentials)
            if (isAlistUnsupported(cacheKey)) return false
            val token = cachedAlistToken(endpoint, credentials, cacheKey) ?: return false
            val upload = executeRawOkHttpRequest(
                url = "${endpoint.apiBase}/fs/put",
                method = "PUT",
                headers = mapOf(
                    "Authorization" to token,
                    "File-Path" to childWebDavPath(endpoint.rootPath, normalizeWebDavPath(path).trimStart('/')),
                    "As-Task" to "false",
                    "Content-Type" to contentType,
                ),
                requestBodyOverride = newOkHttpFileRequestBody(file, contentType),
            )
            val ok = upload.code in 200..299 && AlistProtocol.success(upload.bodyString.orEmpty())
            log("AList upload fallback path=$path code=${upload.code} ok=$ok")
            ok
        }.onFailure {
            TaskCancellation.rethrowIfCancelled(it)
            log("AList upload fallback failed path=$path error=${it.message}")
        }.getOrDefault(false)

    fun tryAlistDownloadFallback(
        credentials: WebDavCredentials,
        path: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean =
        runCatching {
            TaskCancellation.checkCurrent()
            val endpoint = alistApiEndpoint(credentials.url) ?: return false
            val cacheKey = alistCacheKey(endpoint, credentials)
            if (isAlistUnsupported(cacheKey)) return false
            val token = cachedAlistToken(endpoint, credentials, cacheKey) ?: return false
            val fsPath = childWebDavPath(endpoint.rootPath, normalizeWebDavPath(path).trimStart('/'))
            val response = executeRawOkHttpRequest(
                url = "${endpoint.apiBase}/fs/get",
                method = "POST",
                headers = mapOf(
                    "Authorization" to token,
                    "Content-Type" to "application/json",
                ),
                requestBodyOverride = newOkHttpStringRequestBody(
                    JSONObject().put("path", fsPath).put("password", "").toString(),
                    "application/json; charset=utf-8",
                ),
            )
            val body = response.bodyString.orEmpty()
            if (response.code !in 200..299 || !AlistProtocol.success(body)) {
                log("AList download fallback fs/get rejected path=$fsPath code=${response.code}")
                return false
            }
            val rawUrl = JSONObject(body).optJSONObject("data")?.optString("raw_url").orEmpty()
            if (rawUrl.isBlank()) {
                log("AList download fallback no raw_url path=$fsPath")
                return false
            }
            TaskCancellation.checkCurrent()
            outputFile.delete()
            requestToFileByUrl(credentials, rawUrl, outputFile, onProgress)
            log("AList download fallback ok path=$fsPath")
            true
        }.onFailure {
            TaskCancellation.rethrowIfCancelled(it)
            log("AList download fallback failed path=$path error=${it.message}")
        }.getOrDefault(false)

    fun searchAlistBooks(
        credentials: WebDavCredentials,
        query: String,
        resultLimit: Int,
        pageSize: Int,
        maxPages: Int,
    ): List<AlistSearchEntry>? =
        runCatching {
            TaskCancellation.checkCurrent()
            val endpoint = alistApiEndpoint(credentials.url) ?: return null
            val cacheKey = alistCacheKey(endpoint, credentials)
            if (isAlistUnsupported(cacheKey)) return null
            val token = cachedAlistToken(endpoint, credentials, cacheKey) ?: return null
            val results = mutableListOf<AlistSearchEntry>()
            var page = 1
            var total = Int.MAX_VALUE
            while (results.size < resultLimit && page <= maxPages) {
                TaskCancellation.checkCurrent()
                val response = executeRawOkHttpRequest(
                    url = "${endpoint.apiBase}/fs/search",
                    method = "POST",
                    headers = mapOf(
                        "Authorization" to token,
                        "Content-Type" to "application/json",
                    ),
                    requestBodyOverride = newOkHttpStringRequestBody(
                        JSONObject()
                            .put("parent", endpoint.rootPath)
                            .put("keywords", query)
                            .put("scope", 0)
                            .put("page", page)
                            .put("per_page", pageSize)
                            .put("password", "")
                            .toString(),
                        "application/json; charset=utf-8",
                    ),
                )
                if (response.code !in 200..299) {
                    log("OpenList/AList search failed code=${response.code} body=${response.bodyString.orEmpty().take(160)}")
                    markAlistUnsupported(cacheKey)
                    return null
                }
                val body = response.bodyString.orEmpty()
                val root = JSONObject(body)
                if (!AlistProtocol.success(body)) {
                    log("OpenList/AList search rejected code=${root.optInt("code", -1)}")
                    markAlistUnsupported(cacheKey)
                    return null
                }
                val data = root.optJSONObject("data") ?: run {
                    markAlistUnsupported(cacheKey)
                    return null
                }
                total = data.optInt("total", total)
                val content = data.optJSONArray("content") ?: data.optJSONArray("files") ?: JSONArray()
                if (content.length() == 0) break
                for (index in 0 until content.length()) {
                    TaskCancellation.checkCurrent()
                    val item = content.optJSONObject(index) ?: continue
                    val entry = alistSearchEntry(item, endpoint.rootPath) ?: continue
                    results.add(entry)
                    if (results.size >= resultLimit) break
                }
                if (page * pageSize >= total) break
                page += 1
            }
            results
        }.onFailure {
            TaskCancellation.rethrowIfCancelled(it)
            log("OpenList/AList search unavailable: ${it.message}")
        }.getOrNull()

    fun buildUrl(baseUrl: String, path: String, directory: Boolean = false): URL {
        val base = URI(WebDavSyncProtocol.baseUrl(baseUrl) + "/")
        val segments = normalizeWebDavPath(path).trim('/').split('/').filter { it.isNotBlank() }
        require(segments.none { it == "." || it == ".." }) { "WebDAV 路径不能越过根目录" }
        val encodedPath = segments.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val suffix = if (directory && encodedPath.isNotBlank()) "/" else ""
        return base.resolve(encodedPath + suffix).toURL()
    }

    fun absoluteUrlFromHref(baseUrl: String, href: String): String =
        runCatching {
            val base = URI(baseUrl.trimEnd('/') + "/")
            base.resolve(URI(href)).toString()
        }.getOrElse { href }

    fun pathFromHref(baseUrl: String, href: String): String {
        val basePath = runCatching { URI(baseUrl).rawPath.orEmpty() }.getOrDefault("")
        val rawPath = runCatching { URI(href).rawPath }.getOrNull()
            ?: runCatching { URL(href).path }.getOrNull()
            ?: href
        // A WebDAV href is a URI path; a literal plus must not decode as a form-space.
        val decodedBase = URLDecoder.decode(basePath.replace("+", "%2B"), "UTF-8").trimEnd('/')
        val decodedPath = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        val relative = if (decodedBase.isNotBlank() &&
            (decodedPath == decodedBase || decodedPath.startsWith("$decodedBase/"))) {
            decodedPath.removePrefix(decodedBase)
        } else {
            decodedPath
        }
        return relative.ifBlank { "/" }
    }

    fun parseWebDavDate(value: String): Long =
        runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(value)?.time ?: 0L
        }.getOrDefault(0L)

    private val nativeHttp by lazy { NativeHostHttp(classLoader, cleartextScope) }

    private fun executeOkHttpRequest(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        directory: Boolean,
        body: String?,
        headers: Map<String, String>,
        outputFile: File? = null,
        onProgress: ((Int) -> Unit)? = null,
        requestBodyOverride: Any? = null,
        absoluteUrl: String? = null,
    ): WebDavHttpResponse {
        val url = absoluteUrl ?: buildUrl(credentials.url, path, directory).toString()
        HttpRequestPolicy.uri(url)
        val authorization = if (HttpRequestPolicy.sameOrigin(credentials.url, url) &&
            (credentials.username.isNotEmpty() || credentials.password.isNotEmpty())) {
            val token = Base64.encodeToString("${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            mapOf("Authorization" to "Basic $token")
        } else emptyMap()
        val payload = requestBodyOverride ?: body?.let { nativeHttp.stringBody(it, "application/xml; charset=utf-8") }
        return nativeHttp.request(url, method, authorization + headers, payload, outputFile, onProgress)
    }

    private fun executeRawOkHttpRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        requestBodyOverride: Any? = null,
    ): WebDavHttpResponse = nativeHttp.request(url, method, headers, requestBodyOverride)

    private fun newOkHttpStringRequestBody(content: String, mimeType: String): Any = nativeHttp.stringBody(content, mimeType)
    private fun newOkHttpFileRequestBody(file: File, mimeType: String): Any = nativeHttp.fileBody(file, mimeType)

    private fun alistApiEndpoint(webDavUrl: String): AlistApiEndpoint? =
        runCatching {
            val uri = URI(webDavUrl.trimEnd('/'))
            val path = uri.path.orEmpty().trimEnd('/')
            val marker = "/dav"
            val markerIndex = path.lowercase(Locale.ROOT).lastIndexOf(marker)
            if (markerIndex < 0) return null
            val afterMarkerIndex = markerIndex + marker.length
            if (path.length > afterMarkerIndex && path[afterMarkerIndex] != '/') return null
            val apiPath = path.substring(0, markerIndex).ifBlank { "" } + "/api"
            val rootPath = path.substring(afterMarkerIndex).ifBlank { "/" }
            AlistApiEndpoint(
                apiBase = URI(uri.scheme, uri.userInfo, uri.host, uri.port, apiPath, null, null).toString().trimEnd('/'),
                rootPath = normalizeWebDavPath(rootPath),
            )
        }.getOrNull()

    private fun cachedAlistToken(endpoint: AlistApiEndpoint, credentials: WebDavCredentials, cacheKey: String): String? {
        TaskCancellation.checkCurrent()
        val now = SystemClock.elapsedRealtime()
        alistTokenCache[cacheKey]?.takeIf { now < it.expiresAtMs }?.let { return it.token }
        val token = alistToken(endpoint.apiBase, credentials.username, credentials.password)
        TaskCancellation.checkCurrent()
        if (token.isNullOrBlank()) {
            markAlistUnsupported(cacheKey)
            return null
        }
        alistTokenCache[cacheKey] = AlistTokenCache(
            token = token,
            expiresAtMs = now + ALIST_TOKEN_CACHE_TTL_MS,
        )
        alistUnsupportedUntil.remove(cacheKey)
        return token
    }

    private fun alistCacheKey(endpoint: AlistApiEndpoint, credentials: WebDavCredentials): String =
        AlistProtocol.sessionKey(alistCacheSalt, endpoint.apiBase, endpoint.rootPath, credentials.username, credentials.password)

    private fun isAlistUnsupported(cacheKey: String): Boolean {
        val until = alistUnsupportedUntil[cacheKey] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        alistUnsupportedUntil.remove(cacheKey, until)
        return false
    }

    private fun markAlistUnsupported(cacheKey: String) {
        TaskCancellation.checkCurrent()
        alistUnsupportedUntil[cacheKey] = SystemClock.elapsedRealtime() + ALIST_UNSUPPORTED_CACHE_TTL_MS
        alistTokenCache.remove(cacheKey)
    }

    private fun alistToken(apiBase: String, username: String, password: String): String? {
        val login = executeRawOkHttpRequest(
            url = "$apiBase/auth/login",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            requestBodyOverride = newOkHttpStringRequestBody(
                """{"username":${username.jsonQuote()},"password":${password.jsonQuote()}}""",
                "application/json; charset=utf-8",
            ),
        )
        if (login.code !in 200..299) {
            log("AList login failed code=${login.code}")
            return null
        }
        return AlistProtocol.token(login.bodyString.orEmpty())
    }

    private fun alistSearchEntry(item: JSONObject, rootPath: String): AlistSearchEntry? {
        val name = item.optString("name", "").trim()
        if (name.isBlank() || name.startsWith(".")) return null
        val isDirectory = item.optBoolean("is_dir", item.optBoolean("isDir", false))
        if (isDirectory) return null
        val rawPath = item.optString("path", "").trim()
        val parent = item.optString("parent", "").trim()
        val path = when {
            rawPath.isNotBlank() && rawPath.substringAfterLast('/') == name -> rawPath
            rawPath.isNotBlank() -> childWebDavPath(rawPath, name)
            parent.isNotBlank() -> childWebDavPath(parent, name)
            else -> childWebDavPath("/", name)
        }
        val relativePath = alistAbsoluteToWebDavPath(path, rootPath) ?: return null
        return AlistSearchEntry(
            name = name,
            path = relativePath,
            size = item.optLong("size", 0L),
            updatedAt = parseAlistDate(
                item.optString("modified", "").ifBlank { item.optString("updated_at", "") },
            ),
        )
    }

    private fun alistAbsoluteToWebDavPath(path: String, rootPath: String): String? {
        val normalizedPath = normalizeWebDavPath(path)
        val normalizedRoot = normalizeWebDavPath(rootPath)
        if (normalizedRoot == "/") return normalizedPath
        return when {
            normalizedPath == normalizedRoot -> "/"
            normalizedPath.startsWith(normalizedRoot.trimEnd('/') + "/") ->
                normalizeWebDavPath(normalizedPath.removePrefix(normalizedRoot))
            else -> null
        }
    }

    private fun childWebDavPath(parent: String, name: String): String {
        val cleanName = name.trim().trim('/')
        return normalizeWebDavPath("${normalizeWebDavPath(parent).trimEnd('/')}/$cleanName")
    }

    private fun normalizeWebDavPath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank() || trimmed == "root") return "/"
        return "/" + trimmed.trim('/').replace(Regex("/{2,}"), "/")
    }

    private fun parseAlistDate(value: String): Long {
        val text = value.trim()
        if (text.isBlank()) return 0L
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }
            .getOrElse {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(text)?.time ?: 0L
                }.getOrElse {
                    runCatching {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(text)?.time ?: 0L
                    }.getOrDefault(0L)
                }
            }
    }

    private fun String.jsonQuote(): String =
        JSONObject.quote(this)

    private data class AlistApiEndpoint(
        val apiBase: String,
        val rootPath: String,
    )

    private data class AlistTokenCache(
        val token: String,
        val expiresAtMs: Long,
    )

    private companion object {
        const val ALIST_TOKEN_CACHE_TTL_MS = 10 * 60_000L
        const val ALIST_UNSUPPORTED_CACHE_TTL_MS = 10 * 60_000L
    }
}
