package com.reamicro.fix.hook

import android.text.InputType
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.reload.runOnModuleUiThread
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.cloud.webdav.ModuleConfigSyncSettings
import com.reamicro.fix.cloud.webdav.ModuleConfigScope

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
            )), inner, actions = if (snapshot.syncBookLimitEnabled) {
                listOf(ActionRow(
                    key = ModuleSettings.KEY_SYNC_BOOK_LIMIT,
                    title = "同步图书数量",
                    trailing = "${snapshot.syncBookLimit} 本",
                    onClick = { openSyncBookLimitDialog() },
                ))
            } else emptyList())
        }
        val activity = activityProvider()
        if (activity != null) {
            val configuration = ModuleConfigSyncSettings(activity.applicationContext)
            addLazyItem(scope, 0x524d5355) { inner ->
                renderHostSettingsCard(listOf(
                    ToggleRow(
                        key = "skip_online_books", title = "跳过在线图书", checked = configuration.skipOnlineBooks,
                        onChanged = { enabled, _ ->
                            configuration.setSkipOnlineBooks(enabled); bumpSyncSettingsVersion(); enabled
                        },
                    ),
                    ToggleRow(
                        key = "online_progress_only", title = "仅同步进度和 UUID", checked = configuration.onlineProgressOnly,
                        visibleProvider = { configuration.skipOnlineBooks },
                        onChanged = { enabled, _ ->
                            configuration.setOnlineProgressOnly(enabled); bumpSyncSettingsVersion(); enabled
                        },
                    ),
                ), inner)
            }
            addLazyItem(scope, 0x524d5350) { inner ->
                val rows = listOf(
                    ToggleRow(
                        key = "module_config_sync_enabled", title = "同步模块配置", checked = configuration.enabled,
                        onChanged = { enabled, _ ->
                            configuration.setEnabled(enabled); bumpSyncSettingsVersion(); enabled
                        },
                    ),
                    ToggleRow(
                        key = "module_config_backup_only", title = "仅备份", checked = configuration.backupOnly,
                        visibleProvider = { configuration.enabled },
                        onChanged = { enabled, _ ->
                            configuration.setBackupOnly(enabled); bumpSyncSettingsVersion(); enabled
                        },
                    ),
                ) + ModuleConfigScope.entries.map { item -> ToggleRow(
                    key = "module_config_scope_${item.name}", title = item.title,
                    checked = item in configuration.scopes,
                    visibleProvider = { configuration.enabled },
                    onChanged = { enabled, _ ->
                        configuration.setScope(item, enabled); bumpSyncSettingsVersion(); enabled
                    },
                ) }
                val actions = buildList {
                    if (configuration.enabled) {
                        add(ActionRow(
                            key = "module_config_backup", title = "立即备份模块配置",
                            onClick = { ModuleConfigSyncActions.request?.invoke(false) },
                        ))
                        if (!configuration.backupOnly) add(ActionRow(
                            key = "module_config_fetch", title = "手动获取并应用模块配置",
                            onClick = { ModuleConfigSyncActions.request?.invoke(true) },
                        ))
                    }
                }
                renderHostSettingsCard(rows, inner, actions)
            }
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
