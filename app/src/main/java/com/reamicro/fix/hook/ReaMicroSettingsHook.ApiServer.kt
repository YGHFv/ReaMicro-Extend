package com.reamicro.fix.hook

import android.app.Dialog
import android.text.InputType
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
