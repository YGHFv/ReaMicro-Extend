package com.reamicro.fix.settings

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject

class XposedModuleSettings(
    private val contextProvider: () -> Context? = { null },
) {
    @Volatile private var attachedContext: Context? = null
    @Volatile private var cachedSnapshot: ModuleSettingsSnapshot? = null
    @Volatile private var cachedAtMs: Long = 0L
    @Volatile private var cachedFontSettings: FontSettingsSnapshot? = null
    @Volatile private var cachedFontSettingsAtMs: Long = 0L
    @Volatile private var cachedDialogueHighlightSettings: ReaderDialogueHighlightSettingsSnapshot? = null
    @Volatile private var cachedDialogueHighlightSettingsAtMs: Long = 0L
    @Volatile private var cachedHighlightSettings: ReaderHighlightSettingsSnapshot? = null
    @Volatile private var cachedHighlightSettingsAtMs: Long = 0L
    @Volatile private var lastLogKey: String = ""

    fun attachContext(context: Context) {
        attachedContext = context.applicationContext ?: context
        cachedSnapshot = null
        cachedAtMs = 0L
        cachedFontSettings = null
        cachedFontSettingsAtMs = 0L
        cachedDialogueHighlightSettings = null
        cachedDialogueHighlightSettingsAtMs = 0L
        cachedHighlightSettings = null
        cachedHighlightSettingsAtMs = 0L
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

    fun setReaderOverwriteCheckEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED, enabled)
    }

    fun setReaderEditOverwriteEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED, enabled)
    }

    fun setReaderDictionaryEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_DICTIONARY_ENABLED, enabled)
    }

    fun setReaderCompactSelectionMenuEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_COMPACT_SELECTION_MENU_ENABLED, enabled)
    }

    fun setReaderDialogueHighlightEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED, enabled)
    }

    fun setFontEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_FONT_ENABLED, enabled)
    }

    fun setFontSettingsEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_FONT_SETTINGS_ENABLED, enabled)
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

    fun setOnlineSourceEnabled(sourceId: String, enabled: Boolean) {
        putBoolean(ModuleSettings.onlineSourceKey(sourceId), enabled)
    }

    fun isOnlineSourceEnabled(sourceId: String): Boolean =
        prefs()?.getBoolean(ModuleSettings.onlineSourceKey(sourceId), false) ?: false

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

    fun dialogueHighlightSettings(): ReaderDialogueHighlightSettingsSnapshot {
        val defaultStyle = highlightSettings().styleById(ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID)
        return ReaderDialogueHighlightSettingsSnapshot(
            color = defaultStyle.color,
            fontFamily = defaultStyle.fontFamily,
        )
    }

    fun highlightSettings(): ReaderHighlightSettingsSnapshot {
        val now = System.currentTimeMillis()
        cachedHighlightSettings
            ?.takeIf { now - cachedHighlightSettingsAtMs < CACHE_WINDOW_MS }
            ?.let { return it }
        val prefs = prefs() ?: return ReaderHighlightSettingsSnapshot()
        val fallbackStyle = ReaderHighlightStyle.default(
            color = normalizeHighlightColor(
                prefs.getString(
                    ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_COLOR,
                    ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
                ).orEmpty(),
            ),
            fontFamily = prefs.getString(
                ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_FONT,
                ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT,
            ).orEmpty(),
        )
        return ReaderHighlightSettingsSnapshot(
            styles = readHighlightStyles(prefs, fallbackStyle),
            rules = readHighlightRules(prefs),
        ).also {
            cachedHighlightSettings = it
            cachedHighlightSettingsAtMs = now
        }
    }

    fun setReaderDialogueHighlightColor(color: String) {
        updateDefaultHighlightStyle { style -> style.copy(color = normalizeHighlightColor(color)) }
    }

    fun setReaderDialogueHighlightFontFamily(family: String) {
        updateDefaultHighlightStyle { style -> style.copy(fontFamily = family) }
    }

    fun setReaderHighlightStyle(style: ReaderHighlightStyle) {
        val current = highlightSettings()
        val next = current.styles
            .filterNot { it.id == style.id }
            .plus(style.copy(color = normalizeHighlightColor(style.color)))
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES, encodeHighlightStyles(next))
    }

    fun setReaderHighlightRule(rule: ReaderHighlightRule) {
        val current = highlightSettings()
        val next = current.rules
            .filterNot { it.id == rule.id }
            .plus(rule)
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_RULES, encodeHighlightRules(next))
    }

    fun setReaderHighlightRuleEnabled(ruleId: String, enabled: Boolean) {
        highlightSettings().rules.firstOrNull { it.id == ruleId }?.let { rule ->
            setReaderHighlightRule(rule.copy(enabled = enabled))
        }
    }

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
        cachedDialogueHighlightSettings = null
        cachedDialogueHighlightSettingsAtMs = 0L
        cachedHighlightSettings = null
        cachedHighlightSettingsAtMs = 0L
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
        migrateLegacyParentSwitches(prefs)
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
        val readerOverwriteCheckEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED,
            ModuleSettings.DEFAULT_READER_OVERWRITE_CHECK_ENABLED,
        )
        val readerEditOverwriteEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED,
            ModuleSettings.DEFAULT_READER_EDIT_OVERWRITE_ENABLED,
        )
        val readerDictionaryEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_DICTIONARY_ENABLED,
            ModuleSettings.DEFAULT_READER_DICTIONARY_ENABLED,
        )
        val readerCompactSelectionMenuEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_COMPACT_SELECTION_MENU_ENABLED,
            ModuleSettings.DEFAULT_READER_COMPACT_SELECTION_MENU_ENABLED,
        )
        val readerDialogueHighlightEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED,
            ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_ENABLED,
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
                    readerDialogueHighlightEnabled ||
                    ModuleSettings.DEFAULT_READER_ENABLED,
            ),
            readerLongPressEnabled = readerLongPressEnabled,
            readerAutoPageEnabled = readerAutoPageEnabled,
            readerOverwriteCheckEnabled = readerOverwriteCheckEnabled,
            readerEditOverwriteEnabled = readerEditOverwriteEnabled,
            readerDictionaryEnabled = readerDictionaryEnabled,
            readerCompactSelectionMenuEnabled = readerCompactSelectionMenuEnabled,
            readerDialogueHighlightEnabled = readerDialogueHighlightEnabled,
            fontEnabled = prefs.getBoolean(
                ModuleSettings.KEY_FONT_ENABLED,
                ModuleSettings.DEFAULT_FONT_ENABLED,
            ),
            fontSettingsEnabled = prefs.getBoolean(
                ModuleSettings.KEY_FONT_SETTINGS_ENABLED,
                ModuleSettings.DEFAULT_FONT_SETTINGS_ENABLED,
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

    private fun migrateLegacyParentSwitches(prefs: SharedPreferences) {
        val disabledKeys = LEGACY_PARENT_SWITCH_KEYS.filter { key ->
            !prefs.getBoolean(key, true)
        }
        if (disabledKeys.isEmpty()) return
        val editor = prefs.edit()
        disabledKeys.forEach { key -> editor.putBoolean(key, true) }
        editor.commit()
        XposedBridge.log("ReaMicro LSP legacy parent switches forced on: ${disabledKeys.joinToString()}")
    }

    private fun normalizeHighlightColor(value: String): String {
        val trimmed = value.trim()
        val hex = when {
            trimmed.matches(Regex("^#[0-9a-fA-F]{6}$")) -> trimmed
            trimmed.matches(Regex("^#[0-9a-fA-F]{8}$")) -> "#${trimmed.takeLast(6)}"
            trimmed.matches(Regex("^[0-9a-fA-F]{6}$")) -> "#$trimmed"
            trimmed.matches(Regex("^[0-9a-fA-F]{8}$")) -> "#${trimmed.takeLast(6)}"
            else -> ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR
        }
        return hex.uppercase()
    }

    private fun updateDefaultHighlightStyle(update: (ReaderHighlightStyle) -> ReaderHighlightStyle) {
        val current = highlightSettings()
        val defaultStyle = current.styleById(ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID)
        setReaderHighlightStyle(update(defaultStyle.copy(id = ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID)))
    }

    private fun readHighlightStyles(
        prefs: SharedPreferences,
        fallbackStyle: ReaderHighlightStyle,
    ): List<ReaderHighlightStyle> {
        val raw = prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES, "").orEmpty()
        val decoded = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        ReaderHighlightStyle(
                            id = id,
                            name = item.optString("name").ifBlank { id },
                            color = normalizeHighlightColor(item.optString("color")),
                            fontFamily = item.optString("fontFamily"),
                            css = item.optString("css"),
                            ninePatchPath = item.optString("ninePatchPath"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        val hasDefault = decoded.any { it.id == ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID }
        return if (hasDefault) decoded else listOf(fallbackStyle) + decoded
    }

    private fun readHighlightRules(prefs: SharedPreferences): List<ReaderHighlightRule> {
        val defaults = ReaderHighlightRule.defaults()
        val raw = prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_RULES, "").orEmpty()
        val decoded = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    val type = runCatching {
                        ReaderHighlightRuleType.valueOf(item.optString("type"))
                    }.getOrNull() ?: defaults.firstOrNull { it.id == id }?.type ?: continue
                    add(
                        ReaderHighlightRule(
                            id = id,
                            name = item.optString("name").ifBlank { id },
                            type = type,
                            styleId = item.optString("styleId")
                                .ifBlank { ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID },
                            enabled = item.optBoolean("enabled", true),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        return defaults.map { default -> decoded.firstOrNull { it.id == default.id } ?: default } +
            decoded.filterNot { item -> defaults.any { it.id == item.id } }
    }

    private fun encodeHighlightStyles(styles: List<ReaderHighlightStyle>): String =
        JSONArray().apply {
            styles.forEach { style ->
                put(
                    JSONObject()
                        .put("id", style.id)
                        .put("name", style.name)
                        .put("color", normalizeHighlightColor(style.color))
                        .put("fontFamily", style.fontFamily)
                        .put("css", style.css)
                        .put("ninePatchPath", style.ninePatchPath),
                )
            }
        }.toString()

    private fun encodeHighlightRules(rules: List<ReaderHighlightRule>): String =
        JSONArray().apply {
            rules.forEach { rule ->
                put(
                    JSONObject()
                        .put("id", rule.id)
                        .put("name", rule.name)
                        .put("type", rule.type.name)
                        .put("styleId", rule.styleId)
                        .put("enabled", rule.enabled),
                )
            }
        }.toString()

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
            snapshot.readerOverwriteCheckEnabled,
            snapshot.readerEditOverwriteEnabled,
            snapshot.readerDictionaryEnabled,
            snapshot.readerCompactSelectionMenuEnabled,
            snapshot.readerDialogueHighlightEnabled,
            snapshot.fontEnabled,
            snapshot.fontSettingsEnabled,
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
                    "readerOverwriteCheck=${snapshot.readerOverwriteCheckEnabled}, " +
                    "readerEditOverwrite=${snapshot.readerEditOverwriteEnabled}, " +
                    "readerDictionary=${snapshot.readerDictionaryEnabled}, " +
                    "readerCompactMenu=${snapshot.readerCompactSelectionMenuEnabled}, " +
                    "readerDialogueHighlight=${snapshot.readerDialogueHighlightEnabled}, " +
                    "font=${snapshot.fontEnabled}, " +
                    "fontSettings=${snapshot.fontSettingsEnabled}, " +
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
        val LEGACY_PARENT_SWITCH_KEYS = listOf(
            ModuleSettings.KEY_ASSOCIATION_ENABLED,
            ModuleSettings.KEY_READER_ENABLED,
            ModuleSettings.KEY_FONT_ENABLED,
            ModuleSettings.KEY_ACCOUNT_ENABLED,
            ModuleSettings.KEY_EDIT_ENABLED,
            ModuleSettings.KEY_CLOUD_ENABLED,
            ModuleSettings.KEY_ROTATION_ENABLED,
        )
    }
}
