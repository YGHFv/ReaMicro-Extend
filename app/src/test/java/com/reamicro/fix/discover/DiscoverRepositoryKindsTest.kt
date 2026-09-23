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

    // ── 多重标签筛选（parseExploreFilter） ─────────────────────────────────────

    /** 晚风里聚合源的裁剪形态：四个维度 + sorts 表 + search 模板，与实机书源一致。 */
    private val multiFilterScript = """
        @js:
        var groups=[["排序",[["最近更新",{"sort":"updated_desc"}],["缓存最多",{"sort":"cache_desc"}]],2],
        ["平台",[["PO18",{"platform":"po18","sort":"cache_desc"}],["起点",{"platform":"qidian","sort":"cache_desc"}]],2],
        ["分类",[["言情",{"category":"言情","sort":"cache_desc"}],["都市",{"category":"都市","sort":"cache_desc"}]],3],
        ["标签",[["纯爱",{"tag":"纯爱","sort":"cache_desc"}],["美食",{"tag":"美食","sort":"cache_desc"}]],3]];
        var sorts=[{label:'更新时间',value:'updated_desc'},{label:'缓存最多',value:'cache_desc'},{label:'字数最多',value:'word_desc'}];
        function search(params){var parts=[];for(var key in params){if(params[key]!=='')parts.push(key+'='+encodeURIComponent(String(params[key])));}parts.push('cache_min=1');parts.push('page={{page}}');parts.push('limit=20');return '/reader-api/search?'+parts.join('&');}
    """.trimIndent()

    @Test
    fun `parses multi filter groups with sorts table`() {
        val filter = parseExploreFilter(multiFilterScript)!!

        assertEquals(listOf("排序", "平台", "分类", "标签"), filter.groups.map { it.name })
        // 排序维度用 sorts 表（与源自己的面板一致），不是 groups[0] 的条目。
        assertEquals(listOf("更新时间", "缓存最多", "字数最多"), filter.groups[0].options.map { it.title })
        assertEquals(listOf("sort" to "updated_desc"), filter.groups[0].options[0].params)
        // 其余维度前面补「全部」（空参数），条目只保留自己的维度键。
        assertEquals(listOf("全部", "PO18", "起点"), filter.groups[1].options.map { it.title })
        assertEquals(emptyList<Pair<String, String>>(), filter.groups[1].options[0].params)
        assertEquals(listOf("platform" to "qidian"), filter.groups[1].options[2].params)
        assertEquals(listOf("category" to "都市"), filter.groups[2].options[2].params)
        assertEquals(listOf("tag" to "美食"), filter.groups[3].options[2].params)
    }

    @Test
    fun `default filter selection matches script state defaults`() {
        val filter = parseExploreFilter(multiFilterScript)!!
        val selection = filter.defaultSelection()

        assertEquals("更新时间", selection["排序"])
        assertEquals("全部", selection["平台"])
        // 默认组合的地址：只有 sort 一个维度参数 + 固定追加。
        assertEquals(
            "/reader-api/search?sort=updated_desc&cache_min=1&page={{page}}&limit=20",
            filter.buildUrl(selection),
        )
        assertEquals("组合筛选", filter.selectionTitle(selection))
    }

    @Test
    fun `combined filter url merges one value per dimension in group order`() {
        val filter = parseExploreFilter(multiFilterScript)!!
        val selection = mapOf("排序" to "字数最多", "平台" to "起点", "分类" to "都市", "标签" to "全部")

        // 键序与脚本 search({sort,platform,category,tag}) 的 for...in 一致；中文按 UTF-8 编码；
        // 平台条目附带的 sort=cache_desc 不得把已选的 sort 覆盖掉。
        assertEquals(
            "/reader-api/search?sort=word_desc&platform=qidian&category=" +
                java.net.URLEncoder.encode("都市", "UTF-8") +
                "&cache_min=1&page={{page}}&limit=20",
            filter.buildUrl(selection),
        )
        assertEquals("字数最多·起点·都市", filter.selectionTitle(selection))
    }

    @Test
    fun `non script or single dimension sources have no multi filter`() {
        assertNull(parseExploreFilter("玄幻::http://a.example.com/xh"))
        assertNull(parseExploreFilter("[{\"title\":\"都市\",\"url\":\"http://a/b\"}]"))
        // 只有一个维度（sorts + 一组，或两组都没有）不算多重筛选。
        val single = """
            @js:
            var groups=[["分类",[["言情",{"category":"言情"}],["都市",{"category":"都市"}]],2]];
            function search(params){var parts=[];parts.push('page={{page}}');return '/api?'+parts.join('&');}
        """.trimIndent()
        assertNull(parseExploreFilter(single))
    }
}
