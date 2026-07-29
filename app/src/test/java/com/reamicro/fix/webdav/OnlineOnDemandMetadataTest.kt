package com.reamicro.fix.webdav

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineOnDemandMetadataTest {
    @Test
    fun `round trips on demand metadata without losing fixed hrefs`() {
        val source = metadata(
            chapters = listOf(
                chapter(0, OnlineChapterState.READY, downloadedAtMs = 1234L),
                chapter(1, OnlineChapterState.PENDING),
            ),
        )

        val decoded = OnlineOnDemandMetadataCodec.decode(
            OnlineOnDemandMetadataCodec.encode(source),
        )

        assertEquals(source, decoded)
        assertEquals("Text/chapter_0002.xhtml", decoded.chapters[1].href)
    }

    @Test
    fun `preserves complete multi volume catalog with only first three chapters ready`() {
        val chapters = (0 until 7).map { index ->
            chapter(
                index = index,
                state = if (index < 3) OnlineChapterState.READY else OnlineChapterState.PENDING,
                volumeTitle = when (index) {
                    in 0..1 -> "第一卷"
                    in 2..4 -> "第二卷"
                    else -> "第三卷"
                },
            )
        }

        val decoded = OnlineOnDemandMetadataCodec.decode(
            OnlineOnDemandMetadataCodec.encode(metadata(chapters)),
        )

        assertEquals(7, decoded.chapters.size)
        assertEquals(
            listOf(
                OnlineChapterState.READY,
                OnlineChapterState.READY,
                OnlineChapterState.READY,
                OnlineChapterState.PENDING,
                OnlineChapterState.PENDING,
                OnlineChapterState.PENDING,
                OnlineChapterState.PENDING,
            ),
            decoded.chapters.map(OnlineOnDemandChapter::state),
        )
        assertEquals(
            listOf("第一卷", "第一卷", "第二卷", "第二卷", "第二卷", "第三卷", "第三卷"),
            decoded.chapters.map(OnlineOnDemandChapter::volumeTitle),
        )
        assertEquals(
            (1..7).map { order -> "Text/chapter_${order.toString().padStart(4, '0')}.xhtml" },
            decoded.chapters.map(OnlineOnDemandChapter::href),
        )
    }

    @Test
    fun `recovers interrupted download as retryable pending chapter`() {
        val source = metadata(
            chapters = listOf(
                chapter(0, OnlineChapterState.DOWNLOADING, attempts = 2),
                chapter(1, OnlineChapterState.READY, downloadedAtMs = 88L),
            ),
        )

        val recovered = source.recoverAfterProcessRestart()

        assertEquals(OnlineChapterState.PENDING, recovered.chapters[0].state)
        assertEquals(2, recovered.chapters[0].attempts)
        assertTrue(recovered.chapters[0].lastError.contains("等待重试"))
        assertEquals(OnlineChapterState.READY, recovered.chapters[1].state)
    }

    @Test
    fun `increments attempts and records terminal state transitions`() {
        val pending = chapter(0, OnlineChapterState.PENDING)

        val downloading = pending.beginDownload()
        val failed = downloading.markFailed("网络错误")
        val ready = failed.beginDownload().markReady(999L)

        assertEquals(1, downloading.attempts)
        assertEquals(OnlineChapterState.FAILED, failed.state)
        assertEquals("网络错误", failed.lastError)
        assertEquals(2, ready.attempts)
        assertEquals(OnlineChapterState.READY, ready.state)
        assertEquals(999L, ready.downloadedAtMs)
        assertEquals("", ready.lastError)
    }

    @Test
    fun `defaults legacy index to full mode and pending state`() {
        val decoded = OnlineOnDemandMetadataCodec.decode(
            """{
              "version": 1,
              "sourceId": "legacy-source",
              "detailUrl": "https://example.com/book/1",
              "chapters": [
                {
                  "sourceChapterId": "chapter:1",
                  "url": "https://example.com/chapter/1",
                  "title": "第一章",
                  "volumeTitle": "正文",
                  "href": "Text/chapter_0001.xhtml"
                }
              ]
            }""".trimIndent(),
        )

        assertEquals(OnlineBookDownloadMode.FULL, decoded.mode)
        assertEquals(OnlineChapterState.PENDING, decoded.chapters.single().state)
    }

    @Test
    fun `writes utf8 metadata atomically without leaving temporary files`() {
        val bookDir = Files.createTempDirectory("reamicro-on-demand").toFile()
        try {
            val source = metadata(chapters = listOf(chapter(0, OnlineChapterState.READY)))

            OnlineOnDemandMetadataStore.write(bookDir, source)
            val loaded = OnlineOnDemandMetadataStore.read(bookDir)

            assertEquals(source, loaded)
            assertTrue(OnlineOnDemandMetadataStore.file(bookDir).readText(Charsets.UTF_8).contains("第一章"))
            assertFalse(
                OnlineOnDemandMetadataStore.file(bookDir).parentFile
                    ?.listFiles()
                    .orEmpty()
                    .any { it.name.contains(".tmp-") },
            )
        } finally {
            bookDir.deleteRecursively()
        }
    }

    @Test
    fun `serializes chapter updates without losing concurrent states`() {
        val bookDir = Files.createTempDirectory("reamicro-on-demand-update").toFile()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        try {
            OnlineOnDemandMetadataStore.write(
                bookDir,
                metadata(
                    chapters = listOf(
                        chapter(0, OnlineChapterState.PENDING),
                        chapter(1, OnlineChapterState.PENDING),
                    ),
                ),
            )
            repeat(2) { index ->
                Thread {
                    ready.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    OnlineOnDemandMetadataStore.update(bookDir) { latest ->
                        latest.updateChapter(index) { it.beginDownload().markReady((index + 1) * 100L) }
                    }
                    completed.countDown()
                }.start()
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))

            val loaded = requireNotNull(OnlineOnDemandMetadataStore.read(bookDir))
            assertEquals(OnlineChapterState.READY, loaded.chapters[0].state)
            assertEquals(OnlineChapterState.READY, loaded.chapters[1].state)
            assertEquals(1, loaded.chapters[0].attempts)
            assertEquals(1, loaded.chapters[1].attempts)
        } finally {
            bookDir.deleteRecursively()
        }
    }

    @Test
    fun `bridge matches href and deduplicates concurrent chapter downloads`() {
        val bookDir = Files.createTempDirectory("reamicro-on-demand-bridge").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val calls = AtomicInteger(0)
        try {
            val source = metadata(chapters = listOf(chapter(0, OnlineChapterState.PENDING)))
            OnlineOnDemandMetadataStore.write(bookDir, source)
            OnlineOnDemandBridge.attach {
                calls.incrementAndGet()
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val request = OnlineOnDemandBridge.pendingRequest(
                bookDir,
                "OEBPS/Text/chapter_0001.xhtml#fragment",
            )
            requireNotNull(request)

            assertTrue(OnlineOnDemandBridge.request(request) { completed.countDown() })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(OnlineOnDemandBridge.request(request) { completed.countDown() })
            release.countDown()

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            OnlineOnDemandBridge.detach()
            bookDir.deleteRecursively()
        }
    }

    private fun metadata(chapters: List<OnlineOnDemandChapter>) = OnlineOnDemandMetadata(
        sourceId = "source-1",
        detailUrl = "https://example.com/book/1",
        bookName = "测试书籍",
        mode = OnlineBookDownloadMode.ON_DEMAND,
        chapters = chapters,
    )

    private fun chapter(
        index: Int,
        state: OnlineChapterState,
        downloadedAtMs: Long = 0L,
        attempts: Int = 0,
        volumeTitle: String = "正文",
    ) = OnlineOnDemandChapter(
        sourceChapterId = "chapter:${index + 1}",
        url = "https://example.com/chapter/${index + 1}",
        title = if (index == 0) "第一章" else "第 ${index + 1} 章",
        volumeTitle = volumeTitle,
        href = "Text/chapter_${(index + 1).toString().padStart(4, '0')}.xhtml",
        state = state,
        downloadedAtMs = downloadedAtMs,
        attempts = attempts,
    )
}
