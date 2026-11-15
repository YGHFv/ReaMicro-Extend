package com.reamicro.fix.core

import java.util.concurrent.atomic.AtomicBoolean

/** Dispatch installation without making the package-ready/main thread wait for its outcome. */
internal class HostStartupTask(
    private val background: (() -> Unit) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val install: () -> Unit,
    private val ready: () -> Unit,
    private val failed: (Throwable) -> Unit,
) {
    private val started = AtomicBoolean()
    @Volatile var finished: Boolean = false
        private set

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        try {
            background {
                val error = runCatching(install).exceptionOrNull()
                try {
                    main {
                        try {
                            if (error == null) ready() else failed(error)
                        } catch (failure: Throwable) {
                            failed(failure)
                        } finally {
                            finished = true
                        }
                    }
                } catch (failure: Throwable) {
                    finished = true
                    failed(failure)
                }
            }
        } catch (failure: Throwable) {
            finished = true
            failed(failure)
        }
        return true
    }
}
