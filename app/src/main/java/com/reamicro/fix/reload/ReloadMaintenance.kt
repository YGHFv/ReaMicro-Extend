package com.reamicro.fix.reload

import java.util.concurrent.RejectedExecutionException

/** 重载前只停止可重新执行的维护任务，仍由原有门禁确认线程和回调全部退出。 */
internal class ReloadMaintenance {
    private val tickets = linkedSetOf<Ticket>()
    private var paused = false

    @Synchronized fun register(thread: Thread): Ticket {
        if (paused) throw RejectedExecutionException("Module maintenance is paused for reload")
        return Ticket(thread).also(tickets::add)
    }

    fun pause() {
        val running = synchronized(this) {
            paused = true
            tickets.toList()
        }
        running.forEach { it.cancel() }
    }

    @Synchronized fun resume() { paused = false }

    inner class Ticket internal constructor(private val thread: Thread) : AutoCloseable {
        private val cancellation = TaskCancellation()

        fun run(action: Runnable) = cancellation.run { action.run() }

        internal fun cancel() {
            // 中断等待并主动断开已注册的网络连接，不能仅等待读取超时。
            cancellation.cancel()
            thread.interrupt()
        }

        override fun close() { synchronized(this@ReloadMaintenance) { tickets.remove(this) } }
    }
}
