package top.yukonga.scripta.editor.text

data class TextPosition(val line: Int, val column: Int) : Comparable<TextPosition> {
    override fun compareTo(other: TextPosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)
}

data class TextRange(val start: TextPosition, val end: TextPosition) {
    val isEmpty: Boolean get() = start == end

    fun normalized(): TextRange = if (start <= end) this else TextRange(end, start)

    companion object {
        fun cursor(p: TextPosition): TextRange = TextRange(p, p)
    }
}
