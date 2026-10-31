package com.reamicro.fix.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookMetadataAssociationTest {
    @Test
    fun overwriteKeepsExistingAssociationIdentity() {
        val patch = BookMetadataPatch(title = "新书名", publisher = "导入文件出版社")
            .withPreservedAssociation(128L, "番茄小说")

        assertEquals("新书名", patch.title)
        assertEquals(128L, patch.cloudId)
        assertEquals("番茄小说", patch.publisher)
    }

    @Test
    fun unassociatedBookKeepsImportedPublisher() {
        val patch = BookMetadataPatch(publisher = "导入文件出版社")
            .withPreservedAssociation(0L, "旧来源")

        assertNull(patch.cloudId)
        assertEquals("导入文件出版社", patch.publisher)
    }
}
