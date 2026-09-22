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
        assertTrue(kinds[0].title.length <= 20)
    }

    @Test
    fun `malformed json array falls back to line parsing`() {
        // 以 `[` 开头但不是合法 JSON：JSON 解析失败后应退回逐行解析，而不是直接返回空。
        val raw = "[玄幻::http://a.example.com/xh]"
        val kinds = parseExploreKinds(raw)
        assertEquals(1, kinds.size)
        assertEquals("玄幻", kinds[0].title)
    }

    @Test
    fun `parses js explore url with grouped literals`() {
        // 晚风里聚合源的形态：@js: 脚本里 groups 是纯 JSON 数组，search() 把查询对象拼成相对地址。
        val raw = """
            @js:
            var groups=[["排序",[["最近更新",{"sort":"updated_desc"}],["缓存最多",{"sort":"cache_desc"}]],2],
            ["分类",[["言情",{"category":"言情","sort":"cache_desc"}],["都市",{"category":"都市","sort":"cache_desc"}]],3]];
            function search(params){var parts=[];for(var key in params){if(params[key]!=='')parts.push(key+'='+encodeURIComponent(String(params[key])));}parts.push('cache_min=1');parts.push('page={{page}}');parts.push('limit=20');return '/reader-api/search?'+parts.join('&');}
        """.trimIndent()

        val kinds = parseExploreKinds(raw)

        assertEquals(4, kinds.size)
        assertEquals("最近更新", kinds[0].title)
        assertEquals(
            "/reader-api/search?sort=updated_desc&cache_min=1&page={{page}}&limit=20",
            kinds[0].urls.single(),
        )
        assertEquals("言情", kinds[2].title)
        assertEquals(
            "/reader-api/search?category=" + java.net.URLEncoder.encode("言情", "UTF-8") +
                "&sort=cache_desc&cache_min=1&page={{page}}&limit=20",
            kinds[2].urls.single(),
        )
    }

    @Test
    fun `js explore url without search template yields nothing`() {
        val raw = "@js:\nvar groups=[[\"排序\",[[\"最近更新\",{\"sort\":\"updated_desc\"}]],2]];"

        assertTrue(parseExploreKinds(raw).isEmpty())
    }
}
