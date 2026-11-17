package com.reamicro.fix.hook

import android.content.Context
import com.reamicro.fix.ui.ModuleTypography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal object BookImageDialogStyle {
    fun capture(loader: ClassLoader, composer: Any?) = EmbeddedHostUi.captureTypography(loader, composer)
    fun typography(context: Context, scale: Float): ModuleTypography {
        val family = EmbeddedHostUi.uiFontFamily(context)
        fun style(role: String, size: Int, bold: Boolean, spacing: Float): TextStyle {
            return EmbeddedHostUi.textStyle(role, TextStyle(fontFamily = family,
                fontSize = (size * scale).sp,
                lineHeight = 1.5.em,
                letterSpacing = (spacing * scale).sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
        return ModuleTypography(
            titleLarge = style("TitleLarge", 19, true, 2f),
            titleMedium = style("TitleMedium", 17, true, 1.5f),
            titleSmall = style("TitleSmall", 15, true, 1f),
            bodyLarge = style("BodyLarge", 14, false, 1f),
            bodyMedium = style("BodyMedium", 12, false, 1f),
            bodySmall = style("BodySmall", 10, false, .5f),
            labelLarge = style("LabelLarge", 14, true, 1f),
            labelMedium = style("LabelMedium", 12, true, 1f),
        )
    }

    const val SHEET_CORNER = 20f
    const val CARD_CORNER = 8f
    const val BUTTON_CORNER = 4f
    const val BORDER = .8f
    const val HANDLE_WIDTH = 40f
    const val HANDLE_HEIGHT = 4f
    const val HANDLE_TOP = 14f
    const val HANDLE_BOTTOM = 16f
}
