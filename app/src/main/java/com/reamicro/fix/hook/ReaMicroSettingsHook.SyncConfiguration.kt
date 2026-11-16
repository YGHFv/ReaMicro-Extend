package com.reamicro.fix.hook

import android.text.InputType
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.reload.runOnModuleUiThread
import com.reamicro.fix.settings.ModuleSettings

internal fun ReaMicroSettingsHook.syncSettingsVersionValue(): Int {
    val state = syncSettingsVersionUiState ?: mutableState(0).also { syncSettingsVersionUiState = it }
    return (stateValue(state) as Number).toInt()
}

internal fun ReaMicroSettingsHook.bumpSyncSettingsVersion() {
    val value = syncSettingsVersionValue()
    setStateValue(requireNotNull(syncSettingsVersionUiState), value + 1)
}

internal fun ReaMicroSettingsHook.syncConfigurationSubtitle(): String {
    val snapshot = settings.snapshot()
    return if (snapshot.syncBookLimitEnabled) "最多同步 ${snapshot.syncBookLimit} 本图书" else "同步图书数量不限"
}

internal fun ReaMicroSettingsHook.renderSyncConfigurationSettingsContent(innerPaddings: Any, composer: Any) {
    val content = functionProxy("SyncConfigurationList", FUNCTION1_CLASS) { args ->
        val scope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        syncSettingsVersionValue()
        val snapshot = settings.snapshot()
        addLazyItem(scope, 0x524d534c) { inner ->
            renderHostSettingsCard(listOf(ToggleRow(
                key = ModuleSettings.KEY_SYNC_BOOK_LIMIT_ENABLED,
                title = "同步图书限制",
                checked = snapshot.syncBookLimitEnabled,
                onChanged = { enabled, _ ->
                    settings.setSyncBookLimitEnabled(enabled)
                    bumpSyncSettingsVersion()
                    enabled
                },
            )), inner)
        }
        if (snapshot.syncBookLimitEnabled) {
            addLazyItem(scope, 0x524d534e) { inner ->
                renderHostActionCard(listOf(ActionRow(
                    key = ModuleSettings.KEY_SYNC_BOOK_LIMIT,
                    title = "同步图书数量",
                    trailing = "${snapshot.syncBookLimit} 本",
                    onClick = { openSyncBookLimitDialog() },
                )), inner)
            }
        }
        addLazyItem(scope, 0x524d534f) { inner ->
            renderHostSupportingText("超过数量时移除远端最长时间未变更的图书，保留本机图书。配置在下次同步时生效。", inner)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, content, composer)
}

internal fun ReaMicroSettingsHook.openSyncBookLimitDialog(onSaved: () -> Unit = {}) {
    val activity = activityProvider() ?: return
    activity.runOnModuleUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = settingsDialog(activity)
        val card = settingsDialogCard(activity, colors)
        val input = settingsDialogInput(activity, "同步图书数量", true, colors).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.snapshot().syncBookLimit.toString())
            selectAll()
        }
        val status = settingsDialogStatus(activity, "可设置 1～${ModuleSettings.MAX_SYNC_BOOK_LIMIT} 本图书", colors)
        val save = settingsDialogButton(activity, "保存", colors)
        val cancel = settingsDialogButton(activity, "取消", colors, SettingsDialogButtonRole.Neutral)
        card.addView(settingsDialogTitle(activity, "同步图书数量", colors))
        card.addView(input)
        card.addView(status)
        card.addView(settingsDialogActions(activity).apply {
            addView(cancel, settingsDialogButtonParams(activity))
            addView(save, settingsDialogButtonParams(activity))
        })
        save.setOnClickListener {
            val value = input.text.toString().trim().toIntOrNull()
            if (value == null || value !in 1..ModuleSettings.MAX_SYNC_BOOK_LIMIT) {
                status.text = "请输入 1～${ModuleSettings.MAX_SYNC_BOOK_LIMIT} 的整数"
            } else {
                settings.setSyncBookLimit(value)
                bumpSyncSettingsVersion()
                dialog.dismiss()
                onSaved()
            }
        }
        cancel.setOnClickListener { dialog.dismiss() }
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
    }
}
