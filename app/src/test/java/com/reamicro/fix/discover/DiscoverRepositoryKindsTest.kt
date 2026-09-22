package com.reamicro.fix.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `exploreUrl` 的两种实际写法都要能解析出分类。
 *
 * JSON 数组写法来自用户实机的书旗源（`[{title,url},...]`），Legado 逐行写法来自
 * 原生 Legado 源。后者带 `-` 续行与注释，前者靠 `title`/`url` 两个键。
 */
class DiscoverRepositoryKindsTest {

    @Test
    fun `parses json array explore url`() {
        val raw = """
            [
              {"title":"都市","url":"http://example.com/list?c=都市&page={{page}}"},
              {"title":"玄幻","url":"http://example.com/list?c=玄幻&page={{page}}"}
            ]
        """.trimIndent()

        val kinds = parseExploreKinds(raw)

        assertEquals(2, kinds.size)
        assertEquals("都市", kinds[0].title)
        assertEquals(listOf("http://example.com/list?c=都市&page={{page}}"), kinds[0].urls)
        assertEquals("玄幻", kinds[1].title)
    }

    @Test
    fun `json array falls back to name key when title is absent`() {
        val raw = """[{"name":"榜单","url":"http://example.com/rank"}]"""

        val kinds = parseExploreKinds(raw)

        assertEquals(1, kinds.size)
        assertEquals("榜单", kinds[0].title)
    }

    @Test
    fun `json object without array wrapper still parses`() {
        val raw = """{"title":"分类","url":"http://example.com/c"}"""

        val kinds = parseExploreKinds(raw)

        assertEquals(1, kinds.size)
        assertEquals("分类", kinds[0].title)
    }

    @Test
    fun `json entries missing title or url are skipped`() {
        val raw = """
            [
              {"title":"","url":"http://example.com/a"},
              {"title":"有名字","url":""},
              {"title":"正常","url":"http://example.com/c"}
            ]
        """.trimIndent()

        val kinds = parseExploreKinds(raw)

        assertEquals(1, kinds.size)
        assertEquals("正常", kinds[0].title)
    }

    @Test
    fun `parses legado line format with continuation and comments`() {
        val raw = """
            # 这是注释
            玄幻::http://a.example.com/xh
            - http://a2.example.com/xh
            // 另一行注释
            都市::http://a.example.com/ds
        """.trimIndent()

        val kinds = parseExploreKinds(raw)

        assertEquals(2, kinds.size)
        assertEquals("玄幻", kinds[0].title)
        assertEquals(
            listOf("http://a.example.com/xh", "http://a2.example.com/xh"),
            kinds[0].urls,
        )
        assertEquals("都市", kinds[1].title)
        assertEquals(listOf("http://a.example.com/ds"), kinds[1].urls)
    }

    @Test
    fun `blank explore url yields no kinds`() {
        assertTrue(parseExploreKinds("   ").isEmpty())
        assertTrue(parseExploreKinds("").isEmpty())
    }

    @Test
    fun `json parser returns null for non json text`() {
        assertNull(parseExploreKindsJson("玄幻::http://a.example.com/xh"))
        assertNull(parseExploreKindsJson(""))
    }

    @Test
    fun `overlong kind title is truncated`() {
        val raw = """[{"title":"这是一个非常非常长的分类名称需要被截断","url":"http://example.com/a"}]"""

        val kinds = parseExploreKinds(raw)

        assertEquals(1, kinds.size)
        assertTrue(kinds[0].title.length <= 12)
    }

    @Test
    fun `malformed json array falls back to line parsing`() {
        // 以 `[` 开头但不是合法 JSON：JSON 解析失败后应退回逐行解析，而不是直接返回空。
        val raw = "[玄幻::http://a.example.com/xh]"
        val kinds = parseExploreKinds(raw)
        assertEquals(1, kinds.size)
        assertEquals("玄幻", kinds[0].title)
    }
}
