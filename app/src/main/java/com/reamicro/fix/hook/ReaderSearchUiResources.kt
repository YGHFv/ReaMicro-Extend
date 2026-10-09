package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.reamicro.fix.epub.editor.FontSelectionResolution
import java.util.concurrent.atomic.AtomicBoolean

internal object ReaderSearchUiResources {
    private val started = AtomicBoolean()
    @Volatile var cachedFonts: StructureHome130Style.Fonts? = null
        private set

    @Synchronized fun fonts(context: Context): StructureHome130Style.Fonts =
        cachedFonts ?: StructureHome130Style.fonts(context).also { cachedFonts = it }

    data class TextResources(val fonts: StructureHome130Style.Fonts, val family: FontFamily?)
    private var textKey: String? = null
    private var textResources: TextResources? = null

    @Synchronized fun text(context: Context, selection: String): TextResources {
        val file = FontSelectionResolution.file(selection, emptyList())?.takeIf { it.isFile }
        val key = "$selection|${file?.lastModified()}|${file?.length()}"
        textResources?.takeIf { textKey == key }?.let { return it }
        val face = when {
            selection == "system" -> Typeface.DEFAULT
            selection.isBlank() || selection == "serif" -> null
            else -> file?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        }
        return TextResources(fonts(context), face?.let { FontFamily(it) }).also {
            textKey = key
            textResources = it
        }
    }

    fun warm(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { fonts(app) }.onFailure { started.set(false) }
        }, "ReaMicroSearchUiWarmup").apply { isDaemon = true; start() }
    }
}
