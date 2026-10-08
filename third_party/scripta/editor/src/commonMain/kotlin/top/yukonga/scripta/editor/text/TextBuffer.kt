package top.yukonga.scripta.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val CACHE_TEXT_LIMIT = 1_000_000

class TextBuffer(initialText: String = "") {

    private val tree = PieceTree()

    var version: Int by mutableStateOf(0)
        private set

    private var cachedText: String? = null

    init {
        setText(initialText)
    }

    val lineCount: Int get() = tree.lineCount

    fun lineText(line: Int): String = tree.lineContent(line)

    fun lineLength(line: Int): Int = tree.lineLength(line)

    fun endPosition(): TextPosition {
        val last = tree.lineCount - 1
        return TextPosition(last, tree.lineLength(last))
    }

    fun clamp(pos: TextPosition): TextPosition {
        val line = pos.line.coerceIn(0, tree.lineCount - 1)
        val col = pos.column.coerceIn(0, tree.lineLength(line))
        return TextPosition(line, col)
    }

    fun textInRange(range: TextRange): String {
        val r = range.normalized()
        val s = tree.offsetAt(clamp(r.start))
        val e = tree.offsetAt(clamp(r.end))
        return tree.substring(s, e - s)
    }

    fun text(): String {
        cachedText?.let { return it }
        val whole = tree.getText()
        if (whole.length <= CACHE_TEXT_LIMIT) cachedText = whole
        return whole
    }

    fun offsetAt(pos: TextPosition): Int = tree.offsetAt(clamp(pos))
    fun positionAt(offset: Int): TextPosition = tree.positionAt(offset)
    fun totalLength(): Int = tree.length

    fun replace(range: TextRange, replacement: String): TextPosition {
        val r = range.normalized()
        val s = tree.offsetAt(clamp(r.start))
        val e = tree.offsetAt(clamp(r.end))
        val repl = normalizeNewlines(replacement)
        tree.delete(s, e - s)
        tree.insert(s, repl)
        cachedText = null
        version++
        return tree.positionAt(s + repl.length)
    }

    fun setText(text: String) {
        val normalized = normalizeNewlines(text)
        tree.reset(normalized)

        cachedText = if (normalized.length <= CACHE_TEXT_LIMIT) normalized else null
        version++
    }

    private fun normalizeNewlines(raw: String): String =
        if (raw.indexOf('\r') >= 0) raw.replace("\r\n", "\n").replace("\r", "\n") else raw
}
