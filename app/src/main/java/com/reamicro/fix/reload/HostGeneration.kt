package com.reamicro.fix.reload

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.reamicro.fix.xposed.XposedBridge
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 保持 Activity/窗口存活，只更换 Composition 并释放旧模块运行时。 */
internal object HostGeneration : Application.ActivityLifecycleCallbacks {
    private val activities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var application: Application? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var blockedByExtension: String? = null

    fun attach(app: Application) {
        if (application === app) return
        application = app
        app.registerActivityLifecycleCallbacks(this)
    }

    fun unsupportedExtension(id: String) { blockedByExtension = id }

    fun prepare(inPlace: Boolean): Array<Any>? {
        if (!inPlace) return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            XposedBridge.logAlways("ReaMicro API102 rejected: framework callback is on main thread")
            return null
        }
        val app = application ?: return null
        if (com.reamicro.fix.core.HookInstallReport.failures().isNotEmpty()) {
            XposedBridge.logAlways("ReaMicro API102 rejected: current generation has incomplete hooks")
            return null
        }
        blockedByExtension?.let {
            XposedBridge.logAlways("ReaMicro API102 rejected: external feature has no retirement contract: $it")
            return null
        }
        val reason = ModuleResources.begin()
        if (reason != null) {
            XposedBridge.logAlways("ReaMicro API102 rejected: $reason")
            return null
        }
        var retiring = false
        var detached = false
        var composition: Array<Any>? = null
        try {
            onMain {
                ModuleResources.verifyMainQueueAccess()
                val activity = activities.single()
                check(activity.hasWindowFocus()) { "阅微不在前台，暂不重载" }
                composition = HostCompositionReload.capture(activity)
                HostCompositionReload.detach(requireNotNull(composition))
                detached = true
            }
            // 先让 Composition 的注销回调执行，再清理模块队列。
            onMain { }
            onMain {
                retiring = true
                ModuleResources.retire()
                app.unregisterActivityLifecycleCallbacks(this)
                activities.clear()
                application = null
            }
            XposedBridge.logAlways("ReaMicro API102 old generation retired pid=${android.os.Process.myPid()}")
            // 只传宿主 Activity、原始宿主回调及基本类型；不传旧模块定义的回调或框架参数。
            return arrayOf(app, app.classLoader, app.packageName, requireNotNull(composition))
        } catch (error: Throwable) {
            XposedBridge.logError("ReaMicro API102 retirement failed", error)
            // 进入不可逆清理后不能回滚成可用旧代际，必须向框架报告异常而非安全拒绝。
            if (retiring) throw IllegalStateException("Old generation cleanup failed", error)
            if (ModuleResources.gate.state == ReloadGate.State.CHECKING) ModuleResources.reject()
            if (detached) composition?.let { onMain { HostCompositionReload.restore(it) } }
            return null
        }
    }

    internal fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val task = Runnable {
            if (!cancelled.get()) {
                try { action() } catch (error: Throwable) { result.set(error) }
            }
            done.countDown()
        }
        main.post(task)
        if (!done.await(8, TimeUnit.SECONDS)) {
            cancelled.set(true)
            main.removeCallbacks(task)
            error("主线程未及时响应热重载")
        }
        result.get()?.let { throw it }
    }

    fun adopt(activity: Activity) {
        attach(activity.application)
        activities += activity
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) { activities += activity }
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
