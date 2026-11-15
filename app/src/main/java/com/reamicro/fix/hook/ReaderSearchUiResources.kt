package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.reamicro.fix.epub.editor.FontSelectionResolution
import java.util.concurrent.atomic.AtomicBoolean

internal object ReaderSearchUiResources {
    private val started = AtomicBoolean()
    @Volatile var cachedFonts: HostEmbeddedStyle.Fonts? = null
        private set

    @Synchronized fun fonts(context: Context): HostEmbeddedStyle.Fonts =
        cachedFonts ?: HostEmbeddedStyle.fonts(context).also { cachedFonts = it }

    data class TextResources(val fonts: HostEmbeddedStyle.Fonts, val family: FontFamily?)
    private var textKey: String? = null
    private var textResources: TextResources? = null

    @Synchronized fun text(context: Context, selection: String): TextResources {
        val activity = context as? android.app.Activity
        val identity = runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull()
        fun current() = identity == null || runCatching {
            AccountHostRuntime.isCurrentStorage(identity, activity)
        }.getOrDefault(false)
        val file = identity?.let { runCatching { com.reamicro.fix.epub.editor.AccountFontFiles.resolve(
            context.filesDir, it.accountId, selection,
        ) }.getOrNull() }?.takeIf { current() }
        val key = "${FontSelectionResolution.cacheKey(selection, file)}|${identity?.accountId}|${identity?.generation}"
        textResources?.takeIf { textKey == key && current() }?.let { return it }
        val face = when {
            selection == "system" -> Typeface.DEFAULT
            selection.isBlank() || selection == "serif" -> null
            else -> file?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        }
        if (!current()) return TextResources(fonts(context), null)
        return TextResources(fonts(context), face?.let { FontFamily(it) }).also {
            textKey = key
            textResources = it
        }
    }

    fun warm(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        com.reamicro.fix.reload.ModuleResources.thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { fonts(app) }.onFailure { started.set(false) }
        }, "ReaMicroSearchUiWarmup").apply { isDaemon = true; start() }
    }
}
