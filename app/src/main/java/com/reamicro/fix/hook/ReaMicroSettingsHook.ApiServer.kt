package com.reamicro.fix.hook

import android.app.Dialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.reamicro.fix.cloud.api.ApiAuthMode
import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettings
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.cloud.api.ApiPackageKind
import com.reamicro.fix.cloud.api.ApiPackageManager
import com.reamicro.fix.cloud.api.checkModuleUpdate
import com.reamicro.fix.cloud.api.ModuleApkInstaller
import com.reamicro.fix.cloud.api.ModuleSettingsBackup
import com.reamicro.fix.cloud.api.CloudTaskManager
import com.reamicro.fix.cloud.api.normalizeApiBaseUrl
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.hook.settings.*
import de.robv.android.xposed.XposedBridge

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
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, "API 服务器", colors))
            val enabled = android.widget.Switch(activity).apply {
                text = "启用 API 服务器"
                isChecked = current.enabled
                setTextColor(colors.title)
            }
            card.addView(enabled, apiServerRowParams(activity))
            val url = apiServerEdit(activity, "服务器地址，例如 https://example.com", current.baseUrl)
            card.addView(url, apiServerRowParams(activity))
            val authModes = ApiAuthMode.entries
            val authMode = Spinner(activity).apply {
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    authModes.map {
                        when (it) {
                            ApiAuthMode.PUBLIC -> "公开访问"
                            ApiAuthMode.API_KEY -> "API Key"
                            ApiAuthMode.ACCOUNT -> "独立账号"
                            ApiAuthMode.HOST_ACCOUNT_ALLOWLIST -> "阅微账号白名单"
                        }
                    },
                )
                setSelection(authModes.indexOf(current.authMode).coerceAtLeast(0))
            }
            card.addView(authMode, apiServerRowParams(activity))
            val key = apiServerEdit(activity, "API Key（可选）", current.apiKey).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            card.addView(key, apiServerRowParams(activity))
            val accountName = apiServerEdit(activity, "独立账号", current.accountName)
            card.addView(accountName, apiServerRowParams(activity))
            val accountPassword = apiServerEdit(activity, "独立账号密码", current.accountPassword).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            card.addView(accountPassword, apiServerRowParams(activity))
            val hostAccount = apiServerEdit(activity, "阅微账号 ID（白名单模式）", current.hostAccountId)
            card.addView(hostAccount, apiServerRowParams(activity))
            val allowHttp = settingsDialogSwitchRow(activity, "允许局域网 HTTP", current.allowHttp, colors)
            card.addView(allowHttp)
            val autoUpdate = settingsDialogSwitchRow(activity, "自动检查内容库更新", current.autoCheckUpdates, colors)
            card.addView(autoUpdate)
            val status = TextView(activity).apply {
                text = "认证模式：${current.authMode.wireValue}\n支持模式会在连接后从服务器读取"
                setTextColor(colors.body)
                setPadding(24, 8, 24, 8)
            }
            card.addView(status)
            val actions = settingsDialogActions(activity)
            actions.addView(settingsDialogButton(activity, "测试连接", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener {
                    val next = current.copy(
                        enabled = enabled.isChecked,
                        baseUrl = url.text.toString(),
                        apiKey = key.text.toString(),
                        accountName = accountName.text.toString(),
                        accountPassword = accountPassword.text.toString(),
                        hostAccountId = hostAccount.text.toString(),
                        authMode = authModes[authMode.selectedItemPosition],
                        allowHttp = allowHttp.isChecked,
                        autoCheckUpdates = autoUpdate.isChecked,
                    )
                    runCatching { normalizeApiBaseUrl(next.baseUrl, next.allowHttp) }
                        .onFailure { status.text = it.message ?: "地址无效" }
                        .onSuccess {
                            status.text = "正在连接……"
                            Thread {
                                val result = ApiServerClient(store).probe(next)
                                activity.runOnUiThread {
                                    status.text = if (result.success) {
                                        "连接成功：${result.meta?.features?.joinToString().orEmpty()}"
                                    } else result.message
                                }
                            }.start()
                        }
                }
            }, settingsDialogButtonParams(activity))
            actions.addView(settingsDialogButton(activity, "保存", colors, SettingsDialogButtonRole.Primary).apply {
                setOnClickListener {
                    val next = current.copy(
                        enabled = enabled.isChecked,
                        baseUrl = url.text.toString(),
                        apiKey = key.text.toString(),
                        accountName = accountName.text.toString(),
                        accountPassword = accountPassword.text.toString(),
                        hostAccountId = hostAccount.text.toString(),
                        authMode = authModes[authMode.selectedItemPosition],
                        allowHttp = allowHttp.isChecked,
                        autoCheckUpdates = autoUpdate.isChecked,
                    )
                    runCatching { normalizeApiBaseUrl(next.baseUrl, next.allowHttp) }
                        .onFailure { status.text = it.message ?: "地址无效" }
                        .onSuccess {
                            store.save(next)
                            dialog.dismiss()
                            showToast("API 服务器设置已保存")
                        }
                }
            }, settingsDialogButtonParams(activity))
            actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener { dialog.dismiss() }
            }, settingsDialogButtonParams(activity))
            card.addView(actions)
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX api server settings dialog failed: ${it.stackTraceToString()}")
        }
    }
}

internal fun ReaMicroSettingsHook.openCloudAutomationDialog() {
    val activity = activityProvider() ?: return
    activity.runOnUiThread {
        val store = ApiServerSettingsStore { activity.applicationContext }
        val client = ApiServerClient(store)
        val colors = SettingsDialogColors(activity)
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "云端任务", colors))
        val credentialIds = mutableListOf<String>()
        val loadedTasks = mutableListOf<com.reamicro.fix.cloud.api.CloudTask>()
        val credentialSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("正在读取已有凭据……"))
        }
        card.addView(credentialSpinner, apiServerRowParams(activity))
        val token = apiServerEdit(activity, "阅微登录密钥（只传输 HTTPS）", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val label = apiServerEdit(activity, "凭据名称", "阅微账号")
        val accountId = apiServerEdit(activity, "阅微账号 ID（可选）", "")
        val time = apiServerEdit(activity, "每日执行时间，例如 00:05", "00:05")
        val duration = apiServerEdit(activity, "云端阅读时长（分钟）", "30")
        val customBook = apiServerEdit(activity, "自定义图书：每行 cloudBookId|书名；留空读取最近阅读", "").apply {
            minLines = 3
            setSingleLine(false)
        }
        val checkin = android.widget.Switch(activity).apply { text = "野社零点云端签到"; setTextColor(colors.title) }
        val draw = android.widget.Switch(activity).apply { text = "野社自动抽卡"; setTextColor(colors.title) }
        val read = android.widget.Switch(activity).apply { text = "云端自动阅读"; setTextColor(colors.title) }
        listOf(token, label, accountId, time, duration, customBook, checkin, draw, read).forEach { view -> card.addView(view, apiServerRowParams(activity)) }
        val status = TextView(activity).apply { setTextColor(colors.body); text = "凭据将在服务器端加密保存，模块设置备份不包含该密钥。" }
        card.addView(status, apiServerRowParams(activity))
        Thread {
            runCatching {
                val manager = CloudTaskManager(client)
                manager.credentials() to manager.list()
            }.onSuccess { (credentials, tasks) ->
                activity.runOnUiThread {
                    credentialIds.clear()
                    credentialIds.addAll(credentials.map { it.id })
                    loadedTasks.clear()
                    loadedTasks.addAll(tasks)
                    credentialSpinner.adapter = ArrayAdapter(
                        activity,
                        android.R.layout.simple_spinner_dropdown_item,
                        credentials.map { "${it.label}${it.accountId.takeIf(String::isNotBlank)?.let { id -> " · $id" }.orEmpty()}" }
                            .ifEmpty { listOf("没有已上传凭据，请填写登录密钥") },
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
                    applyCredentialTasks(credentialSpinner.selectedItemPosition)
                    status.text = "已读取 ${credentials.size} 个凭据、${tasks.size} 个任务；留空登录密钥可复用已选凭据。"
                }
            }.onFailure { error -> activity.runOnUiThread { status.text = error.message ?: "读取云端配置失败" } }
        }.start()
        val actions = settingsDialogActions(activity)
        actions.addView(settingsDialogButton(activity, "保存并验证", colors, SettingsDialogButtonRole.Primary).apply {
            setOnClickListener {
                status.text = "正在验证阅微登录密钥……"
                Thread {
                    runCatching {
                        val manager = CloudTaskManager(client)
                        val enteredToken = token.text.toString().trim()
                        val credentialId = if (enteredToken.isNotBlank()) {
                            manager.uploadCredential(enteredToken, label.text.toString(), accountId.text.toString()).id
                        } else {
                            credentialIds.getOrNull(credentialSpinner.selectedItemPosition) ?: error("请先上传或选择阅微凭据")
                        }
                        val selectedBooks = parseCloudReadingBooks(customBook.text.toString())
                        val readRequest = org.json.JSONObject().put("durationMinutes", duration.text.toString().toIntOrNull()?.coerceIn(1, 720) ?: 30)
                        if (selectedBooks.length() > 0) readRequest.put("books", selectedBooks)
                        manager.saveAutomation("yeshe_checkin", checkin.isChecked, credentialId, time.text.toString())
                        manager.saveAutomation("yeshe_draw_card", draw.isChecked, credentialId, time.text.toString())
                        manager.saveAutomation("cloud_auto_read", read.isChecked, credentialId, time.text.toString(), readRequest)
                    }.onSuccess {
                        activity.runOnUiThread { status.text = "阅微凭据与任务配置已同步" }
                    }.onFailure { error ->
                        activity.runOnUiThread { status.text = error.message ?: "云端任务配置失败" }
                    }
                }.start()
            }
        }, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "立即执行", colors, SettingsDialogButtonRole.Neutral).apply {
            setOnClickListener {
                val credentialId = credentialIds.getOrNull(credentialSpinner.selectedItemPosition)
                if (credentialId == null) {
                    status.text = "请先上传或选择阅微凭据"
                    return@setOnClickListener
                }
                status.text = "正在安排已启用任务立即执行……"
                Thread {
                    runCatching {
                        val manager = CloudTaskManager(client)
                        val runnable = manager.list().filter { it.credentialId == credentialId && it.enabled }
                        runnable.forEach { manager.runNow(it.id) }
                        runnable.size
                    }.onSuccess { count -> activity.runOnUiThread { status.text = "已安排 $count 个任务立即执行" } }
                        .onFailure { error -> activity.runOnUiThread { status.text = error.message ?: "立即执行失败" } }
                }.start()
            }
        }, settingsDialogButtonParams(activity))
        actions.addView(settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral).apply { setOnClickListener { dialog.dismiss() } }, settingsDialogButtonParams(activity))
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
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
                .put("cloudBookId", id)
                .put("bookId", id)
                .put("name", parts.getOrNull(1)?.trim().orEmpty()))
        }
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
        val result = checkModuleUpdate(activity.applicationContext, ApiServerClient(store))
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

private fun apiServerEdit(activity: android.app.Activity, hint: String, value: String): EditText =
    EditText(activity).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
        setPadding(24, 8, 24, 8)
    }

private fun apiServerRowParams(activity: android.app.Activity): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = activity.resources.displayMetrics.density.times(8).toInt() }
