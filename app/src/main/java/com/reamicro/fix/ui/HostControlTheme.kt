package com.reamicro.fix.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/** 宿主字号角色只承载模块自己的 TextStyle，避免跨 ClassLoader 使用宿主的主题类型。 */
@Immutable
internal data class ModuleTypography(
    val displayLarge: TextStyle = controlText(57, 64, spacing = -.25f),
    val displayMedium: TextStyle = controlText(45, 52),
    val displaySmall: TextStyle = controlText(36, 44),
    val headlineLarge: TextStyle = controlText(32, 40),
    val headlineMedium: TextStyle = controlText(28, 36),
    val headlineSmall: TextStyle = controlText(24, 32),
    val titleLarge: TextStyle = controlText(22, 28),
    val titleMedium: TextStyle = controlText(16, 24, FontWeight.Medium, .15f),
    val titleSmall: TextStyle = controlText(14, 20, FontWeight.Medium, .1f),
    val bodyLarge: TextStyle = controlText(16, 24, spacing = .5f),
    val bodyMedium: TextStyle = controlText(14, 20, spacing = .25f),
    val bodySmall: TextStyle = controlText(12, 16, spacing = .4f),
    val labelLarge: TextStyle = controlText(14, 20, FontWeight.Medium, .1f),
    val labelMedium: TextStyle = controlText(12, 16, FontWeight.Medium, .5f),
    val labelSmall: TextStyle = controlText(11, 16, FontWeight.Medium, .5f),
) {
    fun miuixStyles(): TextStyles = defaultTextStyles(
        main = bodyLarge,
        paragraph = bodyLarge,
        body1 = bodyLarge,
        body2 = bodyMedium,
        button = labelLarge,
        footnote1 = bodySmall,
        footnote2 = labelSmall,
        headline1 = headlineLarge,
        headline2 = headlineMedium,
        subtitle = titleSmall,
        title1 = displayLarge,
        title2 = displayMedium,
        title3 = titleLarge,
        title4 = titleMedium,
    )
}

private fun controlText(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    spacing: Float = 0f,
) = TextStyle(fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight,
    letterSpacing = spacing.sp, platformStyle = PlatformTextStyle(includeFontPadding = false))

private val LocalModuleTypography = staticCompositionLocalOf { ModuleTypography() }

/** 捕获的宿主颜色和字号同时供 Miuix 控件与模块正文使用。 */
@Composable
internal fun ModuleTheme(
    colorScheme: Colors = MiuixTheme.colorScheme,
    typography: ModuleTypography = ModuleTheme.typography,
    content: @Composable () -> Unit,
) {
    val textStyles = remember(typography) { typography.miuixStyles() }
    val selectionColors = remember(colorScheme.primary) {
        TextSelectionColors(handleColor = colorScheme.primary,
            backgroundColor = colorScheme.primary.copy(alpha = .4f))
    }
    CompositionLocalProvider(LocalModuleTypography provides typography,
        LocalContentColor provides colorScheme.onBackground,
        LocalTextSelectionColors provides selectionColors) {
        MiuixTheme(colors = colorScheme, textStyles = textStyles, content = content)
    }
}

internal object ModuleTheme {
    val colorScheme: Colors
        @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme

    val typography: ModuleTypography
        @Composable @ReadOnlyComposable get() = LocalModuleTypography.current
}
