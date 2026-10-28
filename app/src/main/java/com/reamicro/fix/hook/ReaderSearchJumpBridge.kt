package com.reamicro.fix.hook

import com.reamicro.fix.core.hostField

import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.hostMethod

internal class ReaderSearchJumpBridge(private val reader: ReaderHook) {
    fun install() {
        com.reamicro.fix.core.HookInstallReport.install("ReaderHook", "virtualPageLoadGuard") {
            ReaderVirtualPageLoadGuard(reader).install()
        }
        reader.searchScrollBridge = ReaderSearchScrollBridge(reader).also { it.install() }
        reader.searchPageBridge = ReaderSearchPageBridge(reader).also { it.install() }
        val loader = reader.classLoader
        val vm = loader.hostClass("app.zhendong.reamicro.ui.reader.ReaderViewModel")
        val book = loader.hostClass("app.zhendong.reamicro.data.db.entity.Book")
        val capture = vm.hostMethod("captureReturnPosition", book)
        // R8 在 2.3.3-b3 只保留父接口重载 onIntent(arch.UiIntent)，精确签名匹配会抛
        // NoSuchMethodException 让整条 searchJumpBridge 装不上；与 ReaderHook 主链一致，
        // 按「同名 + 单参 + 形参能接收 ReaderUiIntent」收集后逐个挂钩。
        val readerUiIntentClass = loader.hostClass("app.zhendong.reamicro.ui.reader.ReaderUiIntent")
        val onIntentMethods = vm.declaredMethods.filter { method ->
            method.hostNameMatches("onIntent") &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(readerUiIntentClass)
        }
        if (onIntentMethods.isEmpty()) {
            error("ReaderViewModel onIntent(${readerUiIntentClass.name}) not found on ${vm.name}")
        }
        XposedBridge.hookMethod(capture, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || reader.pendingSearchReturnCaptures <= 0) return
                reader.pendingSearchReturnCaptures -= 1
                param.result = null
            }
        })
        val onIntentCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || reader.dispatchingSearchJump) return
                val intent = param.args?.firstOrNull()?.javaClass?.name
                    ?.let(HostSymbols::logicalSimpleName) ?: return
                if (intent !in setOf("MarkJump", "BookmarkJump", "JumpChapter", "JumpProgress", "ReturnToOriginalPosition")) return

                reader.pendingSearchReturnCaptures = 0
                reader.clearStaleSearchNavigation()
            }
        }
        onIntentMethods.forEach { XposedBridge.hookMethod(it, onIntentCallback) }
    }
}

internal fun ReaderHook.showSearchReaderContent(viewModel: Any): Boolean {
    if (!readerBottomMenuVisible) return true
    return runCatching {
        val statusClass = classLoader.hostClass("app.zhendong.reamicro.ui.reader.UiStatus")
        val readerStatus = classLoader.hostClass("app.zhendong.reamicro.ui.reader.UiStatus\$Reader")
            .hostField("INSTANCE").get(null)
        val intent = classLoader.hostClass("app.zhendong.reamicro.ui.reader.ReaderUiIntent\$UpdateUIStatus")
            .getConstructor(statusClass).newInstance(readerStatus)
        XposedHelpers.callMethod(viewModel, "onIntent", intent)
        true
    }.getOrElse {
        XposedBridge.logError("ReaMicro search close menu failed type=${it.javaClass.simpleName}", it)
        false
    }
}

internal fun ReaderHook.clearHostSearchJump() {
    val vm = currentViewModelRef?.get() ?: return
    val previous = dispatchingSearchJump
    dispatchingSearchJump = true
    try {
        val type = classLoader.hostClass("app.zhendong.reamicro.ui.reader.ReaderUiIntent\$ClearJump")
        XposedHelpers.callMethod(vm, "onIntent", type.hostField("INSTANCE").get(null))
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
