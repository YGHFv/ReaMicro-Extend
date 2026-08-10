package com.reamicro.fix.online.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线源搜索解析引擎的行为锁定。
 *
 * 这些函数原先埋在 WebDavDriveHook 里，只能靠「换个书源搜一次看结果对不对」验证。
 * 抽成不依赖 hook 的顶层函数后可以直接跑 JVM 单测。
 */
class OnlineSearchParsingTest {

    // ---- 连载状态归一化 ----

    @Test
    fun `从人类可读文本识别连载状态`() {
        assertEquals("断更", statusTextFromHumanText("已断更三个月"))
        assertEquals("下架", statusTextFromHumanText("本书已下架"))
        assertEquals("连载", statusTextFromHumanText("连载中"))
        assertEquals("完结", statusTextFromHumanText("全本完结"))
        assertEquals("完结", statusTextFromHumanText("已完本"))
    }

    @Test
    fun `断更优先于完结，避免"已完结但断更"被判成完结`() {
        assertEquals("断更", statusTextFromHumanText("已完结 断更"))
    }

    @Test
    fun `无法识别的状态文本返回 null 交给后续规则`() {
        assertNull(statusTextFromHumanText("每日更新"))
        assertNull(statusTextFromHumanText(""))
    }

    // ---- legado options 块定位 ----

    @Test
    fun `识别搜索 URL 尾部的 options JSON 块`() {
        val raw = """https://a.com/s?q=key,{"method":"POST"}"""
        val index = indexOfOptionsBlock(raw)
        assertTrue(index > 0)
        assertEquals("""{"method":"POST"}""", raw.substring(index).removePrefix(",").trim())
    }

    @Test
    fun `options 块允许逗号后带空格`() {
        val raw = """https://a.com/s?q=key, {"method":"POST"}"""
        assertTrue(indexOfOptionsBlock(raw) > 0)
    }

    @Test
    fun `URL 自身查询参数里的逗号不会被误判为 options 块`() {
        assertEquals(-1, indexOfOptionsBlock("https://a.com/s?tags=a,b,c"))
    }

    @Test
    fun `没有 options 块时返回 -1`() {
        assertEquals(-1, indexOfOptionsBlock("https://a.com/search?q=key"))
    }

    @Test
    fun `options JSON 解析失败时退回空对象而不是抛异常`() {
        assertEquals(0, parseOptionsJson("不是 json").length())
        assertEquals(0, parseOptionsJson(null).length())
        assertEquals(0, parseOptionsJson("").length())
        assertEquals("POST", parseOptionsJson("""{"method":"POST"}""").optString("method"))
    }

    // ---- JSON 根节点解析 ----

    @Test
    fun `按首字符判断 JSON 根是数组还是对象`() {
        assertTrue(parseOnlineJsonRoot("""[{"a":1}]""") is org.json.JSONArray)
        assertTrue(parseOnlineJsonRoot("""{"a":1}""") is org.json.JSONObject)
    }

    @Test
    fun `非 JSON 响应返回 null 而不是抛异常`() {
        assertNull(parseOnlineJsonRoot("<html>404</html>"))
        assertNull(parseOnlineJsonRoot(""))
    }

    // ---- 番茄封面地址重写 ----

    @Test
    fun `裸文件名补成 novel-pic 路径`() {
        assertEquals(
            "https://p6-novel.byteimg.com/origin/novel-pic/abc123",
            replaceFanqieCover("abc123"),
        )
    }

    @Test
    fun `完整 URL 只取路径部分并去掉 origin 前缀`() {
        assertEquals(
            "https://p6-novel.byteimg.com/origin/novel-pic/abc123",
            replaceFanqieCover("https://other.cdn.com/origin/novel-pic/abc123"),
        )
    }

    @Test
    fun `尾部的尺寸参数与查询串被裁掉`() {
        assertEquals(
            "https://p6-novel.byteimg.com/origin/novel-pic/abc123",
            replaceFanqieCover("novel-pic/abc123~tplv-500x500.jpeg"),
        )
        assertEquals(
            "https://p6-novel.byteimg.com/origin/novel-pic/abc123",
            replaceFanqieCover("novel-pic/abc123?x=1"),
        )
    }

    @Test
    fun `协议相对地址按 https 处理`() {
        assertEquals(
            "https://p6-novel.byteimg.com/origin/novel-images/x/y",
            replaceFanqieCover("//cdn.com/novel-images/x/y"),
        )
    }

    @Test
    fun `空封面返回空串`() {
        assertEquals("", replaceFanqieCover(""))
        assertEquals("", replaceFanqieCover("   "))
    }

    // ---- JSON 取值 ----

    @Test
    fun `firstJsonString 取第一个非空字段`() {
        val json = org.json.JSONObject("""{"a":"","b":"null","c":"命中"}""")
        assertEquals("命中", firstJsonString(json, "a", "b", "c"))
    }

    @Test
    fun `firstJsonString 全空时返回空串`() {
        val json = org.json.JSONObject("""{"a":""}""")
        assertEquals("", firstJsonString(json, "a", "missing"))
    }
}
