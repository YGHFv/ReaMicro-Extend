package com.reamicro.fix.hook

import com.reamicro.fix.epub.editor.selectionWindowOffset
import com.reamicro.fix.epub.editor.selectionSameChapterPage
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers

internal class ReaderSelectionWindow(
    val owner: Any, val visibleSlot: Int, val spine: Int, val localPage: Int, val anchor: Any?,
) {
    @Volatile var applied = false
}
internal fun ReaderHook.installSelectionWindowHook(cls: Class<*>) {
    val hooks = XposedBridge.hookAllMethods(cls, "publishVirtualWindow", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val edit = selectionWindow ?: return
            if (param.thisObject !== edit.owner || edit.applied) return
            val args = param.args ?: return
            if (args.size != 10) return
            runCatching {

                val mapping = args[1] as? Map<*, *> ?: return
                val reverse = args[2] as? Map<*, *> ?: return
                val pages = args[4] as? Map<*, *> ?: return
                val typed = mapping.entries.associate { (key, pair) ->
                    requireNotNull(pair)
                    (key as Number).toInt() to
                        ((callNoArg(pair, "getFirst") as Number).toInt() to
                            (callNoArg(pair, "getSecond") as Number).toInt())
                }
                val available = pages.keys.map { (it as Number).toInt() }.toSet()
                val contained = if (edit.anchor == null) emptySet() else pages.entries.filter { (key, page) ->
                    typed[(key as Number).toInt()]?.first == edit.spine && page != null &&
                        callNoArg(page, "getRange")?.let {
                            XposedHelpers.callMethod(it, "contains", edit.anchor) == true
                        } == true
                }.map { (it.key as Number).toInt() }.toSet()
                val target = selectionSameChapterPage(typed, available, edit.spine, edit.localPage, contained)
                    ?: return
                val delta = selectionWindowOffset(edit.visibleSlot, target, available + typed.keys,
                    (args[7] as? Number)?.toInt(), (args[8] as? Number)?.toInt()) ?: return
                val shifted = com.reamicro.fix.epub.editor.shiftSelectionWindowArguments(args, delta)
                for (i in args.indices) args[i] = shifted[i]
                edit.applied = true
                XposedBridge.log("ReaMicro selection stable-window visible=${edit.visibleSlot} rebuilt=$target shift=$delta spine=${edit.spine}")
            }.onFailure {
                XposedBridge.log("ReaMicro selection stable-window failed: ${it.message}")
            }
        }
    })
    selectionWindowHookInstalled = hooks.isNotEmpty()
}
