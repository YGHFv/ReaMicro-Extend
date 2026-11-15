package com.reamicro.fix.association

import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import org.junit.Assert.*
import org.junit.Test

class ManualAssociationTest {
    private val service = ManualAssociationService()
    private val complete = ManualAssociationDraft("书名", "作者", "简介", words = "12万字",
        status = "完结", coverUrl = "https://example.com/cover.jpg")

    @Test fun draftKeepsAllEditedFieldsThroughCandidateAndSearchResult() {
        val original = ManualAssociationDraft("原书", "原作者")
        val draft = service.updateDraft(original, title = " 新书 ", author = " 新作者 ", intro = " 简介 ",
            source = BookSource.FanQie, words = " 12.5万字 ", status = " 完结 ",
            coverUrl = " https://example.com/封面.jpg?token=a%20b ")
        val result = service.buildCandidate(draft).toSearchResult()
        assertEquals("新书", result.title)
        assertEquals("新作者", result.author)
        assertEquals("简介", result.intro)
        assertEquals(BookSource.FanQie, result.source)
        assertEquals("12.5万字", result.words)
        assertEquals("完结", result.status)
        assertEquals("https://example.com/封面.jpg?token=a%20b", result.coverUrl)
        assertEquals("原书", original.title)
        assertEquals("", original.coverUrl)
    }

    @Test fun allFieldsAreRequiredBeforePreview() {
        for (draft in listOf(complete.copy(title = " "), complete.copy(author = " "),
            complete.copy(intro = " "), complete.copy(words = " "), complete.copy(status = ""),
            complete.copy(coverUrl = " "), complete.copy(status = "未知"))) {
            assertFalse(service.validate(draft).isValid)
            assertThrows(IllegalArgumentException::class.java) { service.buildCandidate(draft) }
        }
    }

    @Test fun malformedOrLocalPathsCannotBecomeRemoteMetadata() {
        for (url in listOf("http://example.com/a.jpg", "https://example.com/封面.webp")) {
            assertTrue(url, service.validate(complete.copy(coverUrl = url)).isValid)
        }
        for (url in listOf("cover.jpg", "/data/book/cover.jpg", "file:///a.jpg",
            "content://images/1", "ftp://example.com/a", "https://", "https://example.com/a\nb")) {
            val draft = complete.copy(coverUrl = url)
            assertNotNull(url, service.validate(draft).coverError)
            assertThrows(IllegalArgumentException::class.java) { service.buildCandidate(draft) }
        }
    }

    @Test fun requiredFieldsIntroLimitAndPlatformWhitelistRemainEnforced() {
        assertFalse(service.validate(ManualAssociationDraft(" ", "作者")).isValid)
        assertFalse(service.validate(ManualAssociationDraft("书名", " ")).isValid)
        assertTrue(service.validate(complete.copy(
            intro = "字".repeat(ManualAssociationDraft.MAX_INTRO_LENGTH))).isValid)
        assertFalse(service.validate(complete.copy(
            intro = "字".repeat(ManualAssociationDraft.MAX_INTRO_LENGTH + 1))).isValid)
        assertNotNull(service.validate(complete.copy(
            source = BookSource.DouBan)).sourceError)
    }
}
