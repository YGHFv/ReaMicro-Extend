package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

internal class ReaderSearchScrollBridge(private val reader: ReaderHook) {
    private var owner = WeakReference<Any>(null)
    private var state = WeakReference<Any>(null)
    private var items = WeakReference<Any>(null)
    data class Command(val state: Any, val index: Int, val offset: Int)
    fun install() {
        val base = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt"
        val methods = reader.classLoader.loadClass(base).declaredMethods.filter { it.name == "ScrollPager" }
        check(methods.isNotEmpty()) { "$base.ScrollPager not found" }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val vm = param.args?.firstOrNull() ?: return
                    val active = reader.currentViewModelRef?.get()
                    if (active == null || active === vm) reader.currentSearchPagerMode = 3
                }
            })
        }
        for (suffix in listOf("\$ScrollPager\$3\$1", "\$ScrollPager\$5\$1")) HookInstallReport.install("ReaderHook", "scrollCapture.$suffix") {
            XposedBridge.hookAllConstructors(reader.classLoader.loadClass(base + suffix), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val obj = param.thisObject ?: return
                        val vm = XposedHelpers.getObjectField(obj, "\$this_ScrollPager") ?: return
                        val active = reader.currentViewModelRef?.get()
                        if (reader.currentSearchPagerMode != 3 || (active != null && active !== vm)) return
                        state = WeakReference(XposedHelpers.getObjectField(obj, "\$listState"))
                        items = WeakReference(XposedHelpers.getObjectField(obj, "\$items"))
                        owner = WeakReference(vm)
                    }
                }
            })
        }
    }

    data class Snapshot(val elements: List<Any>, val rootStyle: Any?)
    fun snapshot(vm: Any): Snapshot? {
        val elements = loadedElements(vm)
        if (elements.isEmpty()) return null
        val window = reader.classLoader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
        return Snapshot(elements, reader.callNoArg(window, "getRootStyle"))
    }
    @Suppress("UNCHECKED_CAST")
    private fun loadedElements(vm: Any): List<Any> {
        if (owner.get() !== vm || reader.currentSearchPagerMode != 3) return emptyList()
        val data = items.get() ?: return emptyList()
        val snapshot = reader.callNoArg(data, "getItemSnapshotList") ?: return emptyList()
        return (reader.callNoArg(snapshot, "getItems") as? List<Any>).orEmpty()
    }
    private var lastItems: List<Any> = emptyList()
    private var lastAnchor: Any? = null
    private var lastIndex: Int? = null
    fun clearCache() { lastItems = emptyList(); lastAnchor = null; lastIndex = null }
    fun command(vm: Any, target: ResolvedSearchTarget): Command? {
        if (owner.get() !== vm || reader.currentSearchPagerMode != 3) return null
        val list = state.get() ?: return null
        val anchor = target.scrollElementId ?: return null
        val loaded = loadedElements(vm)
        val index = if (loaded === lastItems && anchor == lastAnchor) lastIndex else {
            loaded.indexOfFirst { element ->
                val id = reader.callNoArg(element, "getId")
                id != null && XposedHelpers.callMethod(id, "compareTo", anchor) == 0
            }.takeIf { it >= 0 }.also { lastItems = loaded; lastAnchor = anchor; lastIndex = it }
        } ?: return null
        val layout = reader.callNoArg(list, "getLayoutInfo")
        val top = (reader.callNoArg(layout, "getViewportStartOffset") as? Number)?.toInt() ?: 0
        val bottom = (reader.callNoArg(layout, "getViewportEndOffset") as? Number)?.toInt() ?: 0
        val gap = ((bottom - top) / 3).coerceAtLeast(0)
        return Command(list, index, target.scrollGlyphOffsetPx - gap)
    }

    fun execute(command: Command, current: () -> Boolean) {
        val loader = reader.classLoader
        val continuation = loader.loadClass("kotlin.coroutines.Continuation")
        val f2 = loader.loadClass("kotlin.jvm.functions.Function2")
        val scroll = command.state.javaClass.getMethod("scrollToItem", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, continuation)
        val block = Proxy.newProxyInstance(loader, arrayOf(f2)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> if (current()) scroll.invoke(command.state, command.index, command.offset, args?.get(1)) else reader.targetUnit()
                "toString" -> "ReaMicroSearchScroll"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        val main = loader.loadClass("kotlinx.coroutines.Dispatchers").getMethod("getMain").invoke(null)
        val context = loader.loadClass("kotlin.coroutines.CoroutineContext")
        val withContext = loader.loadClass("kotlinx.coroutines.BuildersKt").getMethod("withContext", context, f2, continuation)
        reader.invokeSelectionHostSuspend { withContext.invoke(null, main, block, it) }
    }

    fun visible(vm: Any, target: ResolvedSearchTarget): Boolean {
        val command = command(vm, target) ?: return false
        val info = reader.callNoArg(command.state, "getLayoutInfo") ?: return false
        val visible = (reader.callNoArg(info, "getVisibleItemsInfo") as? List<*>).orEmpty()
        val item = visible.firstOrNull { (it?.let { reader.callNoArg(it, "getIndex") } as? Number)?.toInt() == command.index } ?: return false
        val offset = (reader.callNoArg(item, "getOffset") as Number).toInt()
        val top = (reader.callNoArg(info, "getViewportStartOffset") as Number).toInt()
        val bottom = (reader.callNoArg(info, "getViewportEndOffset") as Number).toInt()
        val y = offset.toLong() + target.scrollGlyphOffsetPx
        return y >= top && y + target.scrollGlyphHeightPx <= bottom
    }
}
