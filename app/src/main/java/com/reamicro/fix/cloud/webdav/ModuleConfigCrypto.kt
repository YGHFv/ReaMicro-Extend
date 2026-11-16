package com.reamicro.fix.cloud.webdav

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 随机盐和 IV 的账号加密信封，认证失败时绝不返回可应用的配置。 */
internal object ModuleConfigCrypto {
    private val magic = "RMCONFIG1".toByteArray(Charsets.US_ASCII)
    const val MAX_BYTES = 32 * 1024 * 1024
    fun accountKey(id: Long): String {
        require(id > 0) { "请先登录阅微账号" }
        return digest("reamicro-module-config-account-v1:$id".toByteArray(Charsets.UTF_8))
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun key(id: Long, salt: ByteArray): SecretKeySpec {
        require(id > 0) { "请先登录阅微账号" }
        val password = "reamicro-module-config-v1:$id".toCharArray()
        val spec = PBEKeySpec(password, salt, 120_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword(); password.fill('\u0000') }
    }
    fun encrypt(id: Long, plain: ByteArray): ByteArray {
        require(plain.size <= MAX_BYTES) { "模块配置备份超过 32 MiB" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(id, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        return magic + salt + iv + cipher.doFinal(plain)
    }
    fun decrypt(id: Long, encrypted: ByteArray): ByteArray {
        require(encrypted.size in (magic.size + 44)..(MAX_BYTES + magic.size + 44)) { "模块配置备份大小无效" }
        val data = ByteBuffer.wrap(encrypted)
        val prefix = ByteArray(magic.size).also(data::get)
        require(prefix.contentEquals(magic)) { "模块配置备份格式无效" }
        val salt = ByteArray(16).also(data::get)
        val iv = ByteArray(12).also(data::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(id, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        return try { cipher.doFinal(ByteArray(data.remaining()).also(data::get)) }
        catch (error: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("备份账号不匹配或数据已损坏，未应用配置", error)
        }
    }
}
