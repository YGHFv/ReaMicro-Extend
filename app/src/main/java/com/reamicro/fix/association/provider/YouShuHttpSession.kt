package com.reamicro.fix.association.provider

import java.util.concurrent.CancellationException

/** Keeps the network policy independent of Android and the embedded browser implementation. */
internal object YouShuHttpSession {
    private class Binding(
        val cookie: (String) -> String,
        val revision: () -> Long,
        val current: (Long) -> Boolean,
        val accept: (String, List<String>, String, Long) -> Unit,
    )

    @Volatile private var binding: Binding? = null

    fun bind(
        cookie: (String) -> String,
        revision: () -> Long,
        current: (Long) -> Boolean,
        accept: (String, List<String>, String, Long) -> Unit,
    ) {
        binding = Binding(cookie, revision, current, accept)
    }

    fun clear() { binding = null }

    fun open(url: String, headers: Map<String, String>): Session? {
        if (!YouShuCookiePolicy.supports(url) || headers.keys.none { it.equals("Cookie", ignoreCase = true) }) return null
        val currentBinding = binding
        return Session(currentBinding?.cookie ?: { "" },
            currentBinding?.current ?: { true },
            currentBinding?.accept ?: { _, _, _, _ -> },
            currentBinding?.revision?.invoke() ?: 0L)
    }

    class Session internal constructor(
        private val readCookie: (String) -> String,
        private val current: (Long) -> Boolean,
        private val accept: (String, List<String>, String, Long) -> Unit,
        private val revision: Long,
    ) {
        fun headers(url: String, headers: Map<String, String>): Map<String, String> {
            if (!current(revision)) throw CancellationException("YouShu request cancelled")
            return YouShuCookiePolicy.requestHeaders(url, headers, readCookie)
        }

        fun acceptResponse(url: String, values: List<String>, headers: Map<String, String>) {
            if (!current(revision) || !YouShuCookiePolicy.supports(url) || values.isEmpty()) return
            val sent = headers.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value.orEmpty()
            if (readCookie(url) == sent) accept(url, values, sent, revision)
        }
    }
}
