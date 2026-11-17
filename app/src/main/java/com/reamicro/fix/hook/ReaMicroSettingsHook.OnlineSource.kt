package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.association.provider.YouShuLoginCookies
import com.reamicro.fix.association.provider.YouShuLoginStatus
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceTrxsCompat
import com.reamicro.fix.online.OnlineSourceTask
import com.reamicro.fix.online.readOnlineSourceBytes
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

internal fun ReaMicroSettingsHook.onlineSourceSubtitle(source: OnlineSourceEntry): String {
    val tags = mutableListOf<String>()
    val loginFields = OnlineSourceAuth.loginFields(source)
    when {
        OnlineSourceAuth.hasSavedLogin(activityProvider()?.applicationContext, source) -> {
            tags += if (loginFields.size == 1) "已配置${loginFields.single().name}" else "已登录"
        }
        source.webLoginUrl.isNotBlank() || OnlineSourceAuth.supportsCredentialLogin(source) -> {
            tags += if (loginFields.size == 1) "需配置${loginFields.single().name}" else "可登录"
            tags += "可跳过"
        }
        source.hasLoginConfig -> {
            tags += "有登录配置"
            tags += "可跳过"
        }
        else -> tags += "免登录"
    }
    val rate = source.configuredRequestsPerSecond
        ?.let { "限速 ${it}次/秒" }
        ?: source.concurrentRate
            .compactOnlineSourceLine()
            .takeIf { it.isNotBlank() && it != "0" }
            ?.let { "频控 $it" }
    rate?.let { tags += it }
    source.dailyChapterLimit?.let { limit ->
        val used = OnlineSourceDownloadPolicyStore.usedToday(activityProvider()?.applicationContext, source.id)
        tags += "今日 $used/$limit 章"
    }
    tags += if (source.preferOnDemandLoading) "逐章加载" else "整本下载"

    return tags.joinToString(" · ")
}

internal fun ReaMicroSettingsHook.listOnlineSources(): List<OnlineSourceEntry> =
    OnlineSourceSettingsLoads.list(this, activityProvider()?.applicationContext)

internal fun ReaMicroSettingsHook.openOnlineSourceManagementDialog(source: OnlineSourceEntry) {
    if (!OnlineSourceStore.isCurrent(source)) { showToast("在线源已变化，请重新打开"); return }
    if (source.hasLoginConfig || OnlineSourceAuth.supportsCredentialLogin(source)) {
        openOnlineSourceLoginDialog(source, enableFlow = false) { changed ->
            if (changed) bumpOnlineSourceVersion()
        }
        return
    }
    openOnlineSourceDownloadConfigDialog(source)
}

internal fun ReaMicroSettingsHook.openOnlineSourceDownloadConfigDialog(source: OnlineSourceEntry) {
    val activity = activityProvider() ?: return
    activity.runOnModuleUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = settingsDialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, "${source.name} 配置", colors))
            card.addView(settingsDialogHint(activity, "下载方式按书源独立保存。", colors))
            val inputs = addOnlineSourceDownloadPolicyInputs(activity, source, card, colors)
            val saveButton = settingsDialogButton(activity, "保存", colors)
            val deleteButton = settingsDialogButton(
                activity,
                "删除",
                colors,
                SettingsDialogButtonRole.Destructive,
            )
            val cancelButton = settingsDialogButton(
                activity,
                "取消",
                colors,
                SettingsDialogButtonRole.Neutral,
            )
            card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, saveButton, cancelButton)))
            saveButton.setOnClickListener {
                if (!saveOnlineSourceDownloadPolicy(activity, source, inputs)) {
                    return@setOnClickListener
                }
                bumpOnlineSourceVersion()
                showToast("已保存在线源配置：${source.name}")
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                confirmOnlineSourceDeletion(activity, source) { dialog.dismiss() }
            }
            cancelButton.setOnClickListener { dialog.dismiss() }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX online source config dialog failed: ${it.stackTraceToString()}")
            showToast("无法打开在线源配置")
        }
    }
}

internal fun ReaMicroSettingsHook.confirmOnlineSourceDeletion(
    activity: Activity,
    source: OnlineSourceEntry,
    onRemoved: () -> Unit,
) {
    val colors = SettingsDialogColors(activity)
    val dialog = settingsDialog(activity)
    val card = settingsDialogCard(activity, colors)
    val deleteButton = settingsDialogButton(
        activity,
        "删除",
        colors,
        SettingsDialogButtonRole.Destructive,
    )
    val cancelButton = settingsDialogButton(
        activity,
        "取消",
        colors,
        SettingsDialogButtonRole.Neutral,
    )
    card.addView(settingsDialogTitle(activity, "删除在线源", colors))
    card.addView(
        settingsDialogHint(
            activity,
            "确定删除“${source.name}”吗？保存的登录信息和下载配置也会一并清除。",
            colors,
        ),
    )
    card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, cancelButton)))
    deleteButton.setOnClickListener {
        deleteButton.isEnabled = false
        cancelButton.isEnabled = false
        dialog.setCancelable(false)
        OnlineSourceEnableRequests.begin(source.id)
        com.reamicro.fix.reload.ModuleResources.thread({
            val result = runCatching { OnlineSourceStore.remove(activity.applicationContext, source.id, source) }
            activity.runOnModuleUiThread {
                if (result.getOrDefault(false)) {
                    settings.setOnlineSourceEnabled(source.id, false)
                    bumpOnlineSourceVersion()
                    showToast("已移除在线源：${source.name}")
                    dialog.dismiss()
                    onRemoved()
                } else if (dialog.isShowing) {
                    deleteButton.isEnabled = true
                    cancelButton.isEnabled = true
                    dialog.setCancelable(true)
                    showToast(result.exceptionOrNull()?.message ?: "在线源移除失败：${source.name}")
                }
            }
        }, "ReaMicroSourceRemove").start()
    }
    cancelButton.setOnClickListener { dialog.dismiss() }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
}

internal fun ReaMicroSettingsHook.renderOnlineSourceSwitch(source: OnlineSourceEntry, composer: Any) {
    val targetChecked = settings.isOnlineSourceEnabled(source.id)
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        setStateValue(state, value)
    }
    val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
    val checked = if (rememberedChecked != targetChecked) {
        updateChecked(targetChecked)
        targetChecked
    } else {
        rememberedChecked
    }
    val onCheckedChange = functionProxy("OnlineSourceSwitch${source.id}", FUNCTION1_CLASS) { args ->
        val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        val applied = setOnlineSourceEnabled(source, enabled, ::updateChecked)
        updateChecked(applied)
        targetUnit()
    }
    nativeUi.settingsSwitch(checked, onCheckedChange, composer)
}

internal fun ReaMicroSettingsHook.setOnlineSourceEnabled(
    source: OnlineSourceEntry,
    enabled: Boolean,
    updateChecked: (Boolean) -> Unit,
): Boolean {
    val request = OnlineSourceEnableRequests.begin(source.id)
    if (!enabled) {
        settings.setOnlineSourceEnabled(source.id, false)
        return false
    }
    val context = activityProvider()?.applicationContext
    if (!OnlineSourceStore.isCurrent(source)) return false
    if (!source.hasLoginConfig || OnlineSourceAuth.hasSavedLogin(context, source)) {
        return runCatching { OnlineSourceStore.withCurrent(source) {
            settings.setOnlineSourceEnabled(source.id, true)
            true
        } }.getOrDefault(false)
    }
    settings.setOnlineSourceEnabled(source.id, false)
    openOnlineSourceLoginDialog(source) { confirmed ->
        if (!OnlineSourceEnableRequests.current(source.id, request) ||
            !OnlineSourceStore.isCurrent(source) || !settings.snapshot().moduleEnabled) return@openOnlineSourceLoginDialog
        if (confirmed) {
            val applied = runCatching { OnlineSourceStore.withCurrent(source) {
                settings.setOnlineSourceEnabled(source.id, true)
            } }.isSuccess
            if (!applied) { updateChecked(false); return@openOnlineSourceLoginDialog }
            updateChecked(true)
            bumpOnlineSourceVersion()
            showToast("已启用在线源：${source.name}")
        } else {
            settings.setOnlineSourceEnabled(source.id, false)
            updateChecked(false)
        }
    }
    return false
}

internal fun ReaMicroSettingsHook.importOnlineSourceFromClipboard() {
    val activity = activityProvider() ?: return
    val url = readClipboardText(activity).trim()
    if (url.isBlank()) {
        showToast("剪贴板中没有在线源链接")
        return
    }
    importOnlineSourceFromUrl(activity, url)
}

internal fun ReaMicroSettingsHook.importOnlineSourceFromUrl(activity: Activity, url: String) {
    importOnlineSourceAsync(activity, "online_clipboard:$url") {
        OnlineSourceStore.importFromUrl(activity.applicationContext, url)
    }
}

internal fun ReaMicroSettingsHook.openOnlineSourceDocumentPicker() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/json",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, ONLINE_SOURCE_DOCUMENT_REQUEST_CODE)
    }.onFailure {
        Toast.makeText(activity, "无法打开在线源选择器", Toast.LENGTH_SHORT).show()
        HookRuntime.log("$LOG_PREFIX failed to open online source picker: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.importOnlineSourceDocumentResult(activity: Activity, intent: Intent) {
    val clipData = intent.clipData
    if (clipData != null && clipData.itemCount != 1) {
        showToast("一次最多导入一个在线源")
        return
    }
    val uri = clipData?.getItemAt(0)?.uri ?: intent.data
    if (uri == null) {
        showToast("未选择在线源文件")
        return
    }
    importOnlineSourceAsync(activity, "uri:$uri") {
        val bytes = activity.contentResolver.openInputStream(uri)?.use(::readOnlineSourceBytes)
            ?: error("无法读取源文件")
        val displayName = queryDisplayName(activity, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "online_source.json"
        OnlineSourceStore.importBytes(activity.applicationContext, bytes, displayName, uri.toString())
    }
}

internal fun ReaMicroSettingsHook.importOnlineSourceAsync(
    activity: Activity,
    token: String,
    importer: () -> OnlineSourceEntry,
) {
    val now = System.currentTimeMillis()
    if (token == lastOnlineSourceImportToken && now - lastOnlineSourceImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS) {
        return
    }
    lastOnlineSourceImportToken = token
    lastOnlineSourceImportAtMs = now
    val accepted = OnlineSourceImports.submit(token) {
        runCatching { importer() }
            .onSuccess { source ->
                activity.runOnModuleUiThread {
                    if (!OnlineSourceStore.isCurrent(source)) return@runOnModuleUiThread
                    bumpOnlineSourceVersion()
                    showToast("已添加在线源：${source.name}")
                }
            }
            .onFailure {
                HookRuntime.log("$LOG_PREFIX online source import failed: ${it.stackTraceToString()}")
                activity.runOnModuleUiThread {
                    if (lastOnlineSourceImportToken == token) {
                        lastOnlineSourceImportToken = ""
                        lastOnlineSourceImportAtMs = 0L
                    }
                    showToast(it.message ?: "导入在线源失败")
                }
            }
    }
    if (!accepted) {
        lastOnlineSourceImportToken = ""
        lastOnlineSourceImportAtMs = 0L
        showToast("已有在线源正在导入，请稍候")
    }
}

internal fun ReaMicroSettingsHook.openOnlineSourceLoginDialog(
    source: OnlineSourceEntry,
    enableFlow: Boolean = true,
    onResult: (Boolean) -> Unit,
) {
    val activity = activityProvider()
    if (activity == null) {
        onResult(false)
        return
    }
    if (!OnlineSourceStore.isCurrent(source)) {
        showToast("在线源已变化，请重新打开")
        onResult(false)
        return
    }
    val loginFields = OnlineSourceAuth.loginFields(source)
    if (OnlineSourceTrxsCompat.isSource(source)) {
        openTrxsOnlineSourceLoginDialog(activity, source, enableFlow, onResult)
        return
    }
    if (OnlineSourceAuth.usesAccountPasswordLogin(source)) {
        openOnlineSourceCredentialDialog(activity, source, enableFlow, onResult)
        return
    }
    if (loginFields.isNotEmpty()) {
        openOnlineSourceLoginInfoDialog(activity, source, enableFlow, onResult)
        return
    }
    val loginUrl = source.webLoginUrl
    if (loginUrl.isBlank()) {
        openOnlineSourceCredentialDialog(activity, source, enableFlow, onResult)
        return
    }
    activity.runOnModuleUiThread {
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            var resolved = false
            val webView = WebView(activity).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        CookieManager.getInstance().flush()
                    }
                }
                loadUrl(loginUrl)
            }
            fun resolve(value: Boolean) {
                if (resolved) return
                resolved = true
                CookieManager.getInstance().flush()
                onResult(value)
            }
            val colors = SettingsDialogColors(activity)
            val dialog = settingsDialog(activity)
            val card = settingsDialogCard(activity, colors)
            val finishButton = settingsDialogButton(activity, "完成", colors)
            val deleteButton = settingsDialogButton(
                activity,
                "删除",
                colors,
                SettingsDialogButtonRole.Destructive,
            )
            val cancelButton = settingsDialogButton(
                activity,
                if (enableFlow) "跳过启用" else "取消",
                colors,
                SettingsDialogButtonRole.Neutral,
            )
            card.addView(settingsDialogTitle(activity, "${source.name} 登录", colors))
            card.addView(
                settingsDialogHint(
                    activity,
                    "请在下方页面完成登录，返回后点击“完成”。",
                    colors,
                ),
            )
            card.addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.58f).toInt(),
                ).apply { bottomMargin = settingsDp(activity, 10) },
            )
            card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, finishButton, cancelButton)))
            finishButton.setOnClickListener {
                resolve(true)
                dialog.dismiss()
            }
            cancelButton.setOnClickListener {
                resolve(enableFlow)
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                confirmOnlineSourceDeletion(activity, source) {
                    resolve(false)
                    dialog.dismiss()
                }
            }
            dialog.setOnDismissListener {
                if (!resolved) resolve(false)
                runCatching { webView.destroy() }
            }
            showSettingsDialog(dialog, card, activity, 0.94f)
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            webView.requestFocus()
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX failed to open online source login: ${it.stackTraceToString()}")
            onResult(false)
        }
    }
}

internal fun ReaMicroSettingsHook.openTrxsOnlineSourceLoginDialog(
    activity: Activity,
    source: OnlineSourceEntry,
    enableFlow: Boolean,
    onResult: (Boolean) -> Unit,
) {
    activity.runOnModuleUiThread {
        var activeOperation: OnlineSourceTask? = null
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = settingsDialog(activity)
            val card = settingsDialogCard(activity, colors)
            val savedToken = OnlineSourceAuth.sourceVariable(activity.applicationContext, source)
            val operation = OnlineSourceTask(source).also { activeOperation = it }
            val tokenInput = settingsDialogInput(
                activity,
                source.variableComment.ifBlank { "共享Token" },
                singleLine = true,
                colors = colors,
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(savedToken)
                setSelection(text?.length ?: 0)
            }
            val inviteInput = settingsDialogInput(activity, "邀请码", singleLine = true, colors = colors)
            val status = settingsDialogStatus(
                activity,
                "可直接粘贴共享Token，或输入邀请码自动注册并保存Token。普通登录需先保存Token，再打开账号管理页。",
                colors,
            )
            card.addView(settingsDialogTitle(activity, "${source.name} 登录", colors))
            card.addView(settingsDialogHint(activity, "该源的搜索、目录和正文请求均使用共享Token。", colors))
            card.addView(tokenInput)
            card.addView(inviteInput)
            val saveTokenButton = settingsDialogButton(activity, "保存Token", colors)
            val inviteButton = settingsDialogButton(activity, "邀请码登录", colors)
            val testButton = settingsDialogButton(activity, "校验Token", colors)
            val accountButton = settingsDialogButton(activity, "账号管理", colors)
            card.addView(settingsDialogButtonRow(activity, listOf(saveTokenButton, inviteButton)))
            card.addView(settingsDialogButtonRow(activity, listOf(testButton, accountButton)))
            card.addView(status)
            val policyInputs = addOnlineSourceDownloadPolicyInputs(activity, source, card, colors)
            var resolved = false
            var authBusy = false
            fun resolve(value: Boolean) {
                if (resolved) return
                resolved = true
                onResult(value)
            }
            fun runAuthTask(button: TextView, task: () -> com.reamicro.fix.online.OnlineSourceAuthResult, onSuccess: (() -> Unit)? = null) {
                if (!operation.isCurrent) { status.text = "在线源已变化，请重新打开"; return }
                if (authBusy) return
                authBusy = true
                button.isEnabled = false
                com.reamicro.fix.reload.ModuleResources.thread({
                    val result = runCatching { operation.run(task) }.getOrElse {
                        com.reamicro.fix.online.OnlineSourceAuthResult(false, it.message ?: "请求失败，请重试")
                    }
                    activity.runOnModuleUiThread {
                        if (!dialog.isShowing) return@runOnModuleUiThread
                        authBusy = false
                        button.isEnabled = true
                        if (!operation.isCurrent) { status.text = "在线源已变化，请重新打开"; return@runOnModuleUiThread }
                        status.text = result.message
                        if (result.success) onSuccess?.invoke()
                    }
                }, "ReaMicroTrxsLogin").start()
            }
            saveTokenButton.setOnClickListener {
                if (!saveOnlineSourceDownloadPolicy(activity, source, policyInputs)) return@setOnClickListener
                val result = OnlineSourceAuth.saveSourceVariable(
                    activity.applicationContext,
                    source,
                    tokenInput.text?.toString().orEmpty(),
                )
                status.text = result.message
            }
            inviteButton.setOnClickListener {
                val inviteCode = inviteInput.text?.toString().orEmpty()
                runAuthTask(inviteButton, {
                    OnlineSourceAuth.registerByInvite(
                        activity.applicationContext,
                        source,
                        inviteCode,
                    )
                }) {
                    tokenInput.setText(OnlineSourceAuth.sourceVariable(activity.applicationContext, source))
                    tokenInput.setSelection(tokenInput.text?.length ?: 0)
                }
            }
            testButton.setOnClickListener {
                val saved = OnlineSourceAuth.saveSourceVariable(
                    activity.applicationContext,
                    source,
                    tokenInput.text?.toString().orEmpty(),
                )
                if (!saved.success) {
                    status.text = saved.message
                    return@setOnClickListener
                }
                runAuthTask(testButton, { OnlineSourceAuth.testSourceVariable(activity.applicationContext, source) })
            }
            accountButton.setOnClickListener {
                val token = tokenInput.text?.toString().orEmpty().trim()
                val saved = OnlineSourceAuth.saveSourceVariable(activity.applicationContext, source, token)
                if (!saved.success) {
                    status.text = saved.message
                    return@setOnClickListener
                }
                val url = OnlineSourceTrxsCompat.accountManagementUrl(source, token).orEmpty()
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { status.text = "无法打开账号管理页" }
            }
            val finishButton = settingsDialogButton(activity, "完成", colors)
            val deleteButton = settingsDialogButton(activity, "删除", colors, SettingsDialogButtonRole.Destructive)
            val cancelButton = settingsDialogButton(
                activity,
                if (enableFlow) "跳过启用" else "取消",
                colors,
                SettingsDialogButtonRole.Neutral,
            )
            card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, finishButton, cancelButton)))
            finishButton.setOnClickListener {
                if (!saveOnlineSourceDownloadPolicy(activity, source, policyInputs)) return@setOnClickListener
                val token = tokenInput.text?.toString().orEmpty().trim()
                if (token.isNotBlank()) {
                    val saved = OnlineSourceAuth.saveSourceVariable(activity.applicationContext, source, token)
                    if (!saved.success) {
                        status.text = saved.message
                        return@setOnClickListener
                    }
                }
                resolve(true)
                dialog.dismiss()
            }
            cancelButton.setOnClickListener {
                resolve(enableFlow)
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                confirmOnlineSourceDeletion(activity, source) {
                    resolve(false)
                    dialog.dismiss()
                }
            }
            dialog.setOnCancelListener { resolve(false) }
            dialog.setOnDismissListener { operation.close(); if (!resolved) resolve(false) }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            tokenInput.requestFocus()
        }.onFailure {
            activeOperation?.close()
            HookRuntime.log("$LOG_PREFIX failed to open trxs source login: ${it.stackTraceToString()}")
            onResult(false)
        }
    }
}

internal fun ReaMicroSettingsHook.openOnlineSourceLoginInfoDialog(
    activity: Activity,
    source: OnlineSourceEntry,
    enableFlow: Boolean,
    onResult: (Boolean) -> Unit,
) {
    activity.runOnModuleUiThread {
        runCatching {
            val fields = OnlineSourceAuth.loginFields(source)
            val savedValues = OnlineSourceAuth.loginInfo(activity.applicationContext, source)
            val colors = SettingsDialogColors(activity)
            val dialog = settingsDialog(activity)
            val card = settingsDialogCard(activity, colors)
            val fieldNames = fields.joinToString("、") { it.name }
            card.addView(settingsDialogTitle(activity, "${source.name} 登录", colors))
            card.addView(
                settingsDialogHint(
                    activity,
                    "请输入$fieldNames。登录信息和下载配置仅保存在本机。",
                    colors,
                ),
            )
            val inputs = fields.associateWith { field ->
                settingsDialogInput(activity, field.name, singleLine = true, colors = colors).apply {
                    inputType = if (field.isSecret) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    }
                    setText(savedValues[field.name].orEmpty().ifBlank { field.defaultValue })
                    setSelection(text?.length ?: 0)
                    card.addView(this)
                }
            }
            OnlineSourceAuth.browserLoginUrl(source).takeIf { it.isNotBlank() }?.let { browserUrl ->
                card.addView(
                    settingsDialogChoiceRow(activity, "打开登录/注册页面", colors) {
                        runCatching {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
                        }.onFailure {
                            Toast.makeText(activity, "无法打开登录/注册页面", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            val policyInputs = addOnlineSourceDownloadPolicyInputs(activity, source, card, colors)
            var resolved = false
            fun resolve(value: Boolean) {
                if (resolved) return
                resolved = true
                onResult(value)
            }
            val saveButton = settingsDialogButton(activity, "保存", colors)
            val deleteButton = settingsDialogButton(
                activity,
                "删除",
                colors,
                SettingsDialogButtonRole.Destructive,
            )
            val cancelButton = settingsDialogButton(
                activity,
                if (enableFlow) "跳过启用" else "取消",
                colors,
                SettingsDialogButtonRole.Neutral,
            )
            card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, saveButton, cancelButton)))
            saveButton.setOnClickListener {
                val values = inputs.entries.associate { (field, input) ->
                    field.name to input.text?.toString().orEmpty()
                }
                if (!saveOnlineSourceDownloadPolicy(activity, source, policyInputs)) {
                    return@setOnClickListener
                }
                val result = OnlineSourceAuth.saveLoginInfo(activity.applicationContext, source, values)
                Toast.makeText(activity, result.message, Toast.LENGTH_SHORT).show()
                if (result.success) {
                    resolve(true)
                    dialog.dismiss()
                }
            }
            cancelButton.setOnClickListener {
                resolve(enableFlow)
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                confirmOnlineSourceDeletion(activity, source) {
                    resolve(false)
                    dialog.dismiss()
                }
            }
            dialog.setOnCancelListener { resolve(false) }
            dialog.setOnDismissListener { if (!resolved) resolve(false) }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            inputs.values.firstOrNull()?.requestFocus()
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX failed to open online source login info dialog: ${it.stackTraceToString()}")
            onResult(false)
        }
    }
}

internal fun ReaMicroSettingsHook.openOnlineSourceCredentialDialog(
    activity: Activity,
    source: OnlineSourceEntry,
    enableFlow: Boolean,
    onResult: (Boolean) -> Unit,
) {
    activity.runOnModuleUiThread {
        var activeOperation: OnlineSourceTask? = null
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = settingsDialog(activity)
            val operation = OnlineSourceTask(source).also { activeOperation = it }
            val card = settingsDialogCard(activity, colors)
            val savedCredentials = OnlineSourceAuth.savedCredentials(activity.applicationContext, source)
            val userInput = settingsDialogInput(activity, "账号", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(savedCredentials.first)
            }
            val passwordInput = settingsDialogInput(activity, "密码", singleLine = true, colors = colors).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(savedCredentials.second)
            }
            card.addView(settingsDialogTitle(activity, "${source.name} 登录", colors))
            card.addView(
                settingsDialogHint(
                    activity,
                    "请输入账号密码；下载限速和每日章节限额均可留空。",
                    colors,
                ),
            )
            card.addView(userInput)
            card.addView(passwordInput)
            val policyInputs = addOnlineSourceDownloadPolicyInputs(activity, source, card, colors)
            var resolved = false
            fun resolve(value: Boolean) {
                if (resolved) return
                resolved = true
                onResult(value)
            }
            val loginButton = settingsDialogButton(activity, "登录并保存", colors)
            val deleteButton = settingsDialogButton(
                activity,
                "删除",
                colors,
                SettingsDialogButtonRole.Destructive,
            )
            val cancelButton = settingsDialogButton(
                activity,
                if (enableFlow) "跳过启用" else "取消",
                colors,
                SettingsDialogButtonRole.Neutral,
            )
            card.addView(settingsDialogButtonRow(activity, listOf(deleteButton, loginButton, cancelButton)))
            loginButton.setOnClickListener {
                val username = userInput.text?.toString().orEmpty()
                val password = passwordInput.text?.toString().orEmpty()
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(activity, "请输入账号和密码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!saveOnlineSourceDownloadPolicy(activity, source, policyInputs)) {
                    return@setOnClickListener
                }
                loginButton.isEnabled = false
                Toast.makeText(activity, "正在登录 ${source.name}", Toast.LENGTH_SHORT).show()
                com.reamicro.fix.reload.ModuleResources.thread({
                    val result = runCatching { operation.run {
                        OnlineSourceAuth.login(activity.applicationContext, source, username, password)
                    } }.getOrElse {
                        com.reamicro.fix.online.OnlineSourceAuthResult(false, it.message ?: "登录失败，请重试")
                    }
                    activity.runOnModuleUiThread {
                        if (!dialog.isShowing) return@runOnModuleUiThread
                        loginButton.isEnabled = true
                        if (!operation.isCurrent) {
                            Toast.makeText(activity, "在线源已变化，请重新打开", Toast.LENGTH_SHORT).show()
                            return@runOnModuleUiThread
                        }
                        Toast.makeText(activity, result.message, Toast.LENGTH_SHORT).show()
                        if (result.success) {
                            resolve(true)
                            dialog.dismiss()
                        }
                    }
                }, "ReaMicroOnlineSourceLogin").start()
            }
            cancelButton.setOnClickListener {
                resolve(enableFlow)
                dialog.dismiss()
            }
            deleteButton.setOnClickListener {
                confirmOnlineSourceDeletion(activity, source) {
                    resolve(false)
                    dialog.dismiss()
                }
            }
            dialog.setOnCancelListener { resolve(false) }
            dialog.setOnDismissListener { operation.close(); if (!resolved) resolve(false) }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            userInput.requestFocus()
        }.onFailure {
            activeOperation?.close()
            HookRuntime.log("$LOG_PREFIX failed to open online source credential dialog: ${it.stackTraceToString()}")
            onResult(false)
        }
    }
}

internal fun ReaMicroSettingsHook.addOnlineSourceDownloadPolicyInputs(
    activity: Activity,
    source: OnlineSourceEntry,
    container: LinearLayout,
    colors: SettingsDialogColors,
): OnlineSourcePolicyInputs {
    container.addView(settingsDialogStatus(activity, "下载配置（可选）", colors))
    val requestsPerSecondInput = settingsDialogInput(
        activity,
        "每秒请求次数（留空使用源内置限速）",
        singleLine = true,
        colors = colors,
    ).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(source.configuredRequestsPerSecond?.toString().orEmpty())
    }
    val dailyChapterLimitInput = settingsDialogInput(
        activity,
        "每日章节限额（留空或 0 表示不限额）",
        singleLine = true,
        colors = colors,
    ).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(source.dailyChapterLimit?.toString().orEmpty())
    }
    fun policySwitch(title: String, checked: Boolean): Switch =
        Switch(activity).apply {
            text = title
            textSize = 14f
            setTextColor(colors.title)
            isChecked = checked
            showText = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = settingsDp(activity, 48)
            setPadding(
                settingsDp(activity, 12),
                settingsDp(activity, 6),
                settingsDp(activity, 8),
                settingsDp(activity, 6),
            )
            background = settingsRoundedRect(colors.field, settingsDp(activity, 8), colors.border)
            thumbTintList = onlineSourcePolicySwitchThumbColors(colors)
            trackTintList = onlineSourcePolicySwitchTrackColors(colors)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(activity, 8) }
        }
    val preferOnDemandLoading = policySwitch(
        "逐章加载（首次下载前 3 章）",
        source.preferOnDemandLoading,
    )
    container.addView(requestsPerSecondInput)
    container.addView(dailyChapterLimitInput)
    container.addView(preferOnDemandLoading)

    return OnlineSourcePolicyInputs(
        requestsPerSecond = requestsPerSecondInput,
        dailyChapterLimit = dailyChapterLimitInput,
        preferOnDemandLoading = preferOnDemandLoading,
        paragraphCommentsEnabled = null,
    )
}

internal fun ReaMicroSettingsHook.onlineSourcePolicySwitchThumbColors(colors: SettingsDialogColors): ColorStateList =
    ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(colors.primary, colors.body),
    )

internal fun ReaMicroSettingsHook.onlineSourcePolicySwitchTrackColors(colors: SettingsDialogColors): ColorStateList =
    ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(
            settingsColorWithAlpha(colors.primary, 0.42f),
            settingsColorWithAlpha(colors.body, 0.24f),
        ),
    )

internal fun ReaMicroSettingsHook.saveOnlineSourceDownloadPolicy(
    activity: Activity,
    source: OnlineSourceEntry,
    inputs: OnlineSourcePolicyInputs,
): Boolean = runCatching {
    check(settings.snapshot().moduleEnabled) { "补全计划已关闭" }
    OnlineSourceStore.withCurrent(source) { OnlineSourceDownloadPolicyStore.save(
        context = activity.applicationContext,
        sourceId = source.id,
        requestsPerSecond = inputs.requestsPerSecond.text?.toString().orEmpty(),
        dailyChapterLimit = inputs.dailyChapterLimit.text?.toString().orEmpty(),
        preferOnDemandLoading = inputs.preferOnDemandLoading.isChecked,
        paragraphCommentsEnabled = inputs.paragraphCommentsEnabled?.isChecked ?: false,
    ) }
    com.reamicro.fix.discover.DiscoverState.invalidate()
}.onFailure { error ->
    Toast.makeText(activity, error.message ?: "下载配置不正确", Toast.LENGTH_SHORT).show()
}.isSuccess

internal fun ReaMicroSettingsHook.setAssociationSearchSourceEnabled(
    groupId: String,
    enabled: Boolean,
    updateChecked: (Boolean) -> Unit,
): Boolean {
    if (groupId != BookSource.YouShu.id) {
        settings.setAssociationSearchSourceEnabled(groupId, enabled)
        return enabled
    }
    val previous = settings.snapshot().isSearchSourceGroupEnabled(groupId)
    val request = YouShuSourceRequests.begin(this)
    fun current(): Boolean = YouShuSourceRequests.current(this, request)
    fun openLogin() {
        if (!current()) return
        settings.setAssociationSearchSourceEnabled(groupId, false)
        updateChecked(false)
        openYouShuLoginPage { loggedInAfterDialog ->
            if (!current()) return@openYouShuLoginPage
            if (loggedInAfterDialog) applyYouShuSourceEnabled(groupId, updateChecked)
            else {
                settings.setAssociationSearchSourceEnabled(groupId, false)
                updateChecked(false)
            }
            YouShuSourceRequests.complete(this, request)
        }
    }
    if (!enabled) {
        settings.setAssociationSearchSourceEnabled(groupId, false)
        YouShuSourceRequests.complete(this, request)
        HostYouShuLoginDialog.dismissActive()
        return false
    }
    if (!current()) { YouShuSourceRequests.complete(this, request); return previous }
    val cookieHeader = YouShuLoginCookies.cookieHeader()
    if (!YouShuLoginCookies.hasLoginCookie(cookieHeader)) {
        openLogin()
        return false
    }

    verifyYouShuLoginAsync(
        attempts = YOUSHU_FAST_LOGIN_VERIFY_ATTEMPTS,
        allowWebView = false,
        cancellation = request.cancellation,
        isCurrent = ::current,
    ) { status ->
        if (!current()) return@verifyYouShuLoginAsync
        when (status) {
            YouShuLoginStatus.VALID -> {
                applyYouShuSourceEnabled(groupId, updateChecked)
                YouShuSourceRequests.complete(this, request)
            }
            YouShuLoginStatus.EXPIRED -> openLogin()
            YouShuLoginStatus.UNKNOWN -> {
                settings.setAssociationSearchSourceEnabled(groupId, previous)
                updateChecked(previous)
                activityProvider()?.let { activity ->
                    Toast.makeText(activity, "暂时无法确认优书网登录状态，请稍后重试", Toast.LENGTH_SHORT).show()
                }
                YouShuSourceRequests.complete(this, request)
            }
        }
    }
    return previous
}

internal fun ReaMicroSettingsHook.visibleAssociationSearchSourceGroups() =
    AssociationSearchProviderRegistry.searchSourceGroups(activityProvider()?.applicationContext)

internal fun ReaMicroSettingsHook.importExternalOnlineSource(activity: Activity, bytes: ByteArray, displayName: String) {
    importOnlineSourceAsync(activity, "external:$displayName:${bytes.size}") {
        OnlineSourceStore.importBytes(activity.applicationContext, bytes, displayName, "import:external")
    }
}

internal fun ReaMicroSettingsHook.onlineSourceVersionState(): Any {
    onlineSourceVersionUiState?.let { return it }
    return mutableState(0).also { onlineSourceVersionUiState = it }
}

internal fun ReaMicroSettingsHook.onlineSourceVersionValue(): Int =
    (onlineSourceVersionState().method0("getValue") as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpOnlineSourceVersion() {
    val state = onlineSourceVersionState()
    val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
    setStateValue(state, value + 1)
}

@Volatile
private var discoverRefreshRegistered = false

internal fun ReaMicroSettingsHook.discoverVersionState(): Any {
    discoverVersionUiState?.let { return it }
    return mutableState(0).also { discoverVersionUiState = it }
}

internal fun ReaMicroSettingsHook.discoverVersionValue(): Int {
    val state = discoverVersionState()

    if (!discoverRefreshRegistered) {
        discoverRefreshRegistered = true
        val mainHandler = com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper())
        com.reamicro.fix.discover.DiscoverState.onChanged = {
            mainHandler.post { runCatching { signalDiscoverRefresh() } }
        }
    }

    val composeValue = (state.method0("getValue") as? Number)?.toInt() ?: 0

    discoverObservedVersion = com.reamicro.fix.discover.DiscoverState.version
    return composeValue
}

internal fun ReaMicroSettingsHook.signalDiscoverRefresh() {
    val mainLooper = runCatching { android.os.Looper.getMainLooper() }.getOrNull() ?: return
    @Suppress("DEPRECATION")
    if (android.os.Looper.myLooper() != mainLooper) return
    runCatching {
        val state = discoverVersionState()
        val current = (state.method0("getValue") as? Number)?.toInt() ?: 0
        setStateValue(state, current + 1)
    }.onFailure {
        HookRuntime.logAlways("[ReaMicro] discover refresh failed: ${it.javaClass.simpleName}: ${it.message}")
    }
}
