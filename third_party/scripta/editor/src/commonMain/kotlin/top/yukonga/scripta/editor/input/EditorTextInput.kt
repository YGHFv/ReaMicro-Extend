package top.yukonga.scripta.editor.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import top.yukonga.scripta.editor.EditorEngine

expect fun Modifier.editorTextInput(
    engine: EditorEngine,
    enabled: Boolean,
    caretRectInEditor: () -> Rect?,
): Modifier
