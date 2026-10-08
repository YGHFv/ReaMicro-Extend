package com.reamicro.fix.hook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

internal object StructureSearchChromeMetrics {
    const val fieldTextInset = 16f
    const val sectionGap = 4f
    const val controlGap = 4f
    const val resultActionGap = 2f
    const val resultActionHorizontalInset = 4f
    const val actionVisualHeight = 24f
    const val actionTouchHeight = 40f
    const val actionHorizontalInset = 8f
    fun controlTextStyle(base: TextStyle) = base.copy(
        fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 1.5.em,
    )
    fun checkboxSide(fontSizeSp: Float, fontScale: Float): Float {
        val size = fontSizeSp.takeIf { it.isFinite() && it > 0f } ?: 12f
        val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        return size * scale
    }
}

@Composable
internal fun structureEditorControlTextStyle() =
    StructureSearchChromeMetrics.controlTextStyle(structureChapterTextStyle())

@Composable
internal fun StructureCompactOutlinedAction(
    label: String, enabled: Boolean, palette: StructureHome130Style.Palette,
    onClick: () -> Unit, busy: Boolean = false,
) {
    val shape = RoundedCornerShape(StructureContentStyle.actionCorner)
    val textStyle = structureEditorControlTextStyle()
    Box(Modifier.widthIn(min = 48.dp).heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp)
        .clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = StructureSearchChromeMetrics.actionVisualHeight.dp)
            .border(BorderStroke(StructureContentStyle.dividerWidth, palette.borderVariant), shape)
            .padding(horizontal = StructureSearchChromeMetrics.actionHorizontalInset.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StructureSearchChromeMetrics.controlGap.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.primary)
            Text(label, style = textStyle,
                color = if (enabled) palette.primary else palette.caption)
        }
    }
}

@Composable
internal fun StructureSearchTextAction(label: String, enabled: Boolean, palette: StructureHome130Style.Palette,
    onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 48.dp).heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp)
        .clip(RoundedCornerShape(StructureContentStyle.actionCorner))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = StructureSearchChromeMetrics.resultActionHorizontalInset.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = structureEditorControlTextStyle(), color = if (enabled) palette.primary else palette.caption)
    }
}
