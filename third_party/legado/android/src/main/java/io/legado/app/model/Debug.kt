package io.legado.app.model

import io.legado.app.utils.HtmlFormatter

/**
 * Parser trace endpoint extracted from the upstream debug UI. Full response
 * bodies are delivered only to an explicitly installed diagnostic consumer.
 */
object Debug {
    data class Event(
        val sourceUrl: String?,
        val message: String,
        val state: Int,
        val timestamp: Long,
    )

    @Volatile var listener: ((Event) -> Unit)? = null
    val hasActiveSession: Boolean get() = listener != null

    @Suppress("UNUSED_PARAMETER")
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1,
    ) {
        val target = listener ?: return
        if (print) target(Event(sourceUrl, if (isHtml) HtmlFormatter.format(msg) else msg,
            state, System.currentTimeMillis()))
    }

    fun log(msg: String?) = log(null, msg.orEmpty())
}
