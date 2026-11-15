package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.reamicro.fix.xposed.HookRuntime

/** Asset reads and Typeface parsing run in the page's IO job, before Compose requests either weight. */
@OptIn(ExperimentalTextApi::class)
internal object StructureHostFonts {
    fun load(context: Context): HostEmbeddedStyle.Fonts {
        val started = android.os.SystemClock.elapsedRealtime()
        val family = runCatching {
            val normal = Typeface.createFromAsset(context.assets, EmbeddedHostUi.FONT_ROOT + "serif_medium.ttf")
            val bold = Typeface.createFromAsset(context.assets, EmbeddedHostUi.FONT_ROOT + "serif_bold.ttf")
            FontFamily(LoadedFont(normal, FontWeight.Normal), LoadedFont(bold, FontWeight.Bold))
        }.onFailure {
            HookRuntime.log("ReaMicro structure fonts unavailable: ${it.javaClass.simpleName}")
        }.getOrElse { FontFamily.Serif }
        HookRuntime.log("ReaMicro structure fonts elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        return HostEmbeddedStyle.Fonts(family, FontFamily.SansSerif)
    }

    private class LoadedFont(val face: Typeface, override val weight: FontWeight) :
        AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
        override val style: FontStyle = FontStyle.Normal
    }

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface = (font as LoadedFont).face
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = (font as LoadedFont).face
    }
}
