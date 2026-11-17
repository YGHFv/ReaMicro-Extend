package com.reamicro.fix.epub.editor

import org.junit.Assert.*
import org.junit.Test

class SelectionSourceTextVerificationTest {
    @Test fun parserRetainedCrLfMatchesDecodedLfInChapterWhitespace() {
        assertTrue(selectionSourceTextMatches("\r\n  ", "\n  "))
        assertTrue(selectionSourceTextMatches("\r\n", "\n"))
        assertTrue(selectionSourceTextMatches("正文\r\n下一行", "正文\n下一行"))
    }

    @Test fun comparisonIsSymmetricAndAcceptsCrLineEndings() {
        assertTrue(selectionSourceTextMatches("正文\n下一行", "正文\r\n下一行"))
        assertTrue(selectionSourceTextMatches("正文\r下一行", "正文\n下一行"))
        assertTrue(selectionSourceTextMatches("正文\r\n下一行\r尾行", "正文\n下一行\n尾行"))
    }

    @Test fun actualTextChangesStillFail() {
        assertFalse(selectionSourceTextMatches("正文\r\n下一行", "正义\n下一行"))
        assertFalse(selectionSourceTextMatches("\r\n  ", "\n "))
        assertFalse(selectionSourceTextMatches("正文\r\n下一行", "正文下一行"))
        assertFalse(selectionSourceTextMatches("A\u00a0B\r\n", "A B\n"))
        assertFalse(selectionSourceTextMatches("A&amp;B\r\n", "A&B\n"))
        assertFalse(selectionSourceTextMatches("正文\r\n", "正文\n\n"))
    }

    @Test fun comparisonDoesNotAlterInputOrUnicode() {
        val actual = "中文😀\r\n尾行"
        val expected = "中文😀\n尾行"
        assertTrue(selectionSourceTextMatches(actual, expected))
        assertEquals("中文😀\r\n尾行", actual)
        assertEquals("中文😀\n尾行", expected)
        assertFalse(selectionSourceTextMatches(actual, "中文😁\n尾行"))
    }
}
