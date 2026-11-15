package com.reamicro.fix.reload

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** UI 撤销后在框架回调线程等待资源释放，所有等待共用同一个截止时间。 */
internal class ResourceRetirement(
    private val pools: List<ExecutorService>,
    private val threads: () -> List<Thread>,
    private val mainThread: Thread,
    private val timeoutMillis: Long = 2_000,
) {
    private var failure: Throwable? = null
    private var interrupted = false

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
    }

    fun shutdown() {
        pools.forEach { pool -> attempt { pool.shutdown() } }
    }

    fun awaitCompletion() {
        check(Thread.currentThread() !== mainThread) { "Cannot wait for module resources on the main thread" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun remaining(): Long = (deadline - System.nanoTime()).coerceAtLeast(0)
        pools.forEach { pool -> attempt {
            while (!pool.isTerminated && remaining() > 0) {
                try {
                    pool.awaitTermination(remaining(), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            check(pool.isTerminated) { "Module executor did not terminate" }
        } }
        // Executor termination can precede Worker.run/Thread.exit. Inspect after shutdown and
        // awaitTermination, so workers created while the last queue entries drain are included.
        threads().forEach { thread -> attempt {
            check(thread !== Thread.currentThread()) { "Cannot retire the current module thread" }
            while (thread.isAlive && remaining() > 0) {
                val wait = remaining()
                if (wait == 0L) break
                try {
                    thread.join(wait / 1_000_000, (wait % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            check(!thread.isAlive) { "Module thread did not terminate: ${thread.name}" }
        } }
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw IllegalStateException("Module resource retirement failed", it) }
    }
}
