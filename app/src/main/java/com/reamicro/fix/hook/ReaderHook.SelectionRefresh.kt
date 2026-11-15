package com.reamicro.fix.hook
import com.reamicro.fix.core.HostReaderWindowBindings


import android.os.SystemClock
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Method

internal data class ReaderSelectionRefreshResult(val virtualPage: Int?, val refreshError: String? = null)

internal fun ReaderHook.saveAndReloadReaderSelection(
    viewModel: Any, epub: Any, spinePosition: Int, anchor: Any, restoredAnchor: Any?, write: () -> Unit,
): ReaderSelectionRefreshResult {
    checkSelectionEditSession(viewModel, epub)
    cancelSelectionPagerConfirmation()

    onDemandRefreshCoordinator.cancelActiveIf { true }
    val host = requireNotNull(HostReaderWindowBindings.current) { "Reader window contract unavailable" }
    val mutex = host.field("vm.mutex").get(viewModel)
        ?: error("宿主缺少章节加载锁，未写入")
    val lease = com.reamicro.fix.core.HostMutex(mutex, Any())
    var locked = false
    var saved = false
    var window: ReaderSelectionWindow? = null
    try {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (!locked && SystemClock.elapsedRealtime() < deadline) {
            checkSelectionEditSession(viewModel, epub)
            locked = lease.tryAcquire()
            if (!locked) Thread.sleep(25)
        }
        check(locked) { "章节仍在加载，请稍后保存；未修改文件" }
        checkSelectionEditSession(viewModel, epub)
        val method = findOnDemandRecenterMethod(viewModel, epub, anchor) ?: error("宿主重排接口不匹配，未写入")

        val oldPages = host.pages(viewModel)
        val oldMapping = host.forward(viewModel)
        val oldKey = oldPages?.entries?.firstOrNull { (_, value) ->
            value != null && host.contains(value, anchor)
        }?.key
        val oldLocalPage = oldMapping[oldKey]?.let { host.pair(it).second } ?: 0
        write()
        saved = true
        checkSelectionEditSession(viewModel, epub)
        window = (oldKey as? Number)?.toInt()?.takeIf { selectionWindowHookInstalled }?.let {
            ReaderSelectionWindow(viewModel, it, spinePosition, oldLocalPage, restoredAnchor)
        }
        selectionWindow = window
        val page = (invokeSelectionRecenter(viewModel, method, spinePosition, epub, restoredAnchor) as? Number)?.toInt()
            ?: error("章节重排没有返回有效页码")
        var target: Int? = null
        var targetPage: Any? = null
        val readbackDeadline = SystemClock.elapsedRealtime() + 2_000L
        while (targetPage == null && SystemClock.elapsedRealtime() < readbackDeadline) {
            checkSelectionEditSession(viewModel, epub)
            val pages = host.pages(viewModel)
            val mapping = host.forward(viewModel)
            val typedMapping = mapping.orEmpty().entries.mapNotNull { (key, pair) ->
                val virtual = (key as? Number)?.toInt() ?: return@mapNotNull null
                if (pair == null) return@mapNotNull null
                virtual to host.pair(pair)
            }.toMap()
            val available = pages.orEmpty().keys.mapNotNull { (it as? Number)?.toInt() }.toSet()
            val contained = typedMapping.keys.filter { key ->
                if (typedMapping[key]?.first != spinePosition) return@filter false
                val page = pages[key]
                restoredAnchor != null && page != null && host.contains(page, restoredAnchor)
            }.toSet()
            target = com.reamicro.fix.epub.editor.selectionSameChapterPage(
                typedMapping, available, spinePosition, oldLocalPage, contained,
            )
            targetPage = target?.let { pages?.get(it) }
            if (targetPage == null) Thread.sleep(40L)
        }
        val resolvedTarget = target ?: return ReaderSelectionRefreshResult(null, "原章节已无可阅读页面，请从目录选择阅读位置")
        val resolvedPage = targetPage ?: error("同章目标页面不存在")
        val containsRestoredAnchor = restoredAnchor != null && host.contains(resolvedPage, restoredAnchor)
        val freshAnchor = if (containsRestoredAnchor) restoredAnchor!! else host.start(resolvedPage)

        persistSelectionReadingPosition(viewModel, epub, freshAnchor)
        HookRuntime.log("ReaMicro selection position persisted after file commit and reflow")
        HookRuntime.log("ReaMicro selection window-preserved=${window?.applied == true} oldSlot=$oldKey")
        HookRuntime.log("ReaMicro selection refresh spine=$spinePosition hostPage=$page target=$resolvedTarget anchorFallback=${!containsRestoredAnchor}")
        return ReaderSelectionRefreshResult(resolvedTarget)
    } catch (error: Throwable) {
        if (!saved) throw error
        HookRuntime.logError("ReaMicro LSP selection file saved but refresh failed: ${error.stackTraceToString()}")
        return ReaderSelectionRefreshResult(null, error.message)
    } finally {
        if (window != null && selectionWindow === window) selectionWindow = null
        if (locked) runCatching { lease.release() }
            .onFailure { HookRuntime.logError("ReaMicro LSP selection load mutex unlock failed: ${it.message}") }
    }
}

private fun ReaderHook.invokeSelectionRecenter(
    target: Any, method: Method, spine: Int, epub: Any, cfi: Any?,
): Any? = invokeSelectionHostSuspend { continuation ->
    invokeOnDemandRecenter(method, target, spine, epub, cfi, continuation)
}

internal fun ReaderHook.invokeSelectionHostSuspend(
    parentContext: Any = emptyCoroutineContext(), operation: (Any?) -> Any?,
): Any? {
    return com.reamicro.fix.core.HostCoroutines.runBlocking(classLoader, parentContext, 20_000L, operation)
}
