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
)

data class ApiServerMeta(
    val apiVersion: String,
    val serverId: String,
    val features: Set<String>,
    val authModes: Set<ApiAuthMode>,
    val minModuleVersion: String,
    val serverTime: String,
    val signingPublicKey: String = "",
)

data class ApiServerProbeResult(
    val success: Boolean,
    val message: String,
    val meta: ApiServerMeta? = null,
)

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
