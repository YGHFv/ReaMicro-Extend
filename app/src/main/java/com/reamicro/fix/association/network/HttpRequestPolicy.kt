package com.reamicro.fix.association.network

import java.net.URI
import java.util.Locale

/** URI origins use effective ports; credentials never follow a change of origin. */
internal object HttpRequestPolicy {
    fun uri(value: String): URI = URI(value).also { uri ->
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "无效的 HTTP(S) 地址" }
    }

    fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = uri(first)
        val b = uri(second)
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }.getOrDefault(false)

    fun redirectHeaders(headers: Map<String, String>, from: String, to: String): Map<String, String> =
        if (sameOrigin(from, to)) headers else headers.filterKeys {
            it.lowercase(Locale.ROOT) in PUBLIC_REDIRECT_HEADERS
        }

    private fun port(uri: URI): Int =
        if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private val PUBLIC_REDIRECT_HEADERS = setOf("accept", "accept-language", "user-agent", "range")
}
