package com.reamicro.fix.hook

import android.content.Context
import android.content.res.Configuration
import top.yukonga.miuix.kmp.theme.Colors
import com.reamicro.fix.ui.ModuleTypography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal object HostEmbeddedStyle {
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
            com.reamicro.fix.xposed.HookRuntime.log(
                "ReaMicro structure host fonts unavailable, system serif fallback: ${it.message}",
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
    fun title(fonts: Fonts) = EmbeddedHostUi.textStyle("TitleMedium", text(17, FontWeight.Bold, 1.5f, fonts.serif))
    fun caption(fonts: Fonts) = EmbeddedHostUi.textStyle("BodySmall", text(10, FontWeight.Normal, .5f, fonts.serif))
    fun fileName(fonts: Fonts) = EmbeddedHostUi.textStyle("LabelLarge", text(14, FontWeight.Bold, 1f, fonts.fileName))

    fun openedCount(fonts: Fonts) = EmbeddedHostUi.textStyle("LabelSmall", text(11, FontWeight.Bold, 1f, fonts.fileName))
    fun drawerName(fonts: Fonts) = EmbeddedHostUi.textStyle("TitleSmall", text(15, FontWeight.Bold, 1f, fonts.fileName))

    fun typography(fonts: Fonts, override: FontFamily? = null): ModuleTypography {
        val family = override ?: fonts.serif
        val base = ModuleTypography()
        fun style(role: String, fallback: TextStyle) =
            EmbeddedHostUi.textStyle(role, fallback.copy(fontFamily = family,
                platformStyle = PlatformTextStyle(includeFontPadding = false)))
        return base.copy(
            displayLarge = style("DisplayLarge", base.displayLarge),
            displayMedium = style("DisplayMedium", base.displayMedium),
            displaySmall = style("DisplaySmall", base.displaySmall),
            headlineLarge = style("HeadlineLarge", base.headlineLarge),
            headlineMedium = style("HeadlineMedium", text(22, FontWeight.Bold, 2f, family)),
            headlineSmall = style("HeadlineSmall", base.headlineSmall),
            titleLarge = style("TitleLarge", text(19, FontWeight.Bold, 2f, family)),
            titleMedium = style("TitleMedium", text(17, FontWeight.Bold, 1.5f, family)),
            titleSmall = style("TitleSmall", text(15, FontWeight.Bold, 1f, family)),
            bodyLarge = style("BodyLarge", text(14, FontWeight.Normal, 1f, family)),
            bodyMedium = style("BodyMedium", text(12, FontWeight.Normal, 1f, family)),
            bodySmall = style("BodySmall", text(10, FontWeight.Normal, .5f, family)),
            labelLarge = style("LabelLarge", text(14, FontWeight.Bold, 1f, family)),
            labelMedium = style("LabelMedium", text(12, FontWeight.Bold, 1f, family)),
            labelSmall = style("LabelSmall", text(11, FontWeight.Bold, 1f, family)),
        )
    }

    data class Palette(val native: HostThemeColors.Snapshot) {
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
        fun controlsScheme(): Colors = native.controlsScheme()
    }
    fun palette(context: Context, darkHint: Boolean) =
        Palette(HostThemeColors.snapshot(context, darkHint))
}
