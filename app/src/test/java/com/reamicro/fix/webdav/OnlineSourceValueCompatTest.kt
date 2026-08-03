package com.reamicro.fix.webdav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineSourceValueCompatTest {
    @Test
    fun parsesLegadoHeadersFromChapterUrl() {
        val request = parseOnlineUrlRequestCompat(
            "https://content.example/chapter/1?mode=auto,{\"headers\":{\"Accept\":\"application/json\",\"X-API-Key\":\"secret\"}}",
        )

        assertEquals("https://content.example/chapter/1?mode=auto", request.url)
        assertEquals(mapOf("Accept" to "application/json", "X-API-Key" to "secret"), request.headers)
    }

    @Test
    fun malformedLegadoOptionsDoNotChangeUrl() {
        val raw = "https://example.com/chapter/1,{not-json}"
        assertEquals(raw, parseOnlineUrlRequestCompat(raw).url)
    }

    @Test
    fun `builds qq reader cover from book id`() {
        val rule = "$.book_id\n@js:\nresult='https://wfqqreader-1252317822.image.myqcloud.com/cover/'"
        assertEquals(
            "https://wfqqreader-1252317822.image.myqcloud.com/cover/328/56826328/t7_56826328.webp",
            evaluateQqReaderCoverRule(rule, "56826328"),
        )
    }

    @Test
    fun `ignores unrelated inline scripts`() {
        assertNull(evaluateQqReaderCoverRule("$.book_id\n@js:\nresult='other'", "56826328"))
    }

    @Test
    fun `unwraps and sorts js wrapped chapter list`() {
        val rule = """
            <js>
            var data={};
            var items=data.items||[];
            items.sort(function(a,b){return (Number(b.order)||0)-(Number(a.order)||0);});
            JSON.stringify({list:items});
            </js>
            $.list[*]
        """.trimIndent()
        val compat = requireNotNull(resolveOnlineChapterListRuleCompat(rule))
        val values = listOf(
            JSONObject("""{"title":"第一章","order":1}"""),
            JSONObject("""{"title":"第三章","order":3}"""),
            JSONObject("""{"title":"第二章","order":2}"""),
        )

        assertEquals("$.items[*]", compat.jsonPath)
        assertEquals("order", compat.sortField)
        assertEquals(true, compat.descending)
        assertEquals(
            listOf("第三章", "第二章", "第一章"),
            applyOnlineChapterListRuleCompat(values, compat).map { (it as JSONObject).getString("title") },
        )
    }

    @Test
    fun `leaves ordinary chapter json path on original parser`() {
        assertNull(resolveOnlineChapterListRuleCompat("$..chapterList[*]"))
    }

    @Test
    fun `builds chapter url from book and chapter ids`() {
        val rule = """
            $.chapter_id
            <js>
            var bid=(String(baseUrl||'').match(/books\/(\d+)/)||[])[1]||'';
            result='http://103.52.152.82:11457/api/books/'+bid+'/chapters/'+result;
            </js>
        """.trimIndent()

        assertEquals(
            "http://103.52.152.82:11457/api/books/54168268/chapters/987654",
            evaluateOnlineChapterUrlRule(
                rule,
                selectedValue = "987654",
                baseUrl = "http://103.52.152.82:11457/api/books/54168268/catalog",
            ),
        )
    }

    @Test
    fun `builds fanqie chapter url from real source rule`() {
        val rule = """
            $.chapterId<js>var cid=String(result||''),m=String(baseUrl).match(/\/books\/(\d+)\/directory/);result=/^volume-/i.test(cid)?'':(m?'/v1/books/'+m[1]+'/chapters/'+cid+'?source=fanqie':'')</js>
        """.trimIndent()
        val baseUrl = "https://api.yuezhi.me/v1/books/7143038691944959011/directory?source=fanqie"
        val evaluatedUrl = requireNotNull(
            evaluateOnlineChapterUrlRule(
                rule,
                selectedValue = "7143038691944959012",
                baseUrl = baseUrl,
            ),
        )

        assertEquals(
            "https://api.yuezhi.me/v1/books/7143038691944959011/chapters/7143038691944959012?source=fanqie",
            resolveOnlineUrlCompat(baseUrl, evaluatedUrl),
        )
    }

    @Test
    fun `keeps absolute online url unchanged`() {
        assertEquals(
            "https://content.example/chapter/1",
            resolveOnlineUrlCompat(
                "https://api.example/books/1/directory",
                "https://content.example/chapter/1",
            ),
        )
    }

    @Test
    fun `infers completion only from strong last chapter signals`() {
        assertEquals("完结", inferOnlineStatusFromLastChapterTitle("完结感言"))
        assertEquals("完结", inferOnlineStatusFromLastChapterTitle("新书《我被天使绑架了》"))
        assertEquals("", inferOnlineStatusFromLastChapterTitle("第147章 学校里的泽希塔"))
    }

    @Test
    fun `removes update count suffix without deleting other parentheses`() {
        assertEquals("第20章 新的旅程", cleanOnlineChapterTitleValue("第20章 新的旅程（三更）"))
        assertEquals("第20章 新的旅程", cleanOnlineChapterTitleValue("第20章 新的旅程(3更)"))
        assertEquals("第20章 新的旅程（3.3k）", cleanOnlineChapterTitleValue("第20章 新的旅程（3.3k）"))
        assertEquals("第20章 新的旅程（番外）", cleanOnlineChapterTitleValue("第20章 新的旅程（番外）"))
    }

    @Test
    fun `removes only explicit chapter end marker from content`() {
        assertEquals("正文  后记（并非结束）", cleanOnlineChapterContentValue("正文（本韋完） 后记（并非结束）"))
        assertEquals("正文  后记", cleanOnlineChapterContentValue("正文(本章完) 后记"))
    }

    @Test
    fun `extracts readable online http error detail`() {
        assertEquals("分包中未找到章节文件", onlineHttpErrorDetail("""{"detail":"分包中未找到章节文件"}"""))
        assertEquals("", onlineHttpErrorDetail("<html>server error</html>"))
    }

    @Test
    fun `formats online word count variants`() {
        assertEquals("123.5万字", formatOnlineWordCountValue("123.50万"))
        assertEquals("12.3万字", formatOnlineWordCountValue("123456.0"))
        assertEquals("8.2亿字", formatOnlineWordCountValue("8.20 亿字"))
        assertEquals("9999字", formatOnlineWordCountValue("9,999"))
        assertEquals("12.3万字", formatOnlineWordCountValue("·12.30万字"))
        assertEquals("", formatOnlineWordCountValue("一"))
    }

    @Test
    fun `does not treat chapter count selector as word count`() {
        assertEquals(true, isOnlineChapterCountSelector("$.book.chapter_count"))
        assertEquals(false, isOnlineChapterCountSelector("$.word_text"))
    }

    @Test
    fun `ranks exact author match ahead of loose title matches`() {
        assertEquals(1_000, onlineSearchRelevanceScore("有一只川海", "有一只川海", "其他作者"))
        assertEquals(900, onlineSearchRelevanceScore("有一只川海", "我被天使绑架了？", "有一只川海"))
        assertEquals(700, onlineSearchRelevanceScore("有一只川海", "这是有一只川海的故事", "其他作者"))
        assertEquals(0, onlineSearchRelevanceScore("有一只川海", "华娱：我有一只机器猫", "默闻我"))
    }
}
