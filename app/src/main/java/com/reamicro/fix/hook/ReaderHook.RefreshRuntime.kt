package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.core.HostCoroutines
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException

internal data class ReaderRefreshSession(
    val viewModel: Any, val epub: Any, val root: File, val generation: Long,
    val presentation: Long, val activity: WeakReference<Activity>,
)

internal fun ReaderHook.captureRefreshSession(viewModel: Any, root: File): ReaderRefreshSession? {
    val activity = activeReaderActivity() ?: return null
    if (currentViewModelRef?.get() !== viewModel || !sameReaderBookRoot(currentEpubRoot(), root)) return null
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return null
    return ReaderRefreshSession(viewModel, epub, root, onDemandRefreshCoordinator.generation(),
        onDemandRefreshCoordinator.presentation(), WeakReference(activity))
}

internal fun ReaderHook.isRefreshSessionCurrent(session: ReaderRefreshSession): Boolean =
    onDemandRefreshCoordinator.isGeneration(session.generation) &&
        onDemandRefreshCoordinator.isPresentation(session.presentation) &&
        session.activity.get()?.let { it === activeReaderActivity() } == true &&
        currentViewModelRef?.get() === session.viewModel &&
        (currentEpubStrong ?: currentEpubRef?.get()) === session.epub

internal fun ReaderHook.activeReaderActivity(): Activity? =
    activityProvider()?.takeIf {
        !readerActivityPaused && readerActivityRef?.get() === it &&
            isActivityResumedProvider() && !it.isFinishing && !it.isDestroyed
    }

internal fun ReaderHook.suspendOnDemandRefreshes() {
    onDemandRefreshCoordinator.suspendPresentation()
    onDemandHandler.removeCallbacksAndMessages(null)
    onDemandJumpLease = null
    onDemandVisibleRefreshKey = null
    onDemandVisibleSlot = null
    onDemandVisibleBusyRetries = 0
    lastOnDemandPrefetchSpineKey = ""
    onDemandPrefetchRetryCount.clear()
}

internal fun ReaderHook.invalidateOnDemandRefreshes() {
    onDemandRefreshCoordinator.reset()
    onDemandHandler.removeCallbacksAndMessages(null)
    onDemandSpineTable = null
    onDemandPrefetchInFlight.clear()
    onDemandPrefetchRetryCount.clear()
    onDemandRefreshPending.clear()
    onDemandJumpLease = null
    onDemandVisibleRefreshKey = null
    onDemandVisibleSlot = null
    onDemandVisibleBusyRetries = 0
}

internal fun requireRefreshCurrent(current: () -> Boolean) {
    if (Thread.currentThread().isInterrupted || !current()) throw CancellationException("Reader refresh superseded")
}

internal fun ReaderHook.invokeRefreshHostSuspend(
    session: ReaderRefreshSession, current: () -> Boolean, operation: (Any?) -> Any?,
): Any? {
    requireRefreshCurrent(current)
    val job = HostCoroutines.activeViewModelJob(classLoader, session.viewModel)
    return invokeSelectionHostSuspend(parentContext = job) { cont ->
        requireRefreshCurrent(current)
        operation(cont)
    }.also { requireRefreshCurrent(current) }
}

internal fun ReaderHook.refreshOnMain(
    session: ReaderRefreshSession, current: () -> Boolean, action: () -> Any?,
): Any? {
    return invokeRefreshHostSuspend(session, current) { continuation ->
        HostCoroutines.withMain(classLoader, continuation) { requireRefreshCurrent(current); action() }
    }
}

internal class ReaderRefreshLockBusy : IllegalStateException("Reader virtual window is busy")
