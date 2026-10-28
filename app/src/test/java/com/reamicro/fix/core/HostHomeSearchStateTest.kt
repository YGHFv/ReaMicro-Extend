package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class HostHomeSearchStateTest {
    class State(
        val model: String, val user: String?, val searching: Boolean, val showLibrary: Boolean,
        val groups: List<String>, val bookshelfGroups: List<String>, val hasBooks: Boolean,
        val localSearchResults: List<String>, val cloudSearchResults: Map<Int, List<String>>,
        val passwordRequest: String?, val oldBooksToMigrate: List<String>,
        val migrationStatus: Map<Int, String>, val migrationResult: String?,
        val isMigrating: Boolean, val bookshelfMultiSelect: String?,
        val autoScanDirUri: String, val autoScanDirLabel: String,
    )

    @Test fun replacingCloudResultsPreservesAllOtherFieldsAndTheOriginal() {
        val state = State("model", null, true, false, listOf("group"), listOf("shelf"), true,
            listOf("local"), mapOf(0 to listOf("native")), null, listOf("old"),
            mapOf(7 to "pending"), null, false, "selection", "content://qa", "QA folder")
        val bridge = HostExactRecordCopy(State::class.java, HostHomeSearchState.b4Properties)
        val before = bridge.snapshot(state)
        val results = state.cloudSearchResults + (100 to listOf("webdav"))
        val copied = bridge.copy(state, mapOf("cloudSearchResults" to results))
        assertNotSame(state, copied)
        assertEquals(before, bridge.snapshot(state))
        assertEquals(before + mapOf("cloudSearchResults" to results), bridge.snapshot(copied))
        val cleared = bridge.copy(copied, mapOf("cloudSearchResults" to emptyMap<Int, List<String>>()))
        assertEquals(before + mapOf("cloudSearchResults" to emptyMap<Int, List<String>>()),
            bridge.snapshot(cleared))
    }
}
