package com.reamicro.fix.association

import com.reamicro.fix.association.model.AssociatedFileMetadata
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAssociationServiceTest {
    private val service = ManualAssociationService()

    @Test
    fun createDraftFallsBackToFileNameWhenTitleIsBlank() {
        val draft = service.createDraft(
            AssociatedFileMetadata(
                title = "",
                author = "作者",
                fileName = "书名.epub",
            ),
            BookSource.QQReader,
        )

        assertEquals("书名", draft.title)
        assertEquals("作者", draft.author)
        assertEquals(BookSource.QQReader, draft.source)
    }

    @Test
    fun validateRejectsBlankTitleAndAuthor() {
        val validation = service.validate(ManualAssociationDraft(title = " ", author = " "))

        assertFalse(validation.isValid)
        assertEquals("书名不能为空", validation.firstError)
    }

    @Test
    fun buildCandidateNormalizesInput() {
        val candidate = service.buildCandidate(
            ManualAssociationDraft(
                title = "  标题  ",
                author = "  作者  ",
                intro = "  简介  ",
                source = BookSource.Ciyuanji,
            ),
        )

        assertEquals("标题", candidate.title)
        assertEquals("作者", candidate.author)
        assertEquals("简介", candidate.intro)
        assertEquals(BookSource.Ciyuanji, candidate.source)
        assertTrue(candidate.toSearchResult().tags.contains("手动关联"))
    }
}
