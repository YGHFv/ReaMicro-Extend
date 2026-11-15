package com.reamicro.fix.online

import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Switching engines invalidates in-flight work and the results produced by it. */
internal object OnlineParserMode {
    @Volatile private var enabled: Boolean? = null
    private val revision = AtomicLong()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    val generation: Long get() = revision.get()
    val isEnhanced: Boolean get() = enabled == true

    @Synchronized fun capture(): OnlineParsingState = OnlineParsingState(enabled == true, revision.get())

    fun publish(value: Boolean) {
        if (enabled == value) return
        val changed = synchronized(this) {
            if (enabled == value) false else {
                val notify = enabled != null
                enabled = value
                if (notify) revision.incrementAndGet()
                notify
            }
        }
        if (changed) listeners.forEach { runCatching(it) }
    }

    fun observeChanges(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun requireCurrent(generation: Long) {
        TaskCancellation.checkCurrent()
        if (generation != revision.get()) {
            throw java.util.concurrent.CancellationException("解析方式已切换，请重新搜索或更新")
        }
    }
}

/** Serialized upstream Book keeps source variables across search/detail/TOC. */
internal data class OnlineParsingState(
    val enhanced: Boolean,
    val generation: Long,
    val bookJson: String = "",
    val sourceTemporaryVariable: String? = null,
)
