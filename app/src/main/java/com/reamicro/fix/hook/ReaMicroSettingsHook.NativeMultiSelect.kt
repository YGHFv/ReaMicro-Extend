package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.widget.TextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.ui.MultiSelectOption

/** Keeps the existing pawn selection and refresh behavior inside the shared native popup. */
internal fun ReaMicroSettingsHook.showNativeMultiSelectDialog(
    activity: Activity,
    title: String,
    hint: String,
    options: List<MultiSelectOption>,
    selected: Set<String>,
    refresh: (() -> List<MultiSelectOption>?)? = null,
    refreshLabel: String = "刷新清单",
    onConfirm: (Set<String>) -> Unit,
): Dialog {
    val dialog = settingsDialog(activity)
    val colors = SettingsDialogColors(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, title, colors))
    val locks = HostLockList(activity, hint, selected)
    locks.accepting = { HostSettingsDialogs.acceptsUpdates(dialog) }
    locks.show(options)
    if (refresh != null) {
        var refreshing = false
        lateinit var refreshButton: TextView
        refreshButton = locks.action(refreshLabel, "$refreshLabel，重新获取期物清单") {
            if (refreshing || !HostSettingsDialogs.acceptsUpdates(dialog)) return@action
            refreshing = true
            refreshButton.isEnabled = false
            refreshButton.alpha = BUSY_ALPHA
            refreshButton.text = "正在读取…"
            ModuleResources.thread(Runnable {
                val result = runCatching(refresh)
                activity.runOnUiThread {
                    if (!HostSettingsDialogs.acceptsUpdates(dialog) || !ModuleResources.accepting) return@runOnUiThread
                    refreshing = false
                    refreshButton.isEnabled = true
                    refreshButton.alpha = 1f
                    refreshButton.text = refreshLabel
                    val updated = result.getOrNull()
                    when {
                        result.isFailure -> showToast(result.exceptionOrNull()?.message ?: "刷新清单失败")
                        updated == null -> showToast("没有读到清单，稍后再试")
                        else -> {
                            locks.show(updated)
                            showToast("清单已更新，共 ${updated.size} 项")
                        }
                    }
                }
            }, "ReaMicroPawnCatalog").start()
        }
    }
    card.addView(locks.body)
    HostSettingsDialogs.setActions(dialog, listOf(
        HostSettingsDialogs.Action("取消", nativeButton = true) { dialog.cancel() },
        HostSettingsDialogs.Action("完成", nativeButton = true) {
            if (!HostSettingsDialogs.acceptsUpdates(dialog)) return@Action
            onConfirm(locks.selection)
            dialog.dismiss()
        },
    ))
    showSettingsDialog(dialog, HostFormScroll(activity, card), activity, dismissOnThemeChange = true)
    return dialog
}

private const val BUSY_ALPHA = .5f
