package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import com.reamicro.fix.hook.settings.SettingsDialogButtonRole
import com.reamicro.fix.reload.ModuleResources

/** Import workers wait outside the UI thread; dismissal always resolves their decision. */
internal object HostImportOverwriteDialogs {
    enum class Choice { COVER, IMPORT_NEW, CANCEL }

    fun show(
        activity: Activity,
        oldTitle: String,
        newTitle: String,
        isCurrent: () -> Boolean,
        onResult: (Choice) -> Unit,
    ): Dialog {
        check(Looper.myLooper() === Looper.getMainLooper())
        check(ModuleResources.accepting && !activity.isFinishing && !activity.isDestroyed && isCurrent())
        val hook = requireNotNull(ReaMicroSettingsHook.activeInstanceOrNull())
        val colors = hook.SettingsDialogColors(activity)
        val dialog = hook.settingsDialog(activity)
        val card = hook.settingsDialogCard(activity, colors)
        var resolved = false
        fun resolve(choice: Choice) {
            if (resolved) return
            resolved = true
            onResult(if (isCurrent()) choice else Choice.CANCEL)
        }
        card.addView(hook.settingsDialogTitle(activity, "发现已有书籍", colors))
        card.addView(hook.settingsDialogStatus(activity, "原：$oldTitle\n新：$newTitle", colors))
        card.addView(hook.settingsDialogStatus(activity,
            "覆盖更新书籍内容，保留原书的阅读进度和关联；独立导入会创建新书。", colors))
        val actions = hook.settingsDialogActions(activity)
        for ((label, choice) in listOf(
            "覆盖导入" to Choice.COVER, "独立导入" to Choice.IMPORT_NEW, "取消导入" to Choice.CANCEL,
        )) {
            actions.addView(hook.settingsDialogButton(activity, label, colors,
                if (choice == Choice.CANCEL) SettingsDialogButtonRole.Neutral
                else SettingsDialogButtonRole.Primary).apply {
                setOnClickListener {
                    try { resolve(choice) } finally { dialog.dismiss() }
                }
            }, hook.settingsDialogButtonParams(activity))
        }
        card.addView(actions)
        dialog.setOnCancelListener { resolve(Choice.CANCEL) }
        dialog.setOnDismissListener { resolve(Choice.CANCEL) }
        try {
            hook.showSettingsDialog(dialog, hook.settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
        } catch (error: Throwable) {
            resolve(Choice.CANCEL)
            runCatching { dialog.dismiss() }.onFailure(error::addSuppressed)
            throw error
        }
        return dialog
    }
}
