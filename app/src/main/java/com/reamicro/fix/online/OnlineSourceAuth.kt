package com.reamicro.fix.online

import android.content.Context
import com.reamicro.fix.settings.ModuleSettings
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class OnlineSourceAuthResult(
    val success: Boolean,
    val message: String,
)

object OnlineSourceAuth {
    private const val KEY_USER_PREFIX = "online_login_user_"
    private const val KEY_PASSWORD_PREFIX = "online_login_password_"
    private const val KEY_INFO_PREFIX = "online_login_info_"
    private const val KEY_COOKIE_PREFIX = "online_login_cookie_"
    private const val KEY_COOKIE_TIME_PREFIX = "online_login_cookie_time_"

    fun supportsCredentialLogin(source: OnlineSourceEntry): Boolean =
        loginEndpoint(source) != null || loginFields(source).isNotEmpty()

    /** 存在真实登录端点时必须执行账号密码请求，不能退化成仅保存 loginUi 字段。 */
    fun usesAccountPasswordLogin(source: OnlineSourceEntry): Boolean =
        loginEndpoint(source) != null

    fun loginFields(source: OnlineSourceEntry): List<OnlineSourceLoginField> =
        OnlineSourceLoginConfig.credentialFields(source.loginUi)

    fun browserLoginUrl(source: OnlineSourceEntry): String =
        source.webLoginUrl.ifBlank { OnlineSourceLoginConfig.browserUrl(source.loginUrl) }

    fun loginInfo(context: Context?, source: OnlineSourceEntry): Map<String, String> {
        val preferences = prefs(context) ?: return emptyMap()
        val values = OnlineSourceLoginConfig.decode(
            preferences.getString(KEY_INFO_PREFIX + source.id, "").orEmpty(),
        ).toMutableMap()
        val username = preferences.getString(KEY_USER_PREFIX + source.id, "").orEmpty()
        val password = preferences.getString(KEY_PASSWORD_PREFIX + source.id, "").orEmpty()
        loginFields(source).forEach { field ->
            if (values[field.name].isNullOrBlank()) {
                legacyLoginValue(field.name, username, password)?.let { values[field.name] = it }
            }
        }
        return values
    }

    fun saveLoginInfo(
        context: Context?,
        source: OnlineSourceEntry,
        values: Map<String, String>,
    ): OnlineSourceAuthResult {
        val appContext = context?.applicationContext
            ?: return OnlineSourceAuthResult(false, "缺少 Context")
        val fields = loginFields(source)
        if (fields.isEmpty()) return OnlineSourceAuthResult(false, "该源没有可保存的登录字段")
        val normalized = fields.associate { field ->
            field.name to values[field.name].orEmpty().trim().ifBlank { field.defaultValue.trim() }
        }
        val missing = fields.filter { normalized[it.name].isNullOrBlank() }.map { it.name }
        if (missing.isNotEmpty()) {
            return OnlineSourceAuthResult(false, "请输入${missing.joinToString("、")}")
        }
        val merged = loginInfo(appContext, source).toMutableMap().apply { putAll(normalized) }
        prefs(appContext)?.edit()
            ?.putString(KEY_INFO_PREFIX + source.id, OnlineSourceLoginConfig.encode(merged))
            ?.apply()
        val label = fields.joinToString("、") { it.name }
        return OnlineSourceAuthResult(true, "已保存$label")
    }

    fun requestHeaders(context: Context?, source: OnlineSourceEntry): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        if (source.enabledCookieJar) {
            val cookie = prefs(context)?.getString(KEY_COOKIE_PREFIX + source.id, "").orEmpty()
            if (cookie.isNotBlank()) headers["Cookie"] = cookie
        }
        // 一些 JSON 书源把 API 密钥写在 header 的内联 JS 中；原先只保存了密钥，
        // 但普通 HTTP 请求没有执行这段 JS，导致详情接口持续 401，章节数无法补全。
        headers.putAll(credentialHeaders(source.header, loginInfo(context, source)))
        return headers
    }

    internal fun credentialHeaders(rawHeader: String, loginInfo: Map<String, String>): Map<String, String> {
        if (!rawHeader.contains("X-API-Key", ignoreCase = true)) return emptyMap()
        val apiKey = sequenceOf(
            loginInfo["密钥"],
            loginInfo["apiKey"],
            loginInfo["api_key"],
            loginInfo["apikey"],
            loginInfo["qq_api_key"],
        ).map { it.orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        return if (apiKey.isBlank()) emptyMap() else mapOf("X-API-Key" to apiKey)
    }

    fun hasSavedLogin(context: Context?, source: OnlineSourceEntry): Boolean {
        val prefs = prefs(context) ?: return false
        val fields = loginFields(source)
        if (!usesAccountPasswordLogin(source) && fields.isNotEmpty()) {
            val info = loginInfo(context, source)
            if (fields.all { info[it.name].orEmpty().isNotBlank() }) return true
        }
        val cookie = prefs.getString(KEY_COOKIE_PREFIX + source.id, "").orEmpty()
        val username = prefs.getString(KEY_USER_PREFIX + source.id, "").orEmpty()
        val password = prefs.getString(KEY_PASSWORD_PREFIX + source.id, "").orEmpty()
        return cookie.isNotBlank() || (username.isNotBlank() && password.isNotBlank())
    }

    fun savedCredentials(context: Context?, source: OnlineSourceEntry): Pair<String, String> {
        val preferences = prefs(context) ?: return "" to ""
        val username = preferences.getString(KEY_USER_PREFIX + source.id, "").orEmpty()
        val password = preferences.getString(KEY_PASSWORD_PREFIX + source.id, "").orEmpty()
        if (username.isNotBlank() || password.isNotBlank()) return username to password
        // 1.3.8 曾把账号密码源误分流到通用字段保存；从该位置回读，避免用户重新输入。
        return accountPasswordFromLoginInfo(loginInfo(context, source), loginFields(source))
    }

    fun clearSourceData(context: Context?, sourceId: String) {
        prefs(context)?.edit()
            ?.remove(KEY_USER_PREFIX + sourceId)
            ?.remove(KEY_PASSWORD_PREFIX + sourceId)
            ?.remove(KEY_INFO_PREFIX + sourceId)
            ?.remove(KEY_COOKIE_PREFIX + sourceId)
            ?.remove(KEY_COOKIE_TIME_PREFIX + sourceId)
            ?.apply()
    }

    fun loginWithSavedCredentials(context: Context?, source: OnlineSourceEntry): OnlineSourceAuthResult {
        val prefs = prefs(context) ?: return OnlineSourceAuthResult(false, "缺少设置存储")
        if (loginEndpoint(source) == null) {
            val fields = loginFields(source)
            if (fields.isNotEmpty()) {
                val info = loginInfo(context, source)
                val missing = fields.filter { info[it.name].orEmpty().isBlank() }
                return if (missing.isEmpty()) {
                    OnlineSourceAuthResult(true, "已读取保存的登录信息")
                } else {
                    OnlineSourceAuthResult(false, "未保存${missing.joinToString("、") { it.name }}")
                }
            }
        }
        val saved = savedCredentials(context, source)
        val username = saved.first
        val password = saved.second
        if (username.isBlank() || password.isBlank()) {
            return OnlineSourceAuthResult(false, "未保存登录账号")
        }
        return login(context, source, username, password, rememberCredentials = false)
    }

    fun login(
        context: Context?,
        source: OnlineSourceEntry,
        username: String,
        password: String,
        rememberCredentials: Boolean = true,
    ): OnlineSourceAuthResult {
        val appContext = context?.applicationContext
            ?: return OnlineSourceAuthResult(false, "缺少 Context")
        val endpoint = loginEndpoint(source)
            ?: return OnlineSourceAuthResult(false, "暂不支持该源脚本登录")
        val user = username.trim()
        if (user.isBlank() || password.isBlank()) {
            return OnlineSourceAuthResult(false, "请输入账号和密码")
        }
        return runCatching {
            val baseUrl = sourceBaseUrl(source).ifBlank { error("源缺少 bookSourceUrl") }
            val requestUrl = resolveUrl(baseUrl, endpoint)
            val payload = JSONObject()
                .put("username", user)
                .put("password", password)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = source.respondTime.coerceIn(15_000, 300_000).coerceAtMost(30_000)
                readTimeout = source.respondTime.coerceIn(15_000, 300_000)
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                parseHeaders(source.header).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                }
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(payload) }
                val code = connection.responseCode
                val body = responseBody(connection, code)
                if (code !in 200..299) {
                    return@runCatching OnlineSourceAuthResult(false, "登录失败：HTTP $code")
                }
                val root = runCatching { JSONObject(body) }.getOrNull()
                val error = root?.optString("error", "").orEmpty()
                if (error.isNotBlank() && error != "null") {
                    return@runCatching OnlineSourceAuthResult(false, "登录失败：$error")
                }
                val cookie = cookieHeader(connection)
                if (source.enabledCookieJar && cookie.isBlank()) {
                    return@runCatching OnlineSourceAuthResult(false, "登录成功但未收到 Cookie")
                }
                prefs(appContext)?.edit()
                    ?.apply {
                        if (rememberCredentials) {
                            putString(KEY_USER_PREFIX + source.id, user)
                            putString(KEY_PASSWORD_PREFIX + source.id, password)
                        }
                        putString(KEY_COOKIE_PREFIX + source.id, cookie)
                        putLong(KEY_COOKIE_TIME_PREFIX + source.id, System.currentTimeMillis())
                    }
                    ?.apply()
                OnlineSourceAuthResult(true, loginSuccessMessage(root))
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            OnlineSourceAuthResult(false, "登录失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun prefs(context: Context?) =
        context?.applicationContext?.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)

    private fun loginEndpoint(source: OnlineSourceEntry): String? {
        val loginUrl = source.loginUrl.trim()
        if (loginUrl.startsWith("http://", ignoreCase = true) || loginUrl.startsWith("https://", ignoreCase = true)) {
            return null
        }
        Regex("""['"]([^'"]*reader-auth/login[^'"]*)['"]""")
            .find(loginUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return if (source.sourceUrl.contains("whispertale", ignoreCase = true)) {
            "/reader-auth/login"
        } else {
            null
        }
    }

    private fun responseBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun cookieHeader(connection: HttpURLConnection): String =
        connection.headerFields
            .filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }
            .values
            .flatten()
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') }
            .distinctBy { it.substringBefore('=').lowercase() }
            .joinToString("; ")

    private fun loginSuccessMessage(root: JSONObject?): String {
        val user = root?.optJSONObject("user")
        val name = user?.optString("nickname", "").orEmpty()
            .ifBlank { user?.optString("username", "").orEmpty() }
            .ifBlank { "用户" }
        val active = user?.let {
            it.optBoolean("membership_permanent", false) ||
                it.optString("membership_expires_at", "").isNotBlank()
        } ?: true
        return if (active) "登录成功：$name" else "登录成功：$name，会员可能未开通或已到期"
    }

    private fun sourceBaseUrl(source: OnlineSourceEntry): String =
        Regex("""https?://[^\s#]+""").find(source.sourceUrl)?.value.orEmpty().trimEnd('/')

    private fun resolveUrl(baseUrl: String, value: String): String {
        val raw = value.trim()
        return when {
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("/") -> baseUrl.trimEnd('/') + raw
            else -> baseUrl.trimEnd('/') + "/" + raw
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { key -> json.optString(key, "") }
        }.getOrElse {
            text.lineSequence()
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null else line.take(index).trim() to line.substring(index + 1).trim()
                }
                .toMap()
        }
    }

    private fun legacyLoginValue(name: String, username: String, password: String): String? {
        val normalized = name.trim().lowercase()
        return when {
            normalized in setOf("账号", "帐号", "用户名", "用户", "邮箱", "手机号", "username", "user", "email") ->
                username.takeIf { it.isNotBlank() }
            normalized in setOf("密码", "password", "pass", "passwd") ->
                password.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    internal fun accountPasswordFromLoginInfo(
        values: Map<String, String>,
        fields: List<OnlineSourceLoginField>,
    ): Pair<String, String> {
        val username = fields.asSequence()
            .filterNot(OnlineSourceLoginField::isSecret)
            .mapNotNull { field ->
                val value = values[field.name].orEmpty().trim()
                value.takeIf { value.isNotBlank() && legacyLoginValue(field.name, value, "") != null }
            }
            .firstOrNull()
            .orEmpty()
        val password = fields.asSequence()
            .filter(OnlineSourceLoginField::isSecret)
            .map { field -> values[field.name].orEmpty() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return username to password
    }
}
