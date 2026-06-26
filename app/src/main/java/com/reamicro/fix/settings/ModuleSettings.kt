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
    const val KEY_READER_AUTO_PAGE_ENABLED = "reader_auto_page_enabled"
    const val KEY_READER_KEEP_SCREEN_ON_ENABLED = "reader_keep_screen_on_enabled"
    const val KEY_READER_OVERWRITE_CHECK_ENABLED = "reader_overwrite_check_enabled"
    const val KEY_READER_EDIT_OVERWRITE_ENABLED = "reader_edit_overwrite_enabled"
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

    private const val KEY_ASSOCIATION_SOURCE_PREFIX = "association_source_"
    const val DEFAULT_MODULE_ENABLED = true
    const val DEFAULT_ASSOCIATION_ENABLED = false
    const val DEFAULT_ASSOCIATION_MANUAL_EDIT_ENABLED = false
    const val DEFAULT_ASSOCIATION_UNLINK_ENABLED = false
    const val DEFAULT_ASSOCIATION_COVER_FIX_ENABLED = false
    const val DEFAULT_READER_ENABLED = false
    const val DEFAULT_READER_LONG_PRESS_ENABLED = false
    const val DEFAULT_READER_AUTO_PAGE_ENABLED = false
    const val DEFAULT_READER_KEEP_SCREEN_ON_ENABLED = true
    const val DEFAULT_READER_OVERWRITE_CHECK_ENABLED = false
    const val DEFAULT_READER_EDIT_OVERWRITE_ENABLED = false
    const val DEFAULT_FONT_ENABLED = false
    const val DEFAULT_FONT_SETTINGS_ENABLED = false
    const val DEFAULT_ACCOUNT_ENABLED = false
    const val DEFAULT_ACCOUNT_EXPORT_ENABLED = false
    const val DEFAULT_ACCOUNT_CACHE_CLEANUP_ENABLED = false
    const val DEFAULT_EDIT_ENABLED = false
    const val DEFAULT_EDIT_FILE_ENABLED = false
    const val DEFAULT_CLOUD_ENABLED = true
    const val DEFAULT_CLOUD_WEBDAV_ENABLED = true
    const val DEFAULT_CLOUD_LOCAL_LIBRARY_ENABLED = true
    const val DEFAULT_CLOUD_EXTENDED_DISPLAY_ENABLED = false
    const val DEFAULT_CLOUD_DOWNLOAD_CANCEL_ENABLED = false
    const val DEFAULT_ROTATION_ENABLED = false
    const val DEFAULT_ROTATION_AUTO_ENABLED = false
    const val DEFAULT_ROTATION_PORTRAIT_LOCK_ENABLED = false
    const val DEFAULT_ROTATION_LANDSCAPE_LOCK_ENABLED = false
    const val DEFAULT_ROTATION_REVERSE_ENABLED = false
    val ROTATION_BASE_KEYS = setOf(
        KEY_ROTATION_AUTO_ENABLED,
        KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
        KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
    )

    const val KEY_FONT_GLOBAL_FAMILY = "font_global_family"
    const val KEY_FONT_MAPPING_SONG = "font_mapping_song"
    const val KEY_FONT_MAPPING_KAI = "font_mapping_kai"

    const val WANFENGLI_SOURCE_GROUP_ID = "wanfengli"
    const val KEY_WANFENGLI_HIDDEN_MIGRATED = "wanfengli_hidden_source_migrated"
    val DEFAULT_SEARCH_SOURCE_GROUP_IDS = setOf("fanqie")


    fun searchSourceKey(groupId: String): String = "$KEY_ASSOCIATION_SOURCE_PREFIX$groupId"

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
    val readerAutoPageEnabled: Boolean = ModuleSettings.DEFAULT_READER_AUTO_PAGE_ENABLED,
    val readerKeepScreenOnEnabled: Boolean = ModuleSettings.DEFAULT_READER_KEEP_SCREEN_ON_ENABLED,
    val readerOverwriteCheckEnabled: Boolean = ModuleSettings.DEFAULT_READER_OVERWRITE_CHECK_ENABLED,
    val readerEditOverwriteEnabled: Boolean = ModuleSettings.DEFAULT_READER_EDIT_OVERWRITE_ENABLED,
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
    val associationSearchSources: Map<String, Boolean> = ModuleSettings.defaultAssociationSearchSources(),
) {
    val canRunAssociation: Boolean
        get() = moduleEnabled && associationEnabled

    val canRunAssociationSearch: Boolean
        get() = canRunAssociation && associationSearchSources.any { it.value }

    val canUseManualEdit: Boolean
        get() = canRunAssociation && associationManualEditEnabled

    val canUseAssociationUnlink: Boolean
        get() = canRunAssociation && associationUnlinkEnabled

    val canUseAssociationCoverFix: Boolean
        get() = canRunAssociation && associationCoverFixEnabled

    val canRunReaderLongPress: Boolean
        get() = moduleEnabled && readerEnabled && readerLongPressEnabled

    val canRunReaderAutoPage: Boolean
        get() = moduleEnabled && readerEnabled && readerAutoPageEnabled

    val canKeepScreenOnDuringAutoPage: Boolean
        get() = canRunReaderAutoPage && readerKeepScreenOnEnabled

    val canRunReaderOverwriteCheck: Boolean
        get() = moduleEnabled && readerEnabled && readerOverwriteCheckEnabled

    val canEditReaderSelection: Boolean
        get() = moduleEnabled && readerEnabled && readerEditOverwriteEnabled

    val canRunFontCompletion: Boolean
        get() = moduleEnabled && fontEnabled

    val canUseFontSettings: Boolean
        get() = canRunFontCompletion && fontSettingsEnabled

    val canRunAccountCompletion: Boolean
        get() = moduleEnabled && accountEnabled

    val canRunAccountDataExport: Boolean
        get() = canRunAccountCompletion && accountExportEnabled

    val canRunStartupCacheCleanup: Boolean
        get() = canRunAccountCompletion && accountCacheCleanupEnabled

    val canRunEditCompletion: Boolean
        get() = moduleEnabled && readerEnabled

    val canUseFileEdit: Boolean
        get() = moduleEnabled && readerEnabled && editFileEnabled

    val canRunWebDavCloud: Boolean
        get() = moduleEnabled && cloudEnabled && cloudWebDavEnabled

    val canRunLocalLibraryCloud: Boolean
        get() = moduleEnabled && cloudEnabled && cloudLocalLibraryEnabled

    val canUseCloudExtendedDisplay: Boolean
        get() = moduleEnabled && cloudEnabled && cloudExtendedDisplayEnabled

    val canCancelCloudDownload: Boolean
        get() = moduleEnabled && cloudEnabled && cloudDownloadCancelEnabled

    val canApplyRotation: Boolean
        get() = moduleEnabled && rotationEnabled &&
            (rotation.autoEnabled || rotation.portraitLockEnabled || rotation.landscapeLockEnabled)

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
