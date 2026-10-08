package com.reamicro.fix.hook

import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers

internal class ReaderSearchJumpBridge(private val reader: ReaderHook) {
    fun install() {
        reader.searchScrollBridge = ReaderSearchScrollBridge(reader).also { it.install() }
        reader.searchPageBridge = ReaderSearchPageBridge(reader).also { it.install() }
        val loader = reader.classLoader
        val vm = loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderViewModel")
        val book = loader.loadClass("app.zhendong.reamicro.data.db.entity.Book")
        val capture = vm.getDeclaredMethod("captureReturnPosition", book)
        val onIntent = vm.getDeclaredMethod("onIntent", loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderUiIntent"))
        XposedBridge.hookMethod(capture, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || reader.pendingSearchReturnCaptures <= 0) return
                reader.pendingSearchReturnCaptures -= 1
                param.result = null
            }
        })
        XposedBridge.hookMethod(onIntent, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || reader.dispatchingSearchJump) return
                val intent = param.args?.firstOrNull()?.javaClass?.simpleName ?: return
                if (intent !in setOf("MarkJump", "BookmarkJump", "JumpChapter", "JumpProgress", "ReturnToOriginalPosition")) return

                reader.pendingSearchReturnCaptures = 0
                reader.clearStaleSearchNavigation()
            }
        })
    }
}

internal fun ReaderHook.showSearchReaderContent(viewModel: Any): Boolean {
    if (!readerBottomMenuVisible) return true
    return runCatching {
        val statusClass = classLoader.loadClass("app.zhendong.reamicro.ui.reader.UiStatus")
        val readerStatus = classLoader.loadClass("app.zhendong.reamicro.ui.reader.UiStatus\$Reader")
            .getField("INSTANCE").get(null)
        val intent = classLoader.loadClass("app.zhendong.reamicro.ui.reader.ReaderUiIntent\$UpdateUIStatus")
            .getConstructor(statusClass).newInstance(readerStatus)
        XposedHelpers.callMethod(viewModel, "onIntent", intent)
        true
    }.getOrElse {
        XposedBridge.log("ReaMicro search close menu failed type=${it.javaClass.simpleName}")
        false
    }
}

internal fun ReaderHook.clearHostSearchJump() {
    val vm = currentViewModelRef?.get() ?: return
    val previous = dispatchingSearchJump
    dispatchingSearchJump = true
    try {
        val type = classLoader.loadClass("app.zhendong.reamicro.ui.reader.ReaderUiIntent\$ClearJump")
        XposedHelpers.callMethod(vm, "onIntent", type.getField("INSTANCE").get(null))
    } catch (e: Exception) {
        XposedBridge.log("ReaMicro search ClearJump failed: $e")
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
