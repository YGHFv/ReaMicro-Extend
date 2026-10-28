package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostHtmlNormalizationTest {
    @Test
    fun `non void tags expand using original case and attributes`() {
        assertEquals("<DIV class='a'></DIV><p></p>", HostHtmlNormalization.normalize("<DIV class='a'/><p/>"))
    }

    @Test
    fun `html void tags remain byte identical`() {
        val input = "<br/><IMG src='x' /><wbr />"
        assertEquals(input, HostHtmlNormalization.normalize(input))
    }

    @Test
    fun `unicode text and ordinary tags remain untouched`() {
        val input = "<body><p>电话 &amp; Alpha</p></body>"
        assertEquals(input, HostHtmlNormalization.normalize(input))
    }
}
