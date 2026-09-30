package com.reamicro.fix.epub.editor

import org.junit.Assert.*
import org.junit.Test
import top.yukonga.scripta.editor.highlight.TokenType

class EpubMarkupHighlighterTest {
    private val highlighter = EpubMarkupHighlighter()

    @Test fun tagsAttributesAndEntities() {
        val result = highlighter.highlightLine("<p id=\"one\">你好&amp;</p>", null)
        assertTrue(result.spans.any { it.type == TokenType.Tag })
        assertTrue(result.spans.any { it.type == TokenType.Property })
        assertTrue(result.spans.any { it.type == TokenType.String })
        assertTrue(result.spans.any { it.type == TokenType.Escape })
    }

    @Test fun multiLineCommentsKeepState() {
        val first = highlighter.highlightLine("<!-- comment", null)
        val next = highlighter.highlightLine("continued --><p>", first.exitState)
        assertNotNull(first.exitState)
        assertNull(next.exitState)
        assertTrue(next.spans.any { it.type == TokenType.Comment })
        assertTrue(next.spans.any { it.type == TokenType.Tag })
    }

    @Test fun cdataDoesNotParseFakeTags() {
        val result = highlighter.highlightLine("<![CDATA[<p>text</p>]]>", null)
        assertTrue(result.spans.all { it.type == TokenType.String })
        assertNull(result.exitState)
    }

    @Test fun veryLongLinesSkipExpensiveHighlighting() {
        val result = highlighter.highlightLine("<p>" + "x".repeat(2_000_000) + "</p>", null)
        assertTrue(result.spans.isEmpty())
    }
}
