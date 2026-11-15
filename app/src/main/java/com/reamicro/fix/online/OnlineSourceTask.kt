package com.reamicro.fix.online

import com.reamicro.fix.reload.TaskCancellation

/** One user operation: removing/updating its source disconnects registered network calls. */
internal class OnlineSourceTask(private val source: OnlineSourceEntry) : AutoCloseable {
    private val cancellation = TaskCancellation.current()?.child() ?: TaskCancellation()
    private val parserGeneration = OnlineParserMode.generation
    private val parserObserver = OnlineParserMode.observeChanges {
        if (parserGeneration != OnlineParserMode.generation) cancellation.cancel()
    }
    private val observer = OnlineSourceStore.observeChanges {
        if (!OnlineSourceStore.isCurrent(source)) cancellation.cancel()
    }

    val isCurrent: Boolean get() = !cancellation.isCancelled &&
        parserGeneration == OnlineParserMode.generation && OnlineSourceStore.isCurrent(source)

    fun <T> run(action: () -> T): T = cancellation.run {
        OnlineSourceStore.requireCurrent(source)
        OnlineParserMode.requireCurrent(parserGeneration)
        action().also {
            OnlineSourceStore.requireCurrent(source)
            OnlineParserMode.requireCurrent(parserGeneration)
        }
    }

    override fun close() {
        cancellation.cancel()
        observer.close()
        parserObserver.close()
    }
}
