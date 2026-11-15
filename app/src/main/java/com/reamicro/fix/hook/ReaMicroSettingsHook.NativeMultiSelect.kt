package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.hook.settings.SettingsDialogButtonRole
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
    if (hint.isNotBlank()) card.addView(settingsDialogStatus(activity, hint, colors))
    val picked = selected.toMutableSet()
    var shown = options
    val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun renderRows() {
        list.removeAllViews()
        shown.forEach { option ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(settingsDp(activity, 12), settingsDp(activity, 10),
                    settingsDp(activity, 12), settingsDp(activity, 10))
            }
            val name = TextView(activity).apply {
                text = option.label
                EmbeddedHostUi.text(this, "LabelLarge")
                includeFontPadding = false
                setTextColor(option.color ?: colors.title)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val state = TextView(activity).apply {
                EmbeddedHostUi.text(this, "BodySmall")
                includeFontPadding = false
            }
            row.addView(name)
            row.addView(state)
            fun applySelection() {
                val locked = option.value in picked
                state.text = if (locked) "已锁定" else "可典当"
                state.setTextColor(if (locked) colors.primaryText else colors.body)
                row.background = settingsRoundedRect(if (locked) colors.primarySoft else colors.field,
                    settingsDp(activity, 8), colors.border)
            }
            applySelection()
            row.setOnClickListener {
                if (!HostSettingsDialogs.acceptsUpdates(dialog)) return@setOnClickListener
                if (!picked.add(option.value)) picked.remove(option.value)
                applySelection()
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = settingsDp(activity, 6) })
        }
    }
    renderRows()
    if (refresh != null) {
        var refreshing = false
        val refreshButton = settingsDialogButton(activity, refreshLabel, colors, SettingsDialogButtonRole.Neutral)
        refreshButton.setOnClickListener {
            if (refreshing || !HostSettingsDialogs.acceptsUpdates(dialog)) return@setOnClickListener
            refreshing = true
            refreshButton.isEnabled = false
            refreshButton.text = "正在读取…"
            ModuleResources.thread(Runnable {
                val result = runCatching(refresh)
                activity.runOnUiThread {
                    if (!HostSettingsDialogs.acceptsUpdates(dialog) || !ModuleResources.accepting) return@runOnUiThread
                    refreshing = false
                    refreshButton.isEnabled = true
                    refreshButton.text = refreshLabel
                    val updated = result.getOrNull()
                    when {
                        result.isFailure -> showToast(result.exceptionOrNull()?.message ?: "刷新清单失败")
                        updated == null -> showToast("没有读到清单，稍后再试")
                        else -> {
                            shown = updated
                            renderRows()
                            showToast("清单已更新，共 ${updated.size} 项")
                        }
                    }
                }
            }, "ReaMicroPawnCatalog").start()
        }
        card.addView(refreshButton)
    }
    card.addView(list)
    card.addView(settingsDialogActions(activity).apply {
        addView(settingsDialogButton(activity, "完成", colors, SettingsDialogButtonRole.Primary).apply {
            setOnClickListener {
                if (!HostSettingsDialogs.acceptsUpdates(dialog)) return@setOnClickListener
                onConfirm(picked.toSet())
                dialog.dismiss()
            }
        }, settingsDialogButtonParams(activity))
        addView(settingsDialogButton(activity, "取消", colors, SettingsDialogButtonRole.Neutral).apply {
            setOnClickListener { dialog.cancel() }
        }, settingsDialogButtonParams(activity))
    })
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    return dialog
}
