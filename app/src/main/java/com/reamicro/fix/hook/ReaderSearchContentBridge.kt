package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.core.typeMatches
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** Content 自己订阅搜索绘制代次，避免滚动段落缓存只刷新当前命中。 */
internal class ReaderSearchContentBridge(private val reader: ReaderHook) {
    private val depth = ThreadLocal.withInitial { 0 }

    fun install() {
        HookInstallReport.install("ReaderHook", "searchContentPaint") {
            val loader = reader.classLoader
            val localMarks = requireNotNull(loader.hostClass("org.epub.ui.BodyKt")
                .hostField("LocalEpubMarks").get(null))
            val content = loader.hostClass("org.epub.ui.ContentKt").hostMethodsNamed("Content")
                .filter { it.parameterTypes.firstOrNull()?.let { typeMatches(it, "org.epub.html.node.ContentDom") } == true }
            check(content.isNotEmpty()) { "Content paint entry unavailable" }
            content.forEach { method ->
                val composer = method.parameterTypes.indexOfFirst { typeMatches(it, "androidx.compose.runtime.Composer") }
                check(composer >= 0 && method.parameterTypes.getOrNull(composer + 1) == Int::class.javaPrimitiveType)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val dom = args.firstOrNull() ?: return
                        if (reader.observeSearchPaint(dom)) {
                            args[composer + 1] = ((args[composer + 1] as? Int) ?: 0) or 1
                        }
                        depth.set(depth.get() + 1)
                        param.setObjectExtra("reamicro.search.contentScope", true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra("reamicro.search.contentScope") == true) {
                            depth.set((depth.get() - 1).coerceAtLeast(0))
                        }
                    }
                })
            }
            val consume = loader.hostClass("androidx.compose.runtime.Composer").hostMethodsNamed("consume")
                .filter { it.parameterCount == 1 }
            check(consume.isNotEmpty()) { "Content local mark reader unavailable" }
            consume.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (depth.get() <= 0 || param.throwable != null || param.args?.firstOrNull() !== localMarks) return
                        val original = param.result as? List<*> ?: return
                        val next = reader.appendSearchResolvedMarker(original)
                        if (next != original) param.result = next
                    }
                })
            }
            XposedBridge.log("ReaMicro search Content paint bridge installed count=${content.size}/${consume.size}")
        }
    }
}
