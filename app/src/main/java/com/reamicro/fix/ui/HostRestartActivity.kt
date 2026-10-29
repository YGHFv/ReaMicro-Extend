package com.reamicro.fix.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.core.HostRestartContract

/** 等旧阅微进程退出后重新拉起，不依赖 Root、后台启动或精确闹钟权限。 */
class HostRestartActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var token: IBinder? = null
    private var receiver: ResultReceiver? = null
    private var launch: Intent? = null
    private var ready = false
    private var completed = false
    private val timeout = Runnable { fail("重启未完成，请手动重新打开阅微") }
    private val death = IBinder.DeathRecipient { main.post { relaunch() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(HostRestartContract.EXTRA_PACKAGE)
        @Suppress("DEPRECATION")
        val result = intent.getParcelableExtra<ResultReceiver>(HostRestartContract.EXTRA_READY)
        receiver = result
        token = intent.extras?.getBinder(HostRestartContract.EXTRA_PROCESS)
        launch = target?.takeIf(HostRestartContract::isSupportedPackage)
            ?.let(packageManager::getLaunchIntentForPackage)
            ?.takeIf { it.component?.packageName == target }
        if (launch == null || token == null || result == null || savedInstanceState != null) {
            fail("无法准备阅微重启")
            return
        }
        setContentView(TextView(this).apply {
            text = "正在重启阅微…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        })
        runCatching { token!!.linkToDeath(death, 0) }
            .onFailure { fail("阅微进程已变化，请重新打开阅微") }
        if (!completed) main.postDelayed(timeout, 5_000)
    }

    override fun onResume() {
        super.onResume()
        if (!ready && !completed) {
            ready = true
            receiver?.send(HostRestartContract.READY, null)
        }
    }

    private fun relaunch() {
        if (completed || !ready || isFinishing || isDestroyed) return
        completed = true
        main.removeCallbacks(timeout)
        runCatching {
            startActivity(requireNotNull(launch).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }.onFailure {
            Toast.makeText(this, "请手动打开阅微", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun fail(message: String) {
        if (completed) return
        completed = true
        main.removeCallbacks(timeout)
        receiver?.send(HostRestartContract.FAILED, null)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        token?.let { runCatching { it.unlinkToDeath(death, 0) } }
        super.onDestroy()
    }
}
