package top.yukonga.scripta.editor.find

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.editorNoFontPaddingStyle

@Composable
internal fun FindField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: EditorColors,
    controlTextStyle: TextStyle,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeSearch: () -> Unit,
) {

    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    if (fieldValue.text != value) {

        fieldValue = TextFieldValue(value, TextRange(0, value.length))
    }

    val fieldLineStyle = remember {
        LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
    }
    val fieldPlatformStyle = remember { editorNoFontPaddingStyle() }
    Box(
        modifier

            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.background.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            BasicText(
                text = placeholder,
                style = controlTextStyle.copy(color = colors.symbolBarForeground.copy(alpha = .45f)),
            )
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != value) onValueChange(it.text)
            },
            singleLine = true,
            textStyle = controlTextStyle.copy(color = colors.foreground, lineHeightStyle = fieldLineStyle,
                platformStyle = fieldPlatformStyle),
            cursorBrush = SolidColor(colors.cursor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = keyboardType),
            keyboardActions = KeyboardActions(onSearch = { onImeSearch() }),

            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ActionChip(label: String, colors: EditorColors, controlTextStyle: TextStyle, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) colors.symbolBarPressed else Color.Transparent)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = label, style = controlTextStyle.copy(color = colors.symbolBarForeground))
    }
}
