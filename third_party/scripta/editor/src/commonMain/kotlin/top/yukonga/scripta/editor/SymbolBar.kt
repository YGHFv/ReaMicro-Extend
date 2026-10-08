package top.yukonga.scripta.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Immutable
data class EditorSymbol(val label: String, val value: String = label)

val DefaultEditorSymbols: List<EditorSymbol> = listOf(
    EditorSymbol("Tab", "    "),
    EditorSymbol(":"), EditorSymbol("="),
    EditorSymbol("{"), EditorSymbol("}"),
    EditorSymbol("["), EditorSymbol("]"),
    EditorSymbol("("), EditorSymbol(")"),
    EditorSymbol("<"), EditorSymbol(">"),
    EditorSymbol("\""), EditorSymbol("'"),
    EditorSymbol("/"), EditorSymbol("\\"),
    EditorSymbol("|"), EditorSymbol("&"),
    EditorSymbol("+"), EditorSymbol("-"),
    EditorSymbol("*"), EditorSymbol("#"),
    EditorSymbol("_"),
)

@Composable
internal fun SymbolBar(
    symbols: List<EditorSymbol>,
    colors: EditorColors,
    onSymbol: (EditorSymbol) -> Unit,
    windowInsets: WindowInsets,
    controlTextStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val divider = colors.symbolBarForeground.copy(alpha = 0.12f)
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.symbolBarBackground)
            .drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
            .windowInsetsPadding(windowInsets)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        symbols.forEach { symbol -> SymbolKey(symbol, colors, controlTextStyle, onSymbol) }
    }
}

@Composable
private fun SymbolKey(
    symbol: EditorSymbol,
    colors: EditorColors,
    controlTextStyle: TextStyle,
    onSymbol: (EditorSymbol) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) colors.symbolBarPressed else Color.Transparent)
            .pointerInput(symbol) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onSymbol(symbol) },
                )
            }
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = symbol.label,
            style = controlTextStyle.copy(color = colors.symbolBarForeground),
        )
    }
}
