package com.reamicro.fix.hook

import android.content.Context
import android.os.Looper
import androidx.compose.ui.text.font.FontFamily
import com.reamicro.fix.reload.ModuleResources

internal data class ReaderSelectionEditorFonts(
    val native: HostEmbeddedStyle.Fonts,
    val ui: FontFamily?,
) {
    companion object {
        private var nativeFonts: Pair<ClassLoader, HostEmbeddedStyle.Fonts>? = null

        init { ModuleResources.cleanup { synchronized(this) { nativeFonts = null } } }

        /** Run with source preparation, before presenting the editor's first frame. */
        fun load(context: Context): ReaderSelectionEditorFonts {
            check(Looper.myLooper() !== Looper.getMainLooper()) { "Editor fonts require a worker thread" }
            val fonts = synchronized(this) {
                nativeFonts?.takeIf { it.first === context.classLoader }?.second
                    ?: StructureHostFonts.load(context).also { nativeFonts = context.classLoader to it }
            }
            // The global font resolver supplies its own file-aware cache and may read a font.
            val ui = EmbeddedHostUi.uiTypeface(context)?.let { FontFamily(it) }
            return ReaderSelectionEditorFonts(fonts, ui)
        }
    }
}
