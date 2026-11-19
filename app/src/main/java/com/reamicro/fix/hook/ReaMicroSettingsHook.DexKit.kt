package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.core.HostRestart
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

internal fun ReaMicroSettingsHook.openDexKitScanDialog() {
    val activity = activityProvider() ?: return
    showDexKitDialog(activity, HostAutoScanner.snapshot(), onRescan = {
        if (DexKitUpdateScan.requestManualScan(activity)) {
            Toast.makeText(activity, "正在扫描，结果将自动显示", Toast.LENGTH_SHORT).show()
        }
    })
}

internal fun isManualDexKitScanning(): Boolean = DexKitUpdateScan.isManualScanning()

internal fun ReaMicroSettingsHook.showDexKitUpdateDialog(
    activity: Activity,
    reason: String,
    finished: Boolean,
    report: HostAutoScanner.Report?,
    error: String?,
    onClose: () -> Unit,
): Dialog = showDexKitDialog(activity, report, reason, !finished, error, onClose)

private fun ReaMicroSettingsHook.showDexKitDialog(
    activity: Activity,
    report: HostAutoScanner.Report?,
    reason: String? = null,
    scanning: Boolean = false,
    error: String? = null,
    onClose: (() -> Unit)? = null,
    onRescan: (() -> Unit)? = null,
): Dialog {
    val colors = SettingsDialogColors(activity)
    val dialog = settingsDialog(activity)
    HostSettingsDialogs.useApplicationTheme(dialog)
    HostSettingsDialogs.inheritSystemBars(dialog)
    val card = settingsDialogCard(activity, colors)
    val title = settingsDialogTitle(activity, if (scanning) "DexKit 自动扫描" else "DexKit 扫描结果", colors)
    var spinner: ProgressBar? = null
    if (scanning) {
        val header = LinearLayout(activity).apply {
            HostSettingsDialogs.markTitleRow(this)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(activity, 14) }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val spinnerSize = settingsDp(activity, 24)
        header.addView(ProgressBar(activity).apply {
            spinner = this
            isIndeterminate = true
            ModuleDialogTheme.tintProgress(this, colors.primary)
        }, LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
            marginStart = settingsDp(activity, 12)
        })
        card.addView(header)
    } else {
        card.addView(title)
    }
    val scanError = error ?: report?.takeIf { !it.functionalityComplete }?.runtimeFailures?.firstOrNull()
    val notice = dexKitScanNotice(
        scanning, scanError, HostSymbols.profileId, report != null, report?.profile != null,
        report?.runtimeFailures?.isNotEmpty() == true, report?.unchanged == true, report?.cached == true,
    )
    val lines = listOfNotNull(
        reason?.let { it to "BodyMedium" },
        (if (scanning) "正在重新扫描宿主，请稍候…" else report?.summary() ?: "扫描未完成") to "BodyLarge",
        "宿主：${activity.packageManager.getPackageInfo(activity.packageName, 0).versionName}" to "BodyMedium",
        "模块：${moduleBuildVersionLine()}" to "BodyMedium",
        "定位成功不等于全部功能验收；签名变更仍需适配。" to "BodySmall",
        notice.message to "BodyMedium",
    )
    val messages = lines.map { (line, role) ->
        settingsDialogStatus(activity, line, colors).apply {
            EmbeddedHostUi.text(this, role)
            if (role == "BodyLarge") setTextColor(colors.title)
            card.addView(this)
        } to role
    }
    HostSettingsDialogs.bindTheme(dialog) { native ->
        EmbeddedHostUi.text(title, "TitleLarge")
        title.setTextColor(native.roles.getValue("OnBackground"))
        spinner?.let { ModuleDialogTheme.tintProgress(it, native.roles.getValue("Primary")) }
        messages.forEach { (view, role) ->
            EmbeddedHostUi.text(view, role)
            view.setTextColor(if (role == "BodyLarge") native.roles.getValue("OnBackground") else native.captionArgb)
        }
    }
    if (!scanning) {
        val actions = mutableListOf<HostSettingsDialogs.Action>()
        if (onRescan != null) {
            actions += HostSettingsDialogs.Action("重新扫描") {
                dialog.dismiss()
                onRescan()
            }
        }
        if (report != null || onClose != null || notice.restartRequired) {
            actions += HostSettingsDialogs.Action("重载") {
                dialog.dismiss()
                onClose?.invoke()
                // 宿主弹窗的退出需要一个主线程队列回合，随后再让框架接管重载。
                com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper()).post {
                    com.reamicro.fix.reload.HostHotReload.request(activity)
                }
            }
        }
        if (report == null && onClose == null && !notice.restartRequired) actions += HostSettingsDialogs.Action("关闭") {
            dialog.dismiss()
            onClose?.invoke()
        }
        HostSettingsDialogs.setActions(dialog, actions)
        dialog.setOnCancelListener { onClose?.invoke() }
    } else {
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
    }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
    return dialog
}
