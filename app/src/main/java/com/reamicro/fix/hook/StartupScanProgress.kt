package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.reamicro.fix.xposed.HookRuntime

/** UI 契约先于其余功能查询就绪，扫描窗口直接使用宿主表面、字体和主题。 */
internal fun showStartupScanProgress(activity: Activity, result: String? = null, reload: (() -> Unit)? = null): Dialog {
    if (!HostSettingsDialogs.ready) return showFallbackStartupScanProgress(activity, result, reload)
    fun dp(value: Int) = EmbeddedHostUi.dp(activity, value.toFloat()).toInt()
    val dialog = HostSettingsDialogs.create(activity)
    HostSettingsDialogs.useApplicationTheme(dialog)
    HostSettingsDialogs.inheritSystemBars(dialog)
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        HostSettingsDialogs.markCard(this)
    }
    val title = TextView(activity).apply {
        text = if (result == null) "DexKit 自动扫描" else "DexKit 扫描结果"
        HostSettingsDialogs.markTitle(this)
    }
    val header = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        HostSettingsDialogs.markTitleRow(this)
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    val progress = if (result == null) ProgressBar(activity).apply {
        isIndeterminate = true
        header.addView(this, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
    } else null
    content.addView(header)
    val message = TextView(activity).apply {
        text = result ?: "正在适配当前安装的阅微，请稍候…"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }
    content.addView(message)
    HostSettingsDialogs.bindTheme(dialog) { native ->
        EmbeddedHostUi.text(title, "TitleLarge")
        EmbeddedHostUi.text(message, "BodyLarge")
        title.setTextColor(native.roles.getValue("OnBackground"))
        message.setTextColor(native.captionArgb)
        progress?.let { ModuleDialogTheme.tintProgress(it, native.roles.getValue("Primary")) }
    }
    if (result != null) HostSettingsDialogs.setActions(dialog, listOf(
        HostSettingsDialogs.Action("重载") { reload?.invoke() },
    ))
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)
    HostSettingsDialogs.show(dialog, content, dismissOnThemeChange = false)
    HookRuntime.logAlways("ReaMicro DexKit startup ${if (result == null) "progress" else "result"} shown with host theme")
    return dialog
}

/** 宿主 UI 查询失败时仍提供错误结果和重载入口。 */
internal fun showFallbackStartupScanProgress(activity: Activity, result: String?, reload: (() -> Unit)?): Dialog {
    val palette = ModuleDialogTheme.palette(activity)
    val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = GradientDrawable().apply {
            setColor(palette.rowBackground)
            cornerRadius = dp(24).toFloat()
        }
    }
    val header = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(activity).apply {
        text = if (result == null) "DexKit 自动扫描" else "DexKit 扫描结果"
        textSize = 20f
        setTextColor(palette.title)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (result == null) header.addView(ProgressBar(activity).apply {
        isIndeterminate = true
        ModuleDialogTheme.tintProgress(this, palette.primary)
    }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
    content.addView(header)
    content.addView(TextView(activity).apply {
        text = result ?: "正在适配当前安装的阅微，请稍候…"
        textSize = 16f
        setTextColor(palette.body)
        setPadding(0, dp(16), 0, dp(20))
    })
    if (result != null) content.addView(TextView(activity).apply {
        text = "重载"
        textSize = 16f
        gravity = android.view.Gravity.CENTER
        setTextColor(palette.primary)
        setPadding(0, dp(12), 0, dp(12))
        setOnClickListener { reload?.invoke() }
    })
    val container = FrameLayout(activity).apply {
        addView(content, FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * .86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER,
        ))
    }
    dialog.setContentView(container)
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.let { window ->
        inheritScanSystemBars(window, activity)
    }
    dialog.show()
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    HookRuntime.logAlways("ReaMicro DexKit startup ${if (result == null) "progress" else "result"} shown before bindings")
    return dialog
}

/** 在完整窗口内绘制一次遮罩，系统栏和内容区使用同一层压暗背景。 */
@Suppress("DEPRECATION")
internal fun inheritScanSystemBars(window: Window, activity: Activity) {
    com.reamicro.fix.ui.DialogWindowStyle.apply(
        window, activity.window, com.reamicro.fix.ui.DialogWindowStyle.SCRIM,
    )
}
