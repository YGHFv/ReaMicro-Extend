package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Rc3FeatureProfileTest {
    private val profile = HostProfile.parse(
        listOf(
            File("src/main/assets/host-profiles/2.3.3-rc3.profile"),
            File("app/src/main/assets/host-profiles/2.3.3-rc3.profile"),
        ).first { it.isFile }.readText(Charsets.UTF_8),
    )

    @Test
    fun `import conflict checks use actual dao and retain uuid argument contract`() {
        val dao = "app.zhendong.reamicro.data.db.dao.BookDao"
        assertEquals("gh0", profile.classOf(dao))
        assertEquals("c", profile.methodOf(dao, "findByUidAndUuid"))
        assertEquals("d", profile.methodOf(dao, "listByUid"))
        assertEquals("g", profile.methodOf("app.zhendong.reamicro.repository.BookshelfRepository", "checkBookExists"))
    }

    @Test
    fun `cancelled copies keep native database and file deletion entry`() {
        assertEquals("h", profile.methodOf("app.zhendong.reamicro.repository.BookshelfRepository", "deleteBook"))
    }

    @Test
    fun `on demand chapter accessors preserve the native record fields`() {
        val chapter = "app.zhendong.reamicro.data.db.entity.BookChapter"
        assertEquals("d", profile.fieldOf(chapter, "title"))
        assertEquals("e", profile.fieldOf(chapter, "href"))
        assertEquals("g", profile.fieldOf(chapter, "epubcfi"))
        assertEquals("i", profile.fieldOf(chapter, "cfi"))
        assertEquals("hq8", profile.classOf("ReaMicroHost.VirtualPageLoadContinuation"))
    }

    @Test
    fun `metadata getters removed by r8 resolve through exact fields`() {
        val metadata = "org.epub.structure.opf.Metadata"
        assertEquals("a", profile.fieldOf(metadata, "title"))
        assertEquals("b", profile.fieldOf(metadata, "uuid"))
        assertEquals("e", profile.fieldOf(metadata, "authors"))
        assertEquals("l", profile.fieldOf(metadata, "publisher"))
        assertEquals("u", profile.fieldOf(metadata, "metas"))
        assertEquals("a", profile.fieldOf("org.epub.structure.metadata.Publisher", "name"))
        assertEquals("a", profile.fieldOf("org.epub.structure.metadata.Creator", "name"))
    }

    @Test
    fun `independent import persists changed opf through native overwrite`() {
        assertEquals("b", profile.methodOf("org.epub.structure.opf.Opf", "overwrite"))
        assertEquals("m", profile.methodOf("org.epub.structure.opf.Opf", "obtain"))
        assertEquals("btd", profile.methodOwners["org.epub.structure.opf.Opf#obtain"])
    }
}
