package com.reamicro.fix.cloud.local

import org.json.JSONObject
import java.util.Base64
import java.io.InputStream
import java.io.ByteArrayOutputStream

internal data class LocalTaskAccount(val id: String, val nickname: String = "", val remark: String = "") {
    val name: String get() = remark.ifBlank { nickname }.ifBlank { id }
    val label: String get() = if (name == id) id else "$name · $id"
}

/** Credentials are never included in generated toString(), logs or task history. */
internal class LocalTaskAccountCredential(val account: LocalTaskAccount, val token: String) {
    override fun toString(): String = "LocalTaskAccountCredential(accountId=${account.id})"
}

internal object LocalTaskCredentials {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_ACCOUNTS = 50
    val taskTypes = listOf("yeshe_checkin", "yeshe_draw_card", "cloud_auto_read", "traveling_merchant", "pawn")
    private const val PREFIX = "REAMICRO_ACCOUNT_1:"

    fun read(input: InputStream): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - result.size()))
            if (count < 0) break
            result.write(buffer, 0, count)
            require(result.size() <= MAX_BYTES) { "凭证文件不能超过 1 MiB" }
        }
        return result.toString("UTF-8")
    }

    fun parse(raw: String): List<LocalTaskAccountCredential> {
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "凭证文件不能超过 1 MiB" }
        val result = linkedMapOf<String, LocalTaskAccountCredential>()
        fun read(value: String, depth: Int) {
            require(depth < 8) { "账号凭证嵌套过深" }
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "账号凭证为空" }
            val decoded = if (trimmed.startsWith("{")) trimmed else {
                val encoded = trimmed.removePrefix(PREFIX).filterNot(Char::isWhitespace)
                val bytes = try { Base64.getDecoder().decode(encoded) }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("账号凭证格式不正确") }
                String(bytes, Charsets.UTF_8)
            }
            val json = JSONObject(decoded)
            if (json.has("accounts")) {
                val accounts = json.getJSONArray("accounts")
                require(accounts.length() in 1..MAX_ACCOUNTS) { "最多支持 50 个账号" }
                for (index in 0 until accounts.length()) {
                    val item = accounts.getJSONObject(index)
                    read(item.optString("credential").ifBlank { item.toString() }, depth + 1)
                }
            } else {
                val id = json.get("accountId").toString()
                require(id.toLongOrNull()?.let { it > 0L && it.toString() == id } == true) { "账号 ID 无效" }
                val token = json.getString("token").trim()
                require(token.isNotEmpty() && token.length <= 16_384 && token.none(Char::isISOControl)) { "账号凭证无效" }
                val nickname = json.optString("nickname").filterNot(Char::isISOControl).trim().take(80)
                val previous = result[id]
                require(previous == null || previous.token == token) { "同一账号包含不同凭证，请分别导入" }
                result[id] = LocalTaskAccountCredential(LocalTaskAccount(id, nickname), token)
                require(result.size <= MAX_ACCOUNTS) { "最多支持 50 个账号" }
            }
        }
        try {
            read(raw, 0)
        } catch (error: org.json.JSONException) {
            throw IllegalArgumentException("账号凭证格式不正确", error)
        }
        require(result.isNotEmpty()) { "凭证中没有账号" }
        return result.values.toList()
    }
}

internal fun localTaskAccount(accountId: String, root: JSONObject): LocalTaskAccount =
    LocalTaskAccount(accountId, root.optString("nickname"), root.optString("remark"))
