package top.yukonga.scripta.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.scripta.editor.text.TextPosition

@Stable
class GotoLineSession internal constructor(private val engine: EditorEngine) {

    var visible: Boolean by mutableStateOf(false)
        private set

    var input: String by mutableStateOf("")

    fun open() {
        input = (engine.caret.line + 1).toString()
        visible = true
    }

    fun close() {
        visible = false
    }

    fun jump(): Boolean {
        val text = input.trim()
        if (text.isEmpty() || text.any { !it.isDigit() }) return false
        val n = text.toLongOrNull() ?: Long.MAX_VALUE
        val line = n.coerceIn(1L, engine.buffer.lineCount.toLong()).toInt() - 1
        engine.setCursor(TextPosition(line, 0))
        engine.requestReveal()
        close()
        return true
    }
}
