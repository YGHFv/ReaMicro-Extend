package com.reamicro.fix.settings

import com.reamicro.fix.association.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsTest {
    @Test
    fun defaultSearchSourcePreferencesOnlySeedDefaultSourceIds() {
        assertEquals(
            mapOf("fanqie" to true),
            ModuleSettings.defaultAssociationSearchSources(),
        )
    }

    @Test
    fun sourceFileGroupsRunWhenTheirPreferenceIsEnabled() {
        val sourceFileGroups = listOf(
            SearchSourceGroup(
                id = BookSource.WanFengLi.id,
                title = BookSource.WanFengLi.displayName,
                sources = setOf(BookSource.WanFengLi),
            ),
            SearchSourceGroup(
                id = BookSource.YouShu.id,
                title = BookSource.YouShu.displayName,
                sources = setOf(BookSource.YouShu),
            ),
        )
        val snapshot = ModuleSettingsSnapshot(
            associationSearchSources = mapOf(
                BookSource.WanFengLi.id to true,
                BookSource.YouShu.id to false,
            ),
        )

        assertTrue(BookSource.WanFengLi in snapshot.enabledAssociationSearchSources(sourceFileGroups))
        assertFalse(BookSource.YouShu in snapshot.enabledAssociationSearchSources(sourceFileGroups))
    }

    @Test
    fun dynamicSourceFileResultIsVisibleWhenItsPreferenceIsEnabled() {
        val snapshot = ModuleSettingsSnapshot(
            associationSearchSources = mapOf(BookSource.WanFengLi.id to true),
        )

        assertTrue(snapshot.isSearchSourceEnabled(BookSource.WanFengLi))
    }

    @Test
    fun rotationBaseSelectionKeepsOnlyOneMode() {
        val selection = ModuleSettings.normalizeRotationSelection(
            autoEnabled = true,
            portraitLockEnabled = true,
            landscapeLockEnabled = true,
        )

        assertTrue(selection.autoEnabled)
        assertFalse(selection.portraitLockEnabled)
        assertFalse(selection.landscapeLockEnabled)
    }

    @Test
    fun reverseRotationAloneDoesNotForceOrientation() {
        val snapshot = ModuleSettingsSnapshot(rotationReverseEnabled = true)

        assertFalse(snapshot.canApplyRotation)
    }

    @Test
    fun readerParentSwitchControlsLongPress() {
        val disabledParent = ModuleSettingsSnapshot(
            readerEnabled = false,
            readerLongPressEnabled = true,
        )
        val enabledParent = disabledParent.copy(readerEnabled = true)

        assertFalse(disabledParent.canRunReaderLongPress)
        assertTrue(enabledParent.canRunReaderLongPress)
    }

    @Test
    fun readerParentSwitchControlsAutoPage() {
        val disabledParent = ModuleSettingsSnapshot(
            readerEnabled = false,
            readerAutoPageEnabled = true,
        )
        val enabledParent = disabledParent.copy(readerEnabled = true)

        assertFalse(disabledParent.canRunReaderAutoPage)
        assertTrue(enabledParent.canRunReaderAutoPage)
    }

    @Test
    fun associationParentSwitchControlsCoverFix() {
        val disabledParent = ModuleSettingsSnapshot(
            associationEnabled = false,
            associationCoverFixEnabled = true,
        )
        val enabledParent = disabledParent.copy(associationEnabled = true)

        assertFalse(disabledParent.canUseAssociationCoverFix)
        assertTrue(enabledParent.canUseAssociationCoverFix)
    }

    @Test
    fun readerParentSwitchControlsFileEdit() {
        val disabledParent = ModuleSettingsSnapshot(
            readerEnabled = false,
            editFileEnabled = true,
        )
        val enabledParent = disabledParent.copy(readerEnabled = true)

        assertFalse(disabledParent.canUseFileEdit)
        assertTrue(enabledParent.canUseFileEdit)
    }
}
