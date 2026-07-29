package com.reamicro.fix.online

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineReaderContextBridgeTest {
    @Test
    fun `maps host itemref CFI steps to zero based chapter index`() {
        assertEquals(0, OnlineReaderContextBridge.chapterIndexFromItemRef(4))
        assertEquals(1, OnlineReaderContextBridge.chapterIndexFromItemRef(6))
        assertEquals(2, OnlineReaderContextBridge.chapterIndexFromItemRef(8))
        assertEquals(null, OnlineReaderContextBridge.chapterIndexFromItemRef(3))
        assertEquals(null, OnlineReaderContextBridge.chapterIndexFromItemRef(5))
    }

    @After
    fun tearDown() {
        OnlineReaderContextBridge.clear()
    }

    @Test
    fun `同一本书更新目录时保留页码而换书时重置`() {
        val first = Files.createTempDirectory("reader-context-first").toFile()
        val second = Files.createTempDirectory("reader-context-second").toFile()
        try {
            OnlineReaderContextBridge.updatePage(first, 6)
            OnlineReaderContextBridge.updateBook(first)

            assertEquals(6, OnlineReaderContextBridge.snapshot()?.spineIndex)
            assertEquals(first.canonicalFile, OnlineReaderContextBridge.snapshot()?.bookDir)

            OnlineReaderContextBridge.updateBook(second)

            assertEquals(-1, OnlineReaderContextBridge.snapshot()?.spineIndex)
            assertEquals(second.canonicalFile, OnlineReaderContextBridge.snapshot()?.bookDir)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun `忽略无效页码和不存在的目录并支持清空`() {
        val directory = Files.createTempDirectory("reader-context-valid").toFile()
        try {
            OnlineReaderContextBridge.updatePage(directory, -1)
            assertNull(OnlineReaderContextBridge.snapshot())

            OnlineReaderContextBridge.updateBook(directory)
            assertTrue(OnlineReaderContextBridge.snapshot() != null)

            OnlineReaderContextBridge.updatePage(directory.resolve("missing"), 3)
            assertEquals(-1, OnlineReaderContextBridge.snapshot()?.spineIndex)

            OnlineReaderContextBridge.clear()
            assertNull(OnlineReaderContextBridge.snapshot())
        } finally {
            directory.deleteRecursively()
        }
    }
}
