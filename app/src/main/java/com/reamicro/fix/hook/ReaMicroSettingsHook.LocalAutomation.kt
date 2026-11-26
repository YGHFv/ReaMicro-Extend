package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.CloudTaskManager
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskBook
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.xposed.HookRuntime
import org.json.JSONObject

private const val LOCAL_AUTOMATION_LOG_PREFIX = "[ReaMicroFix/LocalAutomation]"

private fun ReaMicroSettingsHook.publishLocalAutomationChange() {
    val appContext = activityProvider()?.applicationContext ?: return
    val credential = runCatching { accountController.currentCloudCredential() }.getOrNull()
    val result = runCatching {
        requireNotNull(credential) { "请先在阅微登录账号" }
        LocalTaskStore { appContext }.refreshToken(credential.accountId, credential.token)
    }
    reloadLocalAutomationState()
    result.exceptionOrNull()?.let {
        localAutomationError = it.message ?: "账号凭据恢复失败"
        bumpLocalAutomationVersion()
        showToast(localAutomationError)
    }
}

internal fun ReaMicroSettingsHook.renderLocalAutomationSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("LocalAutomationList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        localAutomationVersionValue()
        ensureLocalAutomationLoaded()
        val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
        val accountRows = listOf(
            ActionRow(
                key = "local_automation_account",
                title = currentCredential?.label?.ifBlank { "当前阅微账号" } ?: "当前未登录阅微账号",
                subtitle = localAutomationAccountSubtitle(currentCredential),
                trailing = "刷新",
                onClick = ::publishLocalAutomationChange,
            ),
        )
        val taskRows = CLOUD_AUTOMATION_TASKS.map { spec ->
            val task = currentCredential?.let { localAutomationTask(spec.taskType) }
            ActionRow(
                key = "local_automation_${spec.taskType}",
                title = spec.title,
                subtitle = localAutomationTaskSubtitle(spec, task, currentCredential),
                detail = task?.takeIf { localAutomationError.isBlank() }?.let(::localAutomationLastRun),
                onClick = {
                    val accountId = runCatching { accountController.currentCloudCredential().accountId }.getOrNull()
                    if (accountId == currentCredential?.accountId) openLocalAutomationTaskDialog(spec, task)
                    else { resetLocalAutomationState(); bumpLocalAutomationVersion(); showToast("账号已切换，请重新选择任务") }
                },
                trailingContent = { itemComposer ->
                    renderLocalAutomationTaskSwitch(spec, task, currentCredential?.accountId.orEmpty(), itemComposer)
                },
            )
        }

        val historyRows = listOf(
            ActionRow(
                key = "local_automation_records",
                title = "任务记录",
                subtitle = localAutomationRecordsSummary(),
                onClick = ::openLocalAutomationRecordsDialog,
            ),
        )
        addLazyItem(lazyListScope, "local_automation_account_card".hashCode()) { itemComposer ->
            renderHostActionCard(accountRows, itemComposer)
        }
        addLazyItem(lazyListScope, "local_automation_task_card".hashCode()) { itemComposer ->
            renderHostActionCard(taskRows, itemComposer)
            renderHostCardNote("已开启的任务在每次打开阅微时执行一次，配置与独立应用分开保存。", itemComposer)
        }
        addLazyItem(lazyListScope, "local_automation_history_card".hashCode()) { itemComposer ->
            renderHostActionCard(historyRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.resetLocalAutomationState() {
    localAutomationLoaded = false
    localAutomationError = ""
}

private fun ReaMicroSettingsHook.reloadLocalAutomationState() {
    localAutomationLoaded = false
    localAutomationError = ""
    bumpLocalAutomationVersion()
    ensureLocalAutomationLoaded()
}

private fun ReaMicroSettingsHook.ensureLocalAutomationLoaded() {
    val activity = activityProvider() ?: return
    val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
    val accountId = currentCredential?.accountId.orEmpty()
    if (localAutomationAccountId != accountId) {
            localAutomationAccountId = accountId
        localAutomationLoaded = false
        localAutomationTasks = emptyList()
        localAutomationUpdatingTaskTypes = emptySet()
    }
    if (localAutomationLoaded) return
    if (currentCredential == null) {
        localAutomationLoaded = true
        localAutomationError = "请先在阅微登录账号"
        localAutomationTasks = emptyList()
        return
    }
    localAutomationTasks = LocalTaskStore { activity.applicationContext }.list(accountId)
    localAutomationError = ""
    localAutomationLoaded = true
}

private fun ReaMicroSettingsHook.localAutomationTask(taskType: String): LocalTask? =
    localAutomationTasks.firstOrNull { it.taskType == taskType }

private fun ReaMicroSettingsHook.localRecords(accountId: String): List<LocalTaskRecord> {
    if (accountId.isBlank()) return emptyList()
    val appContext = activityProvider()?.applicationContext ?: return emptyList()
    return runCatching { LocalTaskStore { appContext }.records(accountId) }.getOrDefault(emptyList())
}

private fun ReaMicroSettingsHook.localAutomationRecordsSummary(): String {
    val accountId = localAutomationAccountId
    if (accountId.isBlank()) return "查看历史执行结果"
    val count = localRecords(accountId).size
    return if (count == 0) "暂无记录 · 点击查看" else "本机已记录 $count 条执行结果"
}

private fun ReaMicroSettingsHook.openLocalAutomationRecordsDialog() {
    val activity = activityProvider() ?: return
    val accountId = localAutomationAccountId
    if (accountId.isBlank()) {
        showToast("请先在阅微登录账号")
        return
    }
    activity.runOnModuleUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = settingsDialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "任务记录", colors))
        val stored = localRecords(accountId)
        val records = stored.take(LOCAL_RECORD_DISPLAY_LIMIT)
        val history = HostLocalTaskRecords(activity)
        if (records.isEmpty()) {
            history.empty("暂无记录。下次打开阅微时执行已开启的任务。")
        } else {
            history.summary(
                if (stored.size > records.size) "最近 ${records.size} 条，共 ${stored.size} 条。仅包含宿主任务。"
                else "共 ${records.size} 条，最新在前。仅包含宿主任务。",
            )
            records.forEach { record ->
                history.record(
                    localTaskTitleOf(record.taskType), formatLocalRecordTime(record.at), record.result, record.message,
                )
            }
        }
        card.addView(history.body)
        HostSettingsDialogs.setActions(dialog, listOf(
            HostSettingsDialogs.Action("清空记录", nativeButton = true) {
                val appContext = activity.applicationContext
                val cleared = runCatching {
                    check(accountController.currentCloudCredential().accountId == accountId) { "账号已切换，请重新打开记录" }
                    LocalTaskStore { appContext }.clearRecords(accountId)
                }
                if (cleared.isFailure) {
                    showToast(cleared.exceptionOrNull()?.message ?: "清空失败")
                } else {
                    dialog.dismiss()
                    showToast("宿主任务记录已清空")
                }
            },
            HostSettingsDialogs.Action("关闭", nativeButton = true) { dialog.dismiss() },
        ))
        showSettingsDialog(dialog, HostFormScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

private fun localTaskTitleOf(taskType: String): String = when (taskType) {
    "yeshe_checkin" -> "每日轶闻"
    "yeshe_draw_card" -> "自动祈愿"
    "cloud_auto_read" -> "自动阅读"
    "traveling_merchant" -> "自动行商"
    "pawn" -> "期物典当"
    else -> taskType
}

private fun formatLocalRecordTime(at: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at))

private const val LOCAL_RECORD_DISPLAY_LIMIT = 60

private fun ReaMicroSettingsHook.localAutomationAccountSubtitle(
    current: AccountCompletionController.CurrentCloudCredential?,
): String = when {
    current == null -> "请先在阅微登录账号"
    localAutomationError.isNotBlank() -> localAutomationError
    else -> "账号 ${current.accountId}"
}

/** What the task is set to do; the switch beside it already says whether it runs. */
private fun ReaMicroSettingsHook.localAutomationTaskSubtitle(
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
    current: AccountCompletionController.CurrentCloudCredential?,
): String = when {
    current == null -> "请先登录阅微账号"
    localAutomationError.isNotBlank() -> localAutomationError
    task == null -> "未配置"
    spec.autoRead -> listOf(
        if (task.hostReadOncePerDay) "每日一次" else "每次打开",
        "${task.durationMinutes} 分钟",
        if (task.books.isEmpty()) "最近阅读" else "${task.books.size} 本指定图书",
    ).joinToString(" · ")
    spec.merchant && !task.merchantAutoComplete -> "仅通知，不自动完成"
    spec.merchant -> "自动完成行商 · ${localAutomationBlessing(spec, task)}"
    spec.rewardTriggered -> if (task.dailyDrawLimit == 0) "抽完全部彩筹" else "每日最多 ${task.dailyDrawLimit} 次"
    spec.blessingOptions.isNotEmpty() -> localAutomationBlessing(spec, task)
    spec.taskType != "pawn" -> "已配置"
    task.forbiddenPawnPropIds.isEmpty() -> "未锁定期物，全部可典当"
    else -> "已锁定 ${task.forbiddenPawnPropIds.size} 项期物"
}

private fun localAutomationBlessing(spec: CloudAutomationTaskSpec, task: LocalTask): String =
    when (spec.resolveBlessingChoice(task.blessingType)) {
        CloudTaskLocalRunner.BLESSING_LUCK -> "无签时求运"
        CloudTaskLocalRunner.BLESSING_SAFETY -> "无签时求安"
        CloudTaskLocalRunner.BLESSING_WEALTH -> "无签时求财"
        else -> "不自动祈签"
    }

/** When the task last ran and what came of it, on one line. */
private fun localAutomationLastRun(task: LocalTask): String {
    val message = task.lastMessage.replace(Regex("\\s+"), " ").trim()
    if (task.lastRunAt <= 0L) return message.ifEmpty { "尚未执行" }
    val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(task.lastRunAt))
    return if (message.isEmpty()) "上次 $time" else "上次 $time · $message"
}

private fun ReaMicroSettingsHook.renderLocalAutomationTaskSwitch(
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
    accountId: String,
    composer: Any,
) {
    val targetChecked = task?.enabled == true
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        setStateValue(state, value)
    }
    val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
    val checked = if (rememberedChecked != targetChecked && spec.taskType !in localAutomationUpdatingTaskTypes) {
        updateChecked(targetChecked)
        targetChecked
    } else {
        rememberedChecked
    }
    val onCheckedChange = functionProxy("LocalAutomationSwitch${spec.taskType}", FUNCTION1_CLASS) { args ->
        val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        val current = runCatching { accountController.currentCloudCredential().accountId }.getOrNull().orEmpty()
        if (current != accountId) {
            resetLocalAutomationState(); bumpLocalAutomationVersion(); showToast("账号已切换，请重新选择任务")
            return@functionProxy targetUnit()
        }
        val applied = setLocalAutomationTaskEnabled(spec, task, enabled)
        updateChecked(applied)
        targetUnit()
    }
    nativeUi.settingsSwitch(checked, onCheckedChange, composer)
}

private fun ReaMicroSettingsHook.setLocalAutomationTaskEnabled(
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
    enabled: Boolean,
): Boolean {
    val activity = activityProvider() ?: return task?.enabled == true
    val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
    if (currentCredential == null) {
        showToast("请先在阅微登录账号")
        return false
    }
    if (task == null) {

        if (enabled) openLocalAutomationTaskDialog(spec, null)
        return false
    }
    val accountId = currentCredential.accountId
    val store = LocalTaskStore { activity.applicationContext }
    val saved = runCatching { store.setEnabled(accountId, spec.taskType, enabled, if (enabled) currentCredential.token else "") }
    if (saved.isFailure) {
        showToast(saved.exceptionOrNull()?.message ?: "任务保存失败")
        return store.get(accountId, spec.taskType)?.enabled == true
    }

    if (enabled) disableCloudAutomationTask(accountId, spec.taskType)
    localAutomationTasks = store.list(accountId)
    bumpLocalAutomationVersion()

    publishLocalAutomationChange()
    showToast(if (enabled) "已启用${spec.title}" else "已停用${spec.title}")
    return enabled
}

private fun ReaMicroSettingsHook.openLocalAutomationTaskDialog(
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
) {
    val activity = activityProvider() ?: return
    val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
    if (currentCredential == null) {
        showToast("请先在阅微登录账号")
        return
    }
    val accountId = currentCredential.accountId
    activity.runOnModuleUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = settingsDialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, spec.title, colors))
        val pawnStore = LocalTaskStore { activity.applicationContext }
        val toPawnOptions = { state: JSONObject ->
            com.reamicro.fix.cloud.local.CloudTaskLocalRunner.pawnPropChoices(state).map {
                com.reamicro.fix.ui.MultiSelectOption(
                    it.propId,
                    it.name,
                    com.reamicro.fix.notification.cloudTaskQualityColor(it.quality),
                    it.hint,
                )
            }
        }
        var pawnPropSelection = task?.forbiddenPawnPropIds
            ?: com.reamicro.fix.cloud.local.CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys
        var pawnPropOptions = toPawnOptions(pawnStore.runtimeState(accountId, "pawn"))

        var refreshedPawnCatalog: JSONObject? = null
        lateinit var editor: HostLocalTaskEditor
        editor = HostLocalTaskEditor(
            activity, spec, task,
            "${currentCredential.label.ifBlank { "当前阅微账号" }} · $accountId",
            com.reamicro.fix.ui.multiSelectSummary(pawnPropOptions, pawnPropSelection),
            onPawnClick = {
                showNativeMultiSelectDialog(
                    activity,
                    "禁当期物",
                    "点一下锁定期物禁止典当；清单来自当日期物与背包，可点「刷新期物清单」现拉一次",
                    pawnPropOptions,
                    pawnPropSelection,
                    refresh = {
                        check(accountController.currentCloudCredential().accountId == accountId) { "账号已切换，请重新打开任务配置" }

                        if (currentCredential.token.isBlank()) {
                            null
                        } else {
                            val fetched = com.reamicro.fix.cloud.local.CloudTaskLocalRunner
                                .fetchPawnPropCatalog(currentCredential.token)
                            fetched.error?.let { HookRuntime.log("$LOCAL_AUTOMATION_LOG_PREFIX 期物清单部分缺失：$it") }
                            refreshedPawnCatalog = fetched.catalog
                            val state = JSONObject().put(
                                com.reamicro.fix.cloud.local.CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG,
                                fetched.catalog,
                            )
                            pawnStore.recordState(accountId, "pawn", state)

                            pawnPropOptions = toPawnOptions(state)
                            pawnPropOptions
                        }
                    },
                    refreshLabel = "刷新期物清单",
                ) { confirmed ->
                    pawnPropSelection = confirmed
                    editor.pawnSummaryView?.text = com.reamicro.fix.ui.multiSelectSummary(pawnPropOptions, pawnPropSelection)
                }
            },
        )
        card.addView(editor.body)
        val save: () -> Unit = save@{
            editor.form.clearError()
            val normalizedTime = task?.timeOfDay?.ifBlank { "00:05" } ?: "00:05"
            val durationMinutes = if (spec.autoRead) {
                editor.duration?.text.toString().toIntOrNull()?.takeIf { it in 1..720 }
                    ?: run { editor.form.showError("阅读时长应为 1 到 720 分钟", editor.duration); return@save }
            } else 30
            val dailyDrawLimit = if (spec.rewardTriggered) {
                editor.drawLimit?.text.toString().toIntOrNull()?.takeIf { it in 0..20 }
                    ?: run { editor.form.showError("每日祈愿上限应为 0 到 20", editor.drawLimit); return@save }
            } else 3
            val selectedBooks = if (spec.autoRead && !editor.usesRecentBooks) {
                runCatching {
                    parseLocalReadingBooks(editor.books?.text.toString()).also {
                        require(it.isNotEmpty()) { "请至少填写一本图书，或选择最近阅读" }
                    }
                }.onFailure { editor.form.showError(it.message ?: "图书格式无效", editor.books) }
                    .getOrNull() ?: return@save
            } else emptyList()
            if (currentCredential.token.isBlank() || accountId.isBlank()) {
                editor.form.showError("当前阅微登录密钥不完整，请重新登录后重试")
                return@save
            }
            fun nonnegative(field: android.widget.EditText?): Long? {
                val value = field?.text?.toString()?.trim().orEmpty()
                return if (value.isEmpty()) 0L else value.toLongOrNull()?.takeIf { it >= 0 }
            }
            val principal = nonnegative(editor.merchantPrincipal) ?: run {
                editor.form.showError("本金应为非负整数，留空沿用上次", editor.merchantPrincipal); return@save
            }
            val transport = nonnegative(editor.merchantTransport) ?: run {
                editor.form.showError("车马 ID 应为非负整数，留空沿用上次", editor.merchantTransport); return@save
            }
            val enabled = task?.enabled ?: false
            val localTask = LocalTask(
                taskType = spec.taskType,
                enabled = enabled,
                timeOfDay = normalizedTime,
                durationMinutes = durationMinutes,
                dailyDrawLimit = dailyDrawLimit,
                books = selectedBooks,
                hostReadOncePerDay = if (spec.autoRead) editor.readOncePerDay?.isChecked != false else task?.hostReadOncePerDay ?: true,
                merchantAutoComplete = spec.merchant && editor.merchantAutoComplete?.isChecked == true,
                merchantCityCode = if (spec.merchant) editor.merchantCity?.text?.toString()?.trim().orEmpty() else "",
                merchantPrincipal = if (spec.merchant) principal else 0L,
                merchantTransportId = if (spec.merchant) transport else 0L,
                forbiddenPawnPropIds = if (spec.taskType == "pawn") {
                    pawnPropSelection
                } else {
                    task?.forbiddenPawnPropIds ?: emptySet()
                },
                blessingType = editor.blessingChoice,
            )
            val store = LocalTaskStore { activity.applicationContext }
            val saved = runCatching {
                val latestCredential = accountController.currentCloudCredential()
                check(latestCredential.accountId == accountId) { "账号已切换，请关闭后重新打开任务配置" }
                check(latestCredential.token.isNotBlank()) { "登录凭据不可用，请重新登录" }
                store.saveEditedTask(accountId, localTask, latestCredential.token, task)
            }
            if (saved.isFailure) { editor.form.showError(saved.exceptionOrNull()?.message ?: "任务保存失败"); return@save }

            if (spec.taskType == "pawn") {
                refreshedPawnCatalog?.let { catalog ->
                    store.recordState(
                        accountId,
                        spec.taskType,
                        JSONObject().put(
                            com.reamicro.fix.cloud.local.CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG,
                            catalog,
                        ),
                    )
                }
            }
            if (enabled) disableCloudAutomationTask(accountId, spec.taskType)
            dialog.dismiss()
            showToast("${spec.title}配置已保存")

            publishLocalAutomationChange()
            reloadLocalAutomationState()
        }
        HostSettingsDialogs.setActions(dialog, listOf(
            HostSettingsDialogs.Action("取消", nativeButton = true) { dialog.dismiss() },
            HostSettingsDialogs.Action("保存", nativeButton = true, onClick = save),
        ))
        showSettingsDialog(dialog, HostFormScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

internal fun ReaMicroSettingsHook.disableLocalAutomationTask(accountId: String, taskType: String) {
    val activity = activityProvider() ?: return
    runCatching {
        val store = LocalTaskStore { activity.applicationContext }
        if (store.get(accountId, taskType)?.enabled == true) {
            store.setEnabled(accountId, taskType, false)
            publishLocalAutomationChange()
            if (localAutomationAccountId == accountId) {
                localAutomationTasks = store.list(accountId)
                bumpLocalAutomationVersion()
            }
        }
    }.onFailure {
        HookRuntime.log("$LOCAL_AUTOMATION_LOG_PREFIX disable local failed: ${it.message}")
    }
}

internal fun ReaMicroSettingsHook.disableCloudAutomationTask(accountId: String, taskType: String) {
    val activity = activityProvider() ?: return
    if (accountId.isBlank()) return
    val store = ApiServerSettingsStore { activity.applicationContext }
    val requestSettings = store.get().copy(hostAccountId = accountId)
    if (!requestSettings.enabled || requestSettings.baseUrl.isBlank()) return
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching {
            // Finish the account's local/cloud handoff even if the foreground changes meanwhile.
            val manager = CloudTaskManager(ApiServerClient(store, requestSettings))
            val credentialIds = manager.credentials().filter { it.accountId == accountId }.mapTo(hashSetOf()) { it.id }
            manager.list()
                .filter { it.taskType == taskType && it.enabled && it.credentialId in credentialIds }
                .forEach { manager.pause(it.id) }
        }.onFailure {
            HookRuntime.log("$LOCAL_AUTOMATION_LOG_PREFIX disable cloud failed: ${it.message}")
            activity.runOnModuleUiThread {
                if (AccountHostRuntime.snapshot(activity)?.accountId?.toString() == accountId)
                    showToast("本地任务已启用，停用对应云端任务失败，请刷新任务状态")
            }
        }
    }.apply { isDaemon = true; start() }
}

private fun parseLocalReadingBooks(raw: String): List<LocalTaskBook> {
    val text = raw.trim()
    if (text.isBlank()) return emptyList()
    return text.lineSequence().map(String::trim).filter(String::isNotBlank).map { line ->
        val parts = line.split('|', limit = 2)
        val id = parts.first().trim().toLongOrNull() ?: error("自定义图书 ID 无效：${parts.first()}")
        require(id > 0L) { "自定义图书 ID 必须大于 0" }
        LocalTaskBook(bookId = id, name = parts.getOrNull(1)?.trim().orEmpty())
    }.toList()
}

private fun ReaMicroSettingsHook.localAutomationVersionState(): Any {
    localAutomationVersionUiState?.let { return it }
    return mutableState(0).also { localAutomationVersionUiState = it }
}

private fun ReaMicroSettingsHook.localAutomationVersionValue(): Int =
    (localAutomationVersionState().method0("getValue") as? Number)?.toInt() ?: 0

private fun ReaMicroSettingsHook.bumpLocalAutomationVersion() {
    val state = localAutomationVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    setStateValue(state, value + 1)
}
