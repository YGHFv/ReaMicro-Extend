package com.reamicro.fix.association.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceTest {
    @Test
    fun rm2A4SourceIdsAreKnown() {
        assertEquals(BookSource.QQReader, BookSource.fromReaMicroQueryType(9))
        assertEquals(BookSource.Ciweimao, BookSource.fromReaMicroQueryType(10))
        assertEquals(BookSource.SFAcg, BookSource.fromReaMicroQueryType(14))
    }

    @Test
    fun manualSourcesAreFixedAssociationPlatformsOnly() {
        assertTrue(BookSource.manualSelectableSources.contains(BookSource.Ciyuanji))
        assertTrue(BookSource.manualSelectableSources.contains(BookSource.FanQie))
        assertTrue(BookSource.manualSelectableSources.contains(BookSource.ShaoNianDream))
        assertTrue(BookSource.manualSelectableSources.contains(BookSource.BuKeNeng))
        assertFalse(BookSource.manualSelectableSources.contains(BookSource.YouShu))
        assertFalse(BookSource.manualSelectableSources.contains(BookSource.BaiduBaike))
        assertFalse(BookSource.manualSelectableSources.contains(BookSource.DaHuiLang))
        assertEquals("番茄小说", BookSource.FanQie.displayName)
        assertFalse(BookSource.Ciyuanji.hasReaMicroQueryType)
        assertFalse(BookSource.FanQie.hasReaMicroQueryType)
    }
}
