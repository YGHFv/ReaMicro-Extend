package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderWindowBindings
import com.reamicro.fix.epub.editor.selectionWindowOffset
import com.reamicro.fix.epub.editor.selectionSameChapterPage
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reload.ModuleResources
import java.util.Collections
import java.util.WeakHashMap

internal class ReaderSelectionWindow(
    val owner: Any, val visibleSlot: Int, val spine: Int, val localPage: Int, val anchor: Any?,
) {
    @Volatile var applied = false
}
internal fun ReaderHook.installSelectionWindowHook() {
    val host = requireNotNull(HostReaderWindowBindings.current) { "Reader window contract unavailable" }
    val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    class Publication(val edit: ReaderSelectionWindow, val page: Any, val location: Pair<Int, Int>)
    val prepared = Collections.synchronizedMap(WeakHashMap<Any, Publication>())
    try {
        handles += HookRuntime.hookMethod(host.constructor("publisher"), object : HookCallback() {
            override fun before(param: Call) {
                val edit = selectionWindow ?: return
                val args = param.args ?: return
                if (args[host.index("publish.owner")] !== edit.owner || edit.applied) return
                runCatching {
                    val mapping = args[host.index("publish.forward")] as Map<*, *>
                    val reverse = args[host.index("publish.reverse")] as Map<*, *>
                    val pages = args[host.index("publish.pages")] as Map<*, *>
                    val typed = mapping.entries.associate { (key, value) -> (key as Number).toInt() to host.pair(requireNotNull(value)) }
                    val available = pages.keys.map { (it as Number).toInt() }.toSet()
                    val contained = if (edit.anchor == null) emptySet() else pages.entries.filter { (key, page) ->
                        typed[(key as Number).toInt()]?.first == edit.spine && page != null && host.contains(page, edit.anchor)
                    }.map { (it.key as Number).toInt() }.toSet()
                    val target = selectionSameChapterPage(typed, available, edit.spine, edit.localPage, contained) ?: return
                    val minimum = args[host.index("publish.minimum")] as? Int
                    val maximum = args[host.index("publish.maximum")] as? Int
                    val delta = selectionWindowOffset(edit.visibleSlot, target, available + typed.keys, minimum, maximum) ?: return
                    fun shifted(value: Any?): Int {
                        val next = (value as Number).toLong() + delta
                        require(next in 0L..9999L)
                        return next.toInt()
                    }
                    // Prepare every replacement before changing constructor arguments.
                    val nextMapping = mapping.entries.associateTo(linkedMapOf()) { (key, value) -> shifted(key) to value }
                    val nextReverse = reverse.entries.associateTo(linkedMapOf()) { (key, value) -> key to shifted(value) }
                    val nextPages = pages.entries.associateTo(linkedMapOf()) { (key, value) -> shifted(key) to value }
                    val nextMinimum = minimum?.let(::shifted)
                    val nextMaximum = maximum?.let(::shifted)
                    args[host.index("publish.forward")] = nextMapping
                    args[host.index("publish.reverse")] = nextReverse
                    args[host.index("publish.pages")] = nextPages
                    args[host.index("publish.minimum")] = nextMinimum
                    args[host.index("publish.maximum")] = nextMaximum
                    param.setObjectExtra("reamicro.window.publication", Publication(edit, requireNotNull(nextPages[edit.visibleSlot]), typed.getValue(target)))
                    HookRuntime.log("ReaMicro selection window prepared visible=${edit.visibleSlot} rebuilt=$target shift=$delta spine=${edit.spine}")
                }.onFailure { HookRuntime.logError("ReaMicro selection window preparation failed", it) }
            }
            override fun after(param: Call) {
                if (param.throwable != null) return
                val publication = param.getObjectExtra("reamicro.window.publication") as? Publication ?: return
                val instance = param.thisObject ?: return
                prepared[instance] = publication
            }
        })
        handles += HookRuntime.hookMethod(host.method("publish"), object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable != null) return
                val publisher = param.thisObject ?: return
                val publication = prepared.remove(publisher) ?: return
                val edit = publication.edit
                if (selectionWindow !== edit) return
                if (host.field("publish.owner").get(publisher) !== edit.owner || edit.applied) return
                val pair = host.forward(edit.owner)[edit.visibleSlot] ?: return
                val page = host.pages(edit.owner)[edit.visibleSlot] ?: return
                if (page === publication.page && host.pair(pair) == publication.location) edit.applied = true
            }
        })
    } catch (error: Throwable) {
        handles.asReversed().forEach { runCatching { it.unhook() } }
        throw error
    }
    HookRuntime.deoptimize(host.method("recenter"))
    ModuleResources.cleanup { prepared.clear(); selectionWindowHookInstalled = false }
    selectionWindowHookInstalled = true
}
