package com.reamicro.fix.reload

import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.widget.Toast
import com.reamicro.fix.xposed.HookRuntime

object HostHotReload {
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val uri = Uri.parse("content://${HotReloadContract.AUTHORITY}")
    private var pending = false
    private var requestSerial = 0L

    fun request(activity: Activity) {
        if (activity.isDestroyed || activity.isFinishing || pending) return
        val callbackLease = ModuleResources.lease("hot-reload-wake") ?: return
        pending = true
        val serial = ++requestSerial
        val context = activity.applicationContext
        val started = SystemClock.elapsedRealtime()
        val wake = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                // An ordered broadcast stores its result receiver outside this ClassLoader.
                // Keep retirement blocked until Android has delivered that final callback.
                callbackLease.close()
                if (!pending || requestSerial != serial) return
                if (resultCode == Activity.RESULT_OK) submit(activity)
                else {
                    pending = false
                    toast(context, "热重载桥接未就绪，阅微未被重启")
                }
            }
        }
        runCatching {
            context.sendOrderedBroadcast(
                Intent().setClassName("com.reamicro.fix", "com.reamicro.fix.reload.HotReloadWakeReceiver")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                null, wake, main, Activity.RESULT_CANCELED, null, null,
            )
            main.postDelayed({
                if (pending && requestSerial == serial && SystemClock.elapsedRealtime() - started >= 10_000) {
                    pending = false
                    toast(context, "热重载桥接未响应，未执行热重载")
                }
            }, 10_000)
        }.onFailure {
            callbackLease.close()
            pending = false
            toast(context, "无法唤醒热重载桥接，阅微未被重启")
        }
    }

    private fun submit(activity: Activity) {
        if (activity.hasWindowFocus()) {
            submitFocused(activity)
            return
        }
        // Compose 弹窗注销与窗口焦点恢复不在同一队列回合，等焦点事件后再提交。
        val decor = activity.window.decorView
        val observer = decor.viewTreeObserver
        val lease = ModuleResources.lease("hot-reload-focus")
        if (lease == null || activity.isDestroyed || activity.isFinishing || !observer.isAlive) {
            lease?.close()
            pending = false
            toast(activity, "阅微页面已退出，未提交热重载")
            return
        }
        var waiting = true
        lateinit var listener: ViewTreeObserver.OnWindowFocusChangeListener
        lateinit var timeout: Runnable
        fun release() {
            if (!waiting) return
            waiting = false
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            main.removeCallbacks(timeout)
            lease.close()
        }
        listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && waiting) {
                release()
                main.post { submitFocused(activity) }
            }
        }
        timeout = Runnable {
            if (waiting) {
                release()
                pending = false
                toast(activity, "阅微窗口尚未恢复前台，请返回阅微后重试")
            }
        }
        observer.addOnWindowFocusChangeListener(listener)
        main.postDelayed(timeout, 2_000)
        if (activity.hasWindowFocus()) listener.onWindowFocusChanged(true)
    }

    private fun submitFocused(activity: Activity) {
        // 原地重载由框架异步执行，不再启动等待 Activity，也不在宿主保留跨代 Binder 回调。
        runCatching {
            check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                "阅微窗口已离开前台"
            }
            val response = activity.contentResolver.call(uri, HotReloadContract.REQUEST, null,
                Bundle().apply { putBoolean(HotReloadContract.IN_PLACE, true) })
            requireNotNull(response?.getString(HotReloadContract.ID))
            HookRuntime.logAlways("ReaMicro API102 request submitted pid=${android.os.Process.myPid()}")
        }.onFailure {
            HookRuntime.logError("ReaMicro API102 request failed", it)
            toast(activity, "无法提交热重载，未退出阅微")
        }.also { pending = false }
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
