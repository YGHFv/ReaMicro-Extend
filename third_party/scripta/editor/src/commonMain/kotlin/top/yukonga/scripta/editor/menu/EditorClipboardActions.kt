package top.yukonga.scripta.editor.menu

import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.scripta.editor.EditorEngine
import top.yukonga.scripta.editor.input.plainText
import top.yukonga.scripta.editor.input.plainTextClipEntry

internal class EditorClipboardActions(
    private val engine: EditorEngine,
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
    private val readOnly: () -> Boolean,
) {
    fun perform(action: EditorContextAction) {
        when (action) {
            EditorContextAction.Undo -> if (!readOnly()) engine.undo()

            EditorContextAction.Redo -> if (!readOnly()) engine.redo()

            EditorContextAction.Copy -> engine.selectedText()?.let { txt ->
                scope.launch { clipboard.setClipEntry(plainTextClipEntry(txt)) }
            }

            EditorContextAction.Cut -> if (!readOnly()) engine.selectedText()?.let { txt ->
                scope.launch { clipboard.setClipEntry(plainTextClipEntry(txt)) }
                engine.replaceSelection("")
            }

            EditorContextAction.Paste -> if (!readOnly()) scope.launch {
                clipboard.getClipEntry()?.plainText()?.let { engine.insert(it) }
            }

            EditorContextAction.SelectAll -> engine.selectAll()
        }
    }
}
