package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderLifecycleBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

internal class ReaderSearchJumpBridge(private val reader: ReaderHook) {
    fun install() {
        com.reamicro.fix.core.HookInstallReport.install("ReaderHook", "virtualPageLoadGuard") {
            ReaderVirtualPageLoadGuard(reader).install()
        }
        reader.searchScrollBridge = ReaderSearchScrollBridge(reader).also { it.install() }
        reader.searchPageBridge = ReaderSearchPageBridge(reader).also { it.install() }
        val host = requireNotNull(HostReaderLifecycleBindings.current) { "Reader lifecycle contract unavailable" }
        val captureHook = HookRuntime.hookMethod(host.method("capture"), object : HookCallback() {
            override fun before(param: Call) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || reader.pendingSearchReturnCaptures <= 0) return
                reader.pendingSearchReturnCaptures -= 1
                param.result = null
            }
        })
        val onIntentCallback = object : HookCallback() {
            override fun before(param: Call) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || reader.dispatchingSearchJump) return
                val intent = param.args?.firstOrNull()?.let(host::intentName) ?: return
                if (intent !in setOf("MarkJump", "BookmarkJump", "JumpChapter", "JumpProgress", "ReturnToOriginalPosition")) return

                reader.pendingSearchReturnCaptures = 0
                reader.clearStaleSearchNavigation()
            }
        }
        try {
            HookRuntime.hookMethod(host.method("dispatch"), onIntentCallback)
        } catch (error: Throwable) {
            captureHook.unhook()
            throw error
        }
        HookRuntime.deoptimize(host.method("dispatch"))
    }
}

internal fun ReaderHook.showSearchReaderContent(viewModel: Any): Boolean {
    if (!readerBottomMenuVisible) return true
    return runCatching {
        val host = requireNotNull(HostReaderLifecycleBindings.current)
        val intent = host.constructor("UpdateUIStatus").newInstance(host.instance("status.reader"))
        host.method("dispatch").invoke(viewModel, intent)
        true
    }.getOrElse {
        HookRuntime.logError("ReaMicro search close menu failed type=${it.javaClass.simpleName}", it)
        false
    }
}

internal fun ReaderHook.clearHostSearchJump() {
    val vm = currentViewModelRef?.get() ?: return
    val previous = dispatchingSearchJump
    dispatchingSearchJump = true
    try {
        val host = requireNotNull(HostReaderLifecycleBindings.current)
        host.method("dispatch").invoke(vm, host.instance("intent.ClearJump"))
    } catch (e: Exception) {
        HookRuntime.log("ReaMicro search ClearJump failed: $e")
    } finally { dispatchingSearchJump = previous }
}

internal fun ReaderHook.jumpToSearchCfi(receiver: Any?, viewModel: Any?, cfi: String, mark: Any? = null,
    chapterIndex: Int, title: String, summary: String): Boolean {
    val previous = dispatchingSearchJump
    dispatchingSearchJump = true
    pendingSearchReturnCaptures += 1
    var dispatched = false
    try {
        dispatched = jumpToCfi(receiver, viewModel, cfi, mark, chapterIndex, title, summary)
        return dispatched
    } finally {
        dispatchingSearchJump = previous
        if (!dispatched) pendingSearchReturnCaptures = (pendingSearchReturnCaptures - 1).coerceAtLeast(0)
    }
}
