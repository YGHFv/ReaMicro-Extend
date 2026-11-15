package com.reamicro.fix.hook

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ManualAssociationCoverTest {
    @Test fun draftSurvivesConfirmationButIsReleasedAfterCancelOrCompletion() {
        val directory = Files.createTempDirectory("manual-cover-draft").toFile()
        try {
            val store = ManualAssociationCoverStore(directory)
            val key = store.stage(byteArrayOf(1, 2, 3), "png")
            val selected = requireNotNull(store.acquire(key))
            store.retain(emptySet())
            assertTrue(selected.file.isFile)
            selected.close()
            assertFalse(selected.file.exists())
            assertNull(store.acquire(key))
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedConfirmationRestoresExistingCoverAndSuccessKeepsNewCover() {
        val directory = Files.createTempDirectory("manual-cover-publish").toFile()
        try {
            val source = directory.resolve("download.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val target = directory.resolve("cover.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            fun publish() = ManualAssociationCoverPublication.publish(source, target, {}, { it() }, {})
            publish().finish(false)
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
            publish().finish(true)
            assertArrayEquals(byteArrayOf(4, 5, 6), target.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rollbackPreservesAConcurrentCoverEdit() {
        val directory = Files.createTempDirectory("manual-cover-concurrent").toFile()
        try {
            val source = directory.resolve("download.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val target = directory.resolve("cover.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val publication = ManualAssociationCoverPublication.publish(source, target, {}, { it() }, {})
            target.writeBytes(byteArrayOf(7, 8, 9))
            assertThrows(IllegalStateException::class.java) { publication.finish(false) }
            assertArrayEquals(byteArrayOf(7, 8, 9), target.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun previewReplacesOnlyItsOwnGroupWithoutDroppingNativeSearchResults() {
        val native = listOf("原生甲", "原生乙")
        val first = ManualAssociationPreview.merge(native, "手动编辑-甲") { it }
        val second = ManualAssociationPreview.merge(first, "手动编辑-乙") { it }
        assertEquals(listOf("手动编辑-乙", "原生甲", "原生乙"), second)
        assertEquals(native, ManualAssociationPreview.merge(second, null) { it })
    }
}
