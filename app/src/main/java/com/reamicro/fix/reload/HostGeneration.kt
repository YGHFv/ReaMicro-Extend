package com.reamicro.fix.reload

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.reamicro.fix.xposed.HookRuntime
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 保持 Activity/窗口存活，只更换 Composition 并释放旧模块运行时。 */
internal object HostGeneration : Application.ActivityLifecycleCallbacks {
    private val activities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val resumedActivities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    @Volatile private var application: Application? = null
    @Volatile private var classLoader: ClassLoader? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var blockedByExtension: String? = null

    fun attach(app: Application, loader: ClassLoader? = null) {
        if (application === app) {
            if (loader != null) classLoader = loader
            return
        }
        classLoader = loader ?: app.classLoader
        application = app
        app.registerActivityLifecycleCallbacks(this)
    }

    fun unsupportedExtension(id: String) { blockedByExtension = id }

    fun prepare(requireForeground: Boolean, saveState: (Array<Any>) -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HookRuntime.logAlways("ReaMicro API102 rejected: framework callback is on main thread")
            return false
        }
        val app = application ?: return false
        val loader = classLoader ?: return false
        val version = com.reamicro.fix.core.HostSupportedVersions.inspect(app, app.applicationInfo.sourceDir)
        if (!version.accepted) {
            HookRuntime.logAlways("ReaMicro API102 rejected: ${version.reason}")
            return false
        }
        if (com.reamicro.fix.core.HookInstallReport.failures().isNotEmpty()) {
            HookRuntime.logAlways("ReaMicro API102 rejected: current generation has incomplete hooks")
            return false
        }
        blockedByExtension?.let {
            HookRuntime.logAlways("ReaMicro API102 rejected: external feature has no retirement contract: $it")
            return false
        }
        var retiring = false
        var detached = false
        var composition: Array<Any>? = null
        var resumed = false
        var retirement: ResourceRetirement? = null
        var refusal: String? = null
        try {
            val idleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000L)
            var waitingLogged = false
            do {
                onMain {
                    ModuleResources.verifyMainQueueAccess()
                    val hostActivity = requireNotNull(com.reamicro.fix.core.HostCompositionBindings.current).type("activity")
                    val candidates = activities.filter {
                        hostActivity.isInstance(it) && !it.isDestroyed && !it.isFinishing
                    }
                    check(candidates.size <= 1) { "宿主存在多个主页面，暂不重载" }
                    val activity = candidates.singleOrNull()
                    if (requireForeground) check(activity?.hasWindowFocus() == true) { "阅微不在前台，暂不重载" }
                    resumed = activity != null && activity in resumedActivities
                    composition = activity?.let(HostCompositionReload::capture)
                    // Validate the neutral state before any irreversible cleanup.
                    saveState(arrayOf(app, loader, app.packageName, composition ?: emptyArray<Any>(), resumed))
                    // Sealing and detaching remain one main-thread transaction.
                    refusal = ModuleResources.begin()
                    if (refusal == null) composition?.let { HostCompositionReload.detach(it) { detached = true } }
                }
                if (refusal == null) break
                if (!waitingLogged) {
                    waitingLogged = true
                    HookRuntime.logAlways("ReaMicro API102 waiting for idle: $refusal ${ModuleResources.busySummary()}")
                }
                val remaining = TimeUnit.NANOSECONDS.toMillis(idleDeadline - System.nanoTime())
                if (remaining <= 0L || !ModuleResources.awaitIdle(remaining)) break
            } while (true)
            refusal?.let {
                HookRuntime.logAlways("ReaMicro API102 rejected: $it ${ModuleResources.busySummary()}")
                return false
            }
            // 先让 Composition 的注销回调执行，再清理模块队列。
            onMain { }
            onMain {
                retiring = true
                retirement = ModuleResources.retireOnMain().also { resources ->
                    resources.attempt { app.unregisterActivityLifecycleCallbacks(this) }
                }
                activities.clear()
                resumedActivities.clear()
                application = null
                classLoader = null
            }
            requireNotNull(retirement).awaitCompletion()
            HookRuntime.logAlways("ReaMicro API102 old generation retired pid=${android.os.Process.myPid()}")
            return true
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            HookRuntime.logError("ReaMicro API102 retirement failed", error)
            // 进入不可逆清理后不能回滚成可用旧代际，必须向框架报告异常而非安全拒绝。
            if (retiring) throw IllegalStateException("Old generation cleanup failed", error)
            if (ModuleResources.gate.state == ReloadGate.State.CHECKING) ModuleResources.reject()
            if (detached) try {
                onMain { HostCompositionReload.restore(requireNotNull(composition)) }
            } catch (restoreError: Throwable) {
                restoreError.addSuppressed(error)
                throw IllegalStateException("Old composition recovery failed", restoreError)
            }
            return false
        }
    }

    internal fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        // Pending work can be cancelled. Once it starts, await its outcome before rollback.
        val phase = java.util.concurrent.atomic.AtomicInteger(0)
        val task = Runnable {
            if (phase.compareAndSet(0, 1)) {
                try { action() } catch (error: Throwable) { result.set(error) }
            }
            phase.set(2)
            done.countDown()
        }
        check(main.post(task)) { "主线程队列已关闭" }
        var interrupted = false
        val finished = try { done.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!finished && phase.compareAndSet(0, 3)) {
            main.removeCallbacks(task)
            if (interrupted) Thread.currentThread().interrupt()
            error("主线程未及时响应热重载")
        }
        while (done.count != 0L) try { done.await() } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
        result.get()?.let { throw it }
    }

    fun adopt(activity: Activity, resumed: Boolean = true) {
        attach(activity.application, classLoader ?: activity.classLoader)
        activities += activity
        if (resumed) resumedActivities += activity else resumedActivities -= activity
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) { activities += activity }
    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
        resumedActivities.remove(activity)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) { resumedActivities += activity }
    override fun onActivityPaused(activity: Activity) { resumedActivities -= activity }
    override fun onActivityStopped(activity: Activity) { resumedActivities -= activity }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
