package com.reamicro.fix.epub.editor

import top.yukonga.scripta.editor.highlight.BlockComment
import top.yukonga.scripta.editor.highlight.HighlightSpan
import top.yukonga.scripta.editor.highlight.LineHighlight
import top.yukonga.scripta.editor.highlight.LineState
import top.yukonga.scripta.editor.highlight.SyntaxHighlighter
import top.yukonga.scripta.editor.highlight.TokenType

internal class EpubMarkupHighlighter : SyntaxHighlighter {
    override val blockComment = BlockComment("<!--", "-->")
    private data class State(val mode: Int, val quote: Char? = null, val tagName: Boolean = false) : LineState

    override fun highlightLine(text: String, entryState: LineState?): LineHighlight {

        if (text.length > 8192) return LineHighlight(emptyList(), null)
        val spans = ArrayList<HighlightSpan>()
        var mode = (entryState as? State)?.mode ?: 0
        var quote = (entryState as? State)?.quote
        var tagName = (entryState as? State)?.tagName ?: false
        var index = 0
        fun span(start: Int, end: Int, type: TokenType) {
            if (end > start) spans += HighlightSpan(start, end, type)
        }
        while (index < text.length) {
            val start = index
            if (mode == 2 || mode == 3) {
                val close = if (mode == 2) "-->" else "]]>"
                val end = text.indexOf(close, index)
                val type = if (mode == 2) TokenType.Comment else TokenType.String
                if (end < 0) {
                    span(index, text.length, type)
                    return LineHighlight(spans, State(mode))
                }
                index = end + close.length
                span(start, index, type)
                mode = 0
            } else if (mode == 1) {
                if (quote != null) {
                    val end = text.indexOf(quote!!, index)
                    if (end < 0) {
                        span(index, text.length, TokenType.String)
                        return LineHighlight(spans, State(mode, quote, tagName))
                    }
                    index = end + 1
                    span(start, index, TokenType.String)
                    quote = null
                } else when (val char = text[index]) {
                    '>' -> {
                        span(index, ++index, TokenType.Punctuation)
                        mode = 0
                    }
                    '\'', '"' -> {
                        quote = char
                        val end = text.indexOf(char, index + 1)
                        if (end < 0) {
                            span(index, text.length, TokenType.String)
                            return LineHighlight(spans, State(mode, quote, tagName))
                        }
                        index = end + 1
                        span(start, index, TokenType.String)
                        quote = null
                    }
                    '/', '=', '?', '!' -> span(index, ++index, TokenType.Punctuation)
                    else -> {
                        if (char.isWhitespace()) {
                            index++
                        } else {
                            while (index < text.length && !text[index].isWhitespace() &&
                                text[index] !in charArrayOf('>', '/', '=', '\'', '"', '?')) index++
                            if (index == start) index++
                            span(start, index, if (tagName) TokenType.Tag else TokenType.Property)
                            tagName = false
                        }
                    }
                }
            } else when {
                text.startsWith("<!--", index) -> mode = 2
                text.startsWith("<![CDATA[", index) -> mode = 3
                text[index] == '<' -> {
                    span(index, ++index, TokenType.Punctuation)
                    mode = 1
                    tagName = true
                }
                text[index] == '&' -> {
                    val end = text.indexOf(';', index + 1)
                    if (end in (index + 1)..minOf(text.lastIndex, index + 16)) {
                        index = end + 1
                        span(start, index, TokenType.Escape)
                    } else index++
                }
                else -> index++
            }
        }
        return LineHighlight(spans, if (mode == 0) null else State(mode, quote, tagName))
    }
}
