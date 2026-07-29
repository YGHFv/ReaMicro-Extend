package com.reamicro.fix.online

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineParagraphCommentTest {
    @Test
    fun `round trips independent paragraph comment cache`() {
        val cache = cache()

        val decoded = OnlineParagraphCommentCacheCodec.decode(
            OnlineParagraphCommentCacheCodec.encode(cache),
        )

        assertEquals(cache, decoded)
        assertEquals(12, decoded.comment(3)?.count)
        assertEquals(null, decoded.comment(4))
    }

    @Test
    fun `writes paragraph cache outside epub content atomically`() {
        val root = Files.createTempDirectory("reamicro-paragraph-comments").toFile()
        try {
            val cache = cache()

            OnlineParagraphCommentCacheStore.write(root, cache)
            val loaded = OnlineParagraphCommentCacheStore.read(
                root,
                cache.sourceId,
                cache.bookId,
                cache.itemId,
            )

            assertEquals(cache, loaded)
            val target = OnlineParagraphCommentCacheStore.file(root, cache.sourceId, cache.bookId, cache.itemId)
            assertTrue(target.isFile)
            assertTrue(target.absolutePath.contains(OnlineParagraphCommentCacheStore.DIRECTORY_NAME))
            assertFalse(target.absolutePath.contains("OEBPS"))
            assertFalse(target.parentFile.listFiles().orEmpty().any { it.name.contains(".tmp-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `parses both nested idea count response shapes`() {
        val doubleNested = FanqieParagraphCommentApi.parseCounts(
            """{"data":{"data":{"idea_data":{"1":3,"2":{"count":5},"4":0}}}}""",
        )
        val singleNested = FanqieParagraphCommentApi.parseCounts(
            """{"data":{"idea_data":[{"para_index":7,"comment_count":9},{"paraIndex":8,"idea_count":2}]}}""",
        )

        assertEquals(mapOf(1 to 3, 2 to 5), doubleNested)
        assertEquals(mapOf(7 to 9, 8 to 2), singleNested)
    }

    @Test
    fun `extracts fanqie book and item identifiers from imported urls`() {
        val encodedItem = java.util.Base64.getEncoder().encodeToString("item-9988".toByteArray())

        assertEquals("12345", FanqieParagraphCommentApi.bookId("https://example.test/detail?bookId=12345"))
        assertEquals("67890", FanqieParagraphCommentApi.bookId("https://example.test/detail?book_id=67890"))
        assertEquals("item-9988", FanqieParagraphCommentApi.itemId("data:;base64,$encodedItem,{\"type\":\"novel\"}"))
        assertEquals("item 2", FanqieParagraphCommentApi.itemId("https://example.test/content?item_id=item%202"))
    }

    @Test
    fun `builds encoded fanqie comment urls from real source contract`() {
        val url = FanqieParagraphCommentApi.commentsUrl(
            bookId = "书 1",
            itemId = "章/2",
            paraIndex = 17,
            offset = 20,
            count = 30,
            apiKey = "密钥 +",
        )
        val query = url.substringAfter('?').split('&').associate { part ->
            val (key, value) = part.split('=', limit = 2)
            decode(key) to decode(value)
        }

        assertEquals("idea_comments", query["api"])
        assertEquals("书 1", query["book_id"])
        assertEquals("章/2", query["item_id"])
        assertEquals("17", query["para_index"])
        assertEquals("20", query["offset"])
        assertEquals("30", query["count"])
        assertEquals("密钥 +", query["api_key"])
    }

    private fun cache() = OnlineParagraphCommentCache(
        sourceId = "source-1",
        bookId = "book-1",
        itemId = "item-1",
        chapterIndex = 4,
        comments = listOf(
            OnlineParagraphCommentCount(
                sourceId = "source-1",
                bookId = "book-1",
                itemId = "item-1",
                chapterIndex = 4,
                paraIndex = 3,
                paragraphText = "这是一个段落。",
                count = 12,
                updatedAtMs = 1234L,
            ),
        ),
        updatedAtMs = 1234L,
    )

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
