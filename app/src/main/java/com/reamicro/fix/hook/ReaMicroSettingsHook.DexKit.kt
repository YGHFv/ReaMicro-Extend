package com.reamicro.fix.hook

import android.app.Dialog
import android.widget.Toast
import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import java.util.concurrent.atomic.AtomicBoolean

private val dexKitRescanning = AtomicBoolean(false)

internal fun ReaMicroSettingsHook.openDexKitScanDialog() {
    val activity = activityProvider() ?: return
    val colors = SettingsDialogColors(activity)
    val dialog = Dialog(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, "DexKit 扫描结果", colors))
    val report = HostAutoScanner.snapshot()
    val lines = listOf(
        report?.summary() ?: "尚未扫描",
        "宿主：${activity.packageManager.getPackageInfo(activity.packageName, 0).versionName}",
        "模块：${moduleBuildVersionLine()}",
        "定位成功不等于全部功能验收；签名变更仍需适配。",
        if (report?.unchanged == true) "结果与缓存一致，无需重开阅微。" else "新结果在下次正常重开阅微时采用。",
    ) + report?.failures.orEmpty().take(80)
    lines.forEach { line -> card.addView(settingsDialogChoiceRow(activity, line, colors) {}) }
    val actions = settingsDialogActions(activity)
    actions.addView(settingsDialogButton(activity, "重新扫描", colors, SettingsDialogButtonRole.Neutral).apply {
        setOnClickListener {
            if (!dexKitRescanning.compareAndSet(false, true)) return@setOnClickListener
            dialog.dismiss()
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
        }
    }, settingsDialogButtonParams(activity))
    actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
        setOnClickListener { dialog.dismiss() }
    }, settingsDialogButtonParams(activity))
    card.addView(actions)
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
}
