package com.reamicro.fix.hook

import android.app.Activity
import android.os.Looper
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookRuntime

/** 首帧前等待后台安装；扫描弹窗可先显示，尚未补全的宿主页始终不绘制。 */
internal class HostStartupDrawGate(activity: Activity) : AutoCloseable {
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val decor = activity.window.decorView
    private val content = activity.findViewById<View>(android.R.id.content)
    private val originalVisibility = content.visibility
    private val observer = decor.viewTreeObserver
    private var closed = false
    private var hiddenForScan = false
    private val splash = if (Build.VERSION.SDK_INT >= 31) activity.splashScreen else null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (closed) true else if (DexKitUpdateScan.isStartupDialogShowing()) {
            // 更新扫描使用独立窗口；允许背景首帧结束系统启动画面，但不露出原首页。
            if (!hiddenForScan) {
                hiddenForScan = true
                content.visibility = View.INVISIBLE
            }
            true
        } else {
            // 正常缓存启动保留系统启动画面，主线程和后台查询仍可继续运行。
            false
        }
    }
    private val timeout = Runnable {
        HookRuntime.logError("ReaMicro startup first frame wait timed out; releasing host window")
        close()
    }

    init {
        if (Build.VERSION.SDK_INT >= 31) splash?.setOnExitAnimationListener { view ->
            // 首帧已经等待补全完成，直接移除启动层，避免系统退出动画再次遮住首页。
            view.remove()
            splash?.clearOnExitAnimationListener()
        }
        observer.addOnPreDrawListener(preDraw)
        main.postDelayed(timeout, 30_000)
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
        val current = decor.viewTreeObserver
        if (current !== observer && current.isAlive) current.removeOnPreDrawListener(preDraw)
        if (hiddenForScan) content.visibility = originalVisibility
        // 新根页面完成测量和 Composition 后再绘制第一帧，不沿用旧页面的布局。
        decor.requestLayout()
        decor.invalidate()
    }

    fun destroy() {
        close()
        if (Build.VERSION.SDK_INT >= 31) splash?.clearOnExitAnimationListener()
    }
}
