package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderMenuBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Scopes the search replacement to the host menu body and its captured reader. */
internal class HostReaderSearchEntryBridge(private val reader: ReaderHook) {
    fun install() {
        val symbols = requireNotNull(HostReaderMenuBindings.current) { "Reader menu contract unavailable" }
        val handles = ArrayList<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.method("render"), object : HookCallback() {
                override fun before(param: Call) {
                    val closure = param.thisObject ?: return
                    val receiver = symbols.field("receiver").get(closure) ?: return
                    if (receiver !== reader.currentViewModelRef?.get()) return
                    val book = symbols.field("book").get(closure) ?: return
                    if (!symbols.type("book").isInstance(book)) return
                    reader.beginReaderSearchBar(receiver, book)
                    param.setObjectExtra("reamicro.menu.search.scope", true)
                    param.args.getOrNull(2)?.let { reader.cacheThemeColors(it, captureSearchTheme = true) }
                }
                override fun after(param: Call) {
                    if (param.getObjectExtra("reamicro.menu.search.scope") == true) reader.endReaderSearchBar()
                }
            })
            handles += HookRuntime.hookMethod(symbols.method("clickable"), object : HookCallback() {
                override fun before(param: Call) {
                    if (!reader.readerBottomBarComposeScope.active) return
                    val original = param.args.getOrNull(4) ?: return
                    param.args[4] = reader.bindReaderThemeClick(original)
                }
            })
            (symbols.callers() + symbols.method("render")).distinct().forEach(HookRuntime::deoptimize)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        HookRuntime.log("ReaMicro reader menu search binding installed from current APK")
    }
}
