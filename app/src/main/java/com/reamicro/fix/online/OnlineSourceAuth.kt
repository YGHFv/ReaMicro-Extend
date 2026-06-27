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
    private const val KEY_COOKIE_PREFIX = "online_login_cookie_"
    private const val KEY_COOKIE_TIME_PREFIX = "online_login_cookie_time_"

    fun supportsCredentialLogin(source: OnlineSourceEntry): Boolean =
        loginEndpoint(source) != null

    fun requestHeaders(context: Context?, source: OnlineSourceEntry): Map<String, String> {
        if (!source.enabledCookieJar) return emptyMap()
        val cookie = prefs(context)?.getString(KEY_COOKIE_PREFIX + source.id, "").orEmpty()
        return if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
    }

    fun loginWithSavedCredentials(context: Context?, source: OnlineSourceEntry): OnlineSourceAuthResult {
        val prefs = prefs(context) ?: return OnlineSourceAuthResult(false, "缺少设置存储")
        val username = prefs.getString(KEY_USER_PREFIX + source.id, "").orEmpty()
        val password = prefs.getString(KEY_PASSWORD_PREFIX + source.id, "").orEmpty()
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
}
