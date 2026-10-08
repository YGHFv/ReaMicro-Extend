package com.reamicro.fix.hook

internal class ReaderHighlightRefreshQueue(
    private val post: (() -> Unit) -> Unit,
    private val context: () -> Any?,
    private val refresh: (String) -> Unit,
) {
    private var scheduled = false
    private var latest: Pair<Any?, String>? = null

    @Synchronized
    fun request(source: String) {
        latest = context() to source
        if (scheduled) return
        scheduled = true
        post(::drain)
    }

    private fun drain() {
        val request = synchronized(this) {
            scheduled = false
            latest.also { latest = null }
        } ?: return
        // 同轮只刷新最终状态；切书或退出后不把旧操作应用到新窗口。
        if (request.first == context()) refresh(request.second)
    }
}
