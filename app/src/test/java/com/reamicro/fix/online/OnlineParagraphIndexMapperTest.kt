package com.reamicro.fix.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineParagraphIndexMapperTest {
    @Test
    fun `skips first title and maps normal paragraphs from zero`() {
        val paragraphs = OnlineParagraphIndexMapper.mapFullV2Content(
            """
            第一章 开始

            第一段正文
            <p>第二段正文</p>
            """.trimIndent(),
        )

        assertEquals(listOf(0, 1), paragraphs.map { it.paraIndex })
        assertEquals(listOf("第一段正文", "第二段正文"), paragraphs.map { it.text })
    }

    @Test
    fun `maps pure images into server image paragraph range`() {
        val paragraphs = OnlineParagraphIndexMapper.mapFullV2Content(
            """
            楔子
            正文
            <img src="a.jpg">
            <p><img src="b.jpg"></p>
            后文
            """.trimIndent(),
        )

        assertEquals(
            listOf(0, 1, 20_000, 20_001),
            paragraphs.map { it.paraIndex },
        )
        assertTrue(paragraphs.filter { it.imageOnly }.all { it.paraIndex >= 20_000 })
    }

    @Test
    fun `joins only positive counts with existing paragraph indices`() {
        val mapped = OnlineParagraphIndexMapper.commentsForCounts(
            content = "第一章\n甲\n乙\n<img src=\"a\">",
            counts = mapOf(0 to 2, 1 to 0, 20_000 to 7, 99 to 8),
        )

        assertEquals(listOf(0 to 2, 20_000 to 7), mapped.map { it.first.paraIndex to it.second })
    }
}
