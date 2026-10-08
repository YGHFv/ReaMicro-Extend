package com.reamicro.fix.hook

import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class LatestSearchWorker(name: String) {
    class Ticket(val sequence: Long, private val current: AtomicLong) {
        fun isCurrent(): Boolean = current.get() == sequence
    }
    private val sequence = AtomicLong()
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(1), { task -> Thread(task, name).apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }
    private var pending: FutureTask<Unit>? = null

    @Synchronized fun submit(block: (Ticket) -> Unit): Ticket {
        cancel()
        val ticket = Ticket(sequence.get(), sequence)
        val task = FutureTask<Unit> { block(ticket) }
        pending = task
        executor.execute(task)
        return ticket
    }

    @Synchronized fun cancel() {
        sequence.incrementAndGet()
        pending?.cancel(true)
        pending = null
        executor.queue.clear()
    }
}
