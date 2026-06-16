package com.reamicro.fix.settings

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge

class XposedModuleSettings(
    private val contextProvider: () -> Context? = { null },
) {
    @Volatile private var attachedContext: Context? = null
    @Volatile private var cachedSnapshot: ModuleSettingsSnapshot? = null
    @Volatile private var cachedAtMs: Long = 0L
    @Volatile private var cachedFontSettings: FontSettingsSnapshot? = null
    @Volatile private var cachedFontSettingsAtMs: Long = 0L
    @Volatile private var lastLogKey: String = ""

    fun attachContext(context: Context) {
        attachedContext = context.applicationContext ?: context
        cachedSnapshot = null
        cachedAtMs = 0L
        cachedFontSettings = null
        cachedFontSettingsAtMs = 0L
    }

    fun snapshot(): ModuleSettingsSnapshot {
        val now = System.currentTimeMillis()
        cachedSnapshot?.takeIf { now - cachedAtMs < CACHE_WINDOW_MS }?.let { return it }
        val snapshot = prefs()?.let(::readSnapshot) ?: ModuleSettingsSnapshot()
        cachedSnapshot = snapshot
        cachedAtMs = now
        logSnapshot(snapshot)
        return snapshot
    }

    fun setModuleEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_MODULE_ENABLED, enabled)
    }

    fun setAssociationEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ASSOCIATION_ENABLED, enabled)
    }

    fun setAssociationManualEditEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ASSOCIATION_MANUAL_EDIT_ENABLED, enabled)
    }

    fun setAssociationUnlinkEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ASSOCIATION_UNLINK_ENABLED, enabled)
    }

    fun setAssociationCoverFixEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ASSOCIATION_COVER_FIX_ENABLED, enabled)
    }

    fun setReaderEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_ENABLED, enabled)
    }

    fun setReaderLongPressEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_LONG_PRESS_ENABLED, enabled)
    }

    fun setReaderAutoPageEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_AUTO_PAGE_ENABLED, enabled)
    }

    fun setReaderKeepScreenOnEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_KEEP_SCREEN_ON_ENABLED, enabled)
    }

    fun setReaderOverwriteCheckEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED, enabled)
    }

    fun setReaderEditOverwriteEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED, enabled)
    }

    fun setFontEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_FONT_ENABLED, enabled)
    }

    fun setFontSettingsEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_FONT_SETTINGS_ENABLED, enabled)
    }

    fun setFontIsolationEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_FONT_ISOLATION_ENABLED, enabled)
    }

    fun setFontObfuscationDetectionEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_FONT_OBFUSCATION_DETECTION_ENABLED, enabled)
    }

    fun setAccountEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ACCOUNT_ENABLED, enabled)
    }

    fun setAccountExportEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ACCOUNT_EXPORT_ENABLED, enabled)
    }

    fun setAccountCacheCleanupEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ACCOUNT_CACHE_CLEANUP_ENABLED, enabled)
    }

    fun setEditEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_EDIT_ENABLED, enabled)
    }

    fun setEditFileEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_EDIT_FILE_ENABLED, enabled)
    }

    fun setCloudEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_CLOUD_ENABLED, enabled)
    }

    fun setCloudWebDavEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_CLOUD_WEBDAV_ENABLED, enabled)
    }

    fun setCloudLocalLibraryEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_CLOUD_LOCAL_LIBRARY_ENABLED, enabled)
    }

    fun setCloudExtendedDisplayEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_CLOUD_EXTENDED_DISPLAY_ENABLED, enabled)
    }

    fun setCloudDownloadCancelEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_CLOUD_DOWNLOAD_CANCEL_ENABLED, enabled)
    }

    fun setRotationEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ROTATION_ENABLED, enabled)
    }

    fun setRotationAutoEnabled(enabled: Boolean) {
        putExclusiveRotationBase(ModuleSettings.KEY_ROTATION_AUTO_ENABLED, enabled)
    }

    fun setRotationPortraitLockEnabled(enabled: Boolean) {
        putExclusiveRotationBase(ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED, enabled)
    }

    fun setRotationLandscapeLockEnabled(enabled: Boolean) {
        putExclusiveRotationBase(ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED, enabled)
    }

    fun setRotationReverseEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_ROTATION_REVERSE_ENABLED, enabled)
    }

    fun setAssociationSearchSourceEnabled(groupId: String, enabled: Boolean) {
        putBoolean(ModuleSettings.searchSourceKey(groupId), enabled)
    }

    fun fontSettings(): FontSettingsSnapshot {
        val now = System.currentTimeMillis()
        cachedFontSettings?.takeIf { now - cachedFontSettingsAtMs < CACHE_WINDOW_MS }?.let { return it }
        val prefs = prefs() ?: return FontSettingsSnapshot()
        return FontSettingsSnapshot(
            globalFamily = prefs.getString(ModuleSettings.KEY_FONT_GLOBAL_FAMILY, "").orEmpty(),
            songMapping = prefs.getString(ModuleSettings.KEY_FONT_MAPPING_SONG, "").orEmpty(),
            kaiMapping = prefs.getString(ModuleSettings.KEY_FONT_MAPPING_KAI, "").orEmpty(),
        ).also {
            cachedFontSettings = it
            cachedFontSettingsAtMs = now
        }
    }

    fun setFontGlobalFamily(family: String) {
        putString(ModuleSettings.KEY_FONT_GLOBAL_FAMILY, family)
    }

    fun setFontSongMapping(family: String) {
        putString(ModuleSettings.KEY_FONT_MAPPING_SONG, family)
    }

    fun setFontKaiMapping(family: String) {
        putString(ModuleSettings.KEY_FONT_MAPPING_KAI, family)
    }

    fun bookTypeSetting(bookId: Long): ReaderTypeSettingSnapshot {
        val prefs = prefs() ?: return ReaderTypeSettingSnapshot()
        val prefix = bookTypeSettingPrefix(bookId)
        return ReaderTypeSettingSnapshot(
            embeddedFonts = prefs.booleanOrNull("${prefix}embedded_fonts"),
        )
    }

    fun setBookTypeSetting(bookId: Long, value: ReaderTypeSettingSnapshot) {
        val editor = prefs()?.edit() ?: return
        val prefix = bookTypeSettingPrefix(bookId)
        putNullableBoolean(editor, "${prefix}embedded_fonts", value.embeddedFonts)
        clearUnsupportedBookTypeSettings(editor, prefix)
        editor.commit()
    }

    fun setBookTypeSettingValue(bookId: Long, keyName: String, value: Any?) {
        val editor = prefs()?.edit() ?: return
        val prefix = bookTypeSettingPrefix(bookId)
        when (keyName) {
            "embedded_fonts" -> putNullableBoolean(editor, "${prefix}embedded_fonts", value as? Boolean)
            else -> return
        }
        clearUnsupportedBookTypeSettings(editor, prefix)
        editor.commit()
    }

    private fun clearUnsupportedBookTypeSettings(editor: SharedPreferences.Editor, prefix: String) {
        editor.remove("${prefix}family")
        editor.remove("${prefix}text_size")
        editor.remove("${prefix}line_height")
        editor.remove("${prefix}padding")
        editor.remove("${prefix}built_in_fonts")
    }

    private fun putNullableBoolean(editor: SharedPreferences.Editor, key: String, value: Boolean?) {
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putBoolean(key, value)
        }
    }

    private fun SharedPreferences.booleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private fun putBoolean(key: String, value: Boolean) {
        prefs()?.edit()?.putBoolean(key, value)?.commit()
        cachedSnapshot = null
        cachedAtMs = 0L
        snapshot()
    }

    private fun putString(key: String, value: String) {
        prefs()?.edit()?.putString(key, value)?.commit()
        cachedSnapshot = null
        cachedAtMs = 0L
        cachedFontSettings = null
        cachedFontSettingsAtMs = 0L
        snapshot()
    }

    private fun putExclusiveRotationBase(key: String, enabled: Boolean) {
        val editor = prefs()?.edit() ?: return
        if (enabled) {
            ModuleSettings.ROTATION_BASE_KEYS.forEach { editor.putBoolean(it, it == key) }
        } else {
            ModuleSettings.ROTATION_BASE_KEYS.forEach { editor.putBoolean(it, false) }
        }
        editor.commit()
        cachedSnapshot = null
        cachedAtMs = 0L
        snapshot()
    }

    private fun prefs(): SharedPreferences? {
        val context = contextProvider() ?: attachedContext ?: return null
        return context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun readSnapshot(prefs: SharedPreferences): ModuleSettingsSnapshot {
        migrateHiddenWanFengLiSource(prefs)
        val rotation = ModuleSettings.normalizeRotationSelection(
            autoEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ROTATION_AUTO_ENABLED,
                ModuleSettings.DEFAULT_ROTATION_AUTO_ENABLED,
            ),
            portraitLockEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
                ModuleSettings.DEFAULT_ROTATION_PORTRAIT_LOCK_ENABLED,
            ),
            landscapeLockEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
                ModuleSettings.DEFAULT_ROTATION_LANDSCAPE_LOCK_ENABLED,
            ),
        )
        if (prefs.getBoolean(ModuleSettings.KEY_READER_LONG_PRESS_ENABLED, false)) {
            prefs.edit().putBoolean(ModuleSettings.KEY_READER_LONG_PRESS_ENABLED, false).commit()
            XposedBridge.log("ReaMicro LSP reader long-press menu disabled by migration")
        }
        val readerLongPressEnabled = false
        val readerAutoPageEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_AUTO_PAGE_ENABLED,
            ModuleSettings.DEFAULT_READER_AUTO_PAGE_ENABLED,
        )
        val readerKeepScreenOnEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_KEEP_SCREEN_ON_ENABLED,
            ModuleSettings.DEFAULT_READER_KEEP_SCREEN_ON_ENABLED,
        )
        val readerOverwriteCheckEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED,
            ModuleSettings.DEFAULT_READER_OVERWRITE_CHECK_ENABLED,
        )
        val readerEditOverwriteEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED,
            ModuleSettings.DEFAULT_READER_EDIT_OVERWRITE_ENABLED,
        )
        val editFileEnabled = prefs.getBoolean(
            ModuleSettings.KEY_EDIT_FILE_ENABLED,
            ModuleSettings.DEFAULT_EDIT_FILE_ENABLED,
        )
        val cloudWebDavEnabled = prefs.getBoolean(
            ModuleSettings.KEY_CLOUD_WEBDAV_ENABLED,
            ModuleSettings.DEFAULT_CLOUD_WEBDAV_ENABLED,
        )
        val cloudLocalLibraryEnabled = prefs.getBoolean(
            ModuleSettings.KEY_CLOUD_LOCAL_LIBRARY_ENABLED,
            ModuleSettings.DEFAULT_CLOUD_LOCAL_LIBRARY_ENABLED,
        )
        val cloudExtendedDisplayEnabled = prefs.getBoolean(
            ModuleSettings.KEY_CLOUD_EXTENDED_DISPLAY_ENABLED,
            ModuleSettings.DEFAULT_CLOUD_EXTENDED_DISPLAY_ENABLED,
        )
        val cloudDownloadCancelEnabled = prefs.getBoolean(
            ModuleSettings.KEY_CLOUD_DOWNLOAD_CANCEL_ENABLED,
            ModuleSettings.DEFAULT_CLOUD_DOWNLOAD_CANCEL_ENABLED,
        )
        return ModuleSettingsSnapshot(
            moduleEnabled = prefs.getBoolean(
                ModuleSettings.KEY_MODULE_ENABLED,
                ModuleSettings.DEFAULT_MODULE_ENABLED,
            ),
            associationEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ASSOCIATION_ENABLED,
                ModuleSettings.DEFAULT_ASSOCIATION_ENABLED,
            ),
            associationManualEditEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ASSOCIATION_MANUAL_EDIT_ENABLED,
                ModuleSettings.DEFAULT_ASSOCIATION_MANUAL_EDIT_ENABLED,
            ),
            associationUnlinkEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ASSOCIATION_UNLINK_ENABLED,
                ModuleSettings.DEFAULT_ASSOCIATION_UNLINK_ENABLED,
            ),
            associationCoverFixEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ASSOCIATION_COVER_FIX_ENABLED,
                ModuleSettings.DEFAULT_ASSOCIATION_COVER_FIX_ENABLED,
            ),
            readerEnabled = prefs.getBoolean(
                ModuleSettings.KEY_READER_ENABLED,
                readerLongPressEnabled ||
                    readerAutoPageEnabled ||
                    readerOverwriteCheckEnabled ||
                    readerEditOverwriteEnabled ||
                    editFileEnabled ||
                    ModuleSettings.DEFAULT_READER_ENABLED,
            ),
            readerLongPressEnabled = readerLongPressEnabled,
            readerAutoPageEnabled = readerAutoPageEnabled,
            readerKeepScreenOnEnabled = readerKeepScreenOnEnabled,
            readerOverwriteCheckEnabled = readerOverwriteCheckEnabled,
            readerEditOverwriteEnabled = readerEditOverwriteEnabled,
            fontEnabled = prefs.getBoolean(
                ModuleSettings.KEY_FONT_ENABLED,
                ModuleSettings.DEFAULT_FONT_ENABLED,
            ),
            fontSettingsEnabled = prefs.getBoolean(
                ModuleSettings.KEY_FONT_SETTINGS_ENABLED,
                ModuleSettings.DEFAULT_FONT_SETTINGS_ENABLED,
            ),
            fontIsolationEnabled = prefs.getBoolean(
                ModuleSettings.KEY_FONT_ISOLATION_ENABLED,
                ModuleSettings.DEFAULT_FONT_ISOLATION_ENABLED,
            ),
            fontObfuscationDetectionEnabled = prefs.getBoolean(
                ModuleSettings.KEY_FONT_OBFUSCATION_DETECTION_ENABLED,
                ModuleSettings.DEFAULT_FONT_OBFUSCATION_DETECTION_ENABLED,
            ),
            accountEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ACCOUNT_ENABLED,
                ModuleSettings.DEFAULT_ACCOUNT_ENABLED,
            ),
            accountExportEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ACCOUNT_EXPORT_ENABLED,
                ModuleSettings.DEFAULT_ACCOUNT_EXPORT_ENABLED,
            ),
            accountCacheCleanupEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ACCOUNT_CACHE_CLEANUP_ENABLED,
                ModuleSettings.DEFAULT_ACCOUNT_CACHE_CLEANUP_ENABLED,
            ),
            editEnabled = prefs.getBoolean(
                ModuleSettings.KEY_EDIT_ENABLED,
                ModuleSettings.DEFAULT_EDIT_ENABLED,
            ),
            editFileEnabled = editFileEnabled,
            cloudEnabled = prefs.getBoolean(
                ModuleSettings.KEY_CLOUD_ENABLED,
                cloudWebDavEnabled || cloudLocalLibraryEnabled || ModuleSettings.DEFAULT_CLOUD_ENABLED,
            ),
            cloudWebDavEnabled = cloudWebDavEnabled,
            cloudLocalLibraryEnabled = cloudLocalLibraryEnabled,
            cloudExtendedDisplayEnabled = cloudExtendedDisplayEnabled,
            cloudDownloadCancelEnabled = cloudDownloadCancelEnabled,
            rotationEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ROTATION_ENABLED,
                ModuleSettings.DEFAULT_ROTATION_ENABLED,
            ),
            rotation = rotation,
            rotationReverseEnabled = prefs.getBoolean(
                ModuleSettings.KEY_ROTATION_REVERSE_ENABLED,
                ModuleSettings.DEFAULT_ROTATION_REVERSE_ENABLED,
            ),
            associationSearchSources = readAssociationSearchSources(prefs),
        )
    }

    private fun readAssociationSearchSources(prefs: SharedPreferences): Map<String, Boolean> {
        val values = ModuleSettings.defaultAssociationSearchSources().toMutableMap()
        prefs.all.keys
            .filter { it.startsWith(ASSOCIATION_SOURCE_KEY_PREFIX) }
            .forEach { key ->
                val groupId = key.removePrefix(ASSOCIATION_SOURCE_KEY_PREFIX)
                if (groupId.isNotBlank()) {
                    values[groupId] = prefs.getBoolean(
                        key,
                        ModuleSettings.isSearchSourceGroupDefaultEnabled(groupId),
                    )
                }
            }
        return values
    }

    private fun migrateHiddenWanFengLiSource(prefs: SharedPreferences) {
        if (prefs.contains(ModuleSettings.KEY_WANFENGLI_HIDDEN_MIGRATED)) return
        val wanFengLiKey = ModuleSettings.searchSourceKey(ModuleSettings.WANFENGLI_SOURCE_GROUP_ID)
        if (prefs.contains(wanFengLiKey)) {
            prefs.edit().putBoolean(ModuleSettings.KEY_WANFENGLI_HIDDEN_MIGRATED, true).commit()
            return
        }
        val hadExistingSettings = prefs.all.keys.any { it != ModuleSettings.KEY_WANFENGLI_HIDDEN_MIGRATED }
        prefs.edit()
            .putBoolean(ModuleSettings.KEY_WANFENGLI_HIDDEN_MIGRATED, true)
            .apply {
                if (hadExistingSettings) {
                    putBoolean(wanFengLiKey, true)
                }
            }
            .commit()
        if (hadExistingSettings) {
            XposedBridge.log("ReaMicro LSP WanFengLi source kept enabled by migration")
        }
    }

    private fun logSnapshot(snapshot: ModuleSettingsSnapshot) {
        val key = listOf(
            snapshot.moduleEnabled,
            snapshot.associationEnabled,
            snapshot.associationManualEditEnabled,
            snapshot.associationUnlinkEnabled,
            snapshot.associationCoverFixEnabled,
            snapshot.readerEnabled,
            snapshot.readerLongPressEnabled,
            snapshot.readerAutoPageEnabled,
            snapshot.readerKeepScreenOnEnabled,
            snapshot.readerOverwriteCheckEnabled,
            snapshot.readerEditOverwriteEnabled,
            snapshot.fontEnabled,
            snapshot.fontSettingsEnabled,
            snapshot.fontIsolationEnabled,
            snapshot.fontObfuscationDetectionEnabled,
            snapshot.accountEnabled,
            snapshot.accountExportEnabled,
            snapshot.accountCacheCleanupEnabled,
            snapshot.editEnabled,
            snapshot.editFileEnabled,
            snapshot.cloudEnabled,
            snapshot.cloudWebDavEnabled,
            snapshot.cloudLocalLibraryEnabled,
            snapshot.cloudExtendedDisplayEnabled,
            snapshot.cloudDownloadCancelEnabled,
            snapshot.rotationEnabled,
            snapshot.rotation,
            snapshot.rotationReverseEnabled,
            snapshot.associationSearchSources,
        ).joinToString("|")
        if (key == lastLogKey) return
        lastLogKey = key
        runCatching {
            XposedBridge.log(
                "ReaMicro LSP settings from reamicro-settings: " +
                    "module=${snapshot.moduleEnabled}, " +
                    "association=${snapshot.associationEnabled}, " +
                    "manualEdit=${snapshot.associationManualEditEnabled}, " +
                    "unlink=${snapshot.associationUnlinkEnabled}, " +
                    "coverFix=${snapshot.associationCoverFixEnabled}, " +
                    "reader=${snapshot.readerEnabled}, " +
                    "readerLongPress=${snapshot.readerLongPressEnabled}, " +
                    "readerAutoPage=${snapshot.readerAutoPageEnabled}, " +
                    "readerKeepScreenOn=${snapshot.readerKeepScreenOnEnabled}, " +
                    "readerOverwriteCheck=${snapshot.readerOverwriteCheckEnabled}, " +
                    "readerEditOverwrite=${snapshot.readerEditOverwriteEnabled}, " +
                    "font=${snapshot.fontEnabled}, " +
                    "fontSettings=${snapshot.fontSettingsEnabled}, " +
                    "fontIsolation=${snapshot.fontIsolationEnabled}, " +
                    "fontObfuscationDetection=${snapshot.fontObfuscationDetectionEnabled}, " +
                    "account=${snapshot.accountEnabled}, " +
                    "accountExport=${snapshot.accountExportEnabled}, " +
                    "accountCacheCleanup=${snapshot.accountCacheCleanupEnabled}, " +
                    "edit=${snapshot.editEnabled}, " +
                    "editFile=${snapshot.editFileEnabled}, " +
                    "cloud=${snapshot.cloudEnabled}, " +
                    "cloudWebDav=${snapshot.cloudWebDavEnabled}, " +
                    "cloudLocalLibrary=${snapshot.cloudLocalLibraryEnabled}, " +
                    "cloudExtendedDisplay=${snapshot.cloudExtendedDisplayEnabled}, " +
                    "cloudDownloadCancel=${snapshot.cloudDownloadCancelEnabled}, " +
                    "rotationEnabled=${snapshot.rotationEnabled}, " +
                    "rotation=${snapshot.rotation}, " +
                    "rotationReverse=${snapshot.rotationReverseEnabled}, " +
                    "sources=${snapshot.associationSearchSources}",
            )
        }
    }

    private companion object {
        const val CACHE_WINDOW_MS = 200L
        const val ASSOCIATION_SOURCE_KEY_PREFIX = "association_source_"
    }

    private fun bookTypeSettingPrefix(bookId: Long): String =
        "${ModuleSettings.KEY_BOOK_TYPESETTING_PREFIX}${bookId}_"
}
