package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
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
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceTrxsCompat
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

// 在线源与关联补全源的设置簇。
//
// 书源导入、启用停用、登录授权（含 trxs 类源的 JS 登录适配）。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
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
    if (source.supportsParagraphComments) {
        tags += if (source.paragraphCommentsEnabled) "段评已开" else "段评已关"
    }
    return tags.joinToString(" · ")
}

internal fun ReaMicroSettingsHook.listOnlineSources(): List<OnlineSourceEntry> =
    OnlineSourceStore.list(activityProvider()?.applicationContext)

internal fun ReaMicroSettingsHook.openOnlineSourceManagementDialog(source: OnlineSourceEntry) {
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
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            card.addView(settingsDialogTitle(activity, "${source.name} 配置", colors))
            card.addView(settingsDialogHint(activity, "下载方式和段评设置按书源独立保存。", colors))
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
            XposedBridge.log("$LOG_PREFIX online source config dialog failed: ${it.stackTraceToString()}")
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
    val dialog = Dialog(activity)
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
        settings.setOnlineSourceEnabled(source.id, false)
        val removed = OnlineSourceStore.remove(activity.applicationContext, source.id)
        if (removed) {
            OnlineSourceAuth.clearSourceData(activity.applicationContext, source.id)
            OnlineSourceDownloadPolicyStore.clear(activity.applicationContext, source.id)
            bumpOnlineSourceVersion()
            showToast("已移除在线源：${source.name}")
            dialog.dismiss()
            onRemoved()
        } else {
            showToast("在线源移除失败：${source.name}")
        }
    }
    cancelButton.setOnClickListener { dialog.dismiss() }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
}

internal fun ReaMicroSettingsHook.renderOnlineSourceSwitch(source: OnlineSourceEntry, composer: Any) {
    val targetChecked = settings.isOnlineSourceEnabled(source.id)
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
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

internal fun ReaMicroSettingsHook.setOnlineSourceEnabled(
    source: OnlineSourceEntry,
    enabled: Boolean,
    updateChecked: (Boolean) -> Unit,
): Boolean {
    if (!enabled) {
        settings.setOnlineSourceEnabled(source.id, false)
        return false
    }
    val context = activityProvider()?.applicationContext
    if (!source.hasLoginConfig || OnlineSourceAuth.hasSavedLogin(context, source)) {
        settings.setOnlineSourceEnabled(source.id, true)
        return true
    }
    settings.setOnlineSourceEnabled(source.id, false)
    openOnlineSourceLoginDialog(source) { confirmed ->
        if (confirmed) {
            settings.setOnlineSourceEnabled(source.id, true)
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
    val token = "online_clipboard:$url"
    val now = System.currentTimeMillis()
    if (token == lastOnlineSourceImportToken && now - lastOnlineSourceImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS) {
        return
    }
    lastOnlineSourceImportToken = token
    lastOnlineSourceImportAtMs = now
    Thread {
        runCatching { OnlineSourceStore.importFromUrl(activity.applicationContext, url) }
            .onSuccess { source ->
                activity.runOnUiThread {
                    bumpOnlineSourceVersion()
                    showToast("\u5df2\u6dfb\u52a0\u5728\u7ebf\u6e90\uff1a${source.name}")
                }
            }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX online source url import failed: ${it.stackTraceToString()}")
                activity.runOnUiThread {
                    showToast(it.message ?: "\u5bfc\u5165\u5728\u7ebf\u6e90\u5931\u8d25")
                }
            }
    }.apply {
        name = "ReaMicroOnlineSourceUrlImport"
        isDaemon = true
        start()
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
        XposedBridge.log("$LOG_PREFIX failed to open online source picker: ${it.stackTraceToString()}")
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
    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: run {
            showToast("\u65e0\u6cd5\u8bfb\u53d6\u6e90\u6587\u4ef6")
            return
        }
    val displayName = queryDisplayName(activity, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "online_source.json"
    importOnlineSourceAsync(activity, "uri:$uri") {
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
    Thread {
        runCatching { importer() }
            .onSuccess { source ->
                activity.runOnUiThread {
                    bumpOnlineSourceVersion()
                    showToast("已添加在线源：${source.name}")
                }
            }
            .onFailure {
                XposedBridge.log("$LOG_PREFIX online source import failed: ${it.stackTraceToString()}")
                activity.runOnUiThread {
                    showToast(it.message ?: "导入在线源失败")
                }
            }
    }.apply {
        name = "ReaMicroOnlineSourceImport"
        isDaemon = true
        start()
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
    activity.runOnUiThread {
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
            val dialog = Dialog(activity)
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
            XposedBridge.log("$LOG_PREFIX failed to open online source login: ${it.stackTraceToString()}")
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
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            val savedToken = OnlineSourceAuth.sourceVariable(activity.applicationContext, source)
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
            fun resolve(value: Boolean) {
                if (resolved) return
                resolved = true
                onResult(value)
            }
            fun runAuthTask(button: TextView, task: () -> com.reamicro.fix.online.OnlineSourceAuthResult, onSuccess: (() -> Unit)? = null) {
                button.isEnabled = false
                Thread({
                    val result = task()
                    activity.runOnUiThread {
                        button.isEnabled = true
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
                runAuthTask(inviteButton, {
                    OnlineSourceAuth.registerByInvite(
                        activity.applicationContext,
                        source,
                        inviteInput.text?.toString().orEmpty(),
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
            dialog.setOnDismissListener { if (!resolved) resolve(false) }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            tokenInput.requestFocus()
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open trxs source login: ${it.stackTraceToString()}")
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
    activity.runOnUiThread {
        runCatching {
            val fields = OnlineSourceAuth.loginFields(source)
            val savedValues = OnlineSourceAuth.loginInfo(activity.applicationContext, source)
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
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
            XposedBridge.log("$LOG_PREFIX failed to open online source login info dialog: ${it.stackTraceToString()}")
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
    activity.runOnUiThread {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
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
                Thread({
                    val result = OnlineSourceAuth.login(
                        activity.applicationContext,
                        source,
                        username,
                        password,
                    )
                    activity.runOnUiThread {
                        loginButton.isEnabled = true
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
            dialog.setOnDismissListener { if (!resolved) resolve(false) }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            userInput.requestFocus()
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open online source credential dialog: ${it.stackTraceToString()}")
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
    val paragraphCommentsEnabled = if (source.supportsParagraphComments) {
        policySwitch(
            "启用段评（下载时获取段评数据）",
            source.paragraphCommentsEnabled,
        )
    } else {
        null
    }
    container.addView(requestsPerSecondInput)
    container.addView(dailyChapterLimitInput)
    container.addView(preferOnDemandLoading)
    paragraphCommentsEnabled?.let(container::addView)
    return OnlineSourcePolicyInputs(
        requestsPerSecond = requestsPerSecondInput,
        dailyChapterLimit = dailyChapterLimitInput,
        preferOnDemandLoading = preferOnDemandLoading,
        paragraphCommentsEnabled = paragraphCommentsEnabled,
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
    OnlineSourceDownloadPolicyStore.save(
        context = activity.applicationContext,
        sourceId = source.id,
        requestsPerSecond = inputs.requestsPerSecond.text?.toString().orEmpty(),
        dailyChapterLimit = inputs.dailyChapterLimit.text?.toString().orEmpty(),
        preferOnDemandLoading = inputs.preferOnDemandLoading.isChecked,
        paragraphCommentsEnabled = inputs.paragraphCommentsEnabled?.isChecked ?: false,
    )
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
    if (!enabled) {
        settings.setAssociationSearchSourceEnabled(groupId, false)
        return false
    }

    settings.setAssociationSearchSourceEnabled(groupId, false)
    val cookieHeader = YouShuLoginCookies.cookieHeader()
    if (!YouShuLoginCookies.hasLoginCookie(cookieHeader)) {
        openYouShuLoginPage { loggedInAfterDialog ->
            if (loggedInAfterDialog) {
                applyYouShuSourceEnabled(groupId, updateChecked)
            } else {
                settings.setAssociationSearchSourceEnabled(groupId, false)
                updateChecked(false)
            }
        }
        return false
    }

    verifyYouShuLoginAsync(
        attempts = YOUSHU_FAST_LOGIN_VERIFY_ATTEMPTS,
        allowWebView = false,
    ) { loggedIn ->
        if (loggedIn) {
            applyYouShuSourceEnabled(groupId, updateChecked)
        } else {
            openYouShuLoginPage { loggedInAfterDialog ->
                if (loggedInAfterDialog) {
                    applyYouShuSourceEnabled(groupId, updateChecked)
                } else {
                    settings.setAssociationSearchSourceEnabled(groupId, false)
                    updateChecked(false)
                }
            }
        }
    }
    return false
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
    state.javaClass.methods
        .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
        ?.invoke(state, value + 1)
}
