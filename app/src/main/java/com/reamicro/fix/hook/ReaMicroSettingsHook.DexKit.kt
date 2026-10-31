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
import java.util.concurrent.atomic.AtomicBoolean

private val dexKitRescanning = AtomicBoolean(false)

internal fun ReaMicroSettingsHook.openDexKitScanDialog() {
    val activity = activityProvider() ?: return
    showDexKitDialog(activity, HostAutoScanner.snapshot(), onRescan = {
        if (DexKitUpdateScan.isBusy() || !dexKitRescanning.compareAndSet(false, true)) return@showDexKitDialog
        Toast.makeText(activity, "正在扫描，结果将自动显示", Toast.LENGTH_SHORT).show()
        Thread({
            try {
                HostAutoScanner.scan(activity.applicationInfo.sourceDir, activity.applicationInfo.dataDir, force = true)
            } finally {
                dexKitRescanning.set(false)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) openDexKitScanDialog()
                }
            }
        }, "ReaMicro-DexKit").start()
    })
}

internal fun isManualDexKitScanning(): Boolean = dexKitRescanning.get()

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
    val dialog = Dialog(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, if (scanning) "DexKit 自动扫描" else "DexKit 扫描结果", colors))
    if (scanning) {
        card.addView(ProgressBar(activity).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
    val notice = if (!scanning && error == null && report?.cached == true) {
        DexKitScanNotice("已读取与当前宿主及扫描规则匹配的记录。扫描缓存不等于功能验收结果。")
    } else dexKitScanNotice(
        scanning, error, HostSymbols.profileId, report != null, report?.profile != null,
        report?.failures?.isNotEmpty() == true, report?.unchanged == true,
    )
    val lines = listOfNotNull(
        reason,
        if (scanning) "正在重新扫描宿主，请稍候…" else report?.summary() ?: "扫描未完成",
        "宿主：${activity.packageManager.getPackageInfo(activity.packageName, 0).versionName}",
        "模块：${moduleBuildVersionLine()}",
        "定位成功不等于全部功能验收；签名变更仍需适配。",
        notice.message,
    )
    lines.forEach { line -> card.addView(settingsDialogStatus(activity, line, colors)) }
    if (notice.restartRequired) {
        card.addView(settingsDialogButton(activity, "重启阅微", colors).apply {
            setOnClickListener {
                if (HostRestart.request(activity)) {
                    dialog.dismiss()
                    onClose?.invoke()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = settingsDp(activity, 8)
        })
    }
    if (!scanning) {
        val actions = settingsDialogActions(activity)
        if (onRescan != null) actions.addView(
            settingsDialogButton(activity, "重新扫描", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onRescan()
                }
            }, settingsDialogButtonParams(activity),
        )
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
            setOnClickListener {
                dialog.dismiss()
                onClose?.invoke()
            }
        }, settingsDialogButtonParams(activity))
        card.addView(actions)
        dialog.setOnCancelListener { onClose?.invoke() }
    } else {
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
    }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
    return dialog
}
