package com.reamicro.fix.hook

import com.reamicro.fix.core.HostWorkBindings
import com.reamicro.fix.online.download.enqueueNativeImport
import java.util.concurrent.CancellationException

/**
 * A returned native handle means queued, not imported. Follow its state on the existing IO worker;
 * host update notifications wake the waiter while a bounded wait keeps cancellation responsive.
 */
internal object NativeImportTaskBridge {
    private val changed = Object()

    fun notifyChanged() = synchronized(changed) { changed.notifyAll() }

    fun await(
        workerManager: Any,
        platformFile: Any,
        checkCurrent: () -> Unit,
        onProgress: (Int) -> Unit,
        onQueued: () -> Unit = {},
        workerFinished: () -> Boolean = { true },
        onCancellationRequested: () -> Unit = {},
    ): String? {
        checkCurrent()
        val work = HostWorkBindings.requireCurrent()
        val tracker = work.tracker(workerManager)
        val handle = requireNotNull(enqueueNativeImport(workerManager, platformFile)) { "原生导入未返回任务" }
        onQueued()
        return awaitHandle(tracker, handle, checkCurrent, onProgress, workerFinished, onCancellationRequested)
    }

    fun awaitHandle(
        tracker: Any,
        handle: Any,
        checkCurrent: () -> Unit,
        onProgress: (Int) -> Unit,
        workerFinished: () -> Boolean = { true },
        onCancellationRequested: () -> Unit = {},
    ): String? {
        val work = HostWorkBindings.requireCurrent()
        val id = work.handleId(handle)
        val flow = requireNotNull(work.field("handle.state").get(handle))
        var reported = -1
        var progressFailureLogged = false
        var cancellation: CancellationException? = null
        var interrupted = false
        fun requestCancellation(failure: CancellationException) {
            if (cancellation == null) {
                cancellation = failure
                onCancellationRequested()
            }
        }
        fun recordInterruption(failure: InterruptedException) {
            interrupted = true
            requestCancellation(CancellationException("原生导入等待已中断").apply { initCause(failure) })
        }
        try {
            while (true) {
                if (Thread.interrupted()) recordInterruption(InterruptedException("Import waiter interrupted"))
                val state = requireNotNull(work.method("flow.get").invoke(flow))
                when (work.stateName(state)) {
                    // A durable success wins over cancellation racing after the commit.
                    "Success" -> return work.field("state.result").get(state) as? String
                    "Error" -> throw cancellation ?: IllegalStateException(
                        (work.field("state.error").get(state) as? String)?.takeIf(String::isNotBlank)
                        ?: "原生导入失败")
                    "Cancelled" -> if (workerFinished()) {
                        throw cancellation ?: CancellationException("原生导入已取消")
                    }
                }
                if (cancellation != null && workerFinished()) throw cancellation
                if (cancellation == null) {
                    try {
                        checkCurrent()
                    } catch (failure: CancellationException) {
                        // The outer task is already cancelled and its source ticket stops new work.
                        // Do not cancel the native tracker before its worker exits: a committed Pair
                        // still needs its final progress callback before native Success is published.
                        requestCancellation(failure)
                    } catch (failure: InterruptedException) {
                        recordInterruption(failure)
                    }
                }
                val progress = ImportCompletionState.progress(work.field("state.progress").getInt(state))
                if (cancellation == null && progress != reported) {
                    try { onProgress(progress) } catch (failure: Throwable) {
                        if (!progressFailureLogged) {
                            progressFailureLogged = true
                            com.reamicro.fix.xposed.HookRuntime.logError("ReaMicro import progress publication failed", failure)
                        }
                    }
                    reported = progress
                }
                try {
                    synchronized(changed) { changed.wait(200L) }
                } catch (failure: InterruptedException) {
                    // Keep the native tracker live until its actual terminal state. The source
                    // ticket stops uncommitted work; committed work may still publish Success.
                    recordInterruption(failure)
                }
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException && workerFinished()) work.cancel(tracker, id)
            val state = work.method("flow.get").invoke(flow)
            if (state != null && work.stateName(state) == "Success") {
                return work.field("state.result").get(state) as? String
            }
            throw failure
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
