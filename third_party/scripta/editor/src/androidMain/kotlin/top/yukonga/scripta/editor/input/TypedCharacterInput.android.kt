package top.yukonga.scripta.editor.input

import androidx.compose.ui.input.key.KeyEvent
import top.yukonga.scripta.editor.EditorEngine

actual fun insertTypedCharacter(engine: EditorEngine, event: KeyEvent, readOnly: Boolean): Boolean = false
