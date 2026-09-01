package com.reamicro.fix.hook

import android.app.Dialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.reamicro.fix.cloud.api.ApiAuthMode
import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettings
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.ApiUpdateChannel
import com.reamicro.fix.cloud.api.ApiPackageKind
import com.reamicro.fix.cloud.api.ApiPackageManager
import com.reamicro.fix.cloud.api.checkModuleUpdate
import com.reamicro.fix.cloud.api.ModuleApkInstaller
import com.reamicro.fix.cloud.api.ModuleSettingsBackup
import com.reamicro.fix.cloud.api.CloudTaskManager
import com.reamicro.fix.cloud.api.ApiThemeStore
import com.reamicro.fix.cloud.api.CredentialBackupCrypto
import com.reamicro.fix.cloud.api.normalizeApiBaseUrl
import com.reamicro.fix.cloud.api.mirrorToModule
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.hook.settings.*
import de.robv.android.xposed.XposedBridge
import java.io.File

internal fun ReaMicroSettingsHook.apiServerSettingsSubtitle(): String {
    val current = ApiServerSettingsStore { activityProvider()?.applicationContext }.get()
    return when {
        !current.enabled -> "未启用"
        current.baseUrl.isBlank() -> "已启用，未配置地址"
        else -> "${current.authMode.wireValue} · ${current.baseUrl}"
    }
}

internal fun ReaMicroSettingsHook.openApiServerSettingsDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        runCatching {
            val store = ApiServerSettingsStore { activity.applicationContext }
            val current = store.get()
            val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, "API 服务器", colors))
            val enabled = settingsDialogSwitchRow(activity, "启用 API 服务器", current.enabled, colors)
            card.addView(enabled, apiServerRowParams(activity))
            val url = apiServerEdit(activity, colors, "服务器地址，例如 https://example.com", current.baseUrl)
            card.addView(url, apiServerRowParams(activity))
            val authModes = mutableListOf(current.authMode)
            val authMode = Spinner(activity).apply {
                adapter = apiServerAuthAdapter(activity, authModes)
            }
            card.addView(authMode, apiServerRowParams(activity))
            val key = apiServerEdit(activity, colors, "API Key", current.apiKey).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            card.addView(key, apiServerRowParams(activity))
            val accountName = apiServerEdit(activity, colors, "独立账号", current.accountName)
            card.addView(accountName, apiServerRowParams(activity))
            val accountPassword = apiServerEdit(activity, colors, "独立账号密码", current.accountPassword).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            card.addView(accountPassword, apiServerRowParams(activity))
            val hostAccount = TextView(activity).apply {
                setTextColor(colors.body)
                text = currentCredential?.let { "当前阅微账号：${it.label}（${it.accountId}）" } ?: "当前未登录阅微账号"
                setPadding(24, 8, 24, 8)
            }
            card.addView(hostAccount, apiServerRowParams(activity))
            val allowHttp = settingsDialogSwitchRow(activity, "允许局域网 HTTP", current.allowHttp, colors)
            card.addView(allowHttp)
            val autoUpdate = settingsDialogSwitchRow(activity, "自动检查内容库更新", current.autoCheckUpdates, colors)
            card.addView(autoUpdate)
            val channels = listOf(ApiUpdateChannel.BETA, ApiUpdateChannel.STABLE)
            val channelSpinner = Spinner(activity).apply {
                adapter = apiServerStringAdapter(activity, channels.map {
                    when (it) {
                        ApiUpdateChannel.BETA -> "模块更新渠道：预发布（含正式版）"
                        ApiUpdateChannel.STABLE -> "模块更新渠道：仅正式版"
                    }
                })
                setSelection(channels.indexOf(current.updateChannel).coerceAtLeast(0))
            }
            card.addView(channelSpinner, apiServerRowParams(activity))
            val status = TextView(activity).apply {
                text = "输入服务器地址后自动识别认证模式"
                setTextColor(colors.body)
                setPadding(24, 8, 24, 8)
            }
            card.addView(status)

            fun selectedMode(): ApiAuthMode = authModes.getOrElse(authMode.selectedItemPosition) { current.authMode }

            fun updateAuthFields(mode: ApiAuthMode, modes: Set<ApiAuthMode>) {
                authMode.visibility = if (modes.size > 1 && modes != setOf(ApiAuthMode.PUBLIC)) View.VISIBLE else View.GONE
                key.visibility = if (mode == ApiAuthMode.API_KEY) View.VISIBLE else View.GONE
                accountName.visibility = if (mode == ApiAuthMode.ACCOUNT) View.VISIBLE else View.GONE
                accountPassword.visibility = if (mode == ApiAuthMode.ACCOUNT) View.VISIBLE else View.GONE
                hostAccount.visibility = if (mode == ApiAuthMode.HOST_ACCOUNT_ALLOWLIST) View.VISIBLE else View.GONE
            }

            fun draftSettings(mode: ApiAuthMode = selectedMode()): ApiServerSettings = current.copy(
                enabled = enabled.isChecked,
                baseUrl = url.text.toString(),
                apiKey = key.text.toString(),
                accountName = accountName.text.toString(),
                accountPassword = accountPassword.text.toString(),
                hostAccountId = currentCredential?.accountId.orEmpty(),
                authMode = mode,
                allowHttp = allowHttp.isChecked,
                autoCheckUpdates = autoUpdate.isChecked,
                updateChannel = channels.getOrElse(channelSpinner.selectedItemPosition) { current.updateChannel },
            )

            fun applyDiscovery(meta: com.reamicro.fix.cloud.api.ApiServerMeta) {
                authModes.clear()
                authModes.addAll(meta.authModes.ifEmpty { setOf(ApiAuthMode.PUBLIC) })
                val preferred = com.reamicro.fix.cloud.api.selectApiAuthMode(meta, draftSettings(current.authMode))
                    ?: authModes.first()
                authMode.adapter = apiServerAuthAdapter(activity, authModes)
                authMode.setSelection(authModes.indexOf(preferred).coerceAtLeast(0))
                updateAuthFields(preferred, meta.authModes)
                status.text = when (preferred) {
                    ApiAuthMode.PUBLIC -> "认证模式：公开访问"
                    ApiAuthMode.HOST_ACCOUNT_ALLOWLIST -> currentCredential?.let { "认证模式：阅微账号白名单\n正在验证账号 ${it.accountId}……" }
                        ?: "连接被服务器拒绝：请先登录阅微账号"
                    ApiAuthMode.API_KEY -> "认证模式：API Key"
                    ApiAuthMode.ACCOUNT -> "认证模式：独立账号"
                }
            }

            fun probe(showProgress: Boolean = true, onSuccess: ((ApiServerSettings) -> Unit)? = null) {
                val base = draftSettings()
                runCatching { normalizeApiBaseUrl(base.baseUrl, base.allowHttp) }
                    .onFailure { status.text = it.message ?: "地址无效" }
                    .onSuccess {
                        if (showProgress) status.text = "正在识别服务器设置……"
                        Thread {
                            val client = ApiServerClient(store)
                            val discovered = runCatching { client.discovery(base) }
                            activity.runOnUiThread {
                                discovered.onSuccess(::applyDiscovery)
                                    .onFailure { status.text = it.message ?: "服务器能力识别失败" }
                            }
                            val meta = discovered.getOrNull() ?: return@Thread
                            val mode = com.reamicro.fix.cloud.api.selectApiAuthMode(meta, base) ?: return@Thread
                            val resolved = base.copy(authMode = mode)
                            if (mode == ApiAuthMode.HOST_ACCOUNT_ALLOWLIST && resolved.hostAccountId.isBlank()) return@Thread
                            if (mode == ApiAuthMode.API_KEY && resolved.apiKey.isBlank()) return@Thread
                            if (mode == ApiAuthMode.ACCOUNT && (resolved.accountName.isBlank() || resolved.accountPassword.isBlank())) return@Thread
                            val result = client.probe(resolved)
                            activity.runOnUiThread {
                                if (result.success) {
                                    status.text = result.message
                                    onSuccess?.invoke(resolved.copy(authMode = result.resolvedAuthMode ?: mode))
                                } else {
                                    status.text = result.message
                                }
                            }
                        }.start()
                    }
            }

            authMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateAuthFields(selectedMode(), authModes.toSet())
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            updateAuthFields(current.authMode, setOf(current.authMode))
            val handler = Handler(Looper.getMainLooper())
            var pendingDiscovery = Runnable {}
            url.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacks(pendingDiscovery)
                    pendingDiscovery = Runnable { if (enabled.isChecked && !s.isNullOrBlank()) probe(showProgress = false) }
                    handler.postDelayed(pendingDiscovery, 700L)
                }
            })
            val actions = settingsDialogActions(activity)
            actions.addView(settingsDialogButton(activity, "测试连接", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener { probe() }
            }, settingsDialogButtonParams(activity))
            actions.addView(settingsDialogButton(activity, "保存", colors, SettingsDialogButtonRole.Primary).apply {
                setOnClickListener {
                    if (!enabled.isChecked) {
                        draftSettings().also {
                            store.save(it)
                            it.mirrorToModule(activity.applicationContext)
                        }
                        dialog.dismiss()
                        showToast("API 服务器已停用")
                    } else {
                        probe(onSuccess = { resolved ->
                            store.save(resolved)
                            resolved.mirrorToModule(activity.applicationContext)
                            dialog.dismiss()
                            showToast("API 服务器设置已保存")
                        })
                    }
                }
            }, settingsDialogButtonParams(activity))
            actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener { dialog.dismiss() }
            }, settingsDialogButtonParams(activity))
            card.addView(actions)
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
            if (current.enabled && current.baseUrl.isNotBlank()) probe(showProgress = false)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX api server settings dialog failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaMicroSettingsHook.openCloudAutomationDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val store = ApiServerSettingsStore { activity.applicationContext }
        val currentCredential = runCatching { accountController.currentCloudCredential() }.getOrNull()
        currentCredential?.let { credential ->
            val saved = store.get()
            if (saved.hostAccountId != credential.accountId) saved.copy(hostAccountId = credential.accountId).also {
                store.save(it)
                it.mirrorToModule(activity.applicationContext)
            }
        }
        val client = ApiServerClient(store)
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "云端任务", colors))
        val credentialIds = mutableListOf<String>()
        val loadedTasks = mutableListOf<com.reamicro.fix.cloud.api.CloudTask>()
        val credentialSpinner = Spinner(activity).apply {
            adapter = apiServerStringAdapter(activity, listOf("正在读取已有凭据……"))
        }
        card.addView(credentialSpinner, apiServerRowParams(activity))
        val currentAccount = TextView(activity).apply {
            setTextColor(colors.body)
            // 宿主账号可能没有昵称，label 是空串而不是 null，必须用 ifBlank 兜底否则界面上一片空白。
            text = currentCredential?.let { "将自动上传当前阅微登录：${it.label.ifBlank { "阅微账号" }}（账号 ${it.accountId}）" }
                ?: "当前未检测到阅微登录，请先在阅微登录账号"
            setPadding(24, 8, 24, 8)
        }
        val label = apiServerEdit(activity, colors, "凭据名称", currentCredential?.label?.ifBlank { "阅微账号" } ?: "阅微账号")
        val time = apiServerEdit(activity, colors, "每日执行时间，例如 00:05", "00:05")
        val duration = apiServerEdit(activity, colors, "云端阅读时长（分钟）", "30")
        val customBook = apiServerEdit(activity, colors, "自定义图书：每行 bookId|书名；留空读取最近阅读", "").apply {
            minLines = 3
            setSingleLine(false)
        }
        val checkin = settingsDialogSwitchRow(activity, "野社零点云端签到", false, colors)
        val draw = settingsDialogSwitchRow(activity, "野社自动抽卡", false, colors)
        val read = settingsDialogSwitchRow(activity, "云端自动阅读", false, colors)
        listOf(currentAccount, label, time, duration, customBook, checkin, draw, read).forEach { view -> card.addView(view, apiServerRowParams(activity)) }
        val status = TextView(activity).apply { setTextColor(colors.body); text = "凭据将在服务器端加密保存，模块设置备份不包含该密钥。" }
        card.addView(status, apiServerRowParams(activity))

        // 后台唤醒自检。任务本身在服务器执行，这里检查的只是"结果通知能否及时到达"。
        val wakeStatus = TextView(activity).apply { setTextColor(colors.body); setPadding(24, 8, 24, 8) }
        card.addView(wakeStatus, apiServerRowParams(activity))
        val wakeActions = settingsDialogActions(activity)
        card.addView(wakeActions)

        fun refreshWakeStatus() {
            val report = com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics.inspect(activity.applicationContext)
            wakeStatus.text = buildString {
                append(report.summary())
                append('\n')
                append(report.details().joinToString("\n"))
                if (!report.healthy) append("\n任务仍会在服务器按时执行，未放行只影响通知时效。")
            }
            wakeActions.removeAllViews()
            if (report.healthy) return
            val diagnostics = com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics
            if (!report.exactAlarmAllowed) {
                wakeActions.addView(
                    settingsDialogButton(activity, "允许精确闹钟", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener {
                            if (!diagnostics.launchFirstAvailable(activity, diagnostics.exactAlarmSettingsIntent(), diagnostics.moduleDetailsIntent())) {
                                showToast("无法打开系统设置，请手动在模块的应用信息里允许闹钟")
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
                                showToast("无法打开系统设置，请手动把模块的耗电策略改为无限制")
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
        }
        refreshWakeStatus()

        fun applyCloudConfig(credentials: List<com.reamicro.fix.cloud.api.ReaMicroCredential>, tasks: List<com.reamicro.fix.cloud.api.CloudTask>) {
            credentialIds.clear()
            credentialIds.addAll(credentials.map { it.id })
            loadedTasks.clear()
            loadedTasks.addAll(tasks)
            credentialSpinner.adapter = apiServerStringAdapter(
                activity,
                credentials.map { "${it.label}${it.accountId.takeIf(String::isNotBlank)?.let { id -> " · $id" }.orEmpty()}" }
                    .ifEmpty { listOf("当前账号尚未上传凭据") },
            )
            fun applyCredentialTasks(position: Int) {
                val selectedId = credentialIds.getOrNull(position).orEmpty()
                checkin.isChecked = tasks.firstOrNull { it.taskType == "yeshe_checkin" && it.credentialId == selectedId }?.enabled == true
                draw.isChecked = tasks.firstOrNull { it.taskType == "yeshe_draw_card" && it.credentialId == selectedId }?.enabled == true
                read.isChecked = tasks.firstOrNull { it.taskType == "cloud_auto_read" && it.credentialId == selectedId }?.enabled == true
            }
            credentialSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = applyCredentialTasks(position)
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            val currentIndex = credentials.indexOfFirst { it.accountId == currentCredential?.accountId }
            if (currentIndex >= 0) credentialSpinner.setSelection(currentIndex)
            applyCredentialTasks(credentialSpinner.selectedItemPosition)
        }

        // 读取云端凭据与任务。上传成功后必须再跑一次，否则下拉框会一直停在"当前账号尚未上传凭据"。
        fun reloadCloudConfig(notice: String? = null) {
            Thread {
                runCatching {
                    val manager = CloudTaskManager(client)
                    manager.credentials() to manager.list()
                }.onSuccess { (credentials, tasks) ->
                    activity.runOnUiThread {
                        applyCloudConfig(credentials, tasks)
                        status.text = notice?.let { "$it\n已读取 ${credentials.size} 个凭据、${tasks.size} 个任务。" }
                            ?: "已读取 ${credentials.size} 个凭据、${tasks.size} 个任务；保存时会自动刷新当前阅微登录密钥。"
                    }
                }.onFailure { error ->
                    activity.runOnUiThread {
                        status.text = notice?.let { "$it\n读取云端配置失败：${error.message.orEmpty()}" }
                            ?: (error.message ?: "读取云端配置失败")
                    }
                }
            }.start()
        }
        reloadCloudConfig()
        val actions = settingsDialogActions(activity)
        actions.addView(settingsDialogButton(activity, "保存并验证", colors, SettingsDialogButtonRole.Primary).apply {
            setOnClickListener {
                val capturedCredential = currentCredential
                if (capturedCredential == null) {
                    status.text = "当前未检测到阅微登录，请先登录阅微后重试"
                    return@setOnClickListener
                }
                if (capturedCredential.token.isBlank() || capturedCredential.accountId.isBlank()) {
                    status.text = "当前阅微登录密钥不完整，请重新登录阅微后重试"
                    return@setOnClickListener
                }
                status.text = "正在验证阅微登录密钥……"
                Thread {
                    runCatching {
                        val manager = CloudTaskManager(client)
                        val credentialId = manager.uploadCredential(
                            capturedCredential.token,
                            label.text.toString().ifBlank { capturedCredential.label },
                            capturedCredential.accountId,
                        ).id
                        val selectedBooks = parseCloudReadingBooks(customBook.text.toString())
                        val readRequest = org.json.JSONObject().put("durationMinutes", duration.text.toString().toIntOrNull()?.coerceIn(1, 720) ?: 30)
                        if (selectedBooks.length() > 0) readRequest.put("books", selectedBooks)
                        manager.saveAutomation("yeshe_checkin", checkin.isChecked, credentialId, time.text.toString())
                        manager.saveAutomation("yeshe_draw_card", draw.isChecked, credentialId, time.text.toString())
                        manager.saveAutomation("cloud_auto_read", read.isChecked, credentialId, time.text.toString(), readRequest)
                    }.onSuccess {
                        // 上传成功后立刻重新拉取，界面才会从"尚未上传凭据"变成真实凭据。
                        activity.runOnUiThread { reloadCloudConfig("阅微凭据与任务配置已同步") }
                    }.onFailure { error ->
                        activity.runOnUiThread {
                            status.text = error.message?.takeIf(String::isNotBlank) ?: "云端任务配置失败"
                        }
                    }
                }.start()
            }
        }, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply { setOnClickListener { dialog.dismiss() } }, settingsDialogButtonParams(activity))
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

private fun parseCloudReadingBooks(raw: String): org.json.JSONArray {
    val text = raw.trim()
    if (text.isBlank()) return org.json.JSONArray()
    if (text.startsWith("[")) return org.json.JSONArray(text)
    return org.json.JSONArray().apply {
        text.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            val parts = line.split('|', limit = 2)
            val id = parts.first().trim().toLongOrNull() ?: error("自定义图书 ID 无效：${parts.first()}")
            put(org.json.JSONObject()
                .put("bookId", id)
                .put("name", parts.getOrNull(1)?.trim().orEmpty()))
        }
    }
}

internal fun ReaMicroSettingsHook.openApiPackageManagementDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val store = ApiServerSettingsStore { activity.applicationContext }
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "已安装内容包", colors))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(activity).apply { setTextColor(colors.body) }
        card.addView(status, apiServerRowParams(activity))
        card.addView(list, apiServerRowParams(activity))

        fun reload() {
            val manager = ApiPackageManager(activity.applicationContext, ApiServerClient(store), settings)
            val packages = manager.installed().sortedWith(compareBy({ it.kind.wireValue }, { it.packageId }))
            list.removeAllViews()
            status.text = if (packages.isEmpty()) "暂无由 API 服务器安装的内容包" else "共 ${packages.size} 个内容包"
            packages.forEach { item ->
                val title = TextView(activity).apply {
                    setTextColor(colors.title)
                    text = "${item.kind.wireValue} · ${item.packageId}\n${item.version} · ${item.buildTime}"
                }
                list.addView(title, apiServerRowParams(activity))
                val actions = settingsDialogActions(activity)
                if (item.kind == ApiPackageKind.THEME) {
                    actions.addView(settingsDialogButton(activity, "使用", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener {
                            runCatching { ApiThemeStore.activate(activity.applicationContext, item.contentId) }
                                .onSuccess { status.text = "已启用主题 ${item.packageId}，重新打开弹窗后生效" }
                                .onFailure { status.text = it.message ?: "主题启用失败" }
                        }
                    }, settingsDialogButtonParams(activity))
                }
                actions.addView(settingsDialogButton(activity, "回滚", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener {
                        status.text = "正在回滚 ${item.packageId}……"
                        Thread {
                            val result = manager.rollback(item.kind, item.packageId)
                            activity.runOnUiThread { status.text = result.message; reload() }
                        }.start()
                    }
                }, settingsDialogButtonParams(activity))
                actions.addView(settingsDialogButton(activity, "卸载", colors, SettingsDialogButtonRole.Neutral).apply {
                    setOnClickListener {
                        val removed = manager.uninstall(item.kind, item.packageId)
                        status.text = if (removed) "已卸载 ${item.packageId}" else "卸载失败"
                        reload()
                    }
                }, settingsDialogButtonParams(activity))
                list.addView(actions)
            }
        }
        reload()
        val actions = settingsDialogActions(activity)
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Primary).apply {
            setOnClickListener { dialog.dismiss() }
        }, settingsDialogButtonParams(activity))
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

// 上传本地书源和关联源到服务器内容库。服务器已有同名同域的源时只关联，不覆盖服务器内容。
internal fun ReaMicroSettingsHook.openApiLibraryUploadDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val store = ApiServerSettingsStore { activity.applicationContext }
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "上传内容库", colors))
        card.addView(
            settingsDialogHint(
                activity,
                "把本机的书源和关联源上传到服务器内容库。需要服务器后台启用“用户模块上传”" +
                    "并把当前阅微账号 ID 加入上传白名单。服务器已存在名称和域名相同的源时不会重复上传，" +
                    "只自动建立关联；新上传的源也会一并关联，用于后续更新。",
                colors,
            ),
            apiServerRowParams(activity),
        )
        val status = TextView(activity).apply { setTextColor(colors.body); text = "正在读取本机内容库和服务器上传许可……" }
        card.addView(status, apiServerRowParams(activity))
        val upload = settingsDialogButton(activity, "开始上传", colors, SettingsDialogButtonRole.Primary).apply { isEnabled = false }
        var pending: List<com.reamicro.fix.cloud.api.ApiLibraryItem> = emptyList()
        var policy: com.reamicro.fix.cloud.api.ApiUploadPolicy? = null

        fun renderSummary(result: com.reamicro.fix.cloud.api.ApiLibrarySyncSummary) {
            status.text = buildString {
                append("共处理 ${result.total} 个源：新上传 ${result.uploaded} 个，自动关联 ${result.linked} 个")
                if (result.failed > 0) append("，失败 ${result.failed} 个")
                append("。")
                if (result.changed > 0) append("\n可回到上一页执行“检查内容库更新”，让服务器版本接管这些源。")
                result.messages.forEach { append("\n$it") }
            }
        }

        if (!store.get().enabled) {
            status.text = "请先启用并配置 API 服务器"
        } else {
            Thread {
                val loaded = runCatching {
                    val sync = com.reamicro.fix.cloud.api.ApiContentLibrarySync(activity.applicationContext, ApiServerClient(store), settings)
                    ApiServerClient(store).uploadPolicy() to sync.collect()
                }
                activity.runOnUiThread {
                    loaded.onSuccess { (loadedPolicy, items) ->
                        policy = loadedPolicy
                        val allowed = loadedPolicy.kinds
                        pending = items.filter { allowed.isEmpty() || it.kind in allowed }
                        val breakdown = listOf(
                            ApiPackageKind.ONLINE_SOURCE to "书源",
                            ApiPackageKind.ASSOCIATION_SOURCE to "关联源",
                            ApiPackageKind.HIGHLIGHT_STYLE to "高亮样式",
                        ).mapNotNull { (kind, label) ->
                            pending.count { it.kind == kind }.takeIf { it > 0 }?.let { "$it 个$label" }
                        }
                        // 图片会内嵌进高亮样式包，先让用户知道本次上传包含图片数据。
                        val localAssets = pending.count { it.usesLocalAssets }
                        status.text = when {
                            !loadedPolicy.allowed -> loadedPolicy.reason.ifBlank { "服务器未允许当前账号上传内容库" }
                            pending.isEmpty() -> "本机没有可上传的内容"
                            else -> buildString {
                                append("本机可上传 ")
                                append(breakdown.joinToString("、"))
                                append("；当前阅微账号 ${loadedPolicy.hostAccountId} 已在上传白名单。")
                                if (localAssets > 0) {
                                    append("\n其中 $localAssets 个高亮样式包含图片，图片会随样式上传并在其他设备安装时自动还原。")
                                }
                            }
                        }
                        upload.isEnabled = loadedPolicy.allowed && pending.isNotEmpty()
                    }.onFailure { error -> status.text = error.message ?: "读取上传许可失败" }
                }
            }.start()
        }
        upload.setOnClickListener {
            if (!upload.isEnabled) return@setOnClickListener
            upload.isEnabled = false
            status.text = "正在上传……"
            val targets = pending
            val kinds = policy?.kinds.orEmpty()
            Thread {
                val result = runCatching {
                    com.reamicro.fix.cloud.api.ApiContentLibrarySync(activity.applicationContext, ApiServerClient(store), settings)
                        .upload(targets, kinds) { index, total, name ->
                            activity.runOnUiThread { status.text = "正在上传 $index/$total：$name" }
                        }
                }
                activity.runOnUiThread {
                    result.onSuccess(::renderSummary)
                        .onFailure { error -> status.text = error.message ?: "上传内容库失败" }
                    upload.isEnabled = true
                }
            }.start()
        }
        val actions = settingsDialogActions(activity)
        actions.addView(upload, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
            setOnClickListener { dialog.dismiss() }
        }, settingsDialogButtonParams(activity))
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

// 把本地书源和关联源与服务器内容库比对，名称和域名一致的视为同一个源并全量关联。
internal fun ReaMicroSettingsHook.openApiLibraryLinkDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val store = ApiServerSettingsStore { activity.applicationContext }
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "关联内容库", colors))
        card.addView(
            settingsDialogHint(
                activity,
                "把本机的书源和关联源与服务器内容库比对。源的名称和域名一致即视为同一个源，" +
                    "关联后即可通过 API 服务器更新。关联本身不改动本机内容；" +
                    "执行“检查内容库更新”时会用服务器版本覆盖已关联的源。",
                colors,
            ),
            apiServerRowParams(activity),
        )
        val status = TextView(activity).apply { setTextColor(colors.body); text = "正在比对本机内容库与服务器内容库……" }
        card.addView(status, apiServerRowParams(activity))
        val link = settingsDialogButton(activity, "全部关联", colors, SettingsDialogButtonRole.Primary).apply { isEnabled = false }
        var pairs: List<Pair<com.reamicro.fix.cloud.api.ApiLibraryItem, com.reamicro.fix.cloud.api.ApiPackageSummary>> = emptyList()

        if (!store.get().enabled) {
            status.text = "请先启用并配置 API 服务器"
        } else {
            Thread {
                val loaded = runCatching {
                    val sync = com.reamicro.fix.cloud.api.ApiContentLibrarySync(activity.applicationContext, ApiServerClient(store), settings)
                    val items = sync.collect()
                    Triple(items.size, sync.match(items), sync.linkedCount())
                }
                activity.runOnUiThread {
                    loaded.onSuccess { (total, matched, alreadyLinked) ->
                        pairs = matched
                        val online = matched.count { it.first.kind == ApiPackageKind.ONLINE_SOURCE }
                        val association = matched.count { it.first.kind == ApiPackageKind.ASSOCIATION_SOURCE }
                        status.text = when {
                            total == 0 -> "本机没有书源或关联源"
                            matched.isEmpty() -> "本机 $total 个源在服务器内容库中没有名称和域名一致的匹配项"
                            else -> buildString {
                                append("本机 $total 个源中有 ${matched.size} 个可关联：$online 个书源、$association 个关联源。")
                                if (alreadyLinked > 0) append("\n其中已登记关联 $alreadyLinked 个，重复关联不会产生副作用。")
                                matched.take(10).forEach { (item, summary) ->
                                    append("\n· ${item.name} → ${summary.packageId} ${summary.version}")
                                }
                                if (matched.size > 10) append("\n· 其余 ${matched.size - 10} 个略")
                            }
                        }
                        link.isEnabled = matched.isNotEmpty()
                    }.onFailure { error -> status.text = error.message ?: "比对内容库失败" }
                }
            }.start()
        }
        link.setOnClickListener {
            if (!link.isEnabled) return@setOnClickListener
            link.isEnabled = false
            status.text = "正在关联……"
            val targets = pairs
            Thread {
                val result = runCatching {
                    com.reamicro.fix.cloud.api.ApiContentLibrarySync(activity.applicationContext, ApiServerClient(store), settings)
                        .link(targets) { index, total, name ->
                            activity.runOnUiThread { status.text = "正在关联 $index/$total：$name" }
                        }
                }
                activity.runOnUiThread {
                    result.onSuccess { summary ->
                        status.text = buildString {
                            append("已关联 ${summary.linked} 个源")
                            if (summary.failed > 0) append("，失败 ${summary.failed} 个")
                            append("。")
                            if (summary.linked > 0) append("\n现在执行“检查内容库更新”即可通过 API 服务器更新这些源。")
                            summary.messages.forEach { append("\n$it") }
                        }
                    }.onFailure { error -> status.text = error.message ?: "关联内容库失败" }
                    link.isEnabled = true
                }
            }.start()
        }
        val actions = settingsDialogActions(activity)
        actions.addView(link, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
            setOnClickListener { dialog.dismiss() }
        }, settingsDialogButtonParams(activity))
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

internal fun ReaMicroSettingsHook.checkApiPackageUpdates() {
    val activity = activityProvider() ?: return
    val store = ApiServerSettingsStore { activity.applicationContext }
    if (!store.get().enabled) {
        showToast("请先启用 API 服务器")
        return
    }
    showToast("正在检查内容库更新")
    Thread {
        val result = runCatching {
            val manager = ApiPackageManager(activity.applicationContext, ApiServerClient(store), settings)
            val candidates = listOf(
                ApiPackageKind.ONLINE_SOURCE,
                ApiPackageKind.EPUB_STYLE,
                ApiPackageKind.HIGHLIGHT_STYLE,
                ApiPackageKind.ASSOCIATION_SOURCE,
                ApiPackageKind.THEME,
            ).flatMap(manager::check)
            candidates.map(manager::install)
        }
        activity.runOnUiThread {
            result.onSuccess { updates ->
                val installed = updates.count { it.installed }
                val failed = updates.count { !it.installed }
                showToast(
                    when {
                        updates.isEmpty() -> "内容库已是最新版本"
                        failed == 0 -> "已更新 $installed 个内容包"
                        else -> "已更新 $installed 个，失败 $failed 个"
                    },
                )
            }.onFailure { showToast(it.message ?: "检查内容库更新失败") }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.checkApiModuleUpdate() {
    val activity = activityProvider() ?: return
    val store = ApiServerSettingsStore { activity.applicationContext }
    Thread {
        val result = checkModuleUpdate(ApiServerClient(store))
        activity.runOnUiThread { showToast(result.message) }
    }.start()
}

internal fun ReaMicroSettingsHook.downloadApiModuleUpdate() {
    val activity = activityProvider() ?: return
    val store = ApiServerSettingsStore { activity.applicationContext }
    showToast("正在下载模块更新")
    Thread {
        runCatching {
            val client = ApiServerClient(store)
            val release = client.latestModuleRelease() ?: error("服务器没有可用模块版本")
            val file = client.downloadModuleReleaseToCache(activity.applicationContext, release)
            activity.runOnUiThread {
                showToast("下载完成：${release.versionName}")
                ModuleApkInstaller.install(activity, file)
            }
        }.onFailure {
            activity.runOnUiThread { showToast(it.message ?: "模块更新下载失败") }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.backupApiModuleSettings() {
    val activity = activityProvider() ?: return
    val store = ApiServerSettingsStore { activity.applicationContext }
    showToast("正在上传模块设置备份")
    Thread {
        runCatching {
            val bytes = ModuleSettingsBackup.create(activity.applicationContext)
            ApiServerClient(store).uploadModuleBackup(bytes)
        }.onSuccess {
            activity.runOnUiThread { showToast("模块设置备份已上传") }
        }.onFailure {
            activity.runOnUiThread { showToast(it.message ?: "上传备份失败") }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.restoreApiModuleSettings() {
    val activity = activityProvider() ?: return
    val store = ApiServerSettingsStore { activity.applicationContext }
    showToast("正在下载模块设置备份")
    Thread {
        runCatching {
            val bytes = ApiServerClient(store).downloadModuleBackup()
            ModuleSettingsBackup.restore(activity.applicationContext, bytes)
        }.onSuccess { count ->
            activity.runOnUiThread { showToast("已恢复 $count 项模块设置，请重新打开设置页") }
        }.onFailure {
            activity.runOnUiThread { showToast(it.message ?: "恢复备份失败") }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.openCredentialBackupDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val store = ApiServerSettingsStore { activity.applicationContext }
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "账号密钥备份", colors))
        card.addView(TextView(activity).apply {
            setTextColor(colors.body)
            text = "备份在本机用口令派生密钥加密，服务器只保存密文。模块设置备份不会包含账号密钥。"
        }, apiServerRowParams(activity))
        val password = apiServerEdit(activity, colors, "备份口令（至少 8 位）", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val status = TextView(activity).apply { setTextColor(colors.body) }
        card.addView(password, apiServerRowParams(activity))
        card.addView(status, apiServerRowParams(activity))
        val actions = settingsDialogActions(activity)
        actions.addView(settingsDialogButton(activity, "加密并上传", colors, SettingsDialogButtonRole.Primary).apply {
            setOnClickListener {
                status.text = "正在加密账号密钥……"
                Thread {
                    runCatching {
                        val bytes = CredentialBackupCrypto.create(activity.applicationContext, password.text.toString().toCharArray())
                        ApiServerClient(store).uploadCredentialsBackup(bytes)
                    }.onSuccess { activity.runOnUiThread { status.text = "账号密钥密文已上传" } }
                        .onFailure { error -> activity.runOnUiThread { status.text = error.message ?: "上传失败" } }
                }.start()
            }
        }, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "下载并恢复", colors, SettingsDialogButtonRole.Neutral).apply {
            setOnClickListener {
                status.text = "正在下载密钥密文……"
                Thread {
                    runCatching {
                        val bytes = ApiServerClient(store).downloadCredentialsBackup()
                        CredentialBackupCrypto.restore(activity.applicationContext, password.text.toString().toCharArray(), bytes)
                    }.onSuccess { count -> activity.runOnUiThread { status.text = "已恢复 $count 项账号密钥" } }
                        .onFailure { error -> activity.runOnUiThread { status.text = error.message ?: "恢复失败" } }
                }.start()
            }
        }, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply { setOnClickListener { dialog.dismiss() } }, settingsDialogButtonParams(activity))
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, dismissOnThemeChange = true)
    }
}

internal fun ReaMicroSettingsHook.apiServerEdit(
    activity: android.app.Activity,
    colors: ReaMicroSettingsHook.SettingsDialogColors,
    hint: String,
    value: String,
): EditText =
    EditText(activity).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
        typeface = apiServerTypeface(activity)
        setTextColor(colors.title)
        setHintTextColor(colors.body)
        setPadding(24, 8, 24, 8)
        background = settingsRoundedRect(colors.field, settingsDp(activity, 8), colors.border)
    }

private fun ReaMicroSettingsHook.apiServerAuthAdapter(activity: android.app.Activity, modes: List<ApiAuthMode>): ArrayAdapter<String> =
    apiServerStringAdapter(
        activity,
        modes.map {
            when (it) {
                ApiAuthMode.PUBLIC -> "公开访问"
                ApiAuthMode.API_KEY -> "API Key"
                ApiAuthMode.ACCOUNT -> "独立账号"
                ApiAuthMode.HOST_ACCOUNT_ALLOWLIST -> "阅微账号白名单"
            }
        },
    )

private fun ReaMicroSettingsHook.apiServerStringAdapter(
    activity: android.app.Activity,
    values: List<String>,
): ArrayAdapter<String> = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, values) {
    private val globalTypeface = apiServerTypeface(activity)

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        super.getView(position, convertView, parent).also { view ->
            (view as? TextView)?.apply {
                typeface = globalTypeface
                setTextColor(SettingsDialogColors(activity).title)
            }
        }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        super.getDropDownView(position, convertView, parent).also { view ->
            (view as? TextView)?.apply {
                typeface = globalTypeface
                val colors = SettingsDialogColors(activity)
                setTextColor(colors.title)
                setBackgroundColor(colors.field)
            }
        }
}

private fun ReaMicroSettingsHook.apiServerTypeface(activity: android.app.Activity): Typeface {
    val selection = settings.fontSettings().globalFamily.trim()
    if (selection.isBlank() || selection == "system") return Typeface.DEFAULT
    if (selection == "serif") return Typeface.SERIF
    val direct = File(selection)
    val file = direct.takeIf(File::isFile) ?: fontDirectories(activity)
        .asSequence()
        .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
        .firstOrNull { it.isFile && it.name == direct.name }
    return file?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
}

internal fun apiServerRowParams(activity: android.app.Activity): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = activity.resources.displayMetrics.density.times(8).toInt() }

// 关于补全页"构建版本"的来源：版本号 + 构建时间。
internal fun ReaMicroSettingsHook.moduleBuildInfo(): Pair<String, String> {
    val version = com.reamicro.fix.BuildConfig.VERSION_NAME
    val buildTime = runCatching {
        val millis = com.reamicro.fix.BuildConfig.BUILD_TIME
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }.getOrElse { "" }
    return version to buildTime
}

// 关于补全页"构建版本"连续点击 6 次后弹出的调试模式解锁确认。
internal fun ReaMicroSettingsHook.confirmApiDebugUnlock() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val dialog = Dialog(activity)
        val colors = SettingsDialogColors(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "测试配置", colors))
        card.addView(
            settingsDialogHint(
                activity,
                "启用后将允许使用自建 API 服务器进行模块调试与服务联调，并显示 API 服务器设置入口。" +
                    "连接非官方服务器可能收集您的个人数据和阅微账号数据等敏感高危信息，请确认是否启用。",
                colors,
            ),
        )
        val actions = settingsDialogActions(activity)
        actions.addView(
            settingsDialogButton(activity, "取消", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener {
                    dialog.dismiss()
                    showToast("已取消启用测试配置")
                }
            },
            settingsDialogButtonParams(activity),
        )
        actions.addView(
            settingsDialogButton(activity, "确认启用", colors, SettingsDialogButtonRole.Primary).apply {
                setOnClickListener {
                    settings.setApiDebugUnlocked(true)
                    bumpAboutVersion()
                    dialog.dismiss()
                    showToast("已启用测试配置")
                }
            },
            settingsDialogButtonParams(activity),
        )
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
    }
}
