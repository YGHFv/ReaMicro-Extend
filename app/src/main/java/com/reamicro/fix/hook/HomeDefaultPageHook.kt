package com.reamicro.fix.hook

import com.reamicro.fix.core.HomeDefaultPagePolicy
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.util.WeakHashMap

/** 仅修正首次索引解析的占位偏好，不改宿主的手动选页、阅读恢复和分组回退。 */
internal class HomeDefaultPageHook(
    private val loader: ClassLoader,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val initialPreferences = WeakHashMap<Any, Boolean>()

    fun install() {
        val home = loader.hostClass(HostClasses.Host.HOME_VIEW_MODEL)
        val initialized = home.getDeclaredField("p").apply { isAccessible = true }
        val resolve = home.hostMethod(
            "resolveHomePageIndex", List::class.java, Boolean::class.javaPrimitiveType,
        )
        val key = loader.hostClass("ReaMicroHost.PreferenceKey").getConstructor(String::class.java)
            .newInstance("reading_enabled")
        val first = loader.hostClass("ReaMicroShelf233.FlowFirst").getDeclaredMethod(
            "c", loader.hostClass("kotlinx.coroutines.flow.Flow"),
            loader.hostClass("kotlin.coroutines.Continuation"),
        )
        XposedBridge.hookMethod(resolve, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!settingsProvider().moduleEnabled) return
                val model = param.thisObject ?: return
                val groups = param.args[0] as? List<*> ?: return
                if (initialized.getBoolean(model) || groups.isEmpty()) return
                val stored = synchronized(initialPreferences) { initialPreferences[model] } ?: runCatching {
                    val repository = home.hostField("repository").get(model)
                    val session = repository.javaClass.hostField("session").get(repository)
                    val store = session.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(session)
                    val flow = XposedHelpers.callMethod(store, "getData")
                    // 不取 Session.M 的初始 false；DataStore 首次值才代表已加载的真实偏好。
                    // 通常已在内存，异常时限时取消，不让首页等待长时间 I/O。
                    val prefs = requireNotNull(HostCoroutines.invokeTimed(loader, first, null, 250L, flow))
                    (prefs.javaClass.hostAbiMethod("c", key.javaClass).invoke(prefs, key) as? Boolean ?: false)
                        .also { value ->
                            synchronized(initialPreferences) { initialPreferences[model] = value }
                            XposedBridge.logAlways("ReaMicro home initial preference loaded: bookshelf=$value")
                        }
                }.getOrElse {
                    XposedBridge.logError("ReaMicro home initial preference unavailable; keeping native behavior", it)
                    return
                }
                HomeDefaultPagePolicy.initialPreference(false, groups, stored)?.let { param.args[1] = it }
            }
        })
        val screen = loader.hostClass("app.zhendong.reamicro.ui.home.HomeScreenKt")
            .hostMethod("HomeScreen", loader.hostClass(HostClasses.Host.NAV_GRAPH_SCOPE),
                home, loader.hostClass(HostClasses.Compose.COMPOSER), Int::class.javaPrimitiveType)
        XposedBridge.deoptimize(screen)
        // 第二个调用方是首屏选页的挂起函数，必须解除内联，才能与首帧使用同一个偏好。
        XposedBridge.deoptimize(loader.loadClass("uc4").getDeclaredMethod("v", Any::class.java))
    }
}
