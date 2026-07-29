package com.reamicro.fix.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineParagraphCommentInjectionPlannerTest {
    @Test
    fun `在多个段落末尾插入评论占位符`() {
        val first = comment(paraIndex = 0, text = "第一段正文", count = 3)
        val second = comment(paraIndex = 1, text = "第二段 正文", count = 7)

        val plan = OnlineParagraphCommentInjectionPlanner.plan(
            text = "第一段正文\n第二段　正文",
            comments = listOf(first, second),
        ) ?: error("缺少注入规划")

        assertEquals("第一段正文\uFFFD\n第二段　正文\uFFFD", plan.text)
        assertEquals(listOf(5, 13), plan.insertions.map(plan::insertedOffset))
        assertTrue(plan.insertions.all { it.stableId.startsWith(OnlineParagraphCommentInjectionPlanner.RUNTIME_ID_PREFIX) })
    }

    @Test
    fun `原有范围跨过插入点时正确平移`() {
        val plan = OnlineParagraphCommentInjectionPlanner.plan(
            text = "甲乙\n丙丁",
            comments = listOf(
                comment(paraIndex = 0, text = "甲乙"),
                comment(paraIndex = 1, text = "丙丁"),
            ),
        ) ?: error("缺少注入规划")

        val full = plan.shiftedRange(0, 5)
        val secondParagraph = plan.shiftedRange(3, 5)

        assertEquals(0, full.first)
        assertEquals(7, full.last)
        assertEquals(4, secondParagraph.first)
        assertEquals(7, secondParagraph.last)
    }

    @Test
    fun `插入点边界上的原范围终点会覆盖新增占位符`() {
        val plan = OnlineParagraphCommentInjectionPlanner.plan(
            text = "甲乙丙丁",
            comments = listOf(comment(paraIndex = 0, text = "甲乙")),
        ) ?: error("缺少注入规划")

        assertEquals(2, plan.shiftedOffset(2))
        assertEquals(3, plan.shiftedOffset(2, includeInsertionAtOffset = true))
        assertEquals(0..3, plan.shiftedRange(0, 2))
        assertEquals(3..5, plan.shiftedRange(2, 4))
    }

    @Test
    fun `同一插入点的多个评论占位符具有不同位置`() {
        val first = comment(paraIndex = 0, text = "甲乙", count = 1)
        val second = first.copy(paragraphHash = "second-hash", count = 2)
        val plan = OnlineParagraphCommentInjectionPlanner.plan(
            text = "甲乙",
            comments = listOf(first, second),
        ) ?: error("缺少注入规划")

        assertEquals("甲乙\uFFFD\uFFFD", plan.text)
        assertEquals(listOf(2, 3), plan.insertions.map(plan::insertedOffset))
        assertEquals(4, plan.shiftedOffset(2, includeInsertionAtOffset = true))
    }

    @Test
    fun `宿主拆分段落时段尾片段仍可注入`() {
        val plan = OnlineParagraphCommentInjectionPlanner.plan(
            text = "后半句",
            comments = listOf(comment(paraIndex = 0, text = "这是一段完整正文后半句")),
        ) ?: error("缺少注入规划")

        assertEquals("后半句\uFFFD", plan.text)
        assertEquals(3, plan.insertions.single().offset)
    }

    @Test
    fun `已有稳定标识不会重复注入`() {
        val value = comment(paraIndex = 2, text = "不会重复")
        val stableId = OnlineParagraphCommentInjectionPlanner.stableId(value)

        assertNull(
            OnlineParagraphCommentInjectionPlanner.plan(
                text = "不会重复",
                comments = listOf(value),
                existingIds = setOf(stableId),
            ),
        )
    }

    @Test
    fun `同文段按段落顺序分别匹配`() {
        val comments = listOf(
            comment(paraIndex = 0, text = "重复段"),
            comment(paraIndex = 1, text = "重复段"),
        )

        val plan = OnlineParagraphCommentInjectionPlanner.plan("重复段\n重复段", comments)
            ?: error("缺少注入规划")

        assertEquals("重复段\uFFFD\n重复段\uFFFD", plan.text)
        assertEquals(2, plan.insertions.size)
    }

    private fun comment(
        paraIndex: Int,
        text: String,
        count: Int = 1,
    ): OnlineParagraphCommentCount =
        OnlineParagraphCommentCount(
            sourceId = "source",
            bookId = "book",
            itemId = "item",
            chapterIndex = 3,
            paraIndex = paraIndex,
            paragraphText = text,
            count = count,
            updatedAtMs = 1L,
        )
}
