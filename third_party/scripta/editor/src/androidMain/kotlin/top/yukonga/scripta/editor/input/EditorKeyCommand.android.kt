package top.yukonga.scripta.editor.input

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

actual fun resolveEditorKeyCommand(event: KeyEvent): EditorKeyCommand? =
    if (event.type != KeyEventType.KeyDown) null else resolveCtrlBased(event)
