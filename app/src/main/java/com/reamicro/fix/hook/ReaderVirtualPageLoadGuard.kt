package com.reamicro.fix.hook

import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicLong

internal class ReaderVirtualPageLoadGuard(private val reader: ReaderHook) {
    fun install() {
        val loader = reader.classLoader
        val vm = loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderViewModel")
        val epub = loader.loadClass("app.zhendong.reamicro.data.epub.Epub")
        val continuation = loader.loadClass("kotlin.coroutines.Continuation")
        val integer = Int::class.javaPrimitiveType!!
        val methods = listOf(
            vm.getDeclaredMethod("ensureVirtualPageWindow", integer, epub, integer, continuation),
            vm.getDeclaredMethod("ensureVirtualPageMapped", integer, epub, continuation),
        )
        val unit = requireNotNull(reader.targetUnit()) { "Host Kotlin Unit unavailable" }
        val discarded = AtomicLong()
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || !reader.canRunFullTextSearch()) return
                val args = param.args ?: return
                // 协程恢复调用会传入 null EPUB 和占位页码，必须交还原状态机继续解包结果。
                if (!epub.isInstance(args.getOrNull(1))) return
                val requested = args[0] as Int
                // 两个入口均由宿主持有 virtualPageLoadMutex 时调用；在此检查才能拦住锁前已排队的旧请求。
                @Suppress("UNCHECKED_CAST")
                val pages = XposedHelpers.getObjectField(param.thisObject, "virtualPages") as Map<Int, *>
                if (ReaderVirtualPageLoadPolicy.accepts(requested, pages.keys)) return
                param.result = unit
                val count = discarded.incrementAndGet()
                if (count <= 4 || count % 64L == 0L) {
                    val spines = XposedHelpers.getObjectField(param.thisObject, "spinePagesCache") as? Map<*, *>
                    XposedBridge.log("ReaMicro stale virtual page load dropped page=$requested " +
                        "window=${pages.keys.minOrNull()}..${pages.keys.maxOrNull()} " +
                        "spines=${spines?.size} count=$count")
                }
            }
        }
        val installed = ArrayList<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            methods.forEach { installed += XposedBridge.hookMethod(it, hook) }
        } catch (error: Throwable) {
            installed.asReversed().forEach { it.unhook() }
            throw error
        }
    }
}
