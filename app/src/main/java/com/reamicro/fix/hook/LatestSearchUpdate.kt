package com.reamicro.fix.hook

// 主线程繁忙时只保留最新批次，不能把每次流式结果快照全部排入消息队列。
internal class LatestSearchUpdate<T>(private val post: (Runnable) -> Boolean, private val consume: (T) -> Unit) {
    private var pending: T? = null
    private var scheduled = false

    @Synchronized fun offer(value: T) {
        pending = value
        if (scheduled) return
        scheduled = true
        val accepted = post(Runnable {
            val latest = synchronized(this) {
                val result = pending
                pending = null
                scheduled = false
                result
            }
            latest?.let(consume)
        })
        if (!accepted) {
            pending = null
            scheduled = false
        }
    }
}
