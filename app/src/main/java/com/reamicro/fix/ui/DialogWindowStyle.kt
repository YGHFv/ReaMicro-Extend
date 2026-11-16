package com.reamicro.fix.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat

/** 所有弹窗共用完整屏幕窗口；遮罩由窗口或内容二选一绘制。 */
internal object DialogWindowStyle {
    const val SCRIM = 0x66000000

    @Suppress("DEPRECATION")
    fun apply(window: Window, source: Window? = null, background: Int = Color.TRANSPARENT) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawable(ColorDrawable(background))
        window.attributes = window.attributes.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            dimAmount = 0f
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        source?.let {
            val appearance = WindowCompat.getInsetsController(it, it.decorView)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = appearance.isAppearanceLightStatusBars
                isAppearanceLightNavigationBars = appearance.isAppearanceLightNavigationBars
            }
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    /** 普通 Android 表单保留原卡片和底部位置，外层窗口负责全屏遮罩与安全区。 */
    fun show(
        dialog: Dialog, activity: Activity, content: View, gravity: Int = Gravity.CENTER,
        widthFraction: Float = 1f,
    ) {
        val frame = FrameLayout(activity).apply {
            addView(content, FrameLayout.LayoutParams(
                if (widthFraction == 1f) ViewGroup.LayoutParams.MATCH_PARENT
                    else (resources.displayMetrics.widthPixels * widthFraction).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity,
            ))
            setOnClickListener { dialog.onBackPressed() }
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                        androidx.core.view.WindowInsetsCompat.Type.ime(),
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        content.isClickable = true
        dialog.setContentView(frame)
        dialog.window?.let { apply(it, activity.window, SCRIM) }
        dialog.show()
        dialog.window?.let {
            apply(it, activity.window, SCRIM)
            it.decorView.setPadding(0, 0, 0, 0)
        }
    }

    fun activity(context: android.content.Context): Activity {
        var current = context
        while (current is android.content.ContextWrapper && current !is Activity) current = current.baseContext
        return requireNotNull(current as? Activity) { "弹窗需要当前 Activity" }
    }
}
