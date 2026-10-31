package com.reamicro.fix.hook

import com.reamicro.fix.settings.ModuleSettingsSnapshot
import org.junit.Assert.*
import org.junit.Test

class BookshelfCompletionPolicyTest {
    @Test
    fun actionsKeepSyncUpdateStyleOrderForEveryCombination() {
        for (hide in listOf(false, true)) for (style in listOf(false, true)) for (update in listOf(false, true)) {
            val settings = ModuleSettingsSnapshot(shelfHideSync = hide, shelfStyleButton = style, shelfBatchUpdate = update)
            val actual = BookshelfCompletionPolicy.actions(settings)
            assertEquals(!hide, BookshelfCompletionPolicy.Action.SYNC in actual)
            assertEquals(style, BookshelfCompletionPolicy.Action.STYLE in actual)
            assertEquals(update, BookshelfCompletionPolicy.Action.UPDATE in actual)
            assertEquals(actual.sortedBy { it.ordinal }, actual)
        }
    }

    @Test
    fun readingHeaderOnlyKeepsTheIndependentUpdateAction() {
        val settings = ModuleSettingsSnapshot(
            shelfHideSync = false,
            shelfStyleButton = true,
            shelfBatchUpdate = true,
        )
        assertEquals(
            listOf(BookshelfCompletionPolicy.Action.UPDATE),
            BookshelfCompletionPolicy.actions(settings, shelfVisible = false),
        )
    }

    @Test
    fun startupUpdateRequiresBothSwitchesAndModuleEnabled() {
        assertFalse(BookshelfCompletionPolicy.autoUpdate(ModuleSettingsSnapshot(shelfAutoUpdate = true)))
        assertTrue(BookshelfCompletionPolicy.autoUpdate(ModuleSettingsSnapshot(shelfBatchUpdate = true, shelfAutoUpdate = true)))
        assertFalse(BookshelfCompletionPolicy.autoUpdate(ModuleSettingsSnapshot(
            moduleEnabled = false, shelfBatchUpdate = true, shelfAutoUpdate = true)))
    }
}
