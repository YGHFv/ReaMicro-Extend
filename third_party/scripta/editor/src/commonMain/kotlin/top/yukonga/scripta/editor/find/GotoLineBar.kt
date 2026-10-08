package top.yukonga.scripta.editor.find

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.GotoLineSession

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GotoLineBar(
    session: GotoLineSession,
    lineCount: Int,
    colors: EditorColors,
    controlTextStyle: TextStyle,
    onRequestEditorFocus: () -> Unit,
) {
    if (!session.visible) return
    val fieldFocus = remember { FocusRequester() }

    fun closeAndRefocus() {
        session.close()
        onRequestEditorFocus()
    }

    fun jumpAndRefocus() {
        if (session.jump()) onRequestEditorFocus()
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .background(colors.symbolBarBackground)
            .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FindField(
            value = session.input,
            onValueChange = { session.input = it.filter(Char::isDigit) },
            placeholder = "跳转到行",
            colors = colors, controlTextStyle = controlTextStyle,
            modifier = Modifier
                .weight(1f)
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            jumpAndRefocus(); true
                        }

                        Key.Escape -> {
                            closeAndRefocus(); true
                        }

                        else -> false
                    }
                },
            keyboardType = KeyboardType.Number,
            onImeSearch = { jumpAndRefocus() },
        )
        BasicText(
            text = "共 $lineCount 行",
            style = controlTextStyle.copy(color = colors.symbolBarForeground.copy(alpha = 0.75f)),
            maxLines = 1,
        )
        ActionChip("跳转", colors, controlTextStyle) { jumpAndRefocus() }
        ActionChip("✕", colors, controlTextStyle) { closeAndRefocus() }
    }

    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
}
