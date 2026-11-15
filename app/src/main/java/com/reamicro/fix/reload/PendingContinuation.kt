package com.reamicro.fix.reload

import java.util.concurrent.atomic.AtomicInteger

/** Keep the old generation alive until a suspended host call and its callback have both returned. */
internal class PendingContinuation(private val lease: AutoCloseable) : AutoCloseable {
    private val phase = AtomicInteger(PENDING)

    fun resume(action: () -> Unit) {
        if (!phase.compareAndSet(PENDING, RUNNING)) return
        try {
            action()
        } finally {
            phase.set(COMPLETED)
            lease.close()
        }
    }

    /** A synchronous result has no resumeWith call; its hook's after phase releases the lease. */
    override fun close() {
        if (phase.compareAndSet(PENDING, COMPLETED)) lease.close()
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val COMPLETED = 2
    }
}
