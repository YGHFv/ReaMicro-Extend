package com.reamicro.fix.hook

import android.graphics.RectF
import com.reamicro.fix.core.HostEpubSourceBindings
import com.reamicro.fix.core.HostHighlightTextBindings
import com.reamicro.fix.core.HostReaderScrollBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import kotlin.math.roundToInt

internal class ReaderSearchScrollBridge(private val reader: ReaderHook) {
    private var owner = WeakReference<Any>(null)
    private var state = WeakReference<Any>(null)
    private var items = WeakReference<Any>(null)
    private val source get() = requireNotNull(HostEpubSourceBindings.current)
    private data class ElementCapture(val owner: WeakReference<Any>, val element: WeakReference<Any>,
        val controller: WeakReference<Any>, val viewport: WeakReference<Any>)
    private val elements = ArrayList<ElementCapture>()
    private var anchors = WeakReference<Map<*, *>>(null)
    data class Command(val state: Any, val index: Int, val offset: Int)
    private val host get() = requireNotNull(HostReaderScrollBindings.current) { "Reader scroll contract unavailable" }
    private class Capture(val owner: Any) { var list: Any? = null; var items: Any? = null }
    private val scopes = ThreadLocal<ArrayDeque<Capture>>()
    private fun bindElement(element: Any, controller: Any, viewport: Any) {
        val vm = reader.currentViewModelRef?.get() ?: return
        if (reader.currentSearchPagerMode != 3) return
        val contract = source
        if (!contract.type("scroll.element").isInstance(element) || !contract.type("controller").isInstance(controller) ||
            !contract.type("scroll.viewport").isInstance(viewport)) return
        synchronized(elements) {
            elements.removeAll { it.owner.get() !== vm || it.element.get() == null || it.element.get() === element }
            if (elements.size >= 64) elements.removeAt(0)
            elements += ElementCapture(WeakReference(vm), WeakReference(element), WeakReference(controller), WeakReference(viewport))
        }
    }
    private fun bind(vm: Any, list: Any, paging: Any) {
        val symbols = host
        if (!symbols.type("vm").isInstance(vm) || !symbols.type("list").isInstance(list) || !symbols.type("paging").isInstance(paging)) return
        if (reader.currentViewModelRef?.get()?.let { it !== vm } == true) return
        if (reader.currentSearchPagerMode != 3 && scopes.get()?.peekLast()?.owner !== vm) return
        if (owner.get()?.let { it !== vm } == true) anchors.clear()
        synchronized(elements) { elements.removeAll { it.owner.get() !== vm } }
        if (owner.get() !== vm || state.get() !== list || items.get() !== paging) clearCache()
        owner = WeakReference(vm); state = WeakReference(list); items = WeakReference(paging)
        reader.currentSearchPagerMode = 3
    }
    fun install() {
        val symbols = host
        val content = source
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.method("entry"), object : HookCallback() {
                override fun before(param: Call) {
                    val receiver = param.args?.getOrNull(symbols.index("owner")) ?: return
                    if (!symbols.type("vm").isInstance(receiver) || reader.currentViewModelRef?.get()?.let { it !== receiver } == true) return
                    val stack = scopes.get() ?: ArrayDeque<Capture>().also(scopes::set)
                    val capture = Capture(receiver)
                    stack.addLast(capture)
                    param.setObjectExtra("reamicro.scroll.capture", capture)
                    reader.currentSearchPagerMode = 3
                }
                override fun after(param: Call) {
                    val capture = param.getObjectExtra("reamicro.scroll.capture") as? Capture ?: return
                    val stack = scopes.get() ?: return
                    check(stack.pollLast() === capture)
                    if (stack.isEmpty()) scopes.remove()
                    if (param.throwable == null && capture.list != null && capture.items != null)
                        bind(capture.owner, requireNotNull(capture.list), requireNotNull(capture.items))
                }
            })
            for (role in listOf("rememberList", "collectItems")) handles += HookRuntime.hookMethod(symbols.method(role), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    val scope = scopes.get()?.peekLast() ?: return
                    if (role == "rememberList") scope.list = param.result else scope.items = param.result
                }
            })
            for (role in symbols.captures()) {
                handles += HookRuntime.hookMethod(symbols.constructor(role), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val args = param.args ?: return
                        val vm = args.getOrNull(symbols.index("$role.owner")) ?: return
                        val list = args.getOrNull(symbols.index("$role.list")) ?: return
                        val paging = args.getOrNull(symbols.index("$role.paging")) ?: return
                        bind(vm, list, paging)
                    }
                })
                val capture = object : HookCallback() {
                    override fun before(param: Call) {
                        val closure = param.thisObject ?: return
                        val vm = symbols.field("$role.owner").get(closure) ?: return
                        val list = symbols.field("$role.list").get(closure) ?: return
                        val paging = symbols.field("$role.paging").get(closure) ?: return
                        bind(vm, list, paging)
                    }
                }
                handles += HookRuntime.hookMethod(symbols.method("$role.invoke"), capture)
                handles += HookRuntime.hookMethod(symbols.method("$role.resume"), capture)
            }
            handles += HookRuntime.hookMethod(content.constructor("scroll.capture"), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    val args = param.args ?: return
                    bindElement(args[content.index("scroll.capture.element")] ?: return,
                        args[content.index("scroll.capture.controller")] ?: return,
                        args[content.index("scroll.capture.viewport")] ?: return)
                }
            })
            handles += HookRuntime.hookMethod(content.method("scroll.capture"), object : HookCallback() {
                override fun before(param: Call) {
                    val closure = param.thisObject ?: return
                    bindElement(content.required("scroll.capture.element", closure), content.required("scroll.capture.controller", closure),
                        content.required("scroll.capture.viewport", closure))
                }
            })
            if (content.index("scroll.structured") == 1) handles += HookRuntime.hookMethod(content.method("scroll.render"), object : HookCallback() {
                override fun before(param: Call) {
                    if (reader.currentSearchPagerMode != 3) return
                    val args = param.args ?: return
                    val element = args.getOrNull(content.index("scroll.element")) ?: return
                    for (callback in args.filterNotNull().filter(content.type("scroll.anchor.registration")::isInstance)) {
                        if (content.value("scroll.anchor.registration.element", callback) !== element) continue
                        val map = content.value("scroll.anchor.registration.map", callback) as? Map<*, *> ?: continue
                        anchors = WeakReference(map)
                    }
                }
            })
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        HookRuntime.deoptimize(symbols.method("entry"))
        HookRuntime.deoptimize(content.method("scroll.render"))
        HookRuntime.deoptimize(content.method("scroll.capture"))
        ModuleResources.cleanup { scopes.remove(); owner.clear(); state.clear(); items.clear(); anchors.clear()
            synchronized(elements) { elements.clear() }; clearCache() }
    }

    data class Snapshot(val elements: List<Any>, val rootStyle: Any?)
    fun snapshot(vm: Any): Snapshot? {
        val elements = loadedElements(vm)
        if (elements.isEmpty()) return null
        return Snapshot(elements, host.method("rootStyle").invoke(null))
    }
    fun currentElement(vm: Any): Any? {
        if (owner.get() !== vm) return null
        val elements = loadedElements(vm)
        if (elements.isEmpty()) return null
        val state = state.get() ?: return elements.firstOrNull()
        val index = (host.method("index").invoke(state) as? Number)?.toInt() ?: return null
        return elements.getOrNull(index - placeholdersBefore(vm))
    }
    fun readingTarget(vm: Any): com.reamicro.fix.hook.reader.ReadingTarget? {
        val element = currentElement(vm) ?: return null
        val cfi = host.field("element.id").get(element)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val list = state.get() ?: return null
        val offset = (host.method("offset").invoke(list) as? Number)?.toInt() ?: return null
        return com.reamicro.fix.hook.reader.ReadingTarget(cfi,
            (host.field("element.chapter").get(element) as? Number)?.toInt() ?: 0,
            (host.field("element.title").get(element) as? String).orEmpty(), "", offset)
    }
    fun originCommand(vm: Any, target: com.reamicro.fix.hook.reader.ReadingTarget): Command? {
        if (owner.get() !== vm || reader.currentSearchPagerMode != 3) return null
        val offset = target.scrollOffsetPx ?: return null
        val list = state.get() ?: return null
        val index = loadedElements(vm).indexOfFirst { host.field("element.id").get(it)?.toString() == target.cfi }
        return if (index >= 0) Command(list, index + placeholdersBefore(vm), offset) else null
    }
    @Suppress("UNCHECKED_CAST")
    private fun loadedElements(vm: Any): List<Any> {
        if (owner.get() !== vm || reader.currentSearchPagerMode != 3) return emptyList()
        val data = items.get() ?: return emptyList()
        val snapshot = host.method("snapshot").invoke(data) ?: return emptyList()
        val elements = host.field("snapshot.items").get(snapshot) as? List<*> ?: return emptyList()
        if (elements.any { !host.type("element").isInstance(it) }) return emptyList()
        return elements as List<Any>
    }
    private fun placeholdersBefore(vm: Any): Int {
        if (owner.get() !== vm) return 0
        val snapshot = items.get()?.let { host.method("snapshot").invoke(it) } ?: return 0
        return host.field("snapshot.before").getInt(snapshot)
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
                val id = host.field("element.id").get(element)
                id != null && host.type("cfi").isInstance(anchor) && host.method("compare").invoke(id, anchor) == 0
            }.takeIf { it >= 0 }.also { lastItems = loaded; lastAnchor = anchor; lastIndex = it }
        } ?: return null
        var offset = target.scrollGlyphOffsetPx
        val element = loaded[index]
        val content = source
        if (content.index("scroll.structured") == 1 && content.field("scroll.element.preserveStructure").getBoolean(element)) {
            val record = anchors.get()?.get(anchor.toString())
            if (record != null && content.type("scroll.anchor").isInstance(record)) {
                val callback = content.required("scroll.anchor.offset", record)
                offset = (content.method("scroll.anchor.offset").invoke(callback, target.start) as? Number)?.toInt() ?: offset
            }
        }
        val absoluteIndex = index + placeholdersBefore(vm)
        val geometry = geometry(vm, target)
        val info = host.method("layout").invoke(list)
        val visibleItem = info?.let { (host.field("layout.visible").get(it) as? List<*>).orEmpty().firstOrNull { item ->
            host.type("item").isInstance(item) && host.field("item.index").getInt(item) == absoluteIndex
        } }
        if (geometry != null && info != null && visibleItem != null) {
            val nativeStart = host.field("layout.start").getInt(info)
            offset = (geometry.glyph.top - geometry.viewport.top + nativeStart -
                host.field("item.offset").getInt(visibleItem)).roundToInt()
        }
        return Command(list, absoluteIndex, offset)
    }

    fun execute(command: Command, current: () -> Boolean) {
        val symbols = host
        val scroll = symbols.method("scroll")
        val block = Proxy.newProxyInstance(reader.classLoader, arrayOf(symbols.type("function2"))) { proxy, method, args ->
            when {
                method == symbols.method("invoke2") -> if (current()) scroll.invoke(command.state, command.index, command.offset, args?.get(1)) else symbols.unit
                method.name == "toString" && method.parameterCount == 0 -> "ReaMicroSearchScroll"
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                method.name == "equals" && method.parameterTypes.contentEquals(arrayOf(Any::class.java)) -> proxy === args?.firstOrNull()
                else -> error("Unexpected native scroll callback: $method")
            }
        }
        val main = symbols.main
        val withContext = symbols.method("withContext")
        reader.invokeSelectionHostSuspend { withContext.invoke(null, main, block, it) }
    }

    private data class Geometry(val glyph: RectF, val viewport: RectF)
    private fun geometry(vm: Any, target: ResolvedSearchTarget): Geometry? {
        if (reader.currentViewModelRef?.get() !== vm || reader.currentSearchPagerMode != 3) return null
        val c = source
        val anchor = target.scrollElementId ?: return null
        val captures = synchronized(elements) { elements.filter { it.owner.get() === vm }.toList() }
        val capture = captures.lastOrNull { item -> item.element.get()?.let {
            c.compare(c.required("scroll.element.id", it), anchor) == 0
        } == true } ?: return null
        val controller = capture.controller.get() ?: return null
        val viewport = capture.viewport.get() ?: return null
        val endpoint = c.method("scroll.resolve").invoke(controller, target.start) ?: return null
        val nodes = c.required("selection.nodes", controller) as Map<*, *>
        val node = nodes[c.required("endpoint.nodeId", endpoint)] ?: return null
        if (!c.type("selectable").isInstance(node)) return null
        val text = requireNotNull(HostHighlightTextBindings.current)
        val rendered = text.text(c.required("selectable.text", node))
        val index = c.field("endpoint.offset").getInt(endpoint)
        if (index !in rendered.indices) return null
        val firstQuote = target.ranges.firstOrNull()?.quote.orEmpty()
        val count = minOf(firstQuote.length, rendered.length - index)
        if (count == 0 || !rendered.regionMatches(index, firstQuote, 0, count)) return null
        fun rect(value: Any) = text.method("rect").invoke(null, value) as RectF
        val boundsRole = if ("field:selectable.scrollBoundsInWindow" in c.values)
            "selectable.scrollBoundsInWindow" else "selectable.boundsInWindow"
        val bounds = rect(c.required(boundsRole, node))
        val glyph = rect(requireNotNull(c.method("scroll.glyph").invoke(c.required("selectable.textLayoutResult", node), index)))
        glyph.offset(bounds.left, bounds.top)
        val window = rect(c.method("scroll.viewport").invoke(viewport) ?: return null)
        if (listOf(glyph.left, glyph.top, glyph.right, glyph.bottom, window.left, window.top, window.right, window.bottom).any { !it.isFinite() } ||
            glyph.height() <= 0f || window.isEmpty) return null
        return Geometry(glyph, window)
    }
    fun visible(vm: Any, target: ResolvedSearchTarget): Boolean {
        val value = geometry(vm, target) ?: return false
        return value.glyph.top >= value.viewport.top - 1f && value.glyph.bottom <= value.viewport.bottom + 1f &&
            value.glyph.right >= value.viewport.left && value.glyph.left <= value.viewport.right
    }
}
