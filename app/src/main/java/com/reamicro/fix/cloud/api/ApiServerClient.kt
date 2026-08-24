package com.reamicro.fix.cloud.api

import com.reamicro.fix.association.network.HttpClient
import org.json.JSONObject
import java.io.File

/** API 服务器最小客户端。所有异常都转成结果，调用方可安全降级到本地能力。 */
class ApiServerClient(private val settingsStore: ApiServerSettingsStore) {
    fun probe(): ApiServerProbeResult = probe(settingsStore.get())

    fun probe(settings: ApiServerSettings): ApiServerProbeResult {
        if (!settings.enabled) return ApiServerProbeResult(false, "API 服务器未启用")
        return runCatching {
            val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
            val headers = authHeaders(settings)
            val body = HttpClient.get(
                "$baseUrl/v1/meta",
                headers = headers,
                connectTimeoutMs = settings.timeoutSeconds * 1_000,
                readTimeoutMs = settings.timeoutSeconds * 1_000,
                followRedirects = false,
            )
            parseMeta(JSONObject(body)).also(settingsStore::cacheMeta).let { ApiServerProbeResult(true, "连接成功", it) }
        }.getOrElse { ApiServerProbeResult(false, it.message ?: "连接失败") }
    }

    fun health(): Boolean = runCatching {
        val settings = settingsStore.get()
        if (!settings.enabled) return false
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        HttpClient.get("$baseUrl/v1/health", authHeaders(settings), settings.timeoutSeconds * 1_000, settings.timeoutSeconds * 1_000)
        true
    }.getOrDefault(false)

    private fun authHeaders(settings: ApiServerSettings): Map<String, String> = buildMap {
        put("Accept", "application/json")
        when (settings.authMode) {
            ApiAuthMode.API_KEY -> if (settings.apiKey.isNotBlank()) put("X-ReaMicro-Api-Key", settings.apiKey)
            ApiAuthMode.ACCOUNT -> if (settings.accountName.isNotBlank()) {
                put("X-ReaMicro-Account", settings.accountName)
                put("X-ReaMicro-Password", settings.accountPassword)
            }
            ApiAuthMode.HOST_ACCOUNT_ALLOWLIST -> if (settings.hostAccountId.isNotBlank()) {
                put("X-ReaMicro-Host-Account-Id", settings.hostAccountId)
            }
            ApiAuthMode.PUBLIC -> Unit
        }
    }

    private fun parseMeta(json: JSONObject): ApiServerMeta {
        val data = json.optJSONObject("data") ?: json
        val features = buildSet {
            val array = data.optJSONArray("features") ?: return@buildSet
            for (index in 0 until array.length()) add(array.optString(index))
        }.filterTo(linkedSetOf()) { it.isNotBlank() }
        val authModes = buildSet {
            val array = data.optJSONArray("authModes") ?: return@buildSet
            for (index in 0 until array.length()) add(ApiAuthMode.fromWireValue(array.optString(index)))
        }
        return ApiServerMeta(
            apiVersion = data.optString("apiVersion"),
            serverId = data.optString("serverId"),
            features = features,
            authModes = authModes,
            minModuleVersion = data.optString("minModuleVersion"),
            serverTime = json.optString("serverTime", data.optString("serverTime")),
            signingPublicKey = data.optString("signingPublicKey"),
        )
    }

    fun packages(kind: ApiPackageKind): List<ApiContentPackage> {
        val settings = settingsStore.get()
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        val body = HttpClient.get(
            "$baseUrl/v1/packages?kind=${java.net.URLEncoder.encode(kind.wireValue, "UTF-8")}",
            authHeaders(settings),
            settings.timeoutSeconds * 1_000,
            settings.timeoutSeconds * 1_000,
            false,
        )
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: root
        val items = data.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let(ApiContentPackage::fromJson)
        }
    }

    fun download(contentPackage: ApiContentPackage): ByteArray {
        val settings = settingsStore.get()
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        val target = when {
            contentPackage.downloadUrl.startsWith("/") -> baseUrl + contentPackage.downloadUrl
            contentPackage.downloadUrl.startsWith("https://") || contentPackage.downloadUrl.startsWith("http://") -> {
                val base = java.net.URI(baseUrl)
                val download = java.net.URI(contentPackage.downloadUrl)
                require(base.scheme == download.scheme && base.host == download.host && base.port == download.port) {
                    "内容包下载地址与 API 服务器不同源"
                }
                contentPackage.downloadUrl
            }
            else -> error("内容包下载地址无效")
        }
        return HttpClient.getBytes(
            target,
            authHeaders(settings),
            settings.timeoutSeconds * 1_000,
            settings.timeoutSeconds * 1_000,
            false,
        )
    }

    fun latestModuleRelease(): ApiModuleRelease? {
        val settings = settingsStore.get()
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        val root = JSONObject(HttpClient.get(
            "$baseUrl/v1/releases/module/latest",
            authHeaders(settings),
            settings.timeoutSeconds * 1_000,
            settings.timeoutSeconds * 1_000,
            false,
        ))
        return ApiModuleRelease.fromJson(root.optJSONObject("data") ?: return null)
    }

    fun downloadModuleRelease(release: ApiModuleRelease): ByteArray {
        val settings = settingsStore.get()
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        val target = if (release.apkUrl.startsWith("/")) baseUrl + release.apkUrl else release.apkUrl
        val bytes = HttpClient.getBytes(
            target,
            authHeaders(settings),
            settings.timeoutSeconds * 1_000,
            maxOf(settings.timeoutSeconds * 1_000, 60_000),
            false,
        )
        if (release.sha256.isNotBlank()) {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            require(digest == release.sha256) { "模块 APK 摘要校验失败" }
        }
        return bytes
    }

    fun downloadModuleReleaseToCache(context: android.content.Context, release: ApiModuleRelease): File {
        val root = File(context.cacheDir, ModuleApkContentProvider.MODULE_APK_DIR).apply { mkdirs() }
        val target = File(root, "ReaMicro-Extend-${release.versionName}.apk")
        val temp = File(root, ".${target.name}.tmp")
        temp.writeBytes(downloadModuleRelease(release))
        require(temp.renameTo(target)) { "模块 APK 缓存写入失败" }
        return target
    }

    fun uploadModuleBackup(bytes: ByteArray): String {
        val settings = settingsStore.get()
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        return HttpClient.postBytes(
            "$baseUrl/v1/backups/module",
            bytes,
            contentType = "application/zip",
            headers = authHeaders(settings),
            connectTimeoutMs = settings.timeoutSeconds * 1_000,
            readTimeoutMs = 60_000,
        )
    }

    fun downloadModuleBackup(): ByteArray {
        val settings = settingsStore.get()
        val baseUrl = normalizeApiBaseUrl(settings.baseUrl, settings.allowHttp)
        return HttpClient.getBytes(
            "$baseUrl/v1/backups/module/latest",
            headers = authHeaders(settings),
            connectTimeoutMs = settings.timeoutSeconds * 1_000,
            readTimeoutMs = 60_000,
            followRedirects = false,
        )
    }
}
