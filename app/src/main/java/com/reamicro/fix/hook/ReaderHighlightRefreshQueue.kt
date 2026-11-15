package com.reamicro.fix.hook

internal class ReaderHighlightRefreshQueue(
    private val post: (() -> Unit) -> Boolean,
    private val context: () -> Any?,
    private val refresh: (String) -> Unit,
) {
    private var generation = 0L
    private var scheduled: Long? = null
    private var latest: Pair<Any?, String>? = null

    @Synchronized
    fun request(source: String) {
        latest = context() to source
        if (scheduled != null) return
        val ticket = ++generation
        scheduled = ticket
        try {
            if (!post { drain(ticket) } && scheduled == ticket) scheduled = null
        } catch (error: Throwable) {
            if (scheduled == ticket) scheduled = null
            throw error
        }
    }

    @Synchronized
    fun cancel() {
        generation++
        scheduled = null
        latest = null
    }

    private fun drain(ticket: Long) {
        val request = synchronized(this) {
            if (scheduled != ticket) return
            scheduled = null
            latest.also { latest = null }
        } ?: return
        // 同轮只刷新最终状态；切书或退出后不把旧操作应用到新窗口。
        if (request.first == context()) refresh(request.second)
    }
}
