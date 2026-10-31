package com.reamicro.fix.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val foreground = if (dark) 0xffe2e3e5.toInt() else 0xff35373b.toInt()
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (dark) 0xff202328.toInt() else 0xfff5f5f7.toInt())
            }
            addView(ProgressBar(this@HostRestartActivity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(foreground)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(18) })
            addView(TextView(this@HostRestartActivity).apply {
                text = "正在重新打开阅微"
                textSize = 15f
                includeFontPadding = false
                setTextColor(foreground)
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        })
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(minOf(dp(320), (resources.displayMetrics.widthPixels * .84f).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT)
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
