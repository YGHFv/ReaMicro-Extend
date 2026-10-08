package com.reamicro.fix.hook

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

@Composable
internal fun StructureToolbar(
    title: String,
    subtitle: String?,
    titleStyle: TextStyle,
    subtitleStyle: TextStyle,
    titleColor: Color,
    subtitleColor: Color,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Layout(modifier = modifier.fillMaxWidth(), content = {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { navigationIcon() }
        Row(
            Modifier.height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        StructureTruncatedText(title, titleStyle, titleColor)
        StructureTruncatedText(subtitle.orEmpty(), subtitleStyle, subtitleColor)
    }) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 320.dp.roundToPx()
        val edge = 4.dp.roundToPx()
        val control = 48.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val navigation = measurables[0].measure(loose.copy(maxWidth = width, maxHeight = control))
        val actionWidth = (width - edge * 2 - navigation.width).coerceAtLeast(0)
        val trailing = measurables[1].measure(loose.copy(maxWidth = actionWidth, maxHeight = control))
        val horizontal = StructureToolbarLayout.horizontal(width, navigation.width, trailing.width,
            edge, 12.dp.roundToPx())
        val textConstraints = loose.copy(maxWidth = horizontal.textWidth)
        val titlePlaceable = measurables[2].measure(textConstraints)
        val subtitlePlaceable = measurables[3].measure(textConstraints)
        val vertical = StructureToolbarLayout.vertical(titlePlaceable.height,
            if (subtitle.isNullOrEmpty()) 0 else subtitlePlaceable.height,
            64.dp.roundToPx(), 30.dp.roundToPx(), 6.dp.roundToPx())
        layout(width, constraints.constrainHeight(vertical.height)) {
            navigation.placeRelative(edge, vertical.controlCenter - navigation.height / 2)
            trailing.placeRelative(horizontal.actionsStart, vertical.controlCenter - trailing.height / 2)
            titlePlaceable.placeRelative(horizontal.textStart, vertical.titleTop)
            if (!subtitle.isNullOrEmpty()) {
                subtitlePlaceable.placeRelative(horizontal.textStart, vertical.subtitleTop)
            }
        }
    }
}

@Composable
internal fun StructureTruncatedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.MiddleEllipsis,
        style = style,
        color = color,
    )
}
