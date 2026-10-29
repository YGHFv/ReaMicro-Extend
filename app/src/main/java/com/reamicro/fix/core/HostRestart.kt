package com.reamicro.fix.core

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.widget.Toast
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.xposed.XposedBridge

internal object HostRestart {
    private val main = Handler(Looper.getMainLooper())
    private var pending: ResultReceiver? = null
    private var processToken: Binder? = null

    fun request(activity: Activity): Boolean {
        if (!HostRestartContract.isSupportedPackage(activity.packageName) ||
            activity.isFinishing || activity.isDestroyed || pending != null) return false
        val token = Binder()
        var ready = false
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (pending !== this || ready) return
                if (resultCode != HostRestartContract.READY) {
                    pending = null
                    processToken = null
                    Toast.makeText(activity, "重启准备失败，请手动关闭并重新打开阅微", Toast.LENGTH_LONG).show()
                    return
                }
                ready = true
                // 接力页已在另一个进程前台；这里只结束阅微自身，不终止模块或其它应用。
                main.postDelayed({ Process.killProcess(Process.myPid()) }, 250)
            }
        }
        pending = receiver
        processToken = token
        val intent = Intent().apply {
            component = ComponentName(BuildConfig.APPLICATION_ID, HostRestartContract.ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(HostRestartContract.EXTRA_PACKAGE, activity.packageName)
            putExtra(HostRestartContract.EXTRA_READY, receiver)
            putExtras(Bundle().apply { putBinder(HostRestartContract.EXTRA_PROCESS, token) })
        }
        return runCatching {
            activity.startActivity(intent)
            main.postDelayed({
                if (pending === receiver && !ready) {
                    pending = null
                    processToken = null
                    Toast.makeText(activity, "重启接力页未就绪，请手动重新打开阅微", Toast.LENGTH_LONG).show()
                }
            }, 5_000)
            true
        }.getOrElse {
            pending = null
            processToken = null
            XposedBridge.logError("ReaMicro restart handoff failed; preserving the host process", it)
            Toast.makeText(activity, "无法启动重启接力页，请手动重新打开阅微", Toast.LENGTH_LONG).show()
            false
        }
    }
}
