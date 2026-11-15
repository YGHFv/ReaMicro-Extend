package com.reamicro.fix.hook

import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation

internal class LatestSearchWorker(name: String) : AutoCloseable {
    class Ticket internal constructor(
        val sequence: Long,
        private val current: AtomicLong,
        internal val cancellation: TaskCancellation,
    ) {
        fun isCurrent(): Boolean = current.get() == sequence &&
            !cancellation.isCancelled && !Thread.currentThread().isInterrupted
    }
    private val lock = Any()
    private val sequence = AtomicLong()
    private val executor = ModuleResources.pool(1, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(1), { task -> Thread(task, name).apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }
    private var pending: FutureTask<Unit>? = null
    private var cancellation: TaskCancellation? = null
    private var closed = false

    fun submit(block: (Ticket) -> Unit): Ticket = checkNotNull(submitIf({ true }, block))

    fun submitIf(accept: () -> Boolean, block: (Ticket) -> Unit): Ticket? {
        val signal = TaskCancellation()
        var previous: TaskCancellation? = null
        var rejected: Throwable? = null
        val ticket = synchronized(lock) {
            if (closed) throw RejectedExecutionException("Search worker closed")
            if (!accept()) return null
            val ticket = Ticket(sequence.incrementAndGet(), sequence, signal)
            previous = cancellation
            pending?.cancel(true)
            executor.queue.clear()
            val task = object : FutureTask<Unit>({ signal.run { block(ticket) } }) {
                override fun done() {
                    synchronized(lock) {
                        if (pending === this) pending = null
                    }
                }
            }
            pending = task
            cancellation = signal
            try {
                executor.execute(task)
            } catch (error: Throwable) {
                task.cancel(false)
                if (pending === task) pending = null
                if (cancellation === signal) cancellation = null
                rejected = error
            }
            ticket
        }
        // 取消 IO 可能回调到任务收尾；不能持有 worker 状态锁。
        previous?.cancel()
        rejected?.let { error ->
            signal.cancel()
            throw error
        }
        return ticket
    }

    fun cancel() = cancelIf { true }

    fun cancelIf(accept: () -> Boolean) {
        val signal = synchronized(lock) {
            if (closed || !accept()) return
            sequence.incrementAndGet()
            cancellation.also {
                cancellation = null
                pending?.cancel(true)
                pending = null
                executor.queue.clear()
            }
        }
        signal?.cancel()
    }

    override fun close() {
        val signal = synchronized(lock) {
            if (closed) return
            closed = true
            sequence.incrementAndGet()
            cancellation.also {
                cancellation = null
                pending?.cancel(true)
                pending = null
                executor.queue.clear()
            }
        }
        signal?.cancel()
        executor.shutdownNow()
    }
}
