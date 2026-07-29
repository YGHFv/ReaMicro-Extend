package com.reamicro.fix.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ReaderFontCompletionHookTest {
    @Test
    fun readerEpubConfig230KeepsHostBooleanFieldOrder() {
        assertArrayEquals(
            arrayOf<Any>(true, 2, false, true, false),
            readerEpubConfig230TrailingArguments(
                globalEmbeddedFonts = true,
                bookEmbeddedFonts = 2,
                embeddedFonts = false,
                buildInFonts = true,
                boldFont = false,
            ),
        )
    }
}
