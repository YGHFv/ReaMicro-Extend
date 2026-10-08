package com.reamicro.fix.hook

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object StructureContentStyle {
    val inset = 16.dp
    val gap = 16.dp
    val corner = 8.dp
    val actionCorner = 4.dp
    val dividerWidth = .8.dp
    val metadataBorderWidth = .5.dp
    const val overlayScrimAlpha = .1f

    fun chapterTextStyle(typography: Typography) = typography.labelLarge.copy(fontWeight = FontWeight.Normal)
}

@Composable
internal fun structureChapterTextStyle() = StructureContentStyle.chapterTextStyle(MaterialTheme.typography)

@Composable
internal fun StructureSectionHeader(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color)
        actions()
    }
}

@Composable
internal fun StructureContentCard(
    palette: StructureHome130Style.Palette,
    modifier: Modifier = Modifier,
    source: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(StructureContentStyle.inset),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(StructureContentStyle.corner)
    Column(modifier.fillMaxWidth().clip(shape)
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .background(if (source) palette.content else palette.page)
        .border(if (source) StructureContentStyle.dividerWidth else StructureContentStyle.metadataBorderWidth,
            if (source) palette.surfaceHighest else palette.borderHigh, shape)
        .padding(contentPadding), content = content)
}

@Composable
internal fun rememberStructureContentTypography(
    hostFonts: StructureHome130Style.Fonts,
    uiFontFileProvider: () -> File?,
): Typography {
    val uiFont by produceState<FontFamily?>(null, uiFontFileProvider) {
        value = withContext(Dispatchers.IO) { runCatching {
            uiFontFileProvider()?.takeIf { it.isFile }?.let { FontFamily(Typeface.createFromFile(it)) }
        }.getOrNull() }
    }
    return remember(uiFont, hostFonts) { StructureHome130Style.typography(hostFonts, uiFont) }
}
