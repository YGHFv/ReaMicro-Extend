package com.reamicro.fix.hook

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal object StructureHome130Style {
    private const val HOST_FONT_ROOT =
        "composeResources/reamicro.composeapp.generated.resources/font/"

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    data class Fonts(val serif: FontFamily, val fileName: FontFamily)

    fun fonts(host: Context): Fonts {
        val serif = runCatching {
            val assets = host.assets
            assets.open(HOST_FONT_ROOT + "serif_medium.ttf").use { }
            assets.open(HOST_FONT_ROOT + "serif_bold.ttf").use { }
            FontFamily(
                Font(path = HOST_FONT_ROOT + "serif_medium.ttf", assetManager = assets,
                    weight = FontWeight.Normal),
                Font(path = HOST_FONT_ROOT + "serif_bold.ttf", assetManager = assets,
                    weight = FontWeight.Bold),
            )
        }.getOrElse {
            com.reamicro.fix.xposed.XposedBridge.log(
                "ReaMicro structure 2.3.2 host fonts unavailable, system serif fallback: ${it.message}",
            )
            FontFamily.Serif
        }

        return Fonts(serif, FontFamily.SansSerif)
    }

    private fun text(size: Int, weight: FontWeight, spacing: Float, family: FontFamily) = TextStyle(
        fontSize = size.sp, fontWeight = weight, fontFamily = family,
        letterSpacing = spacing.sp, lineHeight = 1.5.em,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    fun title(fonts: Fonts) = text(17, FontWeight.Bold, 1.5f, fonts.serif)
    fun caption(fonts: Fonts) = text(10, FontWeight.Normal, .5f, fonts.serif)
    fun fileName(fonts: Fonts) = text(14, FontWeight.Bold, 1f, fonts.fileName)

    fun openedCount(fonts: Fonts) = text(11, FontWeight.Bold, 1f, fonts.fileName)
    fun drawerName(fonts: Fonts) = text(15, FontWeight.Bold, 1f, fonts.fileName)

    fun typography(fonts: Fonts, override: FontFamily? = null): Typography {
        val family = override ?: fonts.serif
        return Typography(
            headlineMedium = text(22, FontWeight.Bold, 2f, family),
            titleLarge = text(19, FontWeight.Bold, 2f, family),
            titleMedium = text(17, FontWeight.Bold, 1.5f, family),
            titleSmall = text(15, FontWeight.Bold, 1f, family),
            bodyLarge = text(14, FontWeight.Normal, 1f, family),
            bodyMedium = text(12, FontWeight.Normal, 1f, family),
            bodySmall = text(10, FontWeight.Normal, .5f, family),
            labelLarge = text(14, FontWeight.Bold, 1f, family),
            labelMedium = text(12, FontWeight.Bold, 1f, family),
            labelSmall = text(11, FontWeight.Bold, 1f, family),
        )
    }

    data class Palette(val native: StructureHost232Colors.Snapshot) {
        val dark = native.dark
        val bright = Color(native.brightArgb)
        val page = Color(native.pageArgb)
        val content = Color(native.contentArgb)
        val folder = content
        val text = native.role("OnBackground")
        val caption = Color(native.captionArgb)
        val icon = text.copy(alpha = .3f)
        val divider = Color(native.borderArgb)
        val borderVariant = Color(native.borderVariantArgb)
        val borderHigh = native.role("SurfaceContainerHigh")
        val surfaceHighest = native.role("SurfaceContainerHighest")
        val primary = native.role("Primary")
        val primarySoft = native.role("PrimaryContainer")
        val error = native.role("Error")
        fun controlsScheme(): ColorScheme = native.controlsScheme()
    }
    fun palette(context: Context, darkHint: Boolean) =
        Palette(StructureHost232Colors.snapshot(context, darkHint))
}
