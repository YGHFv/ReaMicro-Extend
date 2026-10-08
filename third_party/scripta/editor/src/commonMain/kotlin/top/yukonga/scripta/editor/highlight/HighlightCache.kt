package top.yukonga.scripta.editor.highlight

class HighlightCache(
    private val highlighter: SyntaxHighlighter,

    private val maxLexedLineLength: Int = 20_000,
) {

    private val exitStates = ArrayList<LineState?>()
    private var valid = 0

    private var tailFloor = 0
    private var tailEnd = 0

    fun invalidateFrom(line: Int) = invalidate(line, Int.MAX_VALUE, structural = true)

    fun invalidate(from: Int, endExclusive: Int, structural: Boolean) {
        val f = from.coerceAtLeast(0)
        if (structural) {
            tailEnd = 0
            if (f < valid) valid = f
            return
        }
        val e = endExclusive.coerceAtLeast(f)
        if (f < valid) {

            tailFloor = e - 1
            tailEnd = valid
            valid = f
        } else if (f < tailEnd) {

            if (e - 1 > tailFloor) tailFloor = e - 1
        }
    }

    fun spansForLine(line: Int, getLine: (Int) -> String): List<HighlightSpan> {
        while (valid < line) advance(getLine)
        if (line == valid) return advance(getLine).spans
        return lexLine(line, getLine).spans
    }

    private fun entryFor(line: Int): LineState? =
        if (line == 0) highlighter.initialState else exitStates[line - 1]

    private fun lexLine(line: Int, getLine: (Int) -> String): LineHighlight {
        val text = getLine(line)
        val entry = entryFor(line)
        if (text.length > maxLexedLineLength) return LineHighlight(emptyList(), entry)
        return highlighter.highlightLine(text, entry)
    }

    private fun advance(getLine: (Int) -> String): LineHighlight {
        val line = valid
        val comparable = line >= tailFloor && line < tailEnd
        val old = if (comparable) exitStates[line] else null
        val h = lexLine(line, getLine)
        setExit(line, h.exitState)
        valid = if (comparable && h.exitState == old) tailEnd else line + 1
        return h
    }

    private fun setExit(line: Int, state: LineState?) {
        while (exitStates.size <= line) exitStates.add(null)
        exitStates[line] = state
    }
}
