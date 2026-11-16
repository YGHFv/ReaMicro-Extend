package com.reamicro.fix.hook

import android.app.Dialog
import android.widget.LinearLayout
import android.widget.Toast
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HostRestart
import com.reamicro.fix.core.HostSettingsFeatures
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.hook.settings.InjectedRoute
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** The host owns the settings list and its Compose objects; this bridge adds only one lazy item. */
internal class SemanticSettingsUiBridge(private val hook: ReaMicroSettingsHook) {
    private var dialog = WeakReference<Dialog>(null)

    init {
        ModuleResources.cleanup {
            dialog.get()?.dismiss()
            dialog.clear()
        }
    }

    fun install() {
        HookInstallReport.install("ReaMicroSettingsHook", "semanticSettingsEntry") {
            val symbols = requireNotNull(HostSettingsFeatures.current) { "Settings entry bindings unavailable" }
            val reader = requireNotNull(HostReaderFeatures.current) { "Native settings controls unavailable" }
            val content = function(reader, symbols.item.parameterTypes[2]) { args ->
                val composer = args?.getOrNull(1)?.takeIf(reader.composerClass::isInstance) ?: return@function
                runCatching {
                    reader["startGroup"].invoke(composer, 0x524D5354)
                    try {
                        val onClick = function(reader, reader["button"].parameterTypes[2]) { openSettings() }
                        reader["button"].invoke(null, "补全计划", false, onClick, composer, 0)
                    } finally {
                        reader["endGroup"].invoke(composer, false)
                    }
                }.onFailure {
                    HookRuntime.logError("ReaMicro settings entry rendering failed", it)
                }
            }
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                SettingsEntryPlacement.install(symbols, handles) { list ->
                    symbols.item.invoke(list, "reamicro.semantic.settings", null, content)
                }
                listOf(symbols.root, symbols.body, symbols.builder).forEach(HookRuntime::deoptimize)
            } catch (error: Throwable) {
                handles.asReversed().forEach { it.unhook() }
                throw error
            }
        }
    }

    private fun function(reader: HostReaderFeatures.Symbols, type: Class<*>, action: (Array<out Any?>?) -> Unit): Any =
        Proxy.newProxyInstance(reader.loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ReaMicroSettingsEntry"
                else -> {
                    ModuleResources.lease()?.use { action(args) }
                    reader.unit
                }
            }
        }

    /** Uses the existing platform dialog controls while the full host Compose screen ABI is unavailable. */
    internal fun openSettings(): Unit = page("补全计划") { card, colors, current ->
        card.addView(hook.settingsDialogStatus(current.context,
            "设置保存到当前宿主；可用功能由 DexKit 扫描确认。", colors))
        action(card, colors, "阅读") { readerSettings() }
        action(card, colors, "书架与关联") { librarySettings() }
        action(card, colors, "同步与下载") { cloudSettings() }
        action(card, colors, "账户与编辑") { accountSettings() }
        action(card, colors, "AI 与词典") { aiSettings() }
        action(card, colors, "DexKit 扫描") { hook.openDexKitScanDialog() }
        action(card, colors, "日志与其他设置") { diagnosticSettings() }
    }

    private fun readerSettings(): Unit = page("阅读") { card, colors, _ ->
        toggle(card, colors, "长按功能", { it.readerLongPressEnabled }, hook.settings::setReaderLongPressEnabled)
        toggle(card, colors, "自动翻页", { it.readerAutoPageEnabled }, hook.settings::setReaderAutoPageEnabled)
        toggle(card, colors, "编辑覆盖", { it.readerEditOverwriteEnabled }, hook.settings::setReaderEditOverwriteEnabled)
        toggle(card, colors, "覆盖检查", { it.readerOverwriteCheckEnabled }, hook.settings::setReaderOverwriteCheckEnabled)
        toggle(card, colors, "简洁选中菜单", { it.readerCompactSelectionMenuEnabled }, hook.settings::setReaderCompactSelectionMenuEnabled)
        toggle(card, colors, "词典释义", { it.readerDictionaryEnabled }, hook.settings::setReaderDictionaryEnabled)
        toggle(card, colors, "选中高亮", { it.readerSelectionHighlightEnabled }, hook.settings::setReaderSelectionHighlightEnabled)
        toggle(card, colors, "对话高亮", { it.readerDialogueHighlightEnabled }, hook.settings::setReaderDialogueHighlightEnabled)
        toggle(card, colors, "内嵌搜索", { it.inlineSearchIconEnabled }, hook.settings::setInlineSearchIconEnabled)
        toggle(card, colors, "字体设置", { it.fontSettingsEnabled }, hook.settings::setFontSettingsEnabled)
    }

    private fun librarySettings(): Unit = page("书架与关联") { card, colors, _ ->
        toggle(card, colors, "隐藏书架同步", { it.shelfHideSync }, hook.settings::setShelfHideSync)
        toggle(card, colors, "书架样式按钮", { it.shelfStyleButton }, hook.settings::setShelfStyleButton)
        toggle(card, colors, "取消封面淡入", { it.shelfDisableCoverFade }, hook.settings::setShelfDisableCoverFade)
        toggle(card, colors, "批量更新", { it.shelfBatchUpdate }, hook.settings::setShelfBatchUpdate)
        toggle(card, colors, "自动更新", { it.shelfAutoUpdate }, hook.settings::setShelfAutoUpdate)
        toggle(card, colors, "只更新当前分组", { it.shelfCurrentOnly }, hook.settings::setShelfCurrentOnly)
        toggle(card, colors, "跳过已完结书籍", { it.shelfSkipCompleted }, hook.settings::setShelfSkipCompleted)
        toggle(card, colors, "解除关联", { it.associationUnlinkEnabled }, hook.settings::setAssociationUnlinkEnabled)
        toggle(card, colors, "封面修复", { it.associationCoverFixEnabled }, hook.settings::setAssociationCoverFixEnabled)
    }

    private fun cloudSettings(): Unit = page("同步与下载") { card, colors, _ ->
        toggle(card, colors, "WebDAV", { it.cloudWebDavEnabled }, hook.settings::setCloudWebDavEnabled)
        toggle(card, colors, "同步扩展", { it.cloudSyncExtensionEnabled }, hook.settings::setCloudSyncExtensionEnabled)
        toggle(card, colors, "本地书库", { it.cloudLocalLibraryEnabled }, hook.settings::setCloudLocalLibraryEnabled)
        toggle(card, colors, "扩展显示", { it.cloudExtendedDisplayEnabled }, hook.settings::setCloudExtendedDisplayEnabled)
        toggle(card, colors, "下载取消", { it.cloudDownloadCancelEnabled }, hook.settings::setCloudDownloadCancelEnabled)
    }

    private fun rotationSettings(): Unit = page("屏幕方向") { card, colors, _ ->
        toggle(card, colors, "自动旋转", { it.rotation.autoEnabled }) {
            hook.settings.setRotationAutoEnabled(it)
            rotationSettings()
        }
        toggle(card, colors, "锁定竖屏", { it.rotation.portraitLockEnabled }) {
            hook.settings.setRotationPortraitLockEnabled(it)
            rotationSettings()
        }
        toggle(card, colors, "锁定横屏", { it.rotation.landscapeLockEnabled }) {
            hook.settings.setRotationLandscapeLockEnabled(it)
            rotationSettings()
        }
        toggle(card, colors, "反向旋转", { it.rotationReverseEnabled }, hook.settings::setRotationReverseEnabled)
    }

    private fun accountSettings(): Unit = page("账户与编辑") { card, colors, _ ->
        toggle(card, colors, "账号数据导出", { it.accountExportEnabled }, hook.settings::setAccountExportEnabled)
        toggle(card, colors, "文件编辑", { it.editFileEnabled }, hook.settings::setEditFileEnabled)
    }

    private fun aiSettings(): Unit = page("AI 与词典") { card, colors, _ ->
        val context = hook.activityProvider()?.applicationContext
        // Existing editors bump this state after saving; provide the verified host factory.
        if (hook.aiApiVersionUiState == null) {
            hook.aiApiVersionUiState = requireNotNull(HostReaderFeatures.current)["state"].invoke(null, 0)
        }
        action(card, colors, "新增 AI API 配置") { hook.openAiApiConfigDialog() }
        AiApiStore.list(context).forEach { config ->
            action(card, colors, "编辑 API · ${config.displayName}") { hook.openAiApiConfigDialog(config) }
            action(card, colors, "用于词典 · ${config.displayName}") {
                check(AiApiStore.setDictionaryApiId(context, config.id)) { "保存失败" }
                toast("已设为词典 API")
            }
        }
        action(card, colors, "新增词典预设") { hook.openDictionaryPresetDialog() }
        AiApiStore.dictionaryPresets(context).forEach { preset ->
            action(card, colors, "使用预设 · ${preset.name}") {
                check(AiApiStore.setDictionaryPresetId(context, preset.id)) { "保存失败" }
                toast("已切换词典预设")
            }
            if (!preset.builtIn) action(card, colors, "编辑预设 · ${preset.name}") { hook.openDictionaryPresetDialog(preset) }
        }
        action(card, colors, "刷新列表") { aiSettings() }
    }

    private fun diagnosticSettings(): Unit = page("日志与其他设置") { card, colors, _ ->
        action(card, colors, "自动任务") { hook.openNestedInjectedRoute(InjectedRoute.LocalAutomationSettings) }
        action(card, colors, "旋转补全") { rotationSettings() }
        toggle(card, colors, "缓存清理", { it.accountCacheCleanupEnabled }, hook.settings::setAccountCacheCleanupEnabled)
        toggle(card, colors, "简洁日志", { it.conciseLogEnabled }, hook.settings::setConciseLogEnabled)
        toggle(card, colors, "高亮日志", { it.readerHighlightPerformanceLogEnabled }, hook.settings::setReaderHighlightPerformanceLogEnabled)
        action(card, colors, "导出模块日志") { hook.exportModuleLog() }
        action(card, colors, "重启阅微") { hook.activityProvider()?.let(HostRestart::request) }
    }

    private fun page(title: String, content: (LinearLayout, ReaMicroSettingsHook.SettingsDialogColors, Dialog) -> Unit) {
        val activity = hook.activityProvider() ?: return
        activity.runOnUiThread {
            if (!ModuleResources.accepting || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            runCatching {
                dialog.get()?.dismiss()
                val current = hook.settingsDialog(activity)
                dialog = WeakReference(current)
                val colors = hook.SettingsDialogColors(activity)
                val card = hook.settingsDialogCard(activity, colors)
                card.addView(hook.settingsDialogTitle(activity, title, colors))
                content(card, colors, current)
                val isRoot = title == "补全计划"
                val close = hook.settingsDialogChoiceRow(activity, if (isRoot) "关闭" else "返回补全计划", colors) {
                    current.dismiss()
                    if (!isRoot) openSettings()
                }
                card.addView(close)
                current.setOnCancelListener { if (!isRoot) openSettings() }
                hook.showSettingsDialog(current, hook.settingsDialogScroll(activity, card), activity)
            }.onFailure {
                HookRuntime.logError("ReaMicro module settings dialog failed", it)
                toast("设置暂时无法打开，请查看模块日志")
            }
        }
    }

    private fun toggle(card: LinearLayout, colors: ReaMicroSettingsHook.SettingsDialogColors, title: String,
        read: (ModuleSettingsSnapshot) -> Boolean, write: (Boolean) -> Unit) {
        val row = hook.settingsDialogSwitchRow(card.context, title, read(hook.settings.snapshot()), colors)
        var updating = false
        row.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            val saved = runCatching { write(checked) }.onFailure {
                HookRuntime.logError("ReaMicro settings save failed: $title", it)
            }.isSuccess
            val actual = read(hook.settings.snapshot())
            updating = true
            row.isChecked = actual
            updating = false
            if (!saved || actual != checked) toast("保存失败，设置未更改")
        }
        card.addView(row)
    }

    private fun action(card: LinearLayout, colors: ReaMicroSettingsHook.SettingsDialogColors, title: String, run: () -> Unit) {
        card.addView(hook.settingsDialogChoiceRow(card.context, title, colors) {
            runCatching(run).onFailure {
                HookRuntime.logError("ReaMicro settings action failed: $title", it)
                toast("操作未完成，请查看模块日志")
            }
        })
    }

    private fun toast(message: String) {
        hook.activityProvider()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}
