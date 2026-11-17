package com.reamicro.fix.online

import com.reamicro.fix.reload.TaskCancellation

/** One user operation: removing/updating its source disconnects registered network calls. */
internal class OnlineSourceTask(private val source: OnlineSourceEntry) : AutoCloseable {
    private val cancellation = TaskCancellation.current()?.child() ?: TaskCancellation()
    private val observer = OnlineSourceStore.observeChanges {
        if (!OnlineSourceStore.isCurrent(source)) cancellation.cancel()
    }

    val isCurrent: Boolean get() = !cancellation.isCancelled &&
        OnlineSourceStore.isCurrent(source)

    fun <T> run(action: () -> T): T = cancellation.run {
        OnlineSourceStore.requireCurrent(source)
        action().also {
            OnlineSourceStore.requireCurrent(source)
        }
    }

    override fun close() {
        cancellation.cancel()
        observer.close()
    }
}
