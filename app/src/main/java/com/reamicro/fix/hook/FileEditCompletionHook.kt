package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostCompositionBindings
import com.reamicro.fix.core.HostRuntimeBindings
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.util.Locale

class FileEditCompletionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    private val fontSettingsProvider: () -> FontSettingsSnapshot = { FontSettingsSnapshot() },
) {
    private val host get() = requireNotNull(HostBookUiBindings.metadata)
    private val ui get() = requireNotNull(HostUiBindings.current)
    private val nativeUi by lazy { HostNativeUi(classLoader) }
    private val injectingRow = ThreadLocal<Boolean>()

    fun install() {
        val symbols = host
        requireNotNull(HostUiBindings.current)
        requireNotNull(HostReaderFeatures.current)
        // Resolve the boxed End alignment before composition begins.
        symbols.endAlign
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            for (constructor in symbols.constructors("repository.")) handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null) ReaMicroBookMetadataSync.rememberBookshelfRepository(param.thisObject)
                }
            })
            for (role in listOf("title", "sync")) {
                val entry = symbols.method(role)
                handles += HookRuntime.hookMethod(entry, object : HookCallback() {
                    override fun before(param: Call) {
                        if (injectingRow.get() == true || !settingsProvider().canUseFileEdit) return
                        val book = param.args.getOrNull(1)?.takeIf(symbols.type("book")::isInstance) ?: return
                        val composerIndex = entry.parameterCount - 2
                        val composer = param.args.getOrNull(composerIndex)?.takeIf(symbols.type("composer")::isInstance) ?: return
                        val first = param.args.getOrNull(2)?.takeIf(symbols.type("function")::isInstance) ?: return
                        val second = if (role == "title") param.args.getOrNull(3)?.takeIf(symbols.type("function")::isInstance) ?: return else null
                        val lease = ModuleResources.lease() ?: return
                        lease.use {
                            injectingRow.set(true)
                            try {
                                renderCard(composer) { inner ->
                                    if (role == "title") renderMetadata(book, first, requireNotNull(second), inner)
                                    else renderEditorSync(book, first, inner)
                                }
                                param.result = null
                            } finally { injectingRow.remove() }
                        }
                    }
                })
                HookRuntime.deoptimize(entry)
            }
            handles += HookRuntime.hookMethod(symbols.method("activityResult"), object : HookCallback() {
                override fun after(param: Call) {
                    val activity = param.thisObject as? Activity ?: return
                    EpubBookPanel.dispatchActivityResult(activity, param.args[0] as Int, param.args[1] as Int, param.args[2] as? Intent)
                }
            })
            HookRuntime.deoptimize(symbols.method("activityResult"))
            ModuleResources.cleanup { injectingRow.remove() }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    private fun renderMetadata(book: Any, title: Any, author: Any, composer: Any) {
        renderRow("书名", bookString(book, "title"), "edit", composer, true) { invokeFunction0(title) }
        divider(composer)
        renderRow("作者", bookString(book, "author"), "edit", composer, true) { invokeFunction0(author) }
        val identifier = bookIdentifierRawText(book)
        if (identifier.isNotBlank()) {
            divider(composer)
            val display = if (identifier.length > 34) identifier.take(15) + "..." + identifier.takeLast(15) else identifier
            renderRow("标识", display, "copy", composer, true) { copyBookIdentifiers(book) }
        }
    }

    private fun renderEditorSync(book: Any, sync: Any, composer: Any) {
        renderRow("编辑", "图书结构", "next", composer) { openFileEditor(book) }
        divider(composer)
        val type = host.field("book.backupType").getInt(book)
        val storage = when (type) {
            host.number("backup.baidu") -> "baidu" to "百度网盘"
            host.number("backup.yun115") -> "yun115" to "115网盘"
            host.number("backup.aliyun") -> "aliyun" to "阿里云盘"
            else -> "local" to "本地存储"
        }
        renderRow("同步", formatFileSize(host.field("book.size").getLong(book)).replace(" ", ""), "next",
            composer, storage = storage) { invokeFunction0(sync) }
    }

    private fun renderCard(composer: Any, rows: (Any) -> Unit) {
        ui.instance("startGroup", composer, 0x524D4645)
        try {
            val colors = requireNotNull(ui.call("colorScheme", composer))
            val modifier = nativeUi.card(nativeUi.modifier(), ui.call("backgroundAuto", colors) as Long,
                ui.call("borderVariant", colors) as Long, nativeUi.dp(0.8))
            val content = ui.callback(ui.type("function3")) { rows(requireNotNull(it?.getOrNull(1))) }
            nativeUi.column(modifier, nativeUi.top(), nativeUi.start(), content, composer)
        } finally { ui.instance("endGroup", composer, false) }
    }

    private fun renderRow(label: String, value: String, icon: String, composer: Any, weightedValue: Boolean = false,
        storage: Pair<String, String>? = null, onClick: () -> Unit) {
        val click = ui.callback(host.type("function")) { onClick() }
        val modifier = nativeUi.padding(nativeUi.clickable(nativeUi.fillWidth(nativeUi.modifier()), click),
            nativeUi.dp(16), nativeUi.dp(15), nativeUi.dp(16), nativeUi.dp(15))
        val content = ui.callback(ui.type("function3")) { args ->
            val scope = requireNotNull(args?.getOrNull(0))
            val inner = requireNotNull(args?.getOrNull(1))
            val colors = requireNotNull(ui.call("colorScheme", inner))
            val typography = requireNotNull(ui.call("typography", inner))
            val grey = ui.call("onBackgroundVariant", colors) as Long
            val foreground = ui.field("color.onBackground").getLong(colors)
            val style = requireNotNull(ui.field("typography.labelLarge").get(typography))
            text(label, inner, grey, style)
            if (weightedValue) {
                space(12, inner)
                nativeUi.text(
                    text = value, composer = inner, color = foreground, style = style,
                    modifier = nativeUi.weight(scope, nativeUi.modifier()), textAlign = host.endAlign,
                    overflow = 2, maxLines = 1,
                    defaultMask = 131066 and (2 or 1024 or 4096 or 16384).inv(),
                )
            } else {
                nativeUi.spacer(nativeUi.weight(scope, nativeUi.modifier()), inner)
                text(value, inner, foreground, style)
            }
            storage?.let { (role, name) ->
                space(8, inner)
                val sized = nativeUi.height(nativeUi.width(nativeUi.modifier(), nativeUi.dp(18)), nativeUi.dp(18))
                host.call("image", host.call(role), null, sized, inner, 48, 120)
                space(4, inner)
                text(name, inner, foreground, style)
            }
            space(10, inner)
            if (icon == "next") {
                host.call("icon", host.call(icon), null, null,
                    ui.field("color.surfaceContainerHighest").getLong(colors), inner, 48, 4)
            } else {
                val sized = nativeUi.height(nativeUi.width(nativeUi.modifier(), nativeUi.dp(20)), nativeUi.dp(20))
                host.call("icon", host.call(icon), if (icon == "copy") "复制标识" else "编辑$label", sized, grey, inner, 0, 0)
            }
        }
        nativeUi.row(modifier, content, composer)
    }

    private fun text(value: String, composer: Any, color: Long, style: Any) {
        nativeUi.text(value, composer, color, style, defaultMask = 131066)
    }
    private fun space(width: Int, composer: Any) = nativeUi.spacer(nativeUi.width(nativeUi.modifier(), nativeUi.dp(width)), composer)
    private fun divider(composer: Any) = nativeUi.divider(nativeUi.padding(nativeUi.modifier(), nativeUi.dp(16), 0f, 0f, 0f), composer)
    private fun bookString(book: Any, role: String): String = host.field("book.$role").get(book) as? String ?: ""
    private fun bookIdentifierRawText(book: Any): String = BookIdentifierText.display(bookString(book, "uuid"))
    private fun invokeFunction0(callback: Any) { host.method("invoke").invoke(callback) }

    private fun copyBookIdentifiers(book: Any) {
        val activity = activityProvider() ?: return
        val text = bookIdentifierRawText(book)
        if (text.isBlank()) return
        val copy = {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("\u56fe\u4e66\u6807\u8bc6", text))
            Toast.makeText(activity, "\u5df2\u590d\u5236\u6807\u8bc6", Toast.LENGTH_SHORT).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) copy() else activity.runOnModuleUiThread { copy() }
    }

    // NavGraphScope is a lightweight host wrapper. Rebuild it from the active native
    // controller so deferred row clicks do not depend on an earlier screen hook.
    private fun detailNavigationScope(activity: Activity): Any? {
        val composition = requireNotNull(HostCompositionBindings.current)
        val controller = composition.field("navCache").get(null) ?: return null
        val metadata = host
        if (!metadata.type("navigationController").isInstance(controller) ||
            metadata.field("navigation.activity").get(controller) !== activity) return null
        val navigation = requireNotNull(HostRuntimeBindings.navigation)
        check(navigation.constructor.parameterTypes.toList() == listOf(metadata.type("navigationController")))
        return navigation.constructor.newInstance(controller)
    }

    private fun openFileEditor(book: Any) {
        val activity = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val open = open@{
            if (activityProvider() !== activity || activity.isFinishing || activity.isDestroyed ||
                !settingsProvider().canUseFileEdit) return@open
            runCatching {
                val hook = requireNotNull(ReaMicroSettingsHook.activeInstanceOrNull()) { "宿主导航尚未就绪" }
                val navScope = requireNotNull(detailNavigationScope(activity)) { "图书导航尚未就绪" }
                if (!hook.bookStructurePages.open(activity, book, navScope, settingsProvider, fontSettingsProvider)) {
                    Toast.makeText(activity, "账号或宿主导航尚未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                HookRuntime.logError("$LOG_PREFIX structure open failed", it)
                Toast.makeText(activity, "图书结构打开失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) open()
        else activity.runOnModuleUiThread { open() }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    private companion object { const val LOG_PREFIX = "ReaMicro LSP" }
}
