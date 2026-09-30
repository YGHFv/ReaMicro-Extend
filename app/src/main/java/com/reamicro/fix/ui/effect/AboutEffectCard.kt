package com.reamicro.fix.ui.effect

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Records only the shader background, never the cards or the horizontal pager.
internal val LocalAboutBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** miuix 0.9.4 AboutPage / KSU AboutMiuix background-sampling card treatment. */
@Composable
internal fun AboutEffectCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalAboutBackdrop.current
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val blendColors = remember(dark) {
        BlurColors(
            blendColors = if (dark) ColorBlendToken.Overlay_Thin_Light
            else ColorBlendToken.Pured_Regular_Light,
        )
    }
    Card(
        modifier = modifier.then(
            if (backdrop != null) Modifier.textureBlur(
                backdrop = backdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 60f,
                colors = blendColors,
            ) else Modifier,
        ),
        cornerRadius = 16.dp,
        insideMargin = insideMargin,
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        ),
        content = content,
    )
}
