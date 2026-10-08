package top.yukonga.scripta.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange

@Stable
class CodeEditorController internal constructor(initialText: String = "") {

    internal val engine: EditorEngine = EditorEngine(initialText)

    internal val gotoLine: GotoLineSession = GotoLineSession(engine)

    fun getText(): String = engine.getText()

    var lineEnding: LineEnding by mutableStateOf(detectLineEnding(initialText))
        private set

    fun getText(lineEnding: LineEnding): String {
        val lf = engine.getText()
        return if (lineEnding == LineEnding.CRLF) lf.replace("\n", "\r\n") else lf
    }

    val selection: TextRange get() = engine.selection

    val caret: TextPosition get() = engine.caret

    val documentVersion: Int get() = engine.buffer.version

    val isComposing: Boolean get() = engine.composing != null

    private var savedVersion: Int by mutableStateOf(engine.buffer.version)

    val isModified: Boolean get() = engine.buffer.version != savedVersion

    fun markSaved(version: Int) {
        savedVersion = version
    }

    fun undo(): Boolean = engine.undo()
    fun redo(): Boolean = engine.redo()
    val canUndo: Boolean get() = engine.canUndo
    val canRedo: Boolean get() = engine.canRedo

    fun openGotoLine() {
        gotoLine.open()
    }

    fun closeGotoLine() = gotoLine.close()
    val isGotoLineVisible: Boolean get() = gotoLine.visible

    fun select(start: TextPosition, end: TextPosition) {
        engine.setSelection(snapToCodePoint(start), snapToCodePoint(end))
        engine.requestReveal()
    }

    fun jumpTo(position: TextPosition) = select(position, position)

    fun jumpToLine(line: Int) = jumpTo(TextPosition(line, 0))

    fun insertText(text: String) {
        engine.finishComposing()
        if (text.isEmpty() && engine.selection.isEmpty) return

        engine.replaceSelection(text)
    }

    fun replaceRange(start: TextPosition, end: TextPosition, text: String) {
        engine.finishComposing()
        val s = snapToCodePoint(start)
        val e = snapToCodePoint(end)
        if (text.isEmpty() && s == e) return
        engine.replaceRange(TextRange(s, e), text)
        engine.requestReveal()
    }

    private fun snapToCodePoint(p: TextPosition): TextPosition {
        val clamped = engine.buffer.clamp(p)
        val t = engine.buffer.lineText(clamped.line)
        val c = clamped.column
        return if (c in 1 until t.length && t[c].isLowSurrogate() && t[c - 1].isHighSurrogate()) {
            TextPosition(clamped.line, c - 1)
        } else clamped
    }

    fun setDocument(text: String) {
        lineEnding = detectLineEnding(text)
        engine.setText(text)
        engine.requestReveal()
        savedVersion = engine.buffer.version
    }

}

@Composable
fun rememberCodeEditorController(initialText: String = ""): CodeEditorController =
    remember { CodeEditorController(initialText) }

private fun detectLineEnding(raw: String): LineEnding {
    var crlf = 0
    var lf = 0
    var i = raw.indexOf('\n')
    while (i >= 0) {
        if (i > 0 && raw[i - 1] == '\r') crlf++ else lf++
        i = raw.indexOf('\n', i + 1)
    }
    return if (crlf > lf) LineEnding.CRLF else LineEnding.LF
}
