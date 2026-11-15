package com.reamicro.fix.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serializes UI reads and folds bursts into one pending read.
 *
 * A result is applied only if it still represents the latest request. Read and apply choose their
 * own dispatchers at the call site, so background work cannot write presentation preferences.
 */
internal class ModuleUiRefresh<T>(
    private val scope: CoroutineScope,
    private val read: suspend () -> T,
    private val apply: (T) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val quietPeriodMillis: Long = 60L,
) {
    private var version = 0L
    private var pending: Job? = null

    fun cancelPending() {
        version++
        pending?.cancel()
        pending = null
    }

    fun request() {
        version++
        if (pending?.isActive == true) return
        pending = scope.launch {
            try {
                do {
                    val requested = version
                    delay(quietPeriodMillis)
                    if (requested != version) continue
                    val result = runCatching { read() }
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    if (requested == version) {
                        result.fold(apply, onError)
                        break
                    }
                } while (true)
            } finally {
                if (pending === coroutineContext[Job]) pending = null
            }
        }
    }
}
