package com.reamicro.fix.epub.editor

import org.junit.Assert.*
import org.junit.Test

class EpubPreviewSizingTest {
    @Test fun keepsSmallImages() = assertEquals(1, epubPreviewSampleSize(600, 800, 2048))
    @Test fun boundsHugeImagesAndAvoidsOverflow() {
        val sample = epubPreviewSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 2048)
        assertTrue(Int.MAX_VALUE / sample <= 2048)
        assertTrue((Int.MAX_VALUE.toLong() / sample) * (Int.MAX_VALUE.toLong() / sample) <= 4_194_304L)
        assertEquals(0, sample and (sample - 1))
    }
    @Test fun thumbnailsRespectTheirOwnBound() = assertEquals(8, epubPreviewSampleSize(600, 800, 128, 16384))
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownDimensions() {
        epubPreviewSampleSize(-1, -1, 2048)
    }
}
