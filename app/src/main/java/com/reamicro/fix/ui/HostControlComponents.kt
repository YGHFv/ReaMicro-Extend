package com.reamicro.fix.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 工具栏保留原有 48dp 触摸范围，并向未指定 tint 的图标传递禁用颜色。 */
@Composable
internal fun ModuleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ModuleTheme.colorScheme.onSurface,
    disabledContentColor: Color = contentColor.copy(alpha = .38f),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides if (enabled) contentColor else disabledContentColor) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled,
            minHeight = 48.dp, minWidth = 48.dp, cornerRadius = 24.dp, content = content)
    }
}

/** 保留文字动作的内容槽位；实际点击、反馈和布局由 Miuix Button 提供。 */
@Composable
internal fun ModuleTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ModuleTheme.colorScheme.primary,
    disabledContentColor: Color = contentColor.copy(alpha = .38f),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, cornerRadius = 24.dp,
        minWidth = 64.dp, minHeight = 48.dp, insideMargin = contentPadding,
        colors = ButtonDefaults.buttonColors(color = Color.Transparent, disabledColor = Color.Transparent,
            contentColor = contentColor, disabledContentColor = disabledContentColor)) {
        ProvideModuleButtonText { content() }
    }
}

/** Miuix 的任意内容按钮沿用宿主 LabelLarge 字号，避免默认正文影响动作高度。 */
@Composable
internal fun ProvideModuleButtonText(content: @Composable () -> Unit) {
    val current = MiuixTheme.textStyles
    val label = ModuleTheme.typography.labelLarge
    val styles = remember(current, label) { current.copy(main = label) }
    MiuixTheme(colors = ModuleTheme.colorScheme, textStyles = styles, content = content)
}

@Composable
internal fun ModuleCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ModuleTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Transparent,
) {
    CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = color, backgroundColor = trackColor))
}

@Composable
internal fun ModuleLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ModuleTheme.colorScheme.primary,
    trackColor: Color = ModuleTheme.colorScheme.secondaryContainer,
) {
    LinearProgressIndicator(modifier = modifier,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = color, backgroundColor = trackColor))
}
