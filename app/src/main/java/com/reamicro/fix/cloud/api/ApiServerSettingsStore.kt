package com.reamicro.fix.cloud.api

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.reamicro.fix.settings.ModuleSettings
import java.nio.ByteBuffer
import java.security.KeyStore
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 保存 API 服务器配置。API Key/密码单独使用 Android Keystore 加密。 */
class ApiServerSettingsStore(private val contextProvider: () -> Context?) {
    fun get(): ApiServerSettings {
        val prefs = prefs() ?: return ApiServerSettings()
        return ApiServerSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            authMode = ApiAuthMode.fromWireValue(prefs.getString(KEY_AUTH_MODE, null)),
            apiKey = decrypt(prefs.getString(KEY_API_KEY, null)),
            accountName = prefs.getString(KEY_ACCOUNT_NAME, "").orEmpty(),
            accountPassword = decrypt(prefs.getString(KEY_ACCOUNT_PASSWORD, null)),
            hostAccountId = prefs.getString(KEY_HOST_ACCOUNT_ID, "").orEmpty(),
            allowHttp = prefs.getBoolean(KEY_ALLOW_HTTP, false),
            timeoutSeconds = prefs.getInt(KEY_TIMEOUT_SECONDS, 8).coerceIn(5, 60),
            autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
        )
    }

    fun save(settings: ApiServerSettings) {
        val normalizedUrl = settings.baseUrl.trim().removeSuffix("/")
        prefs()?.edit()
            ?.putBoolean(KEY_ENABLED, settings.enabled)
            ?.putString(KEY_BASE_URL, normalizedUrl)
            ?.putString(KEY_AUTH_MODE, settings.authMode.wireValue)
            ?.putString(KEY_API_KEY, encrypt(settings.apiKey))
            ?.putString(KEY_ACCOUNT_NAME, settings.accountName.trim())
            ?.putString(KEY_ACCOUNT_PASSWORD, encrypt(settings.accountPassword))
            ?.putString(KEY_HOST_ACCOUNT_ID, settings.hostAccountId.trim())
            ?.putBoolean(KEY_ALLOW_HTTP, settings.allowHttp)
            ?.putInt(KEY_TIMEOUT_SECONDS, settings.timeoutSeconds.coerceIn(5, 60))
            ?.putBoolean(KEY_AUTO_CHECK_UPDATES, settings.autoCheckUpdates)
            ?.commit()
    }

    fun clearCredentials() {
        prefs()?.edit()
            ?.remove(KEY_API_KEY)
            ?.remove(KEY_ACCOUNT_PASSWORD)
            ?.commit()
    }

    fun cachedMeta(): ApiServerMeta? = runCatching {
        val raw = prefs()?.getString(KEY_CACHED_META, null) ?: return null
        val json = JSONObject(raw)
        ApiServerMeta(
            apiVersion = json.optString("apiVersion"),
            serverId = json.optString("serverId"),
            features = json.optJSONArray("features").toStringSet(),
            authModes = json.optJSONArray("authModes").toStringSet().mapTo(linkedSetOf(), ApiAuthMode::fromWireValue),
            minModuleVersion = json.optString("minModuleVersion"),
            serverTime = json.optString("serverTime"),
            signingPublicKey = json.optString("signingPublicKey"),
        )
    }.getOrNull()

    fun cacheMeta(meta: ApiServerMeta) {
        val json = JSONObject()
            .put("apiVersion", meta.apiVersion)
            .put("serverId", meta.serverId)
            .put("features", JSONArray(meta.features.toList()))
            .put("authModes", JSONArray(meta.authModes.map(ApiAuthMode::wireValue)))
            .put("minModuleVersion", meta.minModuleVersion)
            .put("serverTime", meta.serverTime)
            .put("signingPublicKey", meta.signingPublicKey)
        prefs()?.edit()?.putString(KEY_CACHED_META, json.toString())?.apply()
    }

    private fun prefs(): SharedPreferences? =
        contextProvider()?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(ByteBuffer.allocate(4 + iv.size + body.size).apply {
                putInt(iv.size)
                put(iv)
                put(body)
            }.array(), Base64.NO_WRAP)
        }.getOrElse { "" }
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes)
            val ivSize = buffer.int
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(buffer::get)
            val body = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object {
        const val PREFS_NAME = "reamicro_api_server"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ACCOUNT_PASSWORD = "account_password"
        private const val KEY_HOST_ACCOUNT_ID = "host_account_id"
        private const val KEY_ALLOW_HTTP = "allow_http"
        private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_CACHED_META = "cached_meta"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "reamicro-api-server-credentials"
    }
}

private fun JSONArray?.toStringSet(): Set<String> = buildSet {
    val array = this@toStringSet ?: return@buildSet
    for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
}
