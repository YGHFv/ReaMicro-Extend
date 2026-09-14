package com.reamicro.fix.hook

import android.app.Dialog
import android.text.InputType
import android.view.View
import android.widget.TextView
import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.CloudTaskManager
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskBook
import com.reamicro.fix.cloud.local.LocalTaskMirror
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.hook.settings.*
import de.robv.android.xposed.XposedBridge

private const val LOCAL_AUTOMATION_LOG_PREFIX = "[ReaMicroFix/LocalAutomation]"

/**
 * 配置变更后统一下发镜像并重排闹钟。
 *
 * 两件事缺一不可：
 * 1. **镜像**：设置页在宿主进程写的是宿主的 prefs，而闹钟唤醒后执行任务的模块进程读的是
 *    自己的 prefs，不同步过去后台就是空配置（表现为"设了任务却从不自启"）。
 * 2. **排闹钟**：`schedule()` 此前只在云端轮询/模块 provider 路径被调用，用户在设置页启用
 *    本地任务时从不排程，没有 API 服务器时闹钟根本不存在。
 */
private fun ReaMicroSettingsHook.publishLocalAutomationChange() {
    val appContext = activityProvider()?.applicationContext ?: return
    LocalTaskMirror.push(appContext)
    runCatching { CloudTaskWakeScheduler.schedule(appContext) }
}

/**
 * 本地「自动任务」页。任务列表与配置项与云端任务保持一致，但配置与阅微 token 都保存在本机
 * [LocalTaskStore]，由模块进程直接执行、发本地通知，不依赖 API 服务器。
 */
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
                onClick = ::reloadLocalAutomationState,
            ),
        )
        val taskRows = CLOUD_AUTOMATION_TASKS.map { spec ->
            val task = currentCredential?.let { localAutomationTask(spec.taskType) }
            ActionRow(
                key = "local_automation_${spec.taskType}",
                title = spec.title,
                subtitle = localAutomationTaskSubtitle(spec, task, currentCredential),
                onClick = { openLocalAutomationTaskDialog(spec, task) },
                trailingContent = { itemComposer ->
                    renderLocalAutomationTaskSwitch(spec, task, itemComposer)
                },
            )
        }
        val wakeReport = activityProvider()?.let {
            com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics.inspect(it.applicationContext)
        }
        val utilityRows = listOf(
            ActionRow(
                key = "local_automation_wake",
                title = "任务结果通知",
                subtitle = wakeReport?.summary() ?: "检查通知与后台唤醒权限",
                onClick = ::openLocalAutomationWakeDialog,
            ),
        )
        // 任务记录放在最下面：配置页要的「以配置为主」，记录属于事后回查。
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
        addLazyItem(lazyListScope, "local_automation_utility_card".hashCode()) { itemComposer ->
            renderHostActionCard(utilityRows, itemComposer)
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

/** 前台（宿主进程）写下的执行记录。后台记录由模块进程写，需另行拉取。 */
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
    activity.runOnUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
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

        fun render(hostRecords: List<LocalTaskRecord>, moduleRecords: List<LocalTaskRecord>, moduleReachable: Boolean) {
            // 前后台各写各的记录（宿主进程 / 模块进程），按时间戳合并后统一展示。
            val merged = (hostRecords + moduleRecords).sortedByDescending { it.at }.take(LOCAL_RECORD_DISPLAY_LIMIT)
            list.removeAllViews()
            status.text = when {
                merged.isEmpty() && !moduleReachable ->
                    "暂无记录。后台记录需要模块被唤醒过一次后才能读取（可先用模块自检里的截图确认权限）。"
                merged.isEmpty() -> "暂无执行记录。任务执行后会在这里留下结果。"
                !moduleReachable ->
                    "共 ${merged.size} 条（含前台记录）。模块未启动，后台执行记录暂不可读——这不代表后台没执行。"
                else -> "共 ${merged.size} 条，最新在前。"
            }
            merged.forEach { record ->
                list.addView(
                    TextView(activity).apply {
                        setTextColor(if (record.result == "success") colors.title else colors.destructiveText)
                        setPadding(24, 6, 24, 6)
                        text = buildString {
                            append(formatLocalRecordTime(record.at))
                            append(" · ")
                            append(localTaskTitleOf(record.taskType))
                            append(" · ")
                            append(if (record.result == "success") "成功" else record.result)
                            append('\n')
                            append(record.message)
                        }
                    },
                )
            }
        }

        Thread {
            val hostRecords = localRecords(accountId)
            val moduleRecords = runCatching {
                com.reamicro.fix.cloud.api.readModuleLocalTaskRecords(activity.applicationContext, accountId)
            }.getOrDefault(emptyList())
            activity.runOnUiThread {
                render(hostRecords, moduleRecords, moduleRecords.isNotEmpty())
            }
        }.apply { isDaemon = true; start() }

        val actions = settingsDialogActions(activity)
        actions.addView(
            settingsDialogButton(activity, "清空记录", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener {
                    val appContext = activity.applicationContext
                    runCatching { LocalTaskStore { appContext }.clearRecords(accountId) }
                    dialog.dismiss()
                    showToast("本机记录已清空（后台记录需模块启动后另行清空）")
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
    else -> "账号 ${current.accountId} · 本地任务保存在本机"
}

private fun ReaMicroSettingsHook.localAutomationTaskSubtitle(
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
    current: AccountCompletionController.CurrentCloudCredential?,
): String = when {
    current == null -> "请先登录阅微账号"
    localAutomationError.isNotBlank() -> localAutomationError
    task == null -> "未配置 · ${spec.description}"
    else -> buildString {
        append(if (task.enabled) "已启用" else "已关闭")
        when {
            spec.merchant -> {
                append(" · 每 4 小时检查 · ")
                append(if (task.merchantAutoComplete) "自动完成行商" else "仅通知不自动完成")
            }
            spec.rewardTriggered -> {
                append(" · ")
                append(if (task.dailyDrawLimit == 0) "抽完全部彩筹" else "每日最多 ${task.dailyDrawLimit} 次")
            }
            else -> {
                append(" · 每天 ")
                append(task.timeOfDay.ifBlank { "00:05" })
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
    composer: Any,
) {
    val targetChecked = task?.enabled == true
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
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
        val applied = setLocalAutomationTaskEnabled(spec, task, enabled)
        updateChecked(applied)
        targetUnit()
    }
    method(SWITCH_KT_CLASS, SWITCH_METHOD, 10).invoke(
        null,
        checked,
        onCheckedChange,
        switchModifier(),
        null,
        false,
        switchColors(composer),
        null,
        composer,
        0,
        88,
    )
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
        // 未配置时开关打开等同于打开配置弹窗，保存后启用。
        if (enabled) openLocalAutomationTaskDialog(spec, null, enableAfterSave = true)
        return false
    }
    val accountId = currentCredential.accountId
    val store = LocalTaskStore { activity.applicationContext }
    store.setEnabled(accountId, spec.taskType, enabled, currentCredential.token)
    // 互斥：启用本地任务时关闭云端同类型任务。
    if (enabled) disableCloudAutomationTask(accountId, spec.taskType)
    localAutomationTasks = store.list(accountId)
    bumpLocalAutomationVersion()
    if (enabled) {
        val appContext = activity.applicationContext
        Thread { runCatching { com.reamicro.fix.cloud.local.LocalTaskRunner.runDue(appContext) } }.start()
    }
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
    activity.runOnUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, spec.title, colors))
        val account = TextView(activity).apply {
            setTextColor(colors.body)
            text = "阅微账号：${currentCredential.label.ifBlank { "阅微账号" }}（$accountId）"
            setPadding(24, 8, 24, 8)
        }
        val time = apiServerEdit(activity, colors, "每日执行时间，例如 00:05", task?.timeOfDay?.ifBlank { "00:05" } ?: "00:05")
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
        val merchantCity = apiServerEdit(activity, colors, "新行商城池 cityCode（留空沿用上次行商）", task?.merchantCityCode.orEmpty())
        val merchantPrincipal = apiServerEdit(activity, colors, "新行商本金（铜，留空沿用上次）", (task?.merchantPrincipal?.takeIf { it > 0L })?.toString().orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val merchantTransport = apiServerEdit(activity, colors, "新行商车马 transportId（留空沿用上次）", (task?.merchantTransportId?.takeIf { it > 0L })?.toString().orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        // 运签：每日轶闻固定求运；自动行商在求安/求财之间切换（游戏只认这三个 wire 值）。
        var blessingChoice = task?.blessingType?.trim()?.uppercase()
            ?.takeIf { spec.blessingOptions.contains(it) }
            ?: spec.blessingOptions.firstOrNull().orEmpty()
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
        if (!spec.rewardTriggered && !spec.merchant) card.addView(time, apiServerRowParams(activity))
        if (spec.rewardTriggered) card.addView(drawLimit, apiServerRowParams(activity))
        if (spec.autoRead) {
            card.addView(duration, apiServerRowParams(activity))
            card.addView(books, apiServerRowParams(activity))
        }
        blessingButton?.let { card.addView(it, apiServerRowParams(activity)) }
        if (spec.merchant) {
            card.addView(merchantAutoComplete, apiServerRowParams(activity))
            card.addView(merchantCity, apiServerRowParams(activity))
            card.addView(merchantPrincipal, apiServerRowParams(activity))
            card.addView(merchantTransport, apiServerRowParams(activity))
        }
        val status = TextView(activity).apply {
            setTextColor(colors.body)
            text = when {
                spec.merchant -> "有行商时按它的结束时间检查；完成/结算后通知事件与收益。默认只通知不自动完成；开启自动完成后，城池/本金/车马留空会沿用上次行商的配置，没有在途行商时会自动开一趟"
                spec.rewardTriggered -> "启用后按每日上限抽卡；填写 0 会抽到彩筹用完"
                task == null -> "保存后即完成配置"
                else -> "保存会更新当前任务配置"
            }
            setPadding(24, 8, 24, 8)
        }
        card.addView(status, apiServerRowParams(activity))
        val actions = settingsDialogActions(activity)
        val saveButton = settingsDialogButton(activity, "保存", colors, SettingsDialogButtonRole.Primary)
        saveButton.setOnClickListener {
            val normalizedTime = if (spec.rewardTriggered || spec.merchant) {
                "00:05"
            } else {
                runCatching { normalizeCloudAutomationTime(time.text.toString()) }
                    .onFailure { status.text = it.message ?: "执行时间无效" }
                    .getOrNull() ?: return@setOnClickListener
            }
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
                blessingType = blessingChoice,
            )
            val store = LocalTaskStore { activity.applicationContext }
            store.saveTask(accountId, localTask, currentCredential.token)
            if (enabled) disableCloudAutomationTask(accountId, spec.taskType)
            dialog.dismiss()
            showToast(if (enableAfterSave == true) "已配置并启用${spec.title}" else "${spec.title}配置已保存")
            if (enabled) {
                val appContext = activity.applicationContext
                Thread { runCatching { com.reamicro.fix.cloud.local.LocalTaskRunner.runDue(appContext) } }.start()
            }
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

private fun ReaMicroSettingsHook.openLocalAutomationWakeDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "任务结果通知", colors))
        val wakeStatus = TextView(activity).apply {
            setTextColor(colors.body)
            setPadding(24, 8, 24, 8)
        }
        val wakeActions = settingsDialogActions(activity)
        card.addView(wakeStatus, apiServerRowParams(activity))
        card.addView(wakeActions)

        fun refreshWakeStatus() {
            val diagnostics = com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics
            val report = diagnostics.inspect(activity.applicationContext)
            wakeStatus.text = buildString {
                append(report.summary())
                append('\n')
                append(report.details().joinToString("\n"))
                if (!report.healthy) append("\n本地任务仍会在唤醒时执行，权限只影响通知时效。")
            }
            wakeActions.removeAllViews()
            if (!report.exactAlarmAllowed) {
                wakeActions.addView(
                    settingsDialogButton(activity, "允许精确闹钟", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener {
                            if (!diagnostics.launchFirstAvailable(activity, diagnostics.exactAlarmSettingsIntent(), diagnostics.moduleDetailsIntent())) {
                                showToast("无法打开系统设置，请手动允许模块使用闹钟")
                            }
                        }
                    },
                    settingsDialogButtonParams(activity),
                )
            }
            if (!report.batteryUnrestricted) {
                wakeActions.addView(
                    settingsDialogButton(activity, "放行电池优化", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener {
                            if (!diagnostics.launchFirstAvailable(activity, diagnostics.batteryOptimizationIntent(), diagnostics.moduleDetailsIntent())) {
                                showToast("无法打开系统设置，请手动把模块耗电策略改为无限制")
                            }
                        }
                    },
                    settingsDialogButtonParams(activity),
                )
            }
            if (!report.notificationAllowed) {
                wakeActions.addView(
                    settingsDialogButton(activity, "开启通知", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener {
                            if (!diagnostics.launchFirstAvailable(activity, diagnostics.notificationSettingsIntent(), diagnostics.moduleDetailsIntent())) {
                                showToast("无法打开系统设置，请手动允许模块发送通知")
                            }
                        }
                    },
                    settingsDialogButtonParams(activity),
                )
            }
            wakeActions.addView(
                settingsDialogButton(activity, "重新检测", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener { refreshWakeStatus() }
                },
                settingsDialogButtonParams(activity),
            )
            wakeActions.addView(
                settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener { dialog.dismiss() }
                },
                settingsDialogButtonParams(activity),
            )
        }
        refreshWakeStatus()
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

/** 互斥：关闭本地某类型任务。供云端启用路径调用，best-effort。 */
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
        XposedBridge.log("$LOCAL_AUTOMATION_LOG_PREFIX disable local failed: ${it.message}")
    }
}

/** 互斥：关闭云端某类型任务。供本地启用路径调用，best-effort（需 API 服务器已启用）。 */
internal fun ReaMicroSettingsHook.disableCloudAutomationTask(accountId: String, taskType: String) {
    val activity = activityProvider() ?: return
    val store = ApiServerSettingsStore { activity.applicationContext }
    if (!store.get().enabled || store.get().baseUrl.isBlank()) return
    val appContext = activity.applicationContext
    Thread {
        runCatching {
            val manager = CloudTaskManager(ApiServerClient(store))
            manager.list()
                .filter { it.taskType == taskType && it.enabled }
                .forEach { manager.pause(it.id) }
        }.onFailure {
            XposedBridge.log("$LOCAL_AUTOMATION_LOG_PREFIX disable cloud failed: ${it.message}")
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
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}
