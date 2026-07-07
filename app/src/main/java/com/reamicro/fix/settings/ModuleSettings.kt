package com.reamicro.fix.settings

import com.reamicro.fix.association.model.BookSource

object ModuleSettings {
    const val PREFS_NAME = "reamicro_fix_module_settings"

    const val KEY_MODULE_ENABLED = "module_enabled"
    const val KEY_ASSOCIATION_ENABLED = "association_enabled"
    const val KEY_ASSOCIATION_MANUAL_EDIT_ENABLED = "association_manual_edit_enabled"
    const val KEY_ASSOCIATION_UNLINK_ENABLED = "association_unlink_enabled"
    const val KEY_ASSOCIATION_COVER_FIX_ENABLED = "association_cover_fix_enabled"
    const val KEY_READER_ENABLED = "reader_enabled"
    const val KEY_READER_LONG_PRESS_ENABLED = "reader_long_press_enabled"
    const val KEY_READER_READ_ALOUD_ENABLED = "reader_read_aloud_enabled"
    const val KEY_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS = "reader_read_aloud_ignore_audio_focus"
    const val KEY_READER_READ_ALOUD_RESTART_ON_PAGE_TURN = "reader_read_aloud_restart_on_page_turn"
    const val KEY_READER_READ_ALOUD_SELECTION_ENABLED = "reader_read_aloud_selection_enabled"
    const val KEY_READER_READ_ALOUD_LYRICON_ENABLED = "reader_read_aloud_lyricon_enabled"
    const val KEY_READER_AUTO_PAGE_ENABLED = "reader_auto_page_enabled"
    const val KEY_READER_OVERWRITE_CHECK_ENABLED = "reader_overwrite_check_enabled"
    const val KEY_READER_EDIT_OVERWRITE_ENABLED = "reader_edit_overwrite_enabled"
    const val KEY_READER_DICTIONARY_ENABLED = "reader_dictionary_enabled"
    const val KEY_READER_COMPACT_SELECTION_MENU_ENABLED = "reader_compact_selection_menu_enabled"
    const val KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED = "reader_dialogue_highlight_enabled"
    const val KEY_READER_SELECTION_HIGHLIGHT_ENABLED = "reader_selection_highlight_enabled"
    const val KEY_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED = "reader_highlight_performance_log_enabled"
    const val KEY_CONCISE_LOG_ENABLED = "concise_log_enabled"
    const val KEY_INLINE_SEARCH_ICON_ENABLED = "inline_search_icon_enabled"
    const val KEY_FONT_ENABLED = "font_enabled"
    const val KEY_FONT_SETTINGS_ENABLED = "font_settings_enabled"
    const val KEY_ACCOUNT_ENABLED = "account_enabled"
    const val KEY_ACCOUNT_EXPORT_ENABLED = "account_export_enabled"
    const val KEY_ACCOUNT_CACHE_CLEANUP_ENABLED = "account_cache_cleanup_enabled"
    const val KEY_EDIT_ENABLED = "edit_enabled"
    const val KEY_EDIT_FILE_ENABLED = "edit_file_enabled"
    const val KEY_CLOUD_ENABLED = "cloud_enabled"
    const val KEY_CLOUD_WEBDAV_ENABLED = "cloud_webdav_enabled"
    const val KEY_CLOUD_LOCAL_LIBRARY_ENABLED = "cloud_local_library_enabled"
    const val KEY_CLOUD_EXTENDED_DISPLAY_ENABLED = "cloud_extended_display_enabled"
    const val KEY_CLOUD_DOWNLOAD_CANCEL_ENABLED = "cloud_download_cancel_enabled"
    const val KEY_ROTATION_ENABLED = "rotation_enabled"
    const val KEY_ROTATION_AUTO_ENABLED = "rotation_auto_enabled"
    const val KEY_ROTATION_PORTRAIT_LOCK_ENABLED = "rotation_portrait_lock_enabled"
    const val KEY_ROTATION_LANDSCAPE_LOCK_ENABLED = "rotation_landscape_lock_enabled"
    const val KEY_ROTATION_REVERSE_ENABLED = "rotation_reverse_enabled"
    const val KEY_PROFILE_BACKGROUND_ENABLED = "profile_background_enabled"
    const val KEY_PROFILE_BACKGROUND_COLOR = "profile_background_color"
    const val KEY_PROFILE_BACKGROUND_USE_IMAGE = "profile_background_use_image"
    const val KEY_PROFILE_BACKGROUND_IMAGE = "profile_background_image"
    const val KEY_PROFILE_BACKGROUND_CROP_POSITION = "profile_background_crop_position"
    const val KEY_PROFILE_BACKGROUND_DISPLAY_MODE = "profile_background_display_mode"
    const val KEY_PROFILE_BACKGROUND_BLUR = "profile_background_blur"
    const val KEY_PROFILE_BACKGROUND_TRANSPARENCY = "profile_background_transparency"
    const val KEY_PROFILE_BACKGROUND_CARD_BLUR = "profile_background_card_blur"
    const val KEY_PROFILE_BACKGROUND_CARD_TRANSPARENCY = "profile_background_card_transparency"

    private const val KEY_ASSOCIATION_SOURCE_PREFIX = "association_source_"
    private const val KEY_ONLINE_SOURCE_PREFIX = "online_source_"
    private const val KEY_TTS_SOURCE_PREFIX = "tts_source_"
    const val SYSTEM_TTS_SOURCE_ID = "system_tts"
    const val DEFAULT_MODULE_ENABLED = true
    const val DEFAULT_ASSOCIATION_ENABLED = true
    const val DEFAULT_ASSOCIATION_MANUAL_EDIT_ENABLED = false
    const val DEFAULT_ASSOCIATION_UNLINK_ENABLED = false
    const val DEFAULT_ASSOCIATION_COVER_FIX_ENABLED = false
    const val DEFAULT_READER_ENABLED = true
    const val DEFAULT_READER_LONG_PRESS_ENABLED = false
    const val DEFAULT_READER_READ_ALOUD_ENABLED = true
    const val DEFAULT_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS = false
    const val DEFAULT_READER_READ_ALOUD_RESTART_ON_PAGE_TURN = false
    const val DEFAULT_READER_READ_ALOUD_SELECTION_ENABLED = false
    const val DEFAULT_READER_READ_ALOUD_LYRICON_ENABLED = false
    const val DEFAULT_READER_AUTO_PAGE_ENABLED = false
    const val DEFAULT_READER_OVERWRITE_CHECK_ENABLED = false
    const val DEFAULT_READER_EDIT_OVERWRITE_ENABLED = false
    const val DEFAULT_READER_DICTIONARY_ENABLED = true
    const val DEFAULT_READER_COMPACT_SELECTION_MENU_ENABLED = false
    const val DEFAULT_READER_DIALOGUE_HIGHLIGHT_ENABLED = false
    const val DEFAULT_READER_SELECTION_HIGHLIGHT_ENABLED = false
    const val DEFAULT_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED = false
    const val DEFAULT_CONCISE_LOG_ENABLED = true
    const val DEFAULT_INLINE_SEARCH_ICON_ENABLED = true
    const val DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR = "#FF9800"
    const val DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT = ""
    const val DEFAULT_FONT_ENABLED = true
    const val DEFAULT_FONT_SETTINGS_ENABLED = false
    const val DEFAULT_ACCOUNT_ENABLED = true
    const val DEFAULT_ACCOUNT_EXPORT_ENABLED = false
    const val DEFAULT_ACCOUNT_CACHE_CLEANUP_ENABLED = false
    const val DEFAULT_EDIT_ENABLED = true
    const val DEFAULT_EDIT_FILE_ENABLED = false
    const val DEFAULT_CLOUD_ENABLED = true
    const val DEFAULT_CLOUD_WEBDAV_ENABLED = true
    const val DEFAULT_CLOUD_LOCAL_LIBRARY_ENABLED = true
    const val DEFAULT_CLOUD_EXTENDED_DISPLAY_ENABLED = false
    const val DEFAULT_CLOUD_DOWNLOAD_CANCEL_ENABLED = false
    const val DEFAULT_ROTATION_ENABLED = true
    const val DEFAULT_ROTATION_AUTO_ENABLED = false
    const val DEFAULT_ROTATION_PORTRAIT_LOCK_ENABLED = false
    const val DEFAULT_ROTATION_LANDSCAPE_LOCK_ENABLED = false
    const val DEFAULT_ROTATION_REVERSE_ENABLED = false
    const val DEFAULT_PROFILE_BACKGROUND_ENABLED = false
    const val DEFAULT_PROFILE_BACKGROUND_COLOR = "#80000000"
    const val DEFAULT_PROFILE_BACKGROUND_USE_IMAGE = false
    const val DEFAULT_PROFILE_BACKGROUND_IMAGE = ""
    const val PROFILE_BACKGROUND_CROP_TOP = "top"
    const val PROFILE_BACKGROUND_CROP_CENTER = "center"
    const val PROFILE_BACKGROUND_CROP_BOTTOM = "bottom"
    const val DEFAULT_PROFILE_BACKGROUND_CROP_POSITION = PROFILE_BACKGROUND_CROP_TOP
    const val PROFILE_BACKGROUND_DISPLAY_COVER = "cover"
    const val PROFILE_BACKGROUND_DISPLAY_FIT_WIDTH = "fit_width"
    const val PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT = "fit_height"
    const val DEFAULT_PROFILE_BACKGROUND_DISPLAY_MODE = PROFILE_BACKGROUND_DISPLAY_COVER
    const val DEFAULT_PROFILE_BACKGROUND_BLUR = 50
    const val DEFAULT_PROFILE_BACKGROUND_TRANSPARENCY = 0
    const val DEFAULT_PROFILE_BACKGROUND_CARD_BLUR = 50
    const val DEFAULT_PROFILE_BACKGROUND_CARD_TRANSPARENCY = 4
    val ROTATION_BASE_KEYS = setOf(
        KEY_ROTATION_AUTO_ENABLED,
        KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
        KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
    )

    const val KEY_FONT_GLOBAL_FAMILY = "font_global_family"
    const val KEY_FONT_MAPPING_SONG = "font_mapping_song"
    const val KEY_FONT_MAPPING_KAI = "font_mapping_kai"
    const val KEY_READER_DIALOGUE_HIGHLIGHT_COLOR = "reader_dialogue_highlight_color"
    const val KEY_READER_DIALOGUE_HIGHLIGHT_FONT = "reader_dialogue_highlight_font"
    const val KEY_READER_HIGHLIGHT_STYLES = "reader_highlight_styles"
    const val KEY_READER_HIGHLIGHT_RULES = "reader_highlight_rules"
    const val KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID = "reader_highlight_default_light_style_id"
    const val KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID = "reader_highlight_default_dark_style_id"
    const val KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES = "reader_highlight_book_global_rules"
    const val DEFAULT_READER_HIGHLIGHT_STYLE_ID = "default"
    const val DEFAULT_READER_HIGHLIGHT_LIGHT_STYLE_ID = "builtin_rainbow_glass"
    const val DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID = DEFAULT_READER_HIGHLIGHT_STYLE_ID
    const val READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID = "__default_light__"
    const val READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID = "__default_dark__"
    const val LEGACY_READER_HIGHLIGHT_LIGHT_STYLE_ID = "default_light"
    const val LEGACY_READER_HIGHLIGHT_DARK_STYLE_ID = "default_dark"
    const val BUILTIN_READER_HIGHLIGHT_RAINBOW_GLASS_STYLE_ID = "builtin_rainbow_glass"
    const val DEFAULT_READER_DOUBLE_QUOTE_RULE_ID = "double_quote_dialogue"
    const val DEFAULT_READER_SINGLE_QUOTE_RULE_ID = "single_quote_phrase"

    const val WANFENGLI_SOURCE_GROUP_ID = "wanfengli"
    const val KEY_WANFENGLI_HIDDEN_MIGRATED = "wanfengli_hidden_source_migrated"
    val DEFAULT_SEARCH_SOURCE_GROUP_IDS = setOf("fanqie")


    fun searchSourceKey(groupId: String): String = "$KEY_ASSOCIATION_SOURCE_PREFIX$groupId"

    fun onlineSourceKey(sourceId: String): String = "$KEY_ONLINE_SOURCE_PREFIX$sourceId"

    fun ttsSourceKey(sourceId: String): String = "$KEY_TTS_SOURCE_PREFIX$sourceId"

    fun defaultAssociationSearchSources(): Map<String, Boolean> =
        DEFAULT_SEARCH_SOURCE_GROUP_IDS.associateWith { true }

    fun isSearchSourceGroupDefaultEnabled(groupId: String): Boolean =
        groupId in DEFAULT_SEARCH_SOURCE_GROUP_IDS

    fun searchSourceGroupId(source: BookSource): String? =
        source.id

    fun normalizeRotationSelection(
        autoEnabled: Boolean,
        portraitLockEnabled: Boolean,
        landscapeLockEnabled: Boolean,
    ): RotationSelection =
        when {
            autoEnabled -> RotationSelection(autoEnabled = true)
            portraitLockEnabled -> RotationSelection(portraitLockEnabled = true)
            landscapeLockEnabled -> RotationSelection(landscapeLockEnabled = true)
            else -> RotationSelection()
        }
}

data class SearchSourceGroup(
    val id: String,
    val title: String,
    val sources: Set<BookSource>,
)

data class ModuleSettingsSnapshot(
    val moduleEnabled: Boolean = ModuleSettings.DEFAULT_MODULE_ENABLED,
    val associationEnabled: Boolean = ModuleSettings.DEFAULT_ASSOCIATION_ENABLED,
    val associationManualEditEnabled: Boolean = ModuleSettings.DEFAULT_ASSOCIATION_MANUAL_EDIT_ENABLED,
    val associationUnlinkEnabled: Boolean = ModuleSettings.DEFAULT_ASSOCIATION_UNLINK_ENABLED,
    val associationCoverFixEnabled: Boolean = ModuleSettings.DEFAULT_ASSOCIATION_COVER_FIX_ENABLED,
    val readerEnabled: Boolean = ModuleSettings.DEFAULT_READER_ENABLED,
    val readerLongPressEnabled: Boolean = ModuleSettings.DEFAULT_READER_LONG_PRESS_ENABLED,
    val readerReadAloudEnabled: Boolean = ModuleSettings.DEFAULT_READER_READ_ALOUD_ENABLED,
    val readerReadAloudIgnoreAudioFocus: Boolean = ModuleSettings.DEFAULT_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS,
    val readerReadAloudRestartOnPageTurn: Boolean = ModuleSettings.DEFAULT_READER_READ_ALOUD_RESTART_ON_PAGE_TURN,
    val readerReadAloudSelectionEnabled: Boolean = ModuleSettings.DEFAULT_READER_READ_ALOUD_SELECTION_ENABLED,
    val readerReadAloudLyriconEnabled: Boolean = ModuleSettings.DEFAULT_READER_READ_ALOUD_LYRICON_ENABLED,
    val readerAutoPageEnabled: Boolean = ModuleSettings.DEFAULT_READER_AUTO_PAGE_ENABLED,
    val readerOverwriteCheckEnabled: Boolean = ModuleSettings.DEFAULT_READER_OVERWRITE_CHECK_ENABLED,
    val readerEditOverwriteEnabled: Boolean = ModuleSettings.DEFAULT_READER_EDIT_OVERWRITE_ENABLED,
    val readerDictionaryEnabled: Boolean = ModuleSettings.DEFAULT_READER_DICTIONARY_ENABLED,
    val readerCompactSelectionMenuEnabled: Boolean = ModuleSettings.DEFAULT_READER_COMPACT_SELECTION_MENU_ENABLED,
    val readerDialogueHighlightEnabled: Boolean = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_ENABLED,
    val readerSelectionHighlightEnabled: Boolean = ModuleSettings.DEFAULT_READER_SELECTION_HIGHLIGHT_ENABLED,
    val readerHighlightPerformanceLogEnabled: Boolean = ModuleSettings.DEFAULT_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED,
    val conciseLogEnabled: Boolean = ModuleSettings.DEFAULT_CONCISE_LOG_ENABLED,
    val inlineSearchIconEnabled: Boolean = ModuleSettings.DEFAULT_INLINE_SEARCH_ICON_ENABLED,
    val fontEnabled: Boolean = ModuleSettings.DEFAULT_FONT_ENABLED,
    val fontSettingsEnabled: Boolean = ModuleSettings.DEFAULT_FONT_SETTINGS_ENABLED,
    val accountEnabled: Boolean = ModuleSettings.DEFAULT_ACCOUNT_ENABLED,
    val accountExportEnabled: Boolean = ModuleSettings.DEFAULT_ACCOUNT_EXPORT_ENABLED,
    val accountCacheCleanupEnabled: Boolean = ModuleSettings.DEFAULT_ACCOUNT_CACHE_CLEANUP_ENABLED,
    val editEnabled: Boolean = ModuleSettings.DEFAULT_EDIT_ENABLED,
    val editFileEnabled: Boolean = ModuleSettings.DEFAULT_EDIT_FILE_ENABLED,
    val cloudEnabled: Boolean = ModuleSettings.DEFAULT_CLOUD_ENABLED,
    val cloudWebDavEnabled: Boolean = ModuleSettings.DEFAULT_CLOUD_WEBDAV_ENABLED,
    val cloudLocalLibraryEnabled: Boolean = ModuleSettings.DEFAULT_CLOUD_LOCAL_LIBRARY_ENABLED,
    val cloudExtendedDisplayEnabled: Boolean = ModuleSettings.DEFAULT_CLOUD_EXTENDED_DISPLAY_ENABLED,
    val cloudDownloadCancelEnabled: Boolean = ModuleSettings.DEFAULT_CLOUD_DOWNLOAD_CANCEL_ENABLED,
    val rotationEnabled: Boolean = ModuleSettings.DEFAULT_ROTATION_ENABLED,
    val rotation: RotationSelection = RotationSelection(),
    val rotationReverseEnabled: Boolean = ModuleSettings.DEFAULT_ROTATION_REVERSE_ENABLED,
    val profileBackgroundEnabled: Boolean = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_ENABLED,
    val profileBackgroundColor: String = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR,
    val profileBackgroundUseImage: Boolean = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_USE_IMAGE,
    val profileBackgroundImage: String = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_IMAGE,
    val profileBackgroundCropPosition: String = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CROP_POSITION,
    val profileBackgroundDisplayMode: String = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_DISPLAY_MODE,
    val profileBackgroundBlur: Int = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_BLUR,
    val profileBackgroundTransparency: Int = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_TRANSPARENCY,
    val profileBackgroundCardBlur: Int = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CARD_BLUR,
    val profileBackgroundCardTransparency: Int = ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CARD_TRANSPARENCY,
    val associationSearchSources: Map<String, Boolean> = ModuleSettings.defaultAssociationSearchSources(),
) {
    val canRunAssociation: Boolean
        get() = moduleEnabled

    val canRunAssociationSearch: Boolean
        get() = canRunAssociation && associationSearchSources.any { it.value }

    val canUseManualEdit: Boolean
        get() = canRunAssociation && associationManualEditEnabled

    val canUseAssociationUnlink: Boolean
        get() = false

    val canUseAssociationCoverFix: Boolean
        get() = canRunAssociation && associationCoverFixEnabled

    val canRunReaderLongPress: Boolean
        get() = moduleEnabled && readerLongPressEnabled

    val canRunReaderReadAloud: Boolean
        get() = moduleEnabled

    val canIgnoreReaderReadAloudAudioFocus: Boolean
        get() = moduleEnabled && readerReadAloudIgnoreAudioFocus

    val canRestartReadAloudOnPageTurn: Boolean
        get() = moduleEnabled && readerReadAloudRestartOnPageTurn

    val canUseReaderReadAloudSelection: Boolean
        get() = moduleEnabled && readerReadAloudSelectionEnabled

    val canUseReaderReadAloudLyricon: Boolean
        get() = moduleEnabled && readerReadAloudLyriconEnabled

    val canRunReaderAutoPage: Boolean
        get() = moduleEnabled && readerAutoPageEnabled

    val canRunReaderOverwriteCheck: Boolean
        get() = moduleEnabled && readerOverwriteCheckEnabled

    val canEditReaderSelection: Boolean
        get() = moduleEnabled && readerEditOverwriteEnabled

    val canShowReaderDictionary: Boolean
        get() = moduleEnabled && readerDictionaryEnabled

    val canUseCompactReaderSelectionMenu: Boolean
        get() = moduleEnabled && readerCompactSelectionMenuEnabled

    val canHighlightReaderDialogue: Boolean
        get() = moduleEnabled && readerDialogueHighlightEnabled

    val canHighlightReaderSelection: Boolean
        get() = moduleEnabled && readerSelectionHighlightEnabled

    val canLogReaderHighlightPerformance: Boolean
        get() = moduleEnabled && readerHighlightPerformanceLogEnabled && !conciseLogEnabled

    val canLogCompletionVerbose: Boolean
        get() = moduleEnabled && !conciseLogEnabled

    val canRunFontCompletion: Boolean
        get() = moduleEnabled

    val canUseFontSettings: Boolean
        get() = moduleEnabled

    val canRunAccountCompletion: Boolean
        get() = moduleEnabled

    val canRunAccountDataExport: Boolean
        get() = canRunAccountCompletion && accountExportEnabled

    val canRunStartupCacheCleanup: Boolean
        get() = canRunAccountCompletion && accountCacheCleanupEnabled

    val canRunEditCompletion: Boolean
        get() = moduleEnabled

    val canUseFileEdit: Boolean
        get() = moduleEnabled && editFileEnabled

    val canRunWebDavCloud: Boolean
        get() = moduleEnabled && cloudWebDavEnabled

    val canRunLocalLibraryCloud: Boolean
        get() = moduleEnabled && cloudLocalLibraryEnabled

    val canUseCloudExtendedDisplay: Boolean
        get() = moduleEnabled && cloudExtendedDisplayEnabled

    val canCancelCloudDownload: Boolean
        get() = moduleEnabled && cloudDownloadCancelEnabled

    val canApplyRotation: Boolean
        get() = moduleEnabled &&
            (rotation.autoEnabled || rotation.portraitLockEnabled || rotation.landscapeLockEnabled)

    val canShowProfileBackground: Boolean
        get() = moduleEnabled && profileBackgroundUseImage && profileBackgroundImage.isNotBlank()

    val enabledAssociationSearchSources: Set<BookSource>
        get() = emptySet()

    fun enabledAssociationSearchSources(searchSourceGroups: List<SearchSourceGroup>): Set<BookSource> =
        searchSourceGroups
            .filter { isSearchSourceGroupEnabled(it.id) }
            .flatMapTo(linkedSetOf<BookSource>()) { it.sources }

    fun isSearchSourceGroupEnabled(groupId: String): Boolean =
        associationSearchSources[groupId] ?: ModuleSettings.isSearchSourceGroupDefaultEnabled(groupId)

    fun isSearchSourceEnabled(source: BookSource): Boolean {
        return isSearchSourceGroupEnabled(source.id)
    }
}

data class RotationSelection(
    val autoEnabled: Boolean = ModuleSettings.DEFAULT_ROTATION_AUTO_ENABLED,
    val portraitLockEnabled: Boolean = ModuleSettings.DEFAULT_ROTATION_PORTRAIT_LOCK_ENABLED,
    val landscapeLockEnabled: Boolean = ModuleSettings.DEFAULT_ROTATION_LANDSCAPE_LOCK_ENABLED,
)

data class FontSettingsSnapshot(
    val globalFamily: String = "",
    val songMapping: String = "",
    val kaiMapping: String = "",
)

data class ReaderDialogueHighlightSettingsSnapshot(
    val color: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
    val fontFamily: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT,
)

data class ReaderHighlightSettingsSnapshot(
    val styles: List<ReaderHighlightStyle> = ReaderHighlightStyle.builtIns(),
    val rules: List<ReaderHighlightRule> = ReaderHighlightRule.defaults(),
    val defaultLightStyleId: String = ModuleSettings.DEFAULT_READER_HIGHLIGHT_LIGHT_STYLE_ID,
    val defaultDarkStyleId: String = ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID,
    val bookGlobalRules: Map<String, Set<String>> = emptyMap(),
) {
    fun resolvedStyleId(id: String): String =
        when (id) {
            ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID -> defaultLightStyleId
            ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID -> defaultDarkStyleId
            else -> id
        }

    fun styleById(id: String): ReaderHighlightStyle =
        styles.firstOrNull { it.id == resolvedStyleId(id) } ?: styles.firstOrNull() ?: ReaderHighlightStyle.defaultLight()

    fun defaultLightStyle(): ReaderHighlightStyle = styleById(defaultLightStyleId)

    fun defaultDarkStyle(): ReaderHighlightStyle = styleById(defaultDarkStyleId)

    fun globalRulesEnabledForBook(bookKey: String): Set<String>? =
        bookGlobalRules[bookKey]
            ?: bookGlobalRules.entries.firstOrNull { (storedBookKey, _) ->
                readerHighlightBookIdentityMatches(
                    storedBookKey = storedBookKey,
                    storedBookTitle = "",
                    currentBookKey = bookKey,
                    currentBookTitle = ReaderHighlightBookContext.bookTitle,
                )
            }?.value

    fun bookFollowsGlobalRules(bookKey: String): Boolean =
        globalRulesEnabledForBook(bookKey) == null

    fun globalRules(): List<ReaderHighlightRule> =
        rules.filter { it.bookKey.isBlank() }

    fun enabledGlobalRuleIds(): Set<String> =
        globalRules().filter { it.enabled }.map { it.id }.toSet()

    fun effectiveGlobalRuleIdsForBook(bookKey: String): Set<String> =
        globalRulesEnabledForBook(bookKey) ?: enabledGlobalRuleIds()

    fun bookRules(bookKey: String): List<ReaderHighlightRule> =
        rules.filter { it.bookKey == bookKey }

    fun bookRuleGroups(): List<ReaderHighlightBookRuleGroup> =
        rules
            .filter { it.bookKey.isNotBlank() }
            .groupBy { it.bookKey }
            .map { (bookKey, items) ->
                ReaderHighlightBookRuleGroup(
                    bookKey = bookKey,
                    bookTitle = items.firstNotNullOfOrNull { it.bookTitle.takeIf(String::isNotBlank) } ?: "本书",
                    enabledCount = items.count { it.enabled },
                    totalCount = items.size,
                )
            }
            .sortedBy { it.bookTitle }
}

data class ReaderHighlightBookRuleGroup(
    val bookKey: String,
    val bookTitle: String,
    val enabledCount: Int,
    val totalCount: Int,
)

data class ReaderHighlightStyle(
    val id: String,
    val name: String,
    val color: String,
    val fontFamily: String = "",
    val css: String = "",
    val ninePatchPath: String = "",
    val ninePatchSlice: String = "",
    val darkUsesLight: Boolean = true,
    val darkColor: String = "",
    val darkFontFamily: String = "",
    val darkCss: String = "",
    val darkNinePatchPath: String = "",
    val darkNinePatchSlice: String = "",
) {
    fun colorForTheme(dark: Boolean): String =
        if (dark && !darkUsesLight) darkColor.ifBlank { color } else color

    fun fontFamilyForTheme(dark: Boolean): String =
        if (dark && !darkUsesLight) darkFontFamily.ifBlank { fontFamily } else fontFamily

    fun cssForTheme(dark: Boolean): String =
        if (dark && !darkUsesLight) darkCss.ifBlank { css } else css

    fun ninePatchPathForTheme(dark: Boolean): String =
        if (dark && !darkUsesLight) darkNinePatchPath.ifBlank { ninePatchPath } else ninePatchPath

    fun ninePatchSliceForTheme(dark: Boolean): String =
        if (dark && !darkUsesLight) darkNinePatchSlice.ifBlank { ninePatchSlice } else ninePatchSlice

    companion object {
        fun default(
            color: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
            fontFamily: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT,
        ): ReaderHighlightStyle = orangeDefault(color, fontFamily)

        fun defaultLight(): ReaderHighlightStyle =
            ReaderHighlightStyle(
                id = ModuleSettings.BUILTIN_READER_HIGHLIGHT_RAINBOW_GLASS_STYLE_ID,
                name = "彩色玻璃·紫",
                color = "#5A2E7F",
                css = "background-size: 100% 100%; color: rgba(90,46,127,1); padding-left: 16.0px; padding-right: 16.0px; padding-top: 9.0px; padding-bottom: 9.0px; font-size: 0.9em",
                ninePatchPath = "asset://reader_highlight/rainbow_glass.png",
            )

        fun defaultDark(
            color: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
            fontFamily: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT,
        ): ReaderHighlightStyle =
            ReaderHighlightStyle(
                id = ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID,
                name = "橙色默认",
                color = color,
                fontFamily = fontFamily,
                css = "font-size: 0.9em",
            )

        fun orangeDefault(
            color: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
            fontFamily: String = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT,
        ): ReaderHighlightStyle =
            ReaderHighlightStyle(
                id = ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID,
                name = "橙色默认",
                color = color,
                fontFamily = fontFamily,
                css = "font-size: 0.9em",
            )

        fun builtIns(): List<ReaderHighlightStyle> =
            listOf(defaultLight(), orangeDefault())
    }
}

data class ReaderHighlightRule(
    val id: String,
    val name: String,
    val type: ReaderHighlightRuleType,
    val styleId: String = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
    val darkStyleId: String = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
    val enabled: Boolean = true,
    val pattern: String = "",
    val bookKey: String = "",
    val bookTitle: String = "",
) {
    fun styleIdForTheme(dark: Boolean): String =
        if (dark) {
            darkStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
        } else {
            styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
        }

    fun appliesToBook(currentBookKey: String, currentBookTitle: String = ""): Boolean =
        readerHighlightBookIdentityMatches(
            storedBookKey = bookKey,
            storedBookTitle = bookTitle,
            currentBookKey = currentBookKey,
            currentBookTitle = currentBookTitle,
        )

    companion object {
        fun defaults(): List<ReaderHighlightRule> =
            listOf(
                ReaderHighlightRule(
                    id = ModuleSettings.DEFAULT_READER_DOUBLE_QUOTE_RULE_ID,
                    name = "双引号对话",
                    type = ReaderHighlightRuleType.DoubleQuoteDialogue,
                ),
                ReaderHighlightRule(
                    id = ModuleSettings.DEFAULT_READER_SINGLE_QUOTE_RULE_ID,
                    name = "单引号词组",
                    type = ReaderHighlightRuleType.SingleQuotePhrase,
                ),
            )
    }
}

enum class ReaderHighlightRuleType {
    DoubleQuoteDialogue,
    SingleQuotePhrase,
    FixedText,
    Regex,
}

object ReaderHighlightBookContext {
    @Volatile var bookKey: String = ""
    @Volatile var bookTitle: String = ""
    @Volatile private var runtimeVersion: Long = 0L
    @Volatile var refreshRequester: ((String) -> Unit)? = null

    fun update(bookKey: String, bookTitle: String) {
        this.bookKey = bookKey
        this.bookTitle = bookTitle
    }

    fun version(): Long = runtimeVersion

    fun bumpVersion(source: String, requestRefresh: Boolean = true) {
        runtimeVersion += 1
        if (requestRefresh) refreshRequester?.invoke(source)
    }
}

internal fun readerHighlightBookIdentityMatches(
    storedBookKey: String,
    storedBookTitle: String,
    currentBookKey: String,
    currentBookTitle: String,
): Boolean {
    val storedKey = storedBookKey.trim()
    val currentKey = currentBookKey.trim()
    if (storedKey.isBlank() || currentKey.isBlank()) return false
    if (storedKey == currentKey) return true
    val storedTokens = readerHighlightBookIdentityTokens(storedKey, storedBookTitle)
    val currentTokens = readerHighlightBookIdentityTokens(currentKey, currentBookTitle)
    return storedTokens.any { it in currentTokens }
}

private fun readerHighlightBookIdentityTokens(bookKey: String, bookTitle: String): Set<String> =
    buildSet {
        fun addCandidate(value: String) {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return
            add(trimmed)
            val cleaned = trimmed
                .replace('\\', '/')
                .substringAfterLast('/')
                .removeSuffix(".epub")
                .removeSuffix(".EPUB")
                .trim()
            if (cleaned.isNotBlank()) add(cleaned)
        }
        bookKey.split('|').forEach(::addCandidate)
        addCandidate(bookTitle)
    }
