package com.reamicro.fix.reload

import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.xposed.XposedBridge

object HostHotReload {
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val uri = Uri.parse("content://${HotReloadContract.AUTHORITY}")
    private var pending = false

    fun request(activity: Activity) {
        if (activity.isDestroyed || activity.isFinishing || pending) return
        pending = true
        val context = activity.applicationContext
        val started = SystemClock.elapsedRealtime()
        val wake = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (!pending) return
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
                if (pending && SystemClock.elapsedRealtime() - started >= 10_000) {
                    pending = false
                    toast(context, "热重载桥接未响应，未执行热重载")
                }
            }, 10_000)
        }.onFailure {
            pending = false
            toast(context, "无法唤醒热重载桥接，阅微未被重启")
        }
    }

    private fun submit(activity: Activity) {
        // 原地重载由框架异步执行，不再启动等待 Activity，也不在宿主保留跨代 Binder 回调。
        runCatching {
            check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus())
            val response = activity.contentResolver.call(uri, HotReloadContract.REQUEST, null,
                Bundle().apply { putBoolean(HotReloadContract.IN_PLACE, true) })
            requireNotNull(response?.getString(HotReloadContract.ID))
        }.onFailure {
            XposedBridge.logError("ReaMicro API102 request failed", it)
            toast(activity, "无法提交热重载，未退出阅微")
        }.also { pending = false }
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
