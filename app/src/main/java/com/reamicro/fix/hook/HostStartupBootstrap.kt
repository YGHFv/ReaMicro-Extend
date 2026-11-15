package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostStartupTask
import com.reamicro.fix.reload.HostCompositionReload
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Observe Android lifecycle before queries, then attach all hooks to an already opened page. */
internal class HostStartupBootstrap(
    private val packageName: String,
    private val classLoader: ClassLoader,
    private val entry: ReaMicroHookEntry,
) : Application.ActivityLifecycleCallbacks {
    private val main = ModuleResources.handler(Looper.getMainLooper())
    @Volatile private var application: Application? = null
    private var current = WeakReference<Activity>(null)
    private val created = WeakHashMap<Activity, Boolean>()
    private val attached = WeakHashMap<Activity, Boolean>()
    private var resumed = false
    private var ready = false
    private var startedAt = 0L
    private var bootstrapHook: io.github.libxposed.api.XposedInterface.HookHandle? = null

    fun observe(accept: (Application) -> Boolean, install: () -> Unit) {
        bootstrapHook = HookRuntime.withManagementHooks {
            HookRuntime.hookMethod(Application::class.java.getDeclaredMethod("attach", Context::class.java),
            object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    val app = param.thisObject as? Application ?: return
                    if (app.packageName != packageName || application === app) return
                    // This one-shot bootstrap is the only hook installed before the archive
                    // version is verified. Providers have not been installed at this point.
                    bootstrapHook?.unhook()
                    bootstrapHook = null
                    if (!accept(app)) return
                    application = app
                    app.registerActivityLifecycleCallbacks(this@HostStartupBootstrap)
                    entry.attachStartupContext(app)
                    HostCrashReportBlocker.attachExistingApplication(app)
                    HostGeneration.attach(app, classLoader)
                    start(install)
                }
            })
        }
        ModuleResources.cleanup {
            bootstrapHook?.unhook()
            bootstrapHook = null
            application?.unregisterActivityLifecycleCallbacks(this)
            application = null
            current.clear()
            created.clear()
            attached.clear()
        }
    }

    private fun start(install: () -> Unit) {
        startedAt = SystemClock.elapsedRealtime()
        val task = HostStartupTask(
            background = { action ->
                ModuleResources.backgroundThread("ReaMicro-Host-Bootstrap", true, action)
            },
            main = { action -> check(main.post { action() }) { "Host main queue is closed" } },
            install = {
                check(Looper.myLooper() != Looper.getMainLooper()) { "Cold queries must not run on the main thread" }
                HookRuntime.logAlways("ReaMicro startup queries started pid=${android.os.Process.myPid()} " +
                    "thread=${Thread.currentThread().name}")
                install()
                val report = com.reamicro.fix.core.HostAutoScanner.snapshot()
                val missingUi = buildList {
                    if (com.reamicro.fix.core.HostUiBindings.current == null) add("ui.native")
                    if (com.reamicro.fix.core.HostSettingsFeatures.current == null) add("settings.entry")
                    if (com.reamicro.fix.core.HostCompositionBindings.current == null) add("ui.composition")
                    if (com.reamicro.fix.core.HostRuntimeBindings.coroutines == null) add("runtime.coroutines")
                }
                check(missingUi.isEmpty()) { "Startup UI bindings unavailable: ${missingUi.joinToString()}" }
                val context = application
                if (report?.functionalityComplete == true) {
                    if (context != null) runCatching { DexKitUpdateScan.recordStartupValidation(context) }
                        .onFailure { HookRuntime.logError("ReaMicro startup validation stamp was not saved", it) }
                } else {
                    // Optional bindings already fail independently in their feature installers.
                    // Keep the working settings/diagnostics page reachable without writing a
                    // misleading "complete" validation stamp.
                    HookRuntime.logAlways("ReaMicro startup continuing with unavailable features: " +
                        (report?.runtimeFailures?.take(8)?.joinToString() ?: "query report unavailable"))
                }
            },
            ready = {
                ready = true
                application?.let {
                    HostGeneration.attach(it, classLoader)
                    HostCrashReportBlocker.attachExistingApplication(it)
                }
                current.get()?.let { attach(it) }
                HookRuntime.logAlways("ReaMicro startup installation finished pid=${android.os.Process.myPid()} " +
                    "durationMs=${SystemClock.elapsedRealtime() - startedAt} ${HookInstallReport.summaryLine()}")
            },
            failed = { error ->
                HookInstallReport.record("Entry", "BackgroundBootstrap", false, error)
                HookRuntime.logError("ReaMicro startup background installation failed", error)
            },
        )
        ModuleResources.blockWhile { if (!task.finished) "宿主动态绑定仍在初始化" else null }
        task.start()
    }

    private fun isHost(activity: Activity): Boolean =
        activity.packageName == packageName && activity.javaClass.name == "app.zhendong.reamicro.MainActivity"

    private fun observe(activity: Activity, isResumed: Boolean) {
        if (!isHost(activity)) return
        current = WeakReference(activity)
        resumed = isResumed
        entry.observeStartupActivity(activity, isResumed)
    }

    private fun attach(activity: Activity) {
        if (!ready || !created.containsKey(activity) || attached[activity] == true ||
            current.get() !== activity ||
            activity.isDestroyed || activity.isFinishing) return
        runCatching {
            entry.attachAfterStartup(activity, resumed)
            ReaMicroSettingsHook.activeInstanceOrNull()?.consumeModuleSettingsIntent(activity, activity.intent)
            if (created[activity] == true) HostCompositionReload.refresh(activity)
            attached[activity] = true
            HookInstallReport.record("Entry", "StartupComposition", true)
        }.onFailure {
            attached.remove(activity)
            HookInstallReport.record("Entry", "StartupComposition", false, it)
            HookRuntime.logError("ReaMicro startup page attachment deferred until next resume", it)
        }
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        observe(activity, false)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!isHost(activity)) return
        observe(activity, false)
        created[activity] = !ready
        // Application dispatches this while onCreate is still on the stack.
        main.post { attach(activity) }
    }
    override fun onActivityResumed(activity: Activity) {
        observe(activity, true)
        attach(activity)
    }
    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) {
            resumed = false
            entry.observeStartupActivity(activity, false)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        created.remove(activity)
        attached.remove(activity)
        if (current.get() === activity) current.clear()
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
