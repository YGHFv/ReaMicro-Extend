package com.reamicro.fix.hook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HostThoughtEditorInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    palette: StructureHome130Style.Palette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    sourceMode: Boolean = false,
    inputModifier: Modifier = Modifier,
    scale: Float = 1f,
    placeholder: String = "",
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val metrics = EmbeddedHostUi.textMetrics
    val size = if (metrics.bodyLargePx > 0f) with(density) { metrics.bodyLargePx.toSp() } else metrics.bodyLarge.sp
    val line = if (metrics.bodyLinePx > 0f) with(density) { metrics.bodyLinePx.toSp() } else MaterialTheme.typography.bodyLarge.lineHeight
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = palette.text, fontSize = size, lineHeight = line,
        fontFamily = if (sourceMode) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape((EmbeddedHostMetrics.THOUGHT_CORNER * scale).dp),
        color = palette.bright.copy(alpha = HostThoughtInputStyle.SURFACE_ALPHA),
        contentColor = palette.text,
        border = BorderStroke(HostThoughtInputStyle.BORDER_DP.dp,
            palette.primary.copy(alpha = HostThoughtInputStyle.BORDER_ALPHA)),
        tonalElevation = 0.dp,
        shadowElevation = HostThoughtInputStyle.SHADOW_DP.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding((EmbeddedHostMetrics.THOUGHT_INSET * scale).dp)) {

            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(
                min = (HostThoughtInputStyle.INPUT_BOX_MIN_UDP * scale).dp,
                max = (HostThoughtInputStyle.INPUT_VIEWPORT_MAX_UDP * scale).dp,
            )) {
                BasicTextField(
                    value, onValueChange,
                    Modifier.fillMaxWidth().heightIn(min = (HostThoughtInputStyle.TEXT_MIN_UDP * scale).dp)
                        .then(inputModifier),
                    enabled = enabled, readOnly = readOnly, textStyle = textStyle,
                    cursorBrush = SolidColor(palette.primary),

                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = !sourceMode, imeAction = ImeAction.Default),
                    singleLine = false,
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                                Text(placeholder, style = textStyle, color = palette.caption)
                            }
                            inner()
                        }
                    },
                )
            }
            footer()
        }
    }
}
