package com.reamicro.fix.cloud.api

import android.content.Context
import android.util.Base64
import com.reamicro.fix.settings.ModuleSettings
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** 客户端口令加密的账号密钥备份；服务器只接触密文。 */
object CredentialBackupCrypto {
    private const val PREFIX = "RCRED1\n"

    fun create(context: Context, password: CharArray): ByteArray {
        require(password.size >= 8) { "备份密码至少 8 位" }
        val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val values = JSONObject()
        prefs.all.filterKeys { key ->
            key.startsWith("online_login_") || key.startsWith("online_source_variable_")
        }.forEach { (key, value) -> values.put(key, value) }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(values.toString().toByteArray(Charsets.UTF_8))
        return (PREFIX + JSONObject()
            .put("version", 1)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(body, Base64.NO_WRAP))
            .toString()).toByteArray(Charsets.UTF_8)
    }

    fun restore(context: Context, password: CharArray, bytes: ByteArray): Int {
        require(password.size >= 8) { "备份密码至少 8 位" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.startsWith(PREFIX)) { "密钥备份格式无效" }
        val json = JSONObject(text.removePrefix(PREFIX))
        require(json.optInt("version") == 1) { "不支持的密钥备份版本" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            derive(password, Base64.decode(json.getString("salt"), Base64.NO_WRAP)),
            GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)),
        )
        val values = JSONObject(String(cipher.doFinal(Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8))
        val editor = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
        var count = 0
        values.keys().forEach { key ->
            val value = values.get(key)
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Number -> editor.putLong(key, value.toLong())
                else -> editor.putString(key, value.toString())
            }
            count++
        }
        require(editor.commit()) { "密钥恢复写入失败" }
        return count
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 120_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
