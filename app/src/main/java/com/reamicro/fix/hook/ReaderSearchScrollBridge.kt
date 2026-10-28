package com.reamicro.fix.hook

import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostNameMatches

import com.reamicro.fix.core.hostClass
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
        val methods = reader.classLoader.hostClass(base).declaredMethods.filter { it.hostNameMatches("ScrollPager") }
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
        // 旧宿主保留协程捕获；B2 改用真实 LazyList 闭包。
        if (com.reamicro.fix.core.HostSymbols.isIdentity) {
            for (suffix in listOf("\$ScrollPager\$3\$1", "\$ScrollPager\$5\$1")) HookInstallReport.install("ReaderHook", "scrollCapture.$suffix") {
                XposedBridge.hookAllConstructors(reader.classLoader.hostClass(base + suffix), object : XC_MethodHook() {
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
        HookInstallReport.install("ReaderHook", "scrollCapture.liveLazyList") {
            val loader = reader.classLoader
            val lazyState = loader.hostClass("androidx.compose.foundation.lazy.LazyListState")
            val paging = loader.hostClass("androidx.paging.compose.LazyPagingItems")
            val lambda = loader.hostClass("ReaMicroHost.ScrollLambda")
            var captures = 0
            lambda.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val active = reader.currentViewModelRef?.get() ?: return
                        val newState = args.firstOrNull { lazyState.isInstance(it) }
                        val newItems = args.firstOrNull { paging.isInstance(it) }
                        if (newState != null) state = WeakReference(newState)
                        if (newItems != null) items = WeakReference(newItems)
                        if (newState != null || newItems != null) {
                            owner = WeakReference(active)
                            captures++
                            if (captures <= 3 || captures % 16 == 0) {
                                XposedBridge.log("ReaMicro scroll live capture count=$captures state=${newState != null} items=${newItems != null}")
                            }
                        }
                    }
                })
            }
        }
    }

    data class Snapshot(val elements: List<Any>, val rootStyle: Any?)
    fun snapshot(vm: Any): Snapshot? {
        val elements = loadedElements(vm)
        if (elements.isEmpty()) return null
        return Snapshot(elements, reader.epubWindow.callOrNull("getRootStyle"))
    }
    fun currentElement(vm: Any): Any? {
        if (owner.get() !== vm) return null
        val elements = loadedElements(vm)
        if (elements.isEmpty()) return null
        val state = state.get() ?: return elements.firstOrNull()
        val index = (reader.callNoArg(state, "getFirstVisibleItemIndex") as? Number)?.toInt() ?: return null
        return elements.getOrNull(index - placeholdersBefore(vm))
    }
    fun readingTarget(vm: Any): com.reamicro.fix.hook.reader.ReadingTarget? {
        val element = currentElement(vm) ?: return null
        val cfi = reader.callNoArg(element, "getId")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val list = state.get() ?: return null
        val offset = (reader.callNoArg(list, "getFirstVisibleItemScrollOffset") as? Number)?.toInt() ?: return null
        return com.reamicro.fix.hook.reader.ReadingTarget(cfi,
            (reader.callNoArg(element, "getChapterIndex") as? Number)?.toInt() ?: 0,
            reader.callString(element, "getTitle"), "", offset)
    }
    fun originCommand(vm: Any, target: com.reamicro.fix.hook.reader.ReadingTarget): Command? {
        if (owner.get() !== vm || reader.currentSearchPagerMode != 3) return null
        val offset = target.scrollOffsetPx ?: return null
        val list = state.get() ?: return null
        val index = loadedElements(vm).indexOfFirst { reader.callNoArg(it, "getId")?.toString() == target.cfi }
        return if (index >= 0) Command(list, index + placeholdersBefore(vm), offset) else null
    }
    @Suppress("UNCHECKED_CAST")
    private fun loadedElements(vm: Any): List<Any> {
        if (owner.get() !== vm || reader.currentSearchPagerMode != 3) return emptyList()
        val data = items.get() ?: return emptyList()
        val snapshot = reader.callNoArg(data, "getItemSnapshotList") ?: return emptyList()
        val direct = reader.callNoArg(snapshot, "getItems") as? List<Any>
        if (direct != null) return direct.filterNotNull()
        return runCatching {
            snapshot.javaClass.declaredFields
                .firstOrNull { List::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(snapshot) as? List<Any>
        }.getOrNull().orEmpty().filterNotNull()
    }
    private fun placeholdersBefore(vm: Any): Int {
        if (owner.get() !== vm) return 0
        val snapshot = items.get()?.let { reader.callNoArg(it, "getItemSnapshotList") } ?: return 0
        return (reader.callNoArg(snapshot, "getPlaceholdersBefore") as? Number)?.toInt() ?: 0
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
        return Command(list, index + placeholdersBefore(vm), target.scrollGlyphOffsetPx)
    }

    fun execute(command: Command, current: () -> Boolean) {
        val loader = reader.classLoader
        val continuation = loader.hostClass("kotlin.coroutines.Continuation")
        val f2 = loader.hostClass("kotlin.jvm.functions.Function2")
        val scroll = command.state.javaClass.hostMethod("scrollToItem", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, continuation)
        val block = Proxy.newProxyInstance(loader, arrayOf(f2)) { proxy, method, args ->
            when (com.reamicro.fix.core.HostSymbols.logicalMethodName(method)) {
                "invoke" -> if (current()) scroll.invoke(command.state, command.index, command.offset, args?.get(1)) else reader.targetUnit()
                "toString" -> "ReaMicroSearchScroll"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        val main = com.reamicro.fix.core.HostCoroutines.mainDispatcher(loader)
        val context = loader.hostClass("kotlin.coroutines.CoroutineContext")
        val withContext = loader.hostClass("kotlinx.coroutines.BuildersKt").hostMethod("withContext", context, f2, continuation)
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
