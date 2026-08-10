package com.reamicro.fix.online.search

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineHtmlEntityDecoderTest {
    @Test
    fun decimalAndHexNumericEntitiesDecodeToPunctuation() {
        assertEquals("\"引号\"，以及“中文引号”", "&#34;引号&#34;，以及&#x201C;中文引号&#x201D;".decodeOnlineHtmlEntities())
    }

    @Test
    fun doubleEncodedNumericEntitiesDecodeUntilStable() {
        assertEquals("\"正文\"", "&amp;#34;正文&amp;#34;".decodeOnlineHtmlEntities())
        assertEquals("“正文”", "&amp;#x201C;正文&amp;#x201D;".decodeOnlineHtmlEntities())
        assertEquals("\"正文\"", "&amp;amp;#34;正文&amp;amp;#34;".decodeOnlineHtmlEntities())
    }

    @Test
    fun commonNamedPunctuationEntitiesAreDecoded() {
        assertEquals("“你好”—他说…", "&ldquo;你好&rdquo;&mdash;他说&hellip;".decodeOnlineHtmlEntities())
    }

    @Test
    fun invalidEntitiesRemainUnchanged() {
        assertEquals("A &unknown; B &#x110000;", "A &unknown; B &#x110000;".decodeOnlineHtmlEntities())
    }
}
