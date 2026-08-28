package com.reamicro.fix.online.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节稳定标识的形态与向后兼容。
 *
 * 历史问题：`sourceChapterId` 存的是完整 URL，元数据文件里每一章都重复一遍域名和接口路径
 * （`https://book.example.fun/reader-api/books/1039831421/chapters/789864061`），
 * 又长又没有识别价值。抓正文用的完整地址另存在 `url` 字段，标识只需要 ID 段。
 *
 * 改格式的风险在于**已下载图书**：它们存的是旧格式完整 URL，新算出来是短 ID，
 * 直接比对会全部对不上，更新时每章都被判成新章节重下。所以 `plan()` 两边都过
 * `sourceChapterMatchKey` 归一。
 */
class SourceChapterIdTest {

    private fun id(url: String) = OnlineChapterUpdatePlanner.stableSourceChapterId(url)

    @Test
    fun `REST 风格路径只取章节 ID`() {
        assertEquals(
            "789864061",
            id("https://book.whispertale.fun/reader-api/books/1039831421/chapters/789864061"),
        )
    }

    @Test
    fun `标识里不含域名与接口路径`() {
        val value = id("https://book.whispertale.fun/reader-api/books/1039831421/chapters/789864061")
        assertFalse("不该出现域名", value.contains("whispertale"))
        assertFalse("不该出现协议", value.contains("http"))
        assertFalse("不该出现接口路径", value.contains("reader-api"))
    }

    @Test
    fun `查询参数优先于路径`() {
        // 查询参数是书源显式声明的章节 ID，比路径推断更可靠。
        assertEquals("chapterid:9527", id("https://e.com/read.php?bookid=1&chapterid=9527"))
        assertEquals("itemid:abc", id("https://e.com/x/123?itemid=abc"))
    }

    @Test
    fun `分页式路径带上前一段避免撞号`() {
        // .../read/书ID/章节ID —— 只取末段会让不同书的同号章节撞成一个标识。
        assertEquals("1001/5", id("https://e.com/read/1001/5"))
        assertEquals("1002/5", id("https://e.com/read/1002/5"))
        assertNotEquals(id("https://e.com/read/1001/5"), id("https://e.com/read/1002/5"))
    }

    @Test
    fun `末段带扩展名时去掉扩展名`() {
        assertEquals("123456789", id("https://e.com/book/123456789.html"))
    }

    @Test
    fun `不透明 ID 段可作标识`() {
        assertEquals("a1b2c3d4e5f6", id("https://e.com/chapter/a1b2c3d4e5f6"))
    }

    @Test
    fun `太短的词不当作 ID`() {
        // "read" 这种路径词不是标识，要退回完整 URL，否则所有章节会撞成一个。
        val value = id("https://e.com/book/read")
        assertTrue("应退回完整 URL，实际 $value", value.contains("e.com"))
    }

    @Test
    fun `无法解析时退回原值`() {
        assertEquals("", id(""))
        assertEquals("", id("   "))
    }

    @Test
    fun `锚点被丢弃`() {
        assertEquals(id("https://e.com/c/12345678"), id("https://e.com/c/12345678#top"))
    }

    @Test
    fun `不同章节标识不相同`() {
        val first = id("https://book.e.fun/reader-api/books/100/chapters/789864061")
        val second = id("https://book.e.fun/reader-api/books/100/chapters/790080336")
        assertNotEquals(first, second)
    }

    // ---------- 向后兼容 ----------

    @Test
    fun `匹配键把旧格式完整 URL 压成短 ID`() {
        val legacy = "https://book.whispertale.fun/reader-api/books/1039831421/chapters/789864061"
        assertEquals(
            OnlineChapterUpdatePlanner.sourceChapterMatchKey(legacy),
            OnlineChapterUpdatePlanner.sourceChapterMatchKey("789864061"),
        )
    }

    @Test
    fun `匹配键对短 ID 原样返回`() {
        assertEquals("789864061", OnlineChapterUpdatePlanner.sourceChapterMatchKey("789864061"))
        assertEquals("chapterid:9527", OnlineChapterUpdatePlanner.sourceChapterMatchKey("chapterid:9527"))
    }

    @Test
    fun `已下载图书用旧标识仍能匹配上新标识`() {
        // 这是改格式最大的风险点：对不上就会把每一章都当成新章节重下。
        val legacyUrl = "https://book.e.fun/reader-api/books/100/chapters/789864061"
        val stored = listOf(
            StoredOnlineChapter(
                sourceChapterId = legacyUrl,
                volumeTitle = "正文卷",
                title = "第二章 大难不死",
                href = "Text/chapter_0004.xhtml",
            ),
        )
        val remote = listOf(
            RemoteOnlineChapter(
                sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(legacyUrl),
                volumeTitle = "正文卷",
                title = "第二章 大难不死",
            ),
        )
        val slots = OnlineChapterUpdatePlanner.plan(stored, remote)
        assertEquals(1, slots.size)
        assertEquals(
            "旧标识必须匹配上，否则已下载章节会被重下",
            legacyUrl,
            slots[0].stored?.sourceChapterId,
        )
    }

    @Test
    fun `标题变了但标识不变时仍认作同一章`() {
        val legacyUrl = "https://book.e.fun/reader-api/books/100/chapters/789864061"
        val stored = listOf(
            StoredOnlineChapter(sourceChapterId = legacyUrl, volumeTitle = "正文卷", title = "旧标题", href = "Text/c1.xhtml"),
        )
        val remote = listOf(
            RemoteOnlineChapter(sourceChapterId = "789864061", volumeTitle = "正文卷", title = "新标题"),
        )
        assertEquals(legacyUrl, OnlineChapterUpdatePlanner.plan(stored, remote)[0].stored?.sourceChapterId)
    }

    @Test
    fun `不同章节不会因归一而互相匹配`() {
        val stored = listOf(
            StoredOnlineChapter(
                sourceChapterId = "https://book.e.fun/reader-api/books/100/chapters/111",
                volumeTitle = "正文卷",
                title = "第一章",
                href = "Text/c1.xhtml",
            ),
        )
        val remote = listOf(
            RemoteOnlineChapter(sourceChapterId = "222", volumeTitle = "正文卷", title = "第九章"),
        )
        assertEquals(null, OnlineChapterUpdatePlanner.plan(stored, remote)[0].stored)
    }
}
