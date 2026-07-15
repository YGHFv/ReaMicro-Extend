package com.reamicro.fix.webdav

import android.util.Base64
import de.robv.android.xposed.XposedHelpers
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
    @Volatile private var httpClient: Any? = null
    private val alistTokenCache = ConcurrentHashMap<String, AlistTokenCache>()
    private val alistUnsupportedUntil = ConcurrentHashMap<String, Long>()

    fun request(
        credentials: WebDavCredentials,
        method: String,
        path: String,
        directory: Boolean = false,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebDavHttpResponse {
        val response = executeOkHttpRequest(credentials, method, path, directory, body, headers)
        return WebDavHttpResponse(
            code = response.code,
            bodyString = response.bodyString ?: "",
        )
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

    // 直接使用 PROPFIND href 解析出的绝对 URL 下载，绕过 buildUrl 的重新编码，
    // 避免请求名与服务器原始编码不一致导致的 404。
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
            val ok = upload.code in 200..299 && alistResponseOk(upload.bodyString.orEmpty())
            log("AList upload fallback path=$path code=${upload.code} ok=$ok body=${upload.bodyString.orEmpty().take(160)}")
            ok
        }.onFailure {
            log("AList upload fallback failed path=$path error=${it.message}")
        }.getOrDefault(false)

    // 部分 OpenList/AList 部署的 WebDAV 端点根 = 某个挂载点内容（如 dav 根直接是 OnedriveE5），
    // 而 AList /api/fs 命名空间需要完整挂载路径（/OnedriveE5/...）。搜索走 fs API 拿到的 path
    // 带挂载前缀，拿去请求 WebDAV GET 会 404。这里用 fs/get 拿免鉴权直链 raw_url 再下载，
    // 与搜索使用的 AList API 命名空间一致，绕过 WebDAV 路径前缀差异。成功返回 true。
    fun tryAlistDownloadFallback(
        credentials: WebDavCredentials,
        path: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean =
        runCatching {
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
            if (response.code !in 200..299 || !alistResponseOk(body)) {
                log("AList download fallback fs/get rejected path=$fsPath code=${response.code} body=${body.take(160)}")
                return false
            }
            val rawUrl = JSONObject(body).optJSONObject("data")?.optString("raw_url").orEmpty()
            if (rawUrl.isBlank()) {
                log("AList download fallback no raw_url path=$fsPath")
                return false
            }
            outputFile.delete()
            requestToFileByUrl(credentials, rawUrl, outputFile, onProgress)
            log("AList download fallback ok path=$fsPath")
            true
        }.onFailure {
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
            val endpoint = alistApiEndpoint(credentials.url) ?: return null
            val cacheKey = alistCacheKey(endpoint, credentials)
            if (isAlistUnsupported(cacheKey)) return null
            val token = cachedAlistToken(endpoint, credentials, cacheKey) ?: return null
            val results = mutableListOf<AlistSearchEntry>()
            var page = 1
            var total = Int.MAX_VALUE
            while (results.size < resultLimit && page <= maxPages) {
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
                if (!alistResponseOk(body) && root.optInt("code", 0) != 200) {
                    log("OpenList/AList search rejected body=${body.take(160)}")
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
            log("OpenList/AList search unavailable: ${it.message}")
        }.getOrNull()

    fun buildUrl(baseUrl: String, path: String, directory: Boolean = false): URL {
        val base = URI(baseUrl.trimEnd('/') + "/")
        val segments = normalizeWebDavPath(path).trim('/').split('/').filter { it.isNotBlank() }
        val encodedPath = segments.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val suffix = if (directory && encodedPath.isNotBlank()) "/" else ""
        return base.resolve(encodedPath + suffix).toURL()
    }

    // 把 PROPFIND 返回的 href（可能是绝对 URL 或以 / 开头的绝对路径）解析为可直接请求的
    // 绝对 URL，保留服务器原始百分号编码，避免解码后再编码造成的字符不一致。
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
        val decodedBase = URLDecoder.decode(basePath, "UTF-8").trimEnd('/')
        val decodedPath = URLDecoder.decode(rawPath, "UTF-8")
        val relative = if (decodedBase.isNotBlank() && decodedPath.startsWith(decodedBase)) {
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
        val requestBuilder = cls(OKHTTP_REQUEST_BUILDER_CLASS).getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        requestBuilder.javaClass.getDeclaredMethod("url", String::class.java)
            .apply { isAccessible = true }
            .invoke(requestBuilder, url)

        val token = Base64.encodeToString(
            "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        requestBuilder.javaClass.getDeclaredMethod("header", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(requestBuilder, "Authorization", "Basic $token")
        headers.forEach { (name, value) ->
            requestBuilder.javaClass.getDeclaredMethod("header", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(requestBuilder, name, value)
        }

        val requestBody = requestBodyOverride ?: body?.let { newOkHttpRequestBody(it) }
        requestBuilder.javaClass.getDeclaredMethod("method", String::class.java, cls(OKHTTP_REQUEST_BODY_CLASS))
            .apply { isAccessible = true }
            .invoke(requestBuilder, method, requestBody)
        val request = requestBuilder.javaClass.getDeclaredMethod("build")
            .apply { isAccessible = true }
            .invoke(requestBuilder)

        val client = okHttpClient()
        val call = client.javaClass.getDeclaredMethod("newCall", cls(OKHTTP_REQUEST_CLASS))
            .apply { isAccessible = true }
            .invoke(client, request)
        val response = cleartextScope.run {
            call.javaClass.getDeclaredMethod("execute")
                .apply { isAccessible = true }
                .invoke(call)
        }
        try {
            val code = response.javaClass.getDeclaredMethod("code")
                .apply { isAccessible = true }
                .invoke(response) as Int
            val bodyObj = response.javaClass.getDeclaredMethod("body")
                .apply { isAccessible = true }
                .invoke(response)
            val bodyString = if (outputFile == null) {
                bodyObj?.javaClass?.methods?.firstOrNull { it.name == "string" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.invoke(bodyObj)
                    ?.toString()
            } else {
                if (code in 200..299) {
                    val input = bodyObj?.javaClass?.methods?.firstOrNull { it.name == "byteStream" && it.parameterTypes.isEmpty() }
                        ?.apply { isAccessible = true }
                        ?.invoke(bodyObj) as? java.io.InputStream
                        ?: error("WebDAV $method response has no body")
                    val totalBytes = bodyObj.javaClass.methods.firstOrNull {
                        it.name == "contentLength" && it.parameterTypes.isEmpty()
                    }?.apply { isAccessible = true }?.invoke(bodyObj) as? Long ?: -1L
                    input.use { source ->
                        outputFile.outputStream().use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                copied += read
                                if (totalBytes > 0L) {
                                    onProgress?.invoke(((copied * 100L) / totalBytes).toInt().coerceIn(0, 100))
                                }
                            }
                            onProgress?.invoke(100)
                        }
                    }
                }
                null
            }
            log("$method response path=${normalizeWebDavPath(path)} code=$code")
            return WebDavHttpResponse(code, bodyString)
        } finally {
            runCatching {
                response.javaClass.getDeclaredMethod("close")
                    .apply { isAccessible = true }
                    .invoke(response)
            }
        }
    }

    private fun executeRawOkHttpRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        requestBodyOverride: Any? = null,
    ): WebDavHttpResponse {
        val requestBuilder = cls(OKHTTP_REQUEST_BUILDER_CLASS).getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        requestBuilder.javaClass.getDeclaredMethod("url", String::class.java)
            .apply { isAccessible = true }
            .invoke(requestBuilder, url)
        headers.forEach { (name, value) ->
            requestBuilder.javaClass.getDeclaredMethod("header", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(requestBuilder, name, value)
        }
        requestBuilder.javaClass.getDeclaredMethod("method", String::class.java, cls(OKHTTP_REQUEST_BODY_CLASS))
            .apply { isAccessible = true }
            .invoke(requestBuilder, method, requestBodyOverride)
        val request = requestBuilder.javaClass.getDeclaredMethod("build")
            .apply { isAccessible = true }
            .invoke(requestBuilder)
        val client = okHttpClient()
        val call = client.javaClass.getDeclaredMethod("newCall", cls(OKHTTP_REQUEST_CLASS))
            .apply { isAccessible = true }
            .invoke(client, request)
        val response = cleartextScope.run {
            call.javaClass.getDeclaredMethod("execute")
                .apply { isAccessible = true }
                .invoke(call)
        }
        try {
            val code = response.javaClass.getDeclaredMethod("code")
                .apply { isAccessible = true }
                .invoke(response) as Int
            val bodyObj = response.javaClass.getDeclaredMethod("body")
                .apply { isAccessible = true }
                .invoke(response)
            val bodyString = bodyObj?.javaClass?.methods?.firstOrNull {
                it.name == "string" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(bodyObj)?.toString()
            return WebDavHttpResponse(code, bodyString)
        } finally {
            runCatching {
                response.javaClass.getDeclaredMethod("close")
                    .apply { isAccessible = true }
                    .invoke(response)
            }
        }
    }

    private fun okHttpClient(): Any =
        httpClient ?: synchronized(this) {
            httpClient ?: cls(OKHTTP_CLIENT_CLASS).getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
                .also { httpClient = it }
        }

    private fun newOkHttpRequestBody(content: String): Any =
        cls(OKHTTP_REQUEST_BODY_CLASS).getDeclaredMethod(
            "create",
            String::class.java,
            cls(OKHTTP_MEDIA_TYPE_CLASS),
        ).apply { isAccessible = true }.invoke(null, content, null)
            ?: error("OkHttp RequestBody.create returned null")

    private fun newOkHttpStringRequestBody(content: String, mimeType: String): Any {
        val mediaType = cls(OKHTTP_MEDIA_TYPE_CLASS).getDeclaredMethod("get", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, mimeType)
        val requestBodyClass = cls(OKHTTP_REQUEST_BODY_CLASS)
        return requestBodyClass.declaredMethods.firstOrNull {
            it.name == "create" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java
        }?.apply { isAccessible = true }?.invoke(null, content, mediaType)
            ?: requestBodyClass.declaredMethods.first {
                it.name == "create" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == String::class.java
            }.apply { isAccessible = true }.invoke(null, mediaType, content)
    }

    private fun newOkHttpFileRequestBody(file: File, mimeType: String): Any {
        val mediaType = cls(OKHTTP_MEDIA_TYPE_CLASS).getDeclaredMethod("get", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, mimeType)
        val requestBodyClass = cls(OKHTTP_REQUEST_BODY_CLASS)
        return requestBodyClass.declaredMethods.firstOrNull {
            it.name == "create" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == File::class.java
        }?.apply { isAccessible = true }?.invoke(null, file, mediaType)
            ?: requestBodyClass.declaredMethods.first {
                it.name == "create" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == File::class.java
            }.apply { isAccessible = true }.invoke(null, mediaType, file)
    }

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
        val now = System.currentTimeMillis()
        alistTokenCache[cacheKey]?.takeIf { now < it.expiresAtMs }?.let { return it.token }
        val token = alistToken(endpoint.apiBase, credentials.username, credentials.password)
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
        "${endpoint.apiBase}|${endpoint.rootPath}|${credentials.username}|${credentials.password.hashCode()}"

    private fun isAlistUnsupported(cacheKey: String): Boolean {
        val until = alistUnsupportedUntil[cacheKey] ?: return false
        if (System.currentTimeMillis() < until) return true
        alistUnsupportedUntil.remove(cacheKey)
        return false
    }

    private fun markAlistUnsupported(cacheKey: String) {
        alistUnsupportedUntil[cacheKey] = System.currentTimeMillis() + ALIST_UNSUPPORTED_CACHE_TTL_MS
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
            log("AList login failed code=${login.code} body=${login.bodyString.orEmpty().take(160)}")
            return null
        }
        return Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(login.bodyString.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun alistResponseOk(body: String): Boolean =
        Regex(""""code"\s*:\s*200""").containsMatchIn(body) ||
            Regex(""""message"\s*:\s*"success"""", RegexOption.IGNORE_CASE).containsMatchIn(body)

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

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

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
        const val OKHTTP_CLIENT_CLASS = "okhttp3.OkHttpClient"
        const val OKHTTP_REQUEST_CLASS = "okhttp3.Request"
        const val OKHTTP_REQUEST_BUILDER_CLASS = "okhttp3.Request\$Builder"
        const val OKHTTP_REQUEST_BODY_CLASS = "okhttp3.RequestBody"
        const val OKHTTP_MEDIA_TYPE_CLASS = "okhttp3.MediaType"
        const val ALIST_TOKEN_CACHE_TTL_MS = 10 * 60_000L
        const val ALIST_UNSUPPORTED_CACHE_TTL_MS = 10 * 60_000L
    }
}
