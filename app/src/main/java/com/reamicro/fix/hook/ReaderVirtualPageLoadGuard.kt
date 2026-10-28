package com.reamicro.fix.hook

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostMethod

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostFieldOrNull
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicLong

internal class ReaderVirtualPageLoadGuard(private val reader: ReaderHook) {
    fun install() {
        val loader = reader.classLoader
        val vm = loader.hostClass("app.zhendong.reamicro.ui.reader.ReaderViewModel")
        val epub = loader.hostClass("app.zhendong.reamicro.data.epub.Epub")
        val continuation = loader.hostClass("kotlin.coroutines.Continuation")
        val pagesField = vm.hostField("virtualPages")
        val spinesField = vm.hostFieldOrNull("spinePagesCache")
        val methods = listOf("ensureVirtualPageWindow", "ensureVirtualPageMapped").map { name ->
            val method = vm.hostMethodsNamed(name).single()
            val parameters = method.parameterTypes
            val int = Int::class.javaPrimitiveType
            val expected = when (name) {
                "ensureVirtualPageWindow" -> parameters.size == 4 && parameters[0] == int &&
                    parameters[1] == epub && parameters[2] == int && continuation.isAssignableFrom(parameters[3])
                else -> parameters.size == 3 && parameters[0] == int &&
                    if (HostSymbols.isIdentity) parameters[1] == epub && continuation.isAssignableFrom(parameters[2])
                    else continuation.isAssignableFrom(parameters[1]) && parameters[2] == epub
            }
            require(expected && method.returnType == Any::class.java) { "Virtual load contract differs: $method" }
            method
        }
        val unit = requireNotNull(reader.targetUnit()) { "Host Kotlin Unit unavailable" }
        val discarded = AtomicLong()
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== reader.currentViewModelRef?.get() || !reader.canRunFullTextSearch()) return
                val args = param.args ?: return
                // 协程恢复调用会传入 null EPUB 和占位页码，必须交还原状态机继续解包结果。
                val epubIndex = (param.method as java.lang.reflect.Method).parameterTypes.indexOfFirst {
                    epub.isAssignableFrom(it)
                }
                if (!epub.isInstance(args.getOrNull(epubIndex))) return
                val requested = args[0] as Int
                // 两个入口均由宿主持有 virtualPageLoadMutex 时调用；在此检查才能拦住锁前已排队的旧请求。
                @Suppress("UNCHECKED_CAST")
                val pages = pagesField.get(param.thisObject) as Map<Int, *>
                if (ReaderVirtualPageLoadPolicy.accepts(requested, pages.keys)) return
                // p/q 的所有正常出口均返回原生 Unit；不可把此守卫移到返回 List/Integer 的内部方法。
                param.result = unit
                val count = discarded.incrementAndGet()
                if (count <= 4 || count % 64L == 0L) {
                    val spines = spinesField?.get(param.thisObject) as? Map<*, *>
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
