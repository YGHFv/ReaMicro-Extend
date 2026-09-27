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
    fun legacyParentSwitchDefaultsStayEnabled() {
        assertTrue(ModuleSettings.DEFAULT_ASSOCIATION_ENABLED)
        assertTrue(ModuleSettings.DEFAULT_READER_ENABLED)
        assertTrue(ModuleSettings.DEFAULT_FONT_ENABLED)
        assertTrue(ModuleSettings.DEFAULT_ACCOUNT_ENABLED)
        assertTrue(ModuleSettings.DEFAULT_EDIT_ENABLED)
        assertTrue(ModuleSettings.DEFAULT_CLOUD_ENABLED)
        assertTrue(ModuleSettings.DEFAULT_ROTATION_ENABLED)
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
    fun readerLongPressChildSwitchControlsLongPress() {
        val disabledChild = ModuleSettingsSnapshot(
            readerLongPressEnabled = false,
        )
        val enabledChild = disabledChild.copy(readerLongPressEnabled = true)
        val disabledModule = enabledChild.copy(moduleEnabled = false)

        assertFalse(disabledChild.canRunReaderLongPress)
        assertTrue(enabledChild.canRunReaderLongPress)
        assertFalse(disabledModule.canRunReaderLongPress)
    }

    @Test
    fun readerAutoPageChildSwitchControlsAutoPage() {
        val disabledChild = ModuleSettingsSnapshot(
            readerAutoPageEnabled = false,
        )
        val enabledChild = disabledChild.copy(readerAutoPageEnabled = true)
        val disabledModule = enabledChild.copy(moduleEnabled = false)

        assertFalse(disabledChild.canRunReaderAutoPage)
        assertTrue(enabledChild.canRunReaderAutoPage)
        assertFalse(disabledModule.canRunReaderAutoPage)
    }

    @Test
    fun associationCoverFixChildSwitchControlsCoverFix() {
        val disabledChild = ModuleSettingsSnapshot(
            associationCoverFixEnabled = false,
        )
        val enabledChild = disabledChild.copy(associationCoverFixEnabled = true)
        val disabledModule = enabledChild.copy(moduleEnabled = false)

        assertFalse(disabledChild.canUseAssociationCoverFix)
        assertTrue(enabledChild.canUseAssociationCoverFix)
        assertFalse(disabledModule.canUseAssociationCoverFix)
    }

    @Test
    fun fileEditChildSwitchControlsFileEdit() {
        val disabledChild = ModuleSettingsSnapshot(
            editFileEnabled = false,
        )
        val enabledChild = disabledChild.copy(editFileEnabled = true)
        val disabledModule = enabledChild.copy(moduleEnabled = false)

        assertFalse(disabledChild.canUseFileEdit)
        assertTrue(enabledChild.canUseFileEdit)
        assertFalse(disabledModule.canUseFileEdit)
    }

    @Test
    fun legacyReaderParentSwitchNoLongerBlocksChildSwitches() {
        val snapshot = ModuleSettingsSnapshot(
            readerEnabled = false,
            readerLongPressEnabled = true,
            readerAutoPageEnabled = true,
            editFileEnabled = true,
            readerCompactSelectionMenuEnabled = true,
        )

        assertTrue(snapshot.canRunReaderLongPress)
        assertTrue(snapshot.canRunReaderAutoPage)
        assertTrue(snapshot.canUseFileEdit)
        assertTrue(snapshot.canUseCompactReaderSelectionMenu)
    }

    @Test
    fun legacyAssociationParentSwitchNoLongerBlocksChildSwitches() {
        val snapshot = ModuleSettingsSnapshot(
            associationEnabled = false,
            associationCoverFixEnabled = true,
        )

        assertTrue(snapshot.canUseAssociationCoverFix)
    }

    @Test
    fun legacyBookHighlightRuleMatchesCompositeCurrentBookKey() {
        val rule = ReaderHighlightRule(
            id = "legacy",
            name = "Legacy",
            type = ReaderHighlightRuleType.FixedText,
            pattern = "important",
            bookKey = "12345",
            bookTitle = "Old Title",
        )

        assertTrue(rule.appliesToBook("/storage/books/New Title.epub|12345|67890|New Title", "New Title"))
        assertFalse(rule.appliesToBook("/storage/books/Other.epub|99999|67890|Other", "Other"))
    }

    @Test
    fun legacyBookGlobalRuleSettingMatchesCompositeCurrentBookKey() {
        val previousTitle = ReaderHighlightBookContext.bookTitle
        ReaderHighlightBookContext.bookTitle = "New Title"
        try {
            val snapshot = ReaderHighlightSettingsSnapshot(
                bookGlobalRules = mapOf("12345" to setOf("double_quote_dialogue")),
            )

            assertEquals(
                setOf("double_quote_dialogue"),
                snapshot.globalRulesEnabledForBook("/storage/books/New Title.epub|12345|67890|New Title"),
            )
        } finally {
            ReaderHighlightBookContext.bookTitle = previousTitle
        }
    }

    @Test
    fun moduleParentSwitchControlsProfileBackground() {
        val disabledParent = ModuleSettingsSnapshot(
            moduleEnabled = false,
            profileBackgroundEnabled = true,
            profileBackgroundUseImage = true,
            profileBackgroundImage = "/data/profile_background/bg.png",
        )
        val enabledParent = disabledParent.copy(moduleEnabled = true)

        assertFalse(disabledParent.canShowProfileBackground)
        assertTrue(enabledParent.canShowProfileBackground)
    }

    @Test
    fun profileBackgroundDefaultsOff() {
        assertFalse(ModuleSettingsSnapshot().canShowProfileBackground)
        assertFalse(ModuleSettingsSnapshot().profileBackgroundEnabled)
    }

    @Test
    fun profileBackgroundDefaultColorMatchesSpec() {
        assertEquals(
            ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR,
            ModuleSettingsSnapshot().profileBackgroundColor,
        )
    }

    @Test
    fun profileBackgroundDefaultCropPositionKeepsTopCrop() {
        assertEquals(
            ModuleSettings.PROFILE_BACKGROUND_CROP_TOP,
            ModuleSettingsSnapshot().profileBackgroundCropPosition,
        )
    }

    @Test
    fun profileBackgroundColorIsCarriedThroughSnapshot() {
        val snapshot = ModuleSettingsSnapshot(
            moduleEnabled = true,
            profileBackgroundEnabled = true,
            profileBackgroundColor = "#FF1F2937",
        )
        // 颜色值仍会被快照携带（配置保留），但纯色模式已停用，
        // 只有选择了图片背景才会生效。
        assertEquals("#FF1F2937", snapshot.profileBackgroundColor)
        assertFalse(snapshot.canShowProfileBackground)
    }

    @Test
    fun profileBackgroundImageSelectionEnablesShow() {
        val snapshot = ModuleSettingsSnapshot(
            moduleEnabled = true,
            profileBackgroundEnabled = true,
            profileBackgroundUseImage = true,
            profileBackgroundImage = "/data/profile_background/bg.png",
        )

        assertTrue(snapshot.canShowProfileBackground)
    }

    @Test
    fun profileBackgroundImageSwitchDisablesShow() {
        val snapshot = ModuleSettingsSnapshot(
            moduleEnabled = true,
            profileBackgroundEnabled = false,
            profileBackgroundUseImage = true,
            profileBackgroundImage = "/data/profile_background/bg.png",
        )

        assertFalse(snapshot.canShowProfileBackground)
    }

    @Test
    fun profileBackgroundCropPositionIsCarriedThroughSnapshot() {
        val snapshot = ModuleSettingsSnapshot(
            profileBackgroundCropPosition = ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER,
        )

        assertEquals(ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER, snapshot.profileBackgroundCropPosition)
    }

    @Test
    fun legacyAccountParentSwitchNoLongerBlocksChildSwitches() {
        val snapshot = ModuleSettingsSnapshot(
            accountEnabled = false,
            accountExportEnabled = true,
            accountCacheCleanupEnabled = true,
        )

        assertTrue(snapshot.canRunAccountDataExport)
        assertTrue(snapshot.canRunStartupCacheCleanup)
    }
}
