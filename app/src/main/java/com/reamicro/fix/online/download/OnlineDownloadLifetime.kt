package com.reamicro.fix.online.download

/** Only the worker releases staging, after synchronous publication has returned. */
internal class OnlineDownloadLifetime(private val cleanup: () -> Unit) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) cleanup()
    }
}
