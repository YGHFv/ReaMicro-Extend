package com.reamicro.fix.hook

import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference

internal class ReaderSelectionPagerTarget(
    val viewModel: WeakReference<Any>, val epub: WeakReference<Any>, val page: WeakReference<Any>,
    val virtualPage: Int,
) {
    var deadline = 0L
    var awaitingResume = true
    var discarded = 0
    var poll: Runnable? = null
    val handler = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
}

internal fun ReaderHook.cancelSelectionPagerConfirmation() {
    val old = selectionPagerTarget ?: return
    selectionPagerTarget = null
    old.poll?.let(old.handler::removeCallbacks)
    old.poll = null
}

internal fun ReaderHook.armSelectionPagerTarget(viewModel: Any, virtualPage: Int): Boolean {
    cancelSelectionPagerConfirmation()
    return runCatching {
        val page = (requireNotNull(com.reamicro.fix.core.HostReaderPositionBindings.current).field("vm.pages").get(viewModel) as Map<*, *>)[virtualPage]
            ?: run { HookRuntime.logError("ReaMicro selection pager target missing=$virtualPage"); return false }
        val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return false
        if (currentViewModelRef?.get() !== viewModel) return false
        val pending = ReaderSelectionPagerTarget(
            WeakReference(viewModel), WeakReference(epub), WeakReference(page), virtualPage,
        )
        selectionPagerTarget = pending
        resumeSelectionPagerTarget()
    }.onFailure {
        cancelSelectionPagerConfirmation()
        HookRuntime.logError("ReaMicro selection pager preparation failed: ${it.message}")
    }.getOrDefault(false)
}

/** A save may finish after onPause; retain only a weak target until this session resumes. */
internal fun ReaderHook.resumeSelectionPagerTarget(): Boolean =
    runCatching { resumeSelectionPagerTargetChecked() }.onFailure {
        cancelSelectionPagerConfirmation()
        HookRuntime.logError("ReaMicro selection pager resume failed: ${it.message}")
    }.getOrDefault(false)

private fun ReaderHook.resumeSelectionPagerTargetChecked(): Boolean {
    val pending = selectionPagerTarget ?: return false
    if (!pending.awaitingResume) return true
    if (!selectionPagerForeground()) return false
    if (!selectionPagerSession(pending)) {
        cancelSelectionPagerConfirmation()
        return false
    }
    val viewModel = pending.viewModel.get() ?: return false
    val current = (requireNotNull(com.reamicro.fix.core.HostReaderPositionBindings.current)
        .field("vm.pages").get(viewModel) as Map<*, *>)[pending.virtualPage]
    if (!sameSelectionPage(current, pending.page.get())) {
        cancelSelectionPagerConfirmation()
        HookRuntime.log("ReaMicro selection pager target replaced before resume; retained current reading position")
        return false
    }
    pending.awaitingResume = false
    pending.deadline = SystemClock.elapsedRealtime() + 2_500L
    pending.poll = object : Runnable {
        override fun run() {
            if (selectionPagerTarget !== pending) return
            // Check before reading the native pager, including a queued completion after pause.
            if (!selectionPagerForeground() || !selectionPagerSession(pending)) {
                cancelSelectionPagerConfirmation()
                return
            }
            val native = runCatching { searchPageBridge?.settledSnapshot(viewModel) }.getOrNull()
            val observed = native?.takeIf { it.index == pending.virtualPage }?.page
            if (completeSelectionPagerIfReady(pending, observed, "native-pager", native?.index)) return
            pending.handler.postDelayed(this, 50L)
        }
    }.also { pending.handler.postDelayed(it, 50L) }
    return runCatching {
        requireNotNull(com.reamicro.fix.core.HostEpubRuntimeBindings.current).initialPage(viewModel, pending.virtualPage)
        requireNotNull(com.reamicro.fix.core.HostReaderWindowBindings.current).emitCorrection(viewModel, pending.virtualPage)
    }.onFailure {
        HookRuntime.logError("ReaMicro selection pager dispatch failed: ${it.message}")
    }.getOrDefault(false)
}

private fun ReaderHook.selectionPagerForeground(): Boolean {
    val activity = activityProvider() ?: return false
    return !readerActivityPaused && isActivityResumedProvider() && !activity.isFinishing && !activity.isDestroyed
}

private fun ReaderHook.selectionPagerSession(pending: ReaderSelectionPagerTarget): Boolean {
    val owner = pending.viewModel.get()
    val epub = pending.epub.get()
    return owner != null && epub != null && currentViewModelRef?.get() === owner &&
        (currentEpubStrong ?: currentEpubRef?.get()) === epub
}

private fun ReaderHook.sameSelectionPage(actual: Any?, expected: Any?): Boolean {
    if (actual == null || expected == null) return false
    if (actual === expected) return true
    val host = requireNotNull(com.reamicro.fix.core.HostEpubRuntimeBindings.current)
    if (!host.type("page").isInstance(expected) || !host.type("page").isInstance(actual)) return false
    val document = host.value("page.document", expected) ?: return false
    val start = host.value("page.start", expected) ?: return false
    val end = host.value("page.end", expected) ?: return false
    return host.value("page.document", actual) === document &&
        host.value("page.start", actual) == start && host.value("page.end", actual) == end
}

private fun ReaderHook.completeSelectionPagerIfReady(
    pending: ReaderSelectionPagerTarget, observed: Any?, source: String, actualIndex: Int? = null,
): Boolean {
    if (selectionPagerTarget !== pending) return true
    val expected = pending.page.get()
    val state = readerPagerConfirmation(selectionPagerSession(pending), expected != null,
        sameSelectionPage(observed, expected), SystemClock.elapsedRealtime(), pending.deadline)
    if (state == ReaderPagerConfirmation.WAITING) return false
    cancelSelectionPagerConfirmation()
    when (state) {
        ReaderPagerConfirmation.ACKNOWLEDGED -> {
            currentPageRef = WeakReference(observed)
            currentPageStrong = observed
            currentVisiblePageSignature = epubPageSignature(observed!!)
            currentVisiblePageNumber = epubPageNumber(observed)
            HookRuntime.log("ReaMicro selection pager acknowledged=true source=$source target=${pending.virtualPage} mode=$currentSearchPagerMode discarded=${pending.discarded}")
        }
        ReaderPagerConfirmation.EXPIRED -> {
            HookRuntime.logError("ReaMicro selection pager confirmation expired target=${pending.virtualPage} actual=$actualIndex mode=$currentSearchPagerMode; file saved")
            activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed }?.let {
                Toast.makeText(it, "修改已保存；阅读位置未确认，请检查当前位置", Toast.LENGTH_LONG).show()
            }
        }
        else -> HookRuntime.log("ReaMicro selection pager confirmation cancelled: session changed")
    }
    return true
}

internal fun ReaderHook.filterSelectionPagerTransition(viewModel: Any, page: Any): Boolean {
    val pending = selectionPagerTarget ?: return false
    if (pending.viewModel.get() !== viewModel) return false
    if (pending.awaitingResume) return false
    val expected = pending.page.get()
    if (sameSelectionPage(page, expected)) {
        completeSelectionPagerIfReady(pending, page, "Statistics")
        return false
    }
    val native = runCatching { searchPageBridge?.settledSnapshot(viewModel) }.getOrNull()
    val observed = native?.takeIf { it.index == pending.virtualPage }?.page
    if (completeSelectionPagerIfReady(pending, observed, "native-pager", native?.index)) {
        return sameSelectionPage(observed, expected)
    }
    pending.discarded++
    if (pending.discarded == 1) HookRuntime.log(
        "ReaMicro selection pager waiting target=${pending.virtualPage}; ignored transitional Statistics",
    )
    return true
}
