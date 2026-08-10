package com.reamicro.fix.online.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPUB 生成引擎的行为锁定。
 *
 * 这些函数原先埋在 WebDavDriveHook 里，只能靠「下一本书导入后肉眼看目录对不对」来
 * 验证。抽成不依赖 hook 的顶层函数后可以直接跑 JVM 单测，这里把关键行为固定住。
 */
class OnlineEpubWriterTest {

    // ---- 章节标题拆分 ----

    @Test
    fun `带序号的章节标题拆成序号与副标题两段`() {
        assertEquals(listOf("第一章", "初入江湖"), splitChapterHeading("第一章 初入江湖"))
    }

    @Test
    fun `序号与副标题之间的分隔符会被吃掉`() {
        assertEquals(listOf("第十二章", "旧事"), splitChapterHeading("第十二章：旧事"))
    }

    @Test
    fun `没有序号的标题保持单段`() {
        assertEquals(listOf("楔子"), splitChapterHeading("楔子"))
    }

    @Test
    fun `空标题返回单个空串而不是空列表`() {
        assertEquals(listOf(""), splitChapterHeading("   "))
    }

    @Test
    fun `单段标题渲染为单行标题`() {
        val html = chapterHeadingHtml("楔子")
        assertTrue(html, html.contains("楔子"))
    }

    @Test
    fun `标题里的尖括号会被转义，避免破坏 xhtml`() {
        val html = chapterHeadingHtml("第一章 <script>")
        assertTrue(html, html.contains("&lt;script&gt;"))
        assertTrue(html, !html.contains("<script>"))
    }

    // ---- 正文开头重复标题的清理 ----

    @Test
    fun `正文首行与章节标题重复时整行删掉`() {
        val lines = listOf("第一章 初入江湖", "他推开门。")
        assertEquals(listOf("他推开门。"), stripDuplicatedChapterTitle("第一章 初入江湖", lines))
    }

    @Test
    fun `正文首行以标题开头时只删掉标题部分`() {
        val lines = listOf("第一章 初入江湖 他推开门。")
        assertEquals(listOf("他推开门。"), stripDuplicatedChapterTitle("第一章 初入江湖", lines))
    }

    @Test
    fun `标题被拆到正文前两行时两行一起删`() {
        val lines = listOf("第一章", "初入江湖", "他推开门。")
        assertEquals(listOf("他推开门。"), stripDuplicatedChapterTitle("第一章初入江湖", lines))
    }

    @Test
    fun `正文与标题无关时一行不动`() {
        val lines = listOf("他推开门。", "外面下着雨。")
        assertEquals(lines, stripDuplicatedChapterTitle("第一章 初入江湖", lines))
    }

    @Test
    fun `空正文返回空列表`() {
        assertEquals(emptyList<String>(), stripDuplicatedChapterTitle("第一章", emptyList()))
    }

    // ---- 章节文件名 ----

    @Test
    fun `章节 href 按四位序号补零`() {
        val hrefs = defaultOnlineChapterHrefs(3)
        assertEquals(
            listOf(
                "Text/chapter_0001.xhtml",
                "Text/chapter_0002.xhtml",
                "Text/chapter_0003.xhtml",
            ),
            hrefs,
        )
    }

    @Test
    fun `零章时 href 列表为空`() {
        assertEquals(emptyList<String>(), defaultOnlineChapterHrefs(0))
    }

    // ---- 文件名安全化 ----

    @Test
    fun `文件名里的路径分隔符与通配符被替换`() {
        assertEquals("a_b_c", safeOnlineFileName("a/b\\c"))
        assertEquals("书名_1", safeOnlineFileName("书名?1"))
    }

    @Test
    fun `超长文件名截到 80 字符`() {
        assertEquals(80, safeOnlineFileName("书".repeat(200)).length)
    }

    @Test
    fun `非法字符会被折叠成一个下划线，全空白才退回默认名`() {
        // 连续非法字符整体替换为单个下划线，所以 "///" 得到 "_" 而不是默认名。
        assertEquals("_", safeOnlineFileName("///"))
        assertEquals("online_completion", safeOnlineFileName("   "))
        assertEquals("online_completion", safeOnlineFileName(""))
    }

    // ---- 封面扩展名识别 ----

    @Test
    fun `按 MIME 识别封面扩展名`() {
        assertEquals("jpg", onlineCoverExtFromMime("image/jpeg"))
        assertEquals("jpg", onlineCoverExtFromMime("image/jpg; charset=binary"))
        assertEquals("png", onlineCoverExtFromMime("IMAGE/PNG"))
        assertEquals("webp", onlineCoverExtFromMime("image/webp"))
        assertNull(onlineCoverExtFromMime("text/html"))
    }

    @Test
    fun `按文件头魔数识别封面扩展名`() {
        assertEquals("jpg", onlineCoverExtFromBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(
            "png",
            onlineCoverExtFromBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals("gif", onlineCoverExtFromBytes("GIF89a".toByteArray()))
        assertEquals("webp", onlineCoverExtFromBytes("RIFF____WEBP".toByteArray()))
    }

    @Test
    fun `魔数不够长或不认识时返回 null`() {
        assertNull(onlineCoverExtFromBytes(byteArrayOf(0xFF.toByte())))
        assertNull(onlineCoverExtFromBytes("not an image".toByteArray()))
    }

    @Test
    fun `按 URL 后缀识别封面扩展名并归一化 jpeg`() {
        assertEquals("png", onlineCoverExtFromUrl("https://a.com/x/cover.png"))
        assertEquals("jpg", onlineCoverExtFromUrl("https://a.com/cover.jpeg?v=2#frag"))
        assertEquals("jpg", onlineCoverExtFromUrl("https://a.com/cover"))
        assertEquals("jpg", onlineCoverExtFromUrl("https://a.com/cover.bin"))
    }

    @Test
    fun `扩展名映射回 MIME`() {
        assertEquals("image/png", coverMimeType("png"))
        assertEquals("image/webp", coverMimeType("WEBP"))
        assertEquals("image/gif", coverMimeType("gif"))
        assertEquals("image/jpeg", coverMimeType("jpg"))
        assertEquals("image/jpeg", coverMimeType("未知"))
    }

    // ---- 卷首页 href ----

    @Test
    fun `卷首页 href 按序号补零`() {
        assertEquals("Text/volume_0001.xhtml", onlineVolumeHref(1))
        assertEquals("Text/volume_0042.xhtml", onlineVolumeHref(42))
    }
}
