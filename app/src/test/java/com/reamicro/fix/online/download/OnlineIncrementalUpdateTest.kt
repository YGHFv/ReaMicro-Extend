package com.reamicro.fix.online.download

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineIncrementalUpdateTest {
    private fun legacyBook(count: Int): String = JSONObject().put("version", 1)
        .put("sourceId", "source").put("detailUrl", "https://example/book/1")
        .put("chapters", JSONArray().apply {
            repeat(count) { index ->
                put(JSONObject().put("sourceChapterId", "cid:${index + 1}")
                    .put("title", "第${index + 1}章").put("volumeTitle", "")
                    .put("href", "Text/chapter_${index + 1}.xhtml"))
            }
        }).toString()

    @Test fun aFullBookWith873ChaptersDoesNotBecome873PendingDownloads() {
        val metadata = OnlineOnDemandMetadataCodec.decode(legacyBook(873))
        assertEquals(OnlineBookDownloadMode.FULL, metadata.mode)
        assertEquals(873, metadata.chapters.size)
        assertTrue(metadata.chapters.all { it.state == OnlineChapterState.READY })
    }

    @Test fun updatingAnExistingBookPlansOnlyTheFiveNewChapters() {
        val metadata = OnlineOnDemandMetadataCodec.decode(legacyBook(873))
        val stored = metadata.chapters.map {
            StoredOnlineChapter(it.sourceChapterId, it.title, it.volumeTitle, it.href)
        }
        val remote = (1..878).map { RemoteOnlineChapter("cid:$it", "第${it}章", "") }
        val plan = OnlineChapterUpdatePlanner.plan(stored, remote)
        OnlineChapterUpdatePlanner.requireSafeUpdate(stored, remote, plan)
        assertEquals((873..877).toList(), plan.filter { it.isNew }.map { it.remoteIndex })
        assertEquals(stored.map { it.href }, plan.take(873).map { it.stored!!.href })
        assertTrue(metadata.chapters.none { it.state == OnlineChapterState.PENDING })
    }

    @Test fun anUnchangedFullBookHasNoDownloadCandidates() {
        val metadata = OnlineOnDemandMetadataCodec.decode(legacyBook(25))
        val stored = metadata.chapters.map { StoredOnlineChapter(it.sourceChapterId, it.title, it.volumeTitle, it.href) }
        val remote = stored.map { RemoteOnlineChapter(it.sourceChapterId, it.title, it.volumeTitle) }
        val plan = OnlineChapterUpdatePlanner.plan(stored, remote)
        OnlineChapterUpdatePlanner.requireSafeUpdate(stored, remote, plan)
        assertTrue(plan.none { it.isNew })
        assertTrue(metadata.chapters.all { it.recoverAfterProcessRestart().state == OnlineChapterState.READY })
    }

    @Test fun onDemandAndPausedStatesStillResumeTheirActualPendingChapters() {
        val pending = OnlineOnDemandMetadata("source", "https://example/book/1", "测试书", OnlineBookDownloadMode.ON_DEMAND,
            listOf(
                OnlineOnDemandChapter("1", "url1", "第一章", "", "Text/1.xhtml", OnlineChapterState.READY),
                OnlineOnDemandChapter("2", "url2", "第二章", "", "Text/2.xhtml", OnlineChapterState.DOWNLOADING),
                OnlineOnDemandChapter("3", "url3", "第三章", "", "Text/3.xhtml", OnlineChapterState.PENDING),
            ))
        val decoded = OnlineOnDemandMetadataCodec.decode(OnlineOnDemandMetadataCodec.encode(pending)).recoverAfterProcessRestart()
        assertEquals(listOf(OnlineChapterState.READY, OnlineChapterState.PENDING, OnlineChapterState.PENDING),
            decoded.chapters.map { it.state })
        val malformed = JSONObject(legacyBook(1)).put("version", 2).put("mode", "ON_DEMAND")
        assertEquals(OnlineChapterState.PENDING, OnlineOnDemandMetadataCodec.decode(malformed.toString()).chapters.single().state)
    }

    @Test fun aChangedSourceIndexCannotScheduleAnotherFullBookDownload() {
        val stored = (1..25).map { StoredOnlineChapter("old:$it", "第${it}章", "", "Text/$it.xhtml") }
        val remote = (1..26).map { RemoteOnlineChapter("new:$it", "第${it}章", "") }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineChapterUpdatePlanner.requireSafeUpdate(stored, remote, OnlineChapterUpdatePlanner.plan(stored, remote))
        }
    }

    @Test fun failedPlaceholdersAreStillDetectedInLegacyFullBooks() {
        assertNotNull(OnlineChapterContentValidator.storedXhtmlFailureReason(
            "<html><body><h1>第一章</h1><p>本章下载失败，已按在线源重试后仍失败</p></body></html>"))
        assertNull(OnlineChapterContentValidator.storedXhtmlFailureReason(
            "<html><body><h1>第一章</h1><p>这是一章已经完整下载的正常正文。</p></body></html>"))
    }

    @Test fun metadataUpgradeDoesNotLookLikeAnotherRemoteDirectoryChange() {
        val original = legacyBook(25)
        val metadata = OnlineOnDemandMetadataCodec.decode(original).copy(
            chapters = OnlineOnDemandMetadataCodec.decode(original).chapters.map {
                it.copy(attempts = 2, downloadedAtMs = 1000L)
            },
        )
        assertTrue(OnlineOnDemandMetadataCodec.chapterIndexMatches(
            OnlineOnDemandMetadataCodec.encode(metadata), original))
        val changed = JSONObject(original)
        changed.getJSONArray("chapters").getJSONObject(0).put("title", "修订后的章节名")
        assertFalse(OnlineOnDemandMetadataCodec.chapterIndexMatches(
            OnlineOnDemandMetadataCodec.encode(metadata), changed.toString()))
    }
}
