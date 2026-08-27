package com.reamicro.fix.cloud.api

/** API 服务器认证方式。字符串值会写入设置，保持向后兼容。 */
enum class ApiAuthMode(val wireValue: String) {
    PUBLIC("public"),
    API_KEY("api_key"),
    ACCOUNT("account"),
    HOST_ACCOUNT_ALLOWLIST("host_account_allowlist"),
    ;

    companion object {
        fun fromWireValue(value: String?): ApiAuthMode =
            entries.firstOrNull { it.wireValue == value } ?: PUBLIC
    }
}

/**
 * 模块更新渠道。服务器把 GitHub 预发布 Release 标成 beta，正式 Release 标成 stable，
 * 并且只在请求 stable 时拒绝 beta 版本，所以 BETA 表示"预发布和正式版都接收"。
 * 本模块的 CI 发布的全部是预发布，因此默认值必须是 BETA，否则更新检查一定拿到 404。
 */
enum class ApiUpdateChannel(val wireValue: String) {
    STABLE("stable"),
    BETA("beta"),
    ;

    companion object {
        fun fromWireValue(value: String?): ApiUpdateChannel =
            entries.firstOrNull { it.wireValue == value } ?: BETA
    }
}

data class ApiServerSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val authMode: ApiAuthMode = ApiAuthMode.PUBLIC,
    val apiKey: String = "",
    val accountName: String = "",
    val accountPassword: String = "",
    val hostAccountId: String = "",
    val allowHttp: Boolean = false,
    val timeoutSeconds: Int = 8,
    val autoCheckUpdates: Boolean = true,
    val updateChannel: ApiUpdateChannel = ApiUpdateChannel.BETA,
)

data class ApiServerMeta(
    val apiVersion: String,
    val serverId: String,
    val features: Set<String>,
    val authModes: Set<ApiAuthMode>,
    val minModuleVersion: String,
    val serverTime: String,
    val signingPublicKey: String = "",
    val authRequired: Boolean = false,
)

data class ApiServerProbeResult(
    val success: Boolean,
    val message: String,
    val meta: ApiServerMeta? = null,
    val resolvedAuthMode: ApiAuthMode? = null,
)

internal fun selectApiAuthMode(meta: ApiServerMeta, settings: ApiServerSettings): ApiAuthMode? {
    if (meta.authModes == setOf(ApiAuthMode.PUBLIC)) return ApiAuthMode.PUBLIC
    if (ApiAuthMode.HOST_ACCOUNT_ALLOWLIST in meta.authModes && settings.hostAccountId.isNotBlank()) {
        return ApiAuthMode.HOST_ACCOUNT_ALLOWLIST
    }
    if (settings.authMode in meta.authModes && when (settings.authMode) {
            ApiAuthMode.API_KEY -> settings.apiKey.isNotBlank()
            ApiAuthMode.ACCOUNT -> settings.accountName.isNotBlank() && settings.accountPassword.isNotBlank()
            ApiAuthMode.HOST_ACCOUNT_ALLOWLIST -> settings.hostAccountId.isNotBlank()
            ApiAuthMode.PUBLIC -> true
        }
    ) return settings.authMode
    if (ApiAuthMode.API_KEY in meta.authModes && settings.apiKey.isNotBlank()) return ApiAuthMode.API_KEY
    if (ApiAuthMode.ACCOUNT in meta.authModes && settings.accountName.isNotBlank() && settings.accountPassword.isNotBlank()) return ApiAuthMode.ACCOUNT
    return meta.authModes.singleOrNull()
}

internal fun normalizeApiBaseUrl(raw: String, allowHttp: Boolean = false): String {
    val value = raw.trim().removeSuffix("/")
    require(value.isNotBlank()) { "服务器地址不能为空" }
    val uri = java.net.URI(value)
    require(uri.userInfo == null) { "服务器地址不能包含账号密码" }
    require(uri.host != null) { "服务器地址无效" }
    require(uri.scheme == "https" || (allowHttp && uri.scheme == "http")) {
        "公网服务器必须使用 HTTPS"
    }
    require(uri.query == null && uri.fragment == null) { "服务器地址不能包含查询参数" }
    return value
}
