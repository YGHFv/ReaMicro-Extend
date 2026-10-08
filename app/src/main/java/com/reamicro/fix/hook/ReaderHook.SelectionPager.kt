package com.reamicro.fix.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference

internal class ReaderSelectionPagerTarget(
    val viewModel: WeakReference<Any>, val epub: WeakReference<Any>, val page: WeakReference<Any>,
    val virtualPage: Int, val deadline: Long,
) {
    var discarded = 0
    var poll: Runnable? = null
    val handler = Handler(Looper.getMainLooper())
}

internal fun ReaderHook.cancelSelectionPagerConfirmation() {
    val old = selectionPagerTarget ?: return
    selectionPagerTarget = null
    old.poll?.let(old.handler::removeCallbacks)
    old.poll = null
}

internal fun ReaderHook.armSelectionPagerTarget(viewModel: Any, virtualPage: Int) {
    cancelSelectionPagerConfirmation()
    val page = (XposedHelpers.getObjectField(viewModel, "virtualPages") as? Map<*, *>)?.get(virtualPage)
        ?: run { XposedBridge.logError("ReaMicro selection pager target missing=$virtualPage"); return }
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return
    val pending = ReaderSelectionPagerTarget(
        WeakReference(viewModel), WeakReference(epub), WeakReference(page), virtualPage,
        SystemClock.elapsedRealtime() + 2_500L,
    )
    selectionPagerTarget = pending

    pending.poll = object : Runnable {
        override fun run() {
            if (selectionPagerTarget !== pending) return
            val native = runCatching { searchPageBridge?.settledSnapshot(viewModel) }.getOrNull()
            val observed = native?.takeIf { it.index == pending.virtualPage }?.page
            if (completeSelectionPagerIfReady(pending, observed, "native-pager", native?.index)) return
            pending.handler.postDelayed(this, 50L)
        }
    }.also { pending.handler.postDelayed(it, 50L) }
}

private fun ReaderHook.sameSelectionPage(actual: Any?, expected: Any?): Boolean {
    if (actual == null || expected == null) return false
    if (actual === expected) return true
    val document = callNoArg(expected, "getDocument") ?: return false
    val start = callNoArg(expected, "getStart") ?: return false
    val end = callNoArg(expected, "getEnd") ?: return false
    return callNoArg(actual, "getDocument") === document &&
        callNoArg(actual, "getStart") == start && callNoArg(actual, "getEnd") == end
}

private fun ReaderHook.completeSelectionPagerIfReady(
    pending: ReaderSelectionPagerTarget, observed: Any?, source: String, actualIndex: Int? = null,
): Boolean {
    if (selectionPagerTarget !== pending) return true
    val owner = pending.viewModel.get()
    val epub = pending.epub.get()
    val sameSession = owner != null && epub != null && currentViewModelRef?.get() === owner &&
        (currentEpubStrong ?: currentEpubRef?.get()) === epub
    val expected = pending.page.get()
    val state = readerPagerConfirmation(sameSession, expected != null,
        sameSelectionPage(observed, expected), SystemClock.elapsedRealtime(), pending.deadline)
    if (state == ReaderPagerConfirmation.WAITING) return false
    cancelSelectionPagerConfirmation()
    when (state) {
        ReaderPagerConfirmation.ACKNOWLEDGED -> {
            currentPageRef = WeakReference(observed)
            currentPageStrong = observed
            currentVisiblePageSignature = epubPageSignature(observed!!)
            currentVisiblePageNumber = epubPageNumber(observed)
            XposedBridge.log("ReaMicro selection pager acknowledged=true source=$source target=${pending.virtualPage} mode=$currentSearchPagerMode discarded=${pending.discarded}")
        }
        ReaderPagerConfirmation.EXPIRED -> {
            XposedBridge.logError("ReaMicro selection pager confirmation expired target=${pending.virtualPage} actual=$actualIndex mode=$currentSearchPagerMode; file saved")
            activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed }?.let {
                Toast.makeText(it, "修改已保存；阅读位置未确认，请检查当前位置", Toast.LENGTH_LONG).show()
            }
        }
        else -> XposedBridge.log("ReaMicro selection pager confirmation cancelled: session changed")
    }
    return true
}

internal fun ReaderHook.filterSelectionPagerTransition(viewModel: Any, page: Any): Boolean {
    val pending = selectionPagerTarget ?: return false
    if (pending.viewModel.get() !== viewModel) return false
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
    if (pending.discarded == 1) XposedBridge.log(
        "ReaMicro selection pager waiting target=${pending.virtualPage}; ignored transitional Statistics",
    )
    return true
}
