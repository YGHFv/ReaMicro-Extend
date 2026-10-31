package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class HomeDefaultPagePolicyTest {
    private val groups = listOf("正在阅读", "书架")

    @Test fun `first page uses stored shelf rather than initial false`() {
        assertEquals(true, HomeDefaultPagePolicy.initialPreference(false, groups, true))
        assertEquals(false, HomeDefaultPagePolicy.initialPreference(false, groups, false))
    }

    @Test fun `manual page and initialized navigation are not overridden`() {
        assertNull(HomeDefaultPagePolicy.initialPreference(true, groups, true))
        assertNull(HomeDefaultPagePolicy.initialPreference(true, groups, false))
    }

    @Test fun `empty groups and unavailable preference keep native behavior`() {
        assertNull(HomeDefaultPagePolicy.initialPreference(false, emptyList<String>(), true))
        assertNull(HomeDefaultPagePolicy.initialPreference(false, groups, null))
    }
}
