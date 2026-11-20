package com.reamicro.fix.hook


import com.reamicro.fix.core.HostHomeBindings
import com.reamicro.fix.core.HomeDefaultPagePolicy
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.util.WeakHashMap

/** 仅修正首次索引解析的占位偏好，不改宿主的手动选页、阅读恢复和分组回退。 */
internal class HomeDefaultPageHook(
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val initialPreferences = WeakHashMap<Any, Boolean>()

    fun install() {
        val home = requireNotNull(HostHomeBindings.bookshelf) { "首页动态契约尚未就绪" }
        val preferences = requireNotNull(HostHomeBindings.preferences) { "宿主偏好动态契约尚未就绪" }
        val initialized = home.field("home.initialized")
        val resolve = home.method("resolve")
        val handle = HookRuntime.hookMethod(resolve, object : HookCallback() {
            override fun before(param: Call) {
                if (!settingsProvider().moduleEnabled) return
                val model = param.thisObject ?: return
                val groups = param.args[0] as? List<*> ?: return
                if (initialized.getBoolean(model) || groups.isEmpty()) return
                val stored = synchronized(initialPreferences) { initialPreferences[model] } ?: runCatching {
                    (preferences.read(home.session(model), "reading_enabled", 250L) as? Boolean ?: false)
                        .also { value ->
                            synchronized(initialPreferences) { initialPreferences[model] = value }
                            HookRuntime.logAlways("ReaMicro home initial preference loaded: bookshelf=$value")
                        }
                }.getOrElse {
                    HookRuntime.logError("ReaMicro home initial preference unavailable; keeping native behavior", it)
                    return
                }
                HomeDefaultPagePolicy.initialPreference(false, groups, stored)?.let { param.args[1] = it }
            }
        })
        try {
            home.callers("resolveCaller").forEach(HookRuntime::deoptimize)
        } catch (error: Throwable) {
            handle.unhook()
            throw error
        }
    }
}
