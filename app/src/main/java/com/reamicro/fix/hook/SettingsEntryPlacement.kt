package com.reamicro.fix.hook

import com.reamicro.fix.core.HostSettingsFeatures
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.util.ArrayDeque

/** Own an insertion by the actual builder/list, independent of optional rows and R8 branch IDs. */
internal class SettingsEntryPlacement(private val owner: Any) {
    private var inserted = false
    fun claim(list: Any, target: Boolean): Boolean {
        if (list !== owner || !target || inserted) return false
        inserted = true
        return true
    }

    companion object {
        fun install(symbols: HostSettingsFeatures.Symbols,
            handles: MutableList<XposedInterface.HookHandle>, insert: (Any) -> Unit) {
            val frames = ThreadLocal<ArrayDeque<SettingsEntryPlacement>?>()
            handles += HookRuntime.hookMethod(symbols.builder, object : HookCallback() {
                override fun before(param: Call) {
                    if (!symbols.isSettingsBuilder(param.thisObject ?: return)) return
                    val list = param.args.firstOrNull()?.takeIf(symbols.item.declaringClass::isInstance) ?: return
                    val frame = SettingsEntryPlacement(list)
                    val stack = frames.get() ?: ArrayDeque<SettingsEntryPlacement>().also(frames::set)
                    stack.addLast(frame)
                    param.setObjectExtra("ReaMicro.SettingsEntryFrame", frame)
                }
                override fun after(param: Call) {
                    val frame = param.getObjectExtra("ReaMicro.SettingsEntryFrame") as? SettingsEntryPlacement ?: return
                    val stack = frames.get() ?: return
                    stack.remove(frame)
                    if (stack.isEmpty()) frames.remove()
                }
            })
            val logged = java.util.concurrent.atomic.AtomicBoolean()
            handles += HookRuntime.hookMethod(symbols.item, object : HookCallback() {
                override fun before(param: Call) {
                    val list = param.thisObject ?: return
                    val frame = frames.get()?.peekLast() ?: return
                    if (!frame.claim(list, symbols.isDefaultHomeItem(param.args.getOrNull(2)))) return
                    insert(list)
                    if (logged.compareAndSet(false, true)) {
                        HookRuntime.logAlways("ReaMicro settings placement: completion-plan inserted before native default-home item")
                    }
                }
            })
        }
    }
}
