package com.reamicro.fix.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBookRootIdentityTest {
    @Test
    fun `equivalent native and normalized directory paths retain the same session`() {
        val root = File(System.getProperty("java.io.tmpdir"), "reamicro-root-identity/book")
        assertTrue(sameReaderBookRoot(File(root, "../book"), root.canonicalFile))
        assertFalse(sameReaderBookRoot(File(root.parentFile, "other-book"), root))
        assertFalse(sameReaderBookRoot(null, root))
    }
}
