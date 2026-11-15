package com.reamicro.fix.cloud.webdav

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** AList status belongs to the top-level response, never an arbitrary nested value. */
internal object AlistProtocol {
    fun success(body: String): Boolean = response(body)?.optInt("code", -1) == 200

    fun token(body: String): String? {
        val root = response(body)?.takeIf { it.optInt("code", -1) == 200 } ?: return null
        val data = root.optJSONObject("data") ?: return null
        return (data.opt("token") as? String)?.takeIf { it.isNotBlank() }
    }

    /** Length prefixes avoid separator collisions; the per-client salt stays in memory. */
    fun sessionKey(salt: ByteArray, apiBase: String, root: String, username: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        for (value in listOf(apiBase, root, username, password)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun response(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
}
