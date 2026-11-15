package com.reamicro.fix.online

import org.json.JSONObject

/** This gate applies to the TRXS JSON API, not HTML/Cookie source logins. */
internal fun trxsApiResponseSuccess(httpCode: Int, body: String): Boolean {
    if (httpCode !in 200..299) return false
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return false
    if (root.length() == 0 || (root.has("success") && !root.optBoolean("success", true))) return false
    val error = root.opt("error")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
    if (error.isNotBlank()) return false
    return root.optString("msg", "").let { it.isBlank() || it.equals("success", ignoreCase = true) }
}
