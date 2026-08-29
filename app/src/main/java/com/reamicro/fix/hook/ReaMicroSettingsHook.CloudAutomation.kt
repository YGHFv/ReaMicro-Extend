package com.reamicro.fix.hook

import android.app.Dialog
import android.text.InputType
import android.widget.TextView
import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.CloudTask
import com.reamicro.fix.cloud.api.CloudTaskManager
import com.reamicro.fix.cloud.api.ReaMicroCredential
import com.reamicro.fix.cloud.api.mirrorToModule
import com.reamicro.fix.hook.settings.*
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CloudAutomationTaskSpec(
    val taskType: String,
    val title: String,
    val description: String,
    val autoRead: Boolean = false,
)

private const val CLOUD_AUTOMATION_LOG_PREFIX = "[ReaMicroFix/CloudAutomation]"

private val CLOUD_AUTOMATION_TASKS = listOf(
    CloudAutomationTaskSpec(
        taskType = "yeshe_checkin",
        title = "野社零点签到",
        description = "自动完成野社签到并领取奖励",
    ),
    CloudAutomationTaskSpec(
        taskType = "yeshe_draw_card",
        title = "野社自动抽卡",
        description = "签到奖励领取后自动执行抽卡",
    ),
    CloudAutomationTaskSpec(
        taskType = "cloud_auto_read",
        title = "云端自动阅读",
        description = "上报阅读时长，可使用最近阅读或指定图书",
        autoRead = true,
    ),
)

internal fun ReaMicroSettingsHook.renderCloudAutomationSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("CloudAutomationList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        cloudAutomationVersionValue()
        ensureCloudAutomationLoaded()
        val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
        val serverCredential = currentCredential?.let(::cloudAutomationServerCredential)
        val accountRows = listOf(
            ActionRow(
                key = "cloud_automation_account",
                title = currentCredential?.label?.ifBlank { "当前阅微账号" } ?: "当前未登录阅微账号",
                subtitle = cloudAutomationAccountSubtitle(currentCredential, serverCredential),
                trailing = if (cloudAutomationLoading) "读取中" else "刷新",
                onClick = ::reloadCloudAutomationState,
            ),
        )
        val taskRows = CLOUD_AUTOMATION_TASKS.map { spec ->
            val task = serverCredential?.let { credential -> cloudAutomationTask(spec.taskType, credential.id) }
            ActionRow(
                key = "cloud_automation_${spec.taskType}",
                title = spec.title,
                subtitle = cloudAutomationTaskSubtitle(spec, task, currentCredential, serverCredential),
                onClick = {
                    if (cloudAutomationLoading && !cloudAutomationLoaded) {
                        showToast("正在读取云端任务，请稍候")
                    } else {
                        openCloudAutomationTaskDialog(spec, task)
                    }
                },
                trailingContent = { itemComposer ->
                    renderCloudAutomationTaskSwitch(spec, task, itemComposer)
                },
            )
        }
        val wakeReport = activityProvider()?.let {
            com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics.inspect(it.applicationContext)
        }
        val utilityRows = listOf(
            ActionRow(
                key = "cloud_automation_wake",
                title = "任务结果通知",
                subtitle = wakeReport?.summary() ?: "检查通知与后台唤醒权限",
                onClick = ::openCloudAutomationWakeDialog,
            ),
        )
        addLazyItem(lazyListScope, "cloud_automation_account_card".hashCode()) { itemComposer ->
            renderHostActionCard(accountRows, itemComposer)
        }
        addLazyItem(lazyListScope, "cloud_automation_task_card".hashCode()) { itemComposer ->
            renderHostActionCard(taskRows, itemComposer)
        }
        addLazyItem(lazyListScope, "cloud_automation_utility_card".hashCode()) { itemComposer ->
            renderHostActionCard(utilityRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.resetCloudAutomationState() {
    cloudAutomationLoaded = false
    cloudAutomationError = ""
}

private fun ReaMicroSettingsHook.reloadCloudAutomationState() {
    if (cloudAutomationLoading) return
    cloudAutomationLoaded = false
    cloudAutomationError = ""
    bumpCloudAutomationVersion()
    ensureCloudAutomationLoaded()
}

private fun ReaMicroSettingsHook.ensureCloudAutomationLoaded() {
    val activity = activityProvider() ?: return
    val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
    val accountId = currentCredential?.accountId.orEmpty()
    if (cloudAutomationAccountId != accountId) {
        cloudAutomationAccountId = accountId
        cloudAutomationLoaded = false
        cloudAutomationLoading = false
        cloudAutomationError = ""
        cloudAutomationTasks = emptyList()
        cloudAutomationCredentials = emptyList()
        cloudAutomationUpdatingTaskTypes = emptySet()
    }
    if (cloudAutomationLoaded || cloudAutomationLoading) return
    if (currentCredential == null) {
        cloudAutomationLoaded = true
        cloudAutomationError = "请先在阅微登录账号"
        return
    }
    val store = ApiServerSettingsStore { activity.applicationContext }
    val serverSettings = store.get()
    if (!serverSettings.enabled || serverSettings.baseUrl.isBlank()) {
        cloudAutomationLoaded = true
        cloudAutomationError = "请先启用并配置 API 服务器"
        return
    }
    if (serverSettings.hostAccountId != accountId) {
        serverSettings.copy(hostAccountId = accountId).also {
            store.save(it)
            it.mirrorToModule(activity.applicationContext)
        }
    }
    cloudAutomationLoading = true
    Thread {
        val result = runCatching {
            val manager = CloudTaskManager(ApiServerClient(store))
            manager.credentials() to manager.list()
        }
        activity.runOnUiThread {
            if (cloudAutomationAccountId != accountId) return@runOnUiThread
            result.onSuccess { (credentials, tasks) ->
                cloudAutomationCredentials = credentials
                cloudAutomationTasks = tasks
                cloudAutomationError = ""
                cloudAutomationLoaded = true
            }.onFailure { error ->
                cloudAutomationError = error.message?.takeIf(String::isNotBlank) ?: "读取云端任务失败"
                cloudAutomationLoaded = true
                XposedBridge.log("$CLOUD_AUTOMATION_LOG_PREFIX load failed: ${error.stackTraceToString()}")
            }
            cloudAutomationLoading = false
            bumpCloudAutomationVersion()
        }
    }.apply {
        name = "ReaMicroCloudAutomationLoad"
        isDaemon = true
        start()
    }
}

private fun ReaMicroSettingsHook.cloudAutomationServerCredential(
    current: AccountCompletionController.CurrentCloudCredential,
): ReaMicroCredential? =
    cloudAutomationCredentials.firstOrNull { it.accountId == current.accountId }

private fun ReaMicroSettingsHook.cloudAutomationTask(taskType: String, credentialId: String): CloudTask? =
    cloudAutomationTasks.firstOrNull { it.taskType == taskType && it.credentialId == credentialId }

private fun ReaMicroSettingsHook.cloudAutomationAccountSubtitle(
    current: AccountCompletionController.CurrentCloudCredential?,
    serverCredential: ReaMicroCredential?,
): String = when {
    current == null -> "请先在阅微登录账号"
    cloudAutomationLoading && !cloudAutomationLoaded -> "正在读取账号凭据与任务"
    cloudAutomationError.isNotBlank() -> cloudAutomationError
    serverCredential == null -> "账号 ${current.accountId} · 尚未上传云端凭据"
    else -> "账号 ${current.accountId} · 云端凭据已同步"
}

private fun ReaMicroSettingsHook.cloudAutomationTaskSubtitle(
    spec: CloudAutomationTaskSpec,
    task: CloudTask?,
    current: AccountCompletionController.CurrentCloudCredential?,
    serverCredential: ReaMicroCredential?,
): String = when {
    current == null -> "请先登录阅微账号"
    cloudAutomationLoading && !cloudAutomationLoaded -> "正在读取配置"
    cloudAutomationError.isNotBlank() -> cloudAutomationError
    serverCredential == null || task == null -> "未配置 · ${spec.description}"
    else -> buildString {
        append(if (task.enabled) "已启用" else "已关闭")
        append(" · 每天 ")
        append(task.timeOfDay.ifBlank { "00:05" })
        if (spec.autoRead) {
            append(" · ")
            append(task.durationMinutes)
            append(" 分钟 · ")
            append(if (task.books.isEmpty()) "最近阅读" else "${task.books.size} 本指定图书")
        }
        if (task.enabled && task.nextRunAt > 0L) {
            append("\n下次执行 ")
            append(formatCloudAutomationTime(task.nextRunAt))
        }
        if (task.lastMessage.isNotBlank()) {
            append("\n")
            append(task.lastMessage)
        }
    }
}

private fun ReaMicroSettingsHook.renderCloudAutomationTaskSwitch(
    spec: CloudAutomationTaskSpec,
    task: CloudTask?,
    composer: Any,
) {
    val targetChecked = task?.enabled == true
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
    }
    val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
    val checked = if (rememberedChecked != targetChecked && spec.taskType !in cloudAutomationUpdatingTaskTypes) {
        updateChecked(targetChecked)
        targetChecked
    } else {
        rememberedChecked
    }
    val onCheckedChange = functionProxy("CloudAutomationSwitch${spec.taskType}", FUNCTION1_CLASS) { args ->
        val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        val applied = setCloudAutomationTaskEnabled(spec, task, enabled, ::updateChecked)
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

private fun ReaMicroSettingsHook.setCloudAutomationTaskEnabled(
    spec: CloudAutomationTaskSpec,
    task: CloudTask?,
    enabled: Boolean,
    updateChecked: (Boolean) -> Unit,
): Boolean {
    if (cloudAutomationLoading || spec.taskType in cloudAutomationUpdatingTaskTypes) {
        showToast("云端任务正在更新")
        return task?.enabled == true
    }
    if (task == null) {
        if (enabled) openCloudAutomationTaskDialog(spec, null, enableAfterSave = true)
        return false
    }
    val activity = activityProvider() ?: return task.enabled
    val accountId = cloudAutomationAccountId
    cloudAutomationUpdatingTaskTypes = cloudAutomationUpdatingTaskTypes + spec.taskType
    Thread {
        val result = runCatching {
            val manager = CloudTaskManager(ApiServerClient(ApiServerSettingsStore { activity.applicationContext }))
            val updated = if (enabled) manager.resume(task.id) else manager.pause(task.id)
            updated.copy(
                timeOfDay = updated.timeOfDay.ifBlank { task.timeOfDay },
                durationMinutes = task.durationMinutes,
                books = task.books,
            )
        }
        activity.runOnUiThread {
            cloudAutomationUpdatingTaskTypes = cloudAutomationUpdatingTaskTypes - spec.taskType
            if (cloudAutomationAccountId != accountId) return@runOnUiThread
            result.onSuccess { updated ->
                cloudAutomationTasks = cloudAutomationTasks.map { current ->
                    if (current.id == updated.id) updated else current
                }
                updateChecked(updated.enabled)
                showToast(if (updated.enabled) "已启用${spec.title}" else "已停用${spec.title}")
            }.onFailure { error ->
                updateChecked(task.enabled)
                showToast(error.message ?: "更新${spec.title}失败")
                XposedBridge.log("$CLOUD_AUTOMATION_LOG_PREFIX toggle failed: ${error.stackTraceToString()}")
            }
            bumpCloudAutomationVersion()
        }
    }.apply {
        name = "ReaMicroCloudAutomationToggle"
        isDaemon = true
        start()
    }
    return enabled
}

private fun ReaMicroSettingsHook.openCloudAutomationTaskDialog(
    spec: CloudAutomationTaskSpec,
    task: CloudTask?,
    enableAfterSave: Boolean? = null,
) {
    val activity = activityProvider() ?: return
    val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
    if (currentCredential == null) {
        showToast("请先在阅微登录账号")
        return
    }
    val store = ApiServerSettingsStore { activity.applicationContext }
    val serverSettings = store.get()
    if (!serverSettings.enabled || serverSettings.baseUrl.isBlank()) {
        showToast("请先启用并配置 API 服务器")
        return
    }
    activity.runOnUiThread {
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, spec.title, colors))
        val account = TextView(activity).apply {
            setTextColor(colors.body)
            text = "阅微账号：${currentCredential.label.ifBlank { "阅微账号" }}（${currentCredential.accountId}）"
            setPadding(24, 8, 24, 8)
        }
        val time = apiServerEdit(activity, colors, "每日执行时间，例如 00:05", task?.timeOfDay?.ifBlank { "00:05" } ?: "00:05")
        val duration = apiServerEdit(activity, colors, "云端阅读时长（分钟）", (task?.durationMinutes ?: 30).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val books = apiServerEdit(
            activity,
            colors,
            "自定义图书：每行 cloudBookId|书名；留空读取最近阅读",
            task?.books?.joinToString("\n") { "${it.cloudBookId}|${it.name}" }.orEmpty(),
        ).apply {
            minLines = 3
            setSingleLine(false)
        }
        card.addView(account, apiServerRowParams(activity))
        card.addView(time, apiServerRowParams(activity))
        if (spec.autoRead) {
            card.addView(duration, apiServerRowParams(activity))
            card.addView(books, apiServerRowParams(activity))
        }
        val status = TextView(activity).apply {
            setTextColor(colors.body)
            text = if (task == null) "保存后即完成配置" else "保存会更新当前任务配置"
            setPadding(24, 8, 24, 8)
        }
        card.addView(status, apiServerRowParams(activity))
        val actions = settingsDialogActions(activity)
        val saveButton = settingsDialogButton(activity, "保存", colors, SettingsDialogButtonRole.Primary)
        saveButton.setOnClickListener {
            val normalizedTime = runCatching { normalizeCloudAutomationTime(time.text.toString()) }
                .onFailure { status.text = it.message ?: "执行时间无效" }
                .getOrNull() ?: return@setOnClickListener
            val durationMinutes = if (spec.autoRead) {
                duration.text.toString().toIntOrNull()?.takeIf { it in 1..720 }
                    ?: run {
                        status.text = "阅读时长应为 1 到 720 分钟"
                        return@setOnClickListener
                    }
            } else {
                30
            }
            val selectedBooks = if (spec.autoRead) {
                runCatching { parseCloudReadingBooks(books.text.toString()) }
                    .onFailure { status.text = it.message ?: "自定义图书格式无效" }
                    .getOrNull() ?: return@setOnClickListener
            } else {
                JSONArray()
            }
            if (currentCredential.token.isBlank() || currentCredential.accountId.isBlank()) {
                status.text = "当前阅微登录密钥不完整，请重新登录后重试"
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            status.text = "正在验证账号并保存任务……"
            Thread {
                val result = runCatching {
                    val manager = CloudTaskManager(ApiServerClient(store))
                    val credentialId = manager.uploadCredential(
                        currentCredential.token,
                        currentCredential.label.ifBlank { "阅微账号" },
                        currentCredential.accountId,
                    ).id
                    val request = JSONObject()
                    if (spec.autoRead) {
                        request.put("durationMinutes", durationMinutes)
                        if (selectedBooks.length() > 0) {
                            request.put("books", selectedBooks)
                            request.put("bookLimit", selectedBooks.length())
                        }
                    }
                    manager.saveAutomation(
                        taskType = spec.taskType,
                        enabled = enableAfterSave ?: task?.enabled ?: false,
                        credentialId = credentialId,
                        timeOfDay = normalizedTime,
                        request = request,
                    )
                }
                activity.runOnUiThread {
                    result.onSuccess {
                        dialog.dismiss()
                        showToast(if (enableAfterSave == true) "已配置并启用${spec.title}" else "${spec.title}配置已保存")
                        reloadCloudAutomationState()
                    }.onFailure { error ->
                        saveButton.isEnabled = true
                        status.text = error.message?.takeIf(String::isNotBlank) ?: "云端任务配置失败"
                        XposedBridge.log("$CLOUD_AUTOMATION_LOG_PREFIX save failed: ${error.stackTraceToString()}")
                    }
                }
            }.apply {
                name = "ReaMicroCloudAutomationSave"
                isDaemon = true
                start()
            }
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

private fun ReaMicroSettingsHook.openCloudAutomationWakeDialog() {
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
                if (!report.healthy) append("\n任务仍会在服务器执行，权限只影响结果通知时效。")
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

internal fun normalizeCloudAutomationTime(raw: String): String {
    val parts = raw.trim().split(':', limit = 2)
    require(parts.size == 2) { "执行时间格式应为 HH:mm" }
    val hour = parts[0].toIntOrNull()
    val minute = parts[1].toIntOrNull()
    require(hour != null && hour in 0..23 && minute != null && minute in 0..59) { "执行时间格式应为 HH:mm" }
    return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
}

private fun parseCloudReadingBooks(raw: String): JSONArray {
    val text = raw.trim()
    if (text.isBlank()) return JSONArray()
    if (text.startsWith("[")) return JSONArray(text)
    return JSONArray().apply {
        text.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            val parts = line.split('|', limit = 2)
            val id = parts.first().trim().toLongOrNull() ?: error("自定义图书 ID 无效：${parts.first()}")
            require(id > 0L) { "自定义图书 ID 必须大于 0" }
            put(
                JSONObject()
                    .put("cloudBookId", id)
                    .put("bookId", id)
                    .put("name", parts.getOrNull(1)?.trim().orEmpty()),
            )
        }
    }
}

private fun formatCloudAutomationTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun ReaMicroSettingsHook.cloudAutomationVersionState(): Any {
    cloudAutomationVersionUiState?.let { return it }
    return mutableState(0).also { cloudAutomationVersionUiState = it }
}

private fun ReaMicroSettingsHook.cloudAutomationVersionValue(): Int =
    (cloudAutomationVersionState().method0("getValue") as? Number)?.toInt() ?: 0

private fun ReaMicroSettingsHook.bumpCloudAutomationVersion() {
    val state = cloudAutomationVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}
