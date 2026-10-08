package top.yukonga.scripta.editor.highlight

enum class TokenType {
    Comment, Key, String, Number, Boolean, Null, Keyword, Punctuation, Anchor, Tag, Directive,
    Operator, Variable, Property, Function, Type, Escape, Regex, Heading,
}

data class HighlightSpan(val start: Int, val end: Int, val type: TokenType)

fun clipSpansToWindow(spans: List<HighlightSpan>, from: Int, until: Int): List<HighlightSpan> {
    if (spans.isEmpty() || until <= from) return emptyList()
    val out = ArrayList<HighlightSpan>()
    for (s in spans) {
        if (s.end <= from) continue
        if (s.start >= until) break
        out.add(HighlightSpan(maxOf(s.start, from) - from, minOf(s.end, until) - from, s.type))
    }
    return out
}

data class BlockComment(val open: String, val close: String)

interface LineState

class LineHighlight(val spans: List<HighlightSpan>, val exitState: LineState?)

interface SyntaxHighlighter {

    val initialState: LineState? get() = null

    val lineCommentPrefix: String? get() = null

    val blockComment: BlockComment? get() = null

    fun highlightLine(text: String, entryState: LineState?): LineHighlight
}
