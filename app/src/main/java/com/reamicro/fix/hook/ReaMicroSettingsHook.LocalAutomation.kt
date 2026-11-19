package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.text.InputType
import android.widget.TextView
import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.CloudTaskManager
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
        val status = TextView(activity).apply {
            setTextColor(colors.body)
            setPadding(24, 8, 24, 8)
            text = "正在读取……"
        }
        card.addView(status, apiServerRowParams(activity))
        val list = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        card.addView(list, apiServerRowParams(activity))

        val records = localRecords(accountId).take(LOCAL_RECORD_DISPLAY_LIMIT)
        status.text = if (records.isEmpty()) "暂无记录。下次打开阅微时执行已开启的任务。"
        else "共 ${records.size} 条，最新在前。仅包含宿主任务。"
        records.forEach { record ->
            list.addView(TextView(activity).apply {
                setTextColor(if (record.result == "success") colors.title else colors.destructiveText)
                setPadding(24, 6, 24, 6)
                text = "${formatLocalRecordTime(record.at)} · ${localTaskTitleOf(record.taskType)} · ${record.result}\n${record.message}"
            })
        }
        val actions = settingsDialogActions(activity)
        actions.addView(
            settingsDialogButton(activity, "清空记录", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener {
                    val appContext = activity.applicationContext
                    val cleared = runCatching {
                        check(accountController.currentCloudCredential().accountId == accountId) { "账号已切换，请重新打开记录" }
                        LocalTaskStore { appContext }.clearRecords(accountId)
                    }
                    if (cleared.isFailure) { showToast(cleared.exceptionOrNull()?.message ?: "清空失败"); return@setOnClickListener }
                    dialog.dismiss()
                    showToast("宿主任务记录已清空")
                }
            },
            settingsDialogButtonParams(activity),
        )
        actions.addView(
            settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener { dialog.dismiss() }
            },
            settingsDialogButtonParams(activity),
        )
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
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
    else -> "账号 ${current.accountId} · 打开阅微时执行一次，配置与独立应用分开保存"
}

private fun ReaMicroSettingsHook.localAutomationTaskSubtitle(
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
    current: AccountCompletionController.CurrentCloudCredential?,
): String = when {
    current == null -> "请先登录阅微账号"
    localAutomationError.isNotBlank() -> localAutomationError
    task == null -> "未配置 · 开启后在下次打开阅微时执行一次"
    else -> buildString {
        append(if (task.enabled) "已启用 · 打开阅微时执行一次" else "已关闭")
        when {
            spec.merchant -> {
                append(" · ")
                append(if (task.merchantAutoComplete) "自动完成行商" else "仅通知不自动完成")
            }
            spec.rewardTriggered -> {
                append(" · ")
                append(if (task.dailyDrawLimit == 0) "抽完全部彩筹" else "每日最多 ${task.dailyDrawLimit} 次")
            }
        }
        if (spec.autoRead) {
            append(" · ")
            append(task.durationMinutes)
            append(" 分钟 · ")
            append(if (task.books.isEmpty()) "最近阅读" else "${task.books.size} 本指定图书")
        }
        if (task.lastMessage.isNotBlank()) {
            append("\n")
            append(task.lastMessage)
        }
    }
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

        if (enabled) openLocalAutomationTaskDialog(spec, null, enableAfterSave = true)
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
    enableAfterSave: Boolean? = null,
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
        val account = TextView(activity).apply {
            setTextColor(colors.body)
            text = "阅微账号：${currentCredential.label.ifBlank { "阅微账号" }}（$accountId）"
            setPadding(24, 8, 24, 8)
        }
        val duration = apiServerEdit(activity, colors, "阅读时长（分钟）", (task?.durationMinutes ?: 30).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val drawLimit = apiServerEdit(
            activity,
            colors,
            "每日抽卡上限（0-20，0 表示抽完全部彩筹）",
            (task?.dailyDrawLimit ?: 3).toString(),
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val books = apiServerEdit(
            activity,
            colors,
            "自定义图书：每行 bookId|书名；留空读取最近阅读",
            task?.books?.joinToString("\n") { "${it.bookId}|${it.name}" }.orEmpty(),
        ).apply {
            minLines = 3
            setSingleLine(false)
        }
        val merchantAutoComplete = settingsDialogSwitchRow(activity, "自动完成行商", task?.merchantAutoComplete == true, colors)

        val pawnStore = LocalTaskStore { activity.applicationContext }
        val toPawnOptions = { state: JSONObject ->
            com.reamicro.fix.cloud.local.CloudTaskLocalRunner.pawnPropChoices(state).map {
                com.reamicro.fix.ui.MultiSelectOption(
                    it.propId,
                    it.label,
                    com.reamicro.fix.notification.cloudTaskQualityColor(it.quality),
                )
            }
        }
        var pawnPropSelection = task?.forbiddenPawnPropIds
            ?: com.reamicro.fix.cloud.local.CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys
        var pawnPropOptions = toPawnOptions(pawnStore.runtimeState(accountId, "pawn"))

        var refreshedPawnCatalog: JSONObject? = null
        val forbiddenPawnProps = settingsDialogButton(
            activity,
            com.reamicro.fix.ui.multiSelectSummary(pawnPropOptions, pawnPropSelection),
            colors,
            SettingsDialogButtonRole.Neutral,
        ).apply {
            setOnClickListener {
                showNativeMultiSelectDialog(
                    activity,
                    "禁当期物",
                    "点一下锁定期物禁止典当；清单来自当日期物与背包，可点「刷新期物清单」现拉一次",
                    pawnPropOptions,
                    pawnPropSelection,
                    refresh = {

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
                    text = com.reamicro.fix.ui.multiSelectSummary(pawnPropOptions, pawnPropSelection)
                }
            }
        }
        val merchantCity = apiServerEdit(activity, colors, "新行商城池 cityCode（留空沿用上次行商）", task?.merchantCityCode.orEmpty())
        val merchantPrincipal = apiServerEdit(activity, colors, "新行商本金（铜，留空沿用上次）", (task?.merchantPrincipal?.takeIf { it > 0L })?.toString().orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val merchantTransport = apiServerEdit(activity, colors, "新行商车马 transportId（留空沿用上次）", (task?.merchantTransportId?.takeIf { it > 0L })?.toString().orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        var blessingChoice = spec.resolveBlessingChoice(task?.blessingType)
        val blessingButton = spec.blessingOptions.takeIf { it.isNotEmpty() }?.let {
            settingsDialogButton(
                activity,
                "运签：${com.reamicro.fix.cloud.local.CloudTaskLocalRunner.blessingLabel(blessingChoice)}",
                colors,
                SettingsDialogButtonRole.Neutral,
            ).apply {
                setOnClickListener {
                    if (spec.blessingOptions.size <= 1) {
                        showToast("${spec.title}固定使用求运签")
                        return@setOnClickListener
                    }
                    val next = (spec.blessingOptions.indexOf(blessingChoice) + 1) % spec.blessingOptions.size
                    blessingChoice = spec.blessingOptions[next]
                    text = "运签：${com.reamicro.fix.cloud.local.CloudTaskLocalRunner.blessingLabel(blessingChoice)}"
                }
            }
        }
        card.addView(account, apiServerRowParams(activity))
        if (spec.rewardTriggered) card.addView(drawLimit, apiServerRowParams(activity))
        if (spec.autoRead) {
            card.addView(duration, apiServerRowParams(activity))
            card.addView(books, apiServerRowParams(activity))
        }
        blessingButton?.let { card.addView(it, apiServerRowParams(activity)) }
        if (spec.taskType == "pawn") card.addView(forbiddenPawnProps, apiServerRowParams(activity))
        if (spec.merchant) {
            card.addView(merchantAutoComplete, apiServerRowParams(activity))
            card.addView(merchantCity, apiServerRowParams(activity))
            card.addView(merchantPrincipal, apiServerRowParams(activity))
            card.addView(merchantTransport, apiServerRowParams(activity))
        }
        val status = TextView(activity).apply {
            setTextColor(colors.body)
            text = when {
                spec.merchant -> "仅在打开阅微时检查一次行商并记录结果。开启自动完成后，可结算已完成的行商并按配置开启下一趟；离开阅微后不定时执行"
                spec.taskType == "pawn" -> "按当日期物典当换铜钱；「禁当期物」里锁定的期物会跳过，一项都不锁则任何期物都典当"
                spec.rewardTriggered -> "启用后按每日上限抽卡；填写 0 会抽到彩筹用完"
                task == null -> "保存后，下次打开阅微时执行"
                else -> "保存后，下次打开阅微时使用新配置"
            }
            setPadding(24, 8, 24, 8)
        }
        card.addView(status, apiServerRowParams(activity))
        val actions = settingsDialogActions(activity)
        val saveButton = settingsDialogButton(activity, "保存", colors, SettingsDialogButtonRole.Primary)
        saveButton.setOnClickListener {
            val normalizedTime = task?.timeOfDay?.ifBlank { "00:05" } ?: "00:05"
            val durationMinutes = if (spec.autoRead) {
                duration.text.toString().toIntOrNull()?.takeIf { it in 1..720 }
                    ?: run { status.text = "阅读时长应为 1 到 720 分钟"; return@setOnClickListener }
            } else 30
            val dailyDrawLimit = if (spec.rewardTriggered) {
                drawLimit.text.toString().toIntOrNull()?.takeIf { it in 0..20 }
                    ?: run { status.text = "每日抽卡上限应为 0 到 20；0 表示抽完全部彩筹"; return@setOnClickListener }
            } else 3
            val selectedBooks = if (spec.autoRead) {
                runCatching { parseLocalReadingBooks(books.text.toString()) }
                    .onFailure { status.text = it.message ?: "自定义图书格式无效" }
                    .getOrNull() ?: return@setOnClickListener
            } else emptyList()
            if (currentCredential.token.isBlank() || accountId.isBlank()) {
                status.text = "当前阅微登录密钥不完整，请重新登录后重试"
                return@setOnClickListener
            }
            val enabled = enableAfterSave ?: task?.enabled ?: false
            val localTask = LocalTask(
                taskType = spec.taskType,
                enabled = enabled,
                timeOfDay = normalizedTime,
                durationMinutes = durationMinutes,
                dailyDrawLimit = dailyDrawLimit,
                books = selectedBooks,
                merchantAutoComplete = spec.merchant && merchantAutoComplete.isChecked,
                merchantCityCode = if (spec.merchant) merchantCity.text.toString().trim() else "",
                merchantPrincipal = if (spec.merchant) merchantPrincipal.text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L,
                merchantTransportId = if (spec.merchant) merchantTransport.text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L,
                forbiddenPawnPropIds = if (spec.taskType == "pawn") {
                    pawnPropSelection
                } else {
                    task?.forbiddenPawnPropIds ?: emptySet()
                },
                blessingType = blessingChoice,
            )
            val store = LocalTaskStore { activity.applicationContext }
            val saved = runCatching {
                val latestCredential = accountController.currentCloudCredential()
                check(latestCredential.accountId == accountId) { "账号已切换，请关闭后重新打开任务配置" }
                check(latestCredential.token.isNotBlank()) { "登录凭据不可用，请重新登录" }
                store.saveEditedTask(accountId, localTask, latestCredential.token, task)
            }
            if (saved.isFailure) { status.text = saved.exceptionOrNull()?.message ?: "任务保存失败"; return@setOnClickListener }

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
            showToast(if (enableAfterSave == true) "已配置并启用${spec.title}" else "${spec.title}配置已保存")

            publishLocalAutomationChange()
            reloadLocalAutomationState()
        }
        actions.addView(saveButton, settingsDialogButtonParams(activity))
        actions.addView(
            settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener { dialog.dismiss() }
            },
            settingsDialogButtonParams(activity),
        )
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
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
