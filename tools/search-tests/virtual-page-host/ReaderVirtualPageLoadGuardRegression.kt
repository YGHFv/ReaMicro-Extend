package com.reamicro.fix.hook

import app.zhendong.reamicro.data.epub.Epub
import app.zhendong.reamicro.ui.reader.ReaderViewModel
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.ref.WeakReference

internal class ReaderHook(owner: ReaderViewModel) {
    val classLoader: ClassLoader = javaClass.classLoader
    val currentViewModelRef = WeakReference(owner)
    var enabled = true
    fun canRunFullTextSearch() = enabled
    fun targetUnit() = Unit
}

internal fun runVirtualPageLoadGuardRegression() {
    val vm = ReaderViewModel()
    val reader = ReaderHook(vm)
    ReaderVirtualPageLoadGuard(reader).install()
    check(XposedBridge.hooks.size == 2)
    vm.virtualPages.putAll((4986..5011).associateWith { Any() })
    for (hook in XposedBridge.hooks.values) {
        fun invoke(owner: Any = vm, page: Int, epub: Epub? = Epub()) =
            XC_MethodHook.MethodHookParam(owner, arrayOf(page, epub, 10, null)).also(hook::beforeHookedMethod)
        check(invoke(page = 0).let { it.stopped && it.result === Unit })
        check(!invoke(page = 5000).stopped)
        check(!invoke(page = 4985).stopped)
        check(!invoke(page = 5012).stopped)
        // 已挂起的宿主状态机以 null EPUB 恢复时，即使页码是占位的 0 也不能截断。
        check(!invoke(page = 0, epub = null).stopped)
        check(!invoke(owner = ReaderViewModel(), page = 0).stopped)
        reader.enabled = false
        check(!invoke(page = 0).stopped)
        reader.enabled = true
        vm.virtualPages.clear()
        check(!invoke(page = 5000).stopped)
        vm.virtualPages.putAll((0..3).associateWith { Any() })
        check(invoke(page = 5000).stopped)
        check(!invoke(page = 4).stopped)
        vm.virtualPages.clear()
        vm.virtualPages.putAll((4986..5011).associateWith { Any() })
    }
    println("Virtual page hook regression passed: both entries, Unit return, coroutine resume, owner/settings gating")
}
