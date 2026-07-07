package com.reamicro.fix.settings

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.security.MessageDigest
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

    fun setReaderReadAloudEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_READ_ALOUD_ENABLED, enabled)
    }

    fun setReaderReadAloudIgnoreAudioFocus(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS, enabled)
    }

    fun setReaderReadAloudRestartOnPageTurn(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_READ_ALOUD_RESTART_ON_PAGE_TURN, enabled)
    }

    fun setReaderReadAloudSelectionEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_READ_ALOUD_SELECTION_ENABLED, enabled)
    }

    fun setReaderReadAloudLyriconEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_READ_ALOUD_LYRICON_ENABLED, enabled)
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
        notifyReaderHighlightChanged("dialogue-highlight-enabled")
    }

    fun setReaderSelectionHighlightEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_SELECTION_HIGHLIGHT_ENABLED, enabled)
    }

    fun setReaderHighlightPerformanceLogEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED, enabled)
    }

    fun setConciseLogEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_CONCISE_LOG_ENABLED, enabled)
    }

    fun setInlineSearchIconEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_INLINE_SEARCH_ICON_ENABLED, enabled)
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

    fun setProfileBackgroundEnabled(enabled: Boolean) {
        putBoolean(ModuleSettings.KEY_PROFILE_BACKGROUND_ENABLED, enabled)
    }

    fun setProfileBackgroundColor(color: String) {
        putString(ModuleSettings.KEY_PROFILE_BACKGROUND_COLOR, normalizeProfileBackgroundColor(color))
    }

    fun setProfileBackgroundUseImage(useImage: Boolean) {
        putBoolean(ModuleSettings.KEY_PROFILE_BACKGROUND_USE_IMAGE, useImage)
    }

    fun setProfileBackgroundImage(path: String) {
        putString(ModuleSettings.KEY_PROFILE_BACKGROUND_IMAGE, path)
    }

    fun setProfileBackgroundCropPosition(position: String) {
        putString(ModuleSettings.KEY_PROFILE_BACKGROUND_CROP_POSITION, normalizeProfileBackgroundCropPosition(position))
    }

    fun setProfileBackgroundDisplayMode(mode: String) {
        putString(ModuleSettings.KEY_PROFILE_BACKGROUND_DISPLAY_MODE, normalizeProfileBackgroundDisplayMode(mode))
    }

    fun setProfileBackgroundBlur(value: Int) {
        putInt(ModuleSettings.KEY_PROFILE_BACKGROUND_BLUR, normalizePercent(value))
    }

    fun setProfileBackgroundTransparency(value: Int) {
        putInt(ModuleSettings.KEY_PROFILE_BACKGROUND_TRANSPARENCY, normalizePercent(value))
    }

    fun setProfileBackgroundCardBlur(value: Int) {
        putInt(ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_BLUR, normalizePercent(value))
    }

    fun setProfileBackgroundCardTransparency(value: Int) {
        putInt(ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_TRANSPARENCY, normalizePercent(value))
    }

    fun setAssociationSearchSourceEnabled(groupId: String, enabled: Boolean) {
        putBoolean(ModuleSettings.searchSourceKey(groupId), enabled)
    }

    fun setOnlineSourceEnabled(sourceId: String, enabled: Boolean) {
        putBoolean(ModuleSettings.onlineSourceKey(sourceId), enabled)
    }

    fun isOnlineSourceEnabled(sourceId: String): Boolean =
        prefs()?.getBoolean(ModuleSettings.onlineSourceKey(sourceId), false) ?: false

    fun setTtsSourceEnabled(sourceId: String, enabled: Boolean) {
        putBoolean(ModuleSettings.ttsSourceKey(sourceId), enabled)
    }

    fun isTtsSourceEnabled(sourceId: String): Boolean =
        prefs()?.getBoolean(
            ModuleSettings.ttsSourceKey(sourceId),
            sourceId == ModuleSettings.SYSTEM_TTS_SOURCE_ID,
        ) ?: (sourceId == ModuleSettings.SYSTEM_TTS_SOURCE_ID)

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
        val color = normalizeHighlightColor(
            prefs.getString(
                ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_COLOR,
                ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
            ).orEmpty(),
        )
        val fontFamily = prefs.getString(
            ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_FONT,
            ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT,
        ).orEmpty()
        val styles = readHighlightStyles(prefs, color, fontFamily)
        val defaultLightStyleId = validHighlightStyleId(
            prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID, "").orEmpty(),
            styles,
            ModuleSettings.DEFAULT_READER_HIGHLIGHT_LIGHT_STYLE_ID,
        )
        val defaultDarkStyleId = validHighlightStyleId(
            prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID, "").orEmpty(),
            styles,
            ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID,
        )
        return ReaderHighlightSettingsSnapshot(
            styles = styles,
            rules = readHighlightRules(prefs, defaultLightStyleId, defaultDarkStyleId),
            defaultLightStyleId = defaultLightStyleId,
            defaultDarkStyleId = defaultDarkStyleId,
            bookGlobalRules = readBookGlobalRules(prefs),
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
        val sanitized = normalizeSavedHighlightStyle(style)
        val isExisting = current.styles.any { it.id == sanitized.id }
        val remaining = current.styles.filterNot { it.id == sanitized.id }
        // 修改已有样式：置顶（新修改的在最上面）；新增/导入样式：追加到末尾（新导入的在最下面）。
        val next = if (isExisting) listOf(sanitized) + remaining else remaining + sanitized
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES, encodeHighlightStyles(next))
        notifyReaderHighlightChanged("highlight-style")
    }

    fun removeReaderHighlightStyle(styleId: String) {
        val normalizedStyleId = normalizeHighlightStyleId(styleId)
        if (normalizedStyleId in builtinReaderHighlightStyleIds()) return
        val current = highlightSettings()
        val nextStyles = current.styles.filterNot { it.id == normalizedStyleId }
        val nextDefaultLightStyleId = current.defaultLightStyleId
            .takeIf { it != normalizedStyleId && nextStyles.any { style -> style.id == it } }
            ?: ModuleSettings.DEFAULT_READER_HIGHLIGHT_LIGHT_STYLE_ID
        val nextDefaultDarkStyleId = current.defaultDarkStyleId
            .takeIf { it != normalizedStyleId && nextStyles.any { style -> style.id == it } }
            ?: ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID
        val nextRules = current.rules.map { rule ->
            if (rule.styleId == normalizedStyleId || rule.darkStyleId == normalizedStyleId) {
                rule.copy(
                    styleId = if (rule.styleId == normalizedStyleId) nextDefaultLightStyleId else rule.styleId,
                    darkStyleId = if (rule.darkStyleId == normalizedStyleId) nextDefaultDarkStyleId else rule.darkStyleId,
                )
            } else {
                rule
            }
        }
        putHighlightSettings(nextStyles, nextRules, nextDefaultLightStyleId, nextDefaultDarkStyleId)
        notifyReaderHighlightChanged("highlight-style-remove")
    }

    fun setReaderHighlightRule(rule: ReaderHighlightRule) {
        val current = highlightSettings()
        val sanitizedRule = rule.copy(
            styleId = normalizeHighlightRuleStyleId(
                id = rule.styleId,
                defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
            ),
            darkStyleId = normalizeHighlightRuleStyleId(
                id = rule.darkStyleId,
                defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
            ),
        )
        val next = current.rules
            .filterNot { it.id == sanitizedRule.id }
            .plus(sanitizedRule)
        val editor = prefs()?.edit() ?: return
        editor.putString(ModuleSettings.KEY_READER_HIGHLIGHT_RULES, encodeHighlightRules(next))
        editor.commit()
        cachedSnapshot = null
        cachedAtMs = 0L
        cachedHighlightSettings = null
        cachedHighlightSettingsAtMs = 0L
        snapshot()
        notifyReaderHighlightChanged("highlight-rule")
    }

    fun setReaderHighlightDefaultLightStyle(styleId: String) {
        val normalizedStyleId = normalizeHighlightStyleId(styleId)
        if (highlightSettings().styles.none { it.id == normalizedStyleId }) return
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID, normalizedStyleId)
        notifyReaderHighlightChanged("highlight-default-light")
    }

    fun setReaderHighlightDefaultDarkStyle(styleId: String) {
        val normalizedStyleId = normalizeHighlightStyleId(styleId)
        if (highlightSettings().styles.none { it.id == normalizedStyleId }) return
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID, normalizedStyleId)
        notifyReaderHighlightChanged("highlight-default-dark")
    }

    fun setReaderHighlightBookGlobalRuleEnabled(bookKey: String, ruleId: String, enabled: Boolean) {
        if (bookKey.isBlank() || ruleId.isBlank()) return
        val current = highlightSettings()
        val globalRuleIds = current.globalRules().map { it.id }.toSet()
        if (ruleId !in globalRuleIds) return
        val currentSet = current.globalRulesEnabledForBook(bookKey) ?: current.enabledGlobalRuleIds()
        val nextSet = if (enabled) currentSet + ruleId else currentSet - ruleId
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES, encodeBookGlobalRules(current.bookGlobalRules + (bookKey to nextSet)))
        notifyReaderHighlightChanged("highlight-book-global-rule")
    }

    fun setReaderHighlightBookGlobalRulesEnabled(bookKey: String, enabled: Boolean) {
        if (bookKey.isBlank()) return
        val current = highlightSettings()
        val globalRuleIds = current.globalRules().map { it.id }.toSet()
        val nextSet = if (enabled) globalRuleIds else emptySet()
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES, encodeBookGlobalRules(current.bookGlobalRules + (bookKey to nextSet)))
        notifyReaderHighlightChanged("highlight-book-global-rules")
    }

    fun setReaderHighlightBookFollowsGlobalRules(bookKey: String, follows: Boolean) {
        if (bookKey.isBlank()) return
        val current = highlightSettings()
        val nextRules = if (follows) {
            current.bookGlobalRules - bookKey
        } else {
            current.bookGlobalRules + (bookKey to current.enabledGlobalRuleIds())
        }
        putString(ModuleSettings.KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES, encodeBookGlobalRules(nextRules))
        notifyReaderHighlightChanged("highlight-book-global-follow")
    }

    fun addReaderBookHighlightRule(bookKey: String, bookTitle: String, text: String): ReaderHighlightRule? {
        val pattern = text.trim()
        if (bookKey.isBlank() || pattern.isBlank()) return null
        val normalizedBookTitle = readableReaderBookTitle(bookTitle, *bookKey.split('|').toTypedArray())
        val current = highlightSettings()
        current.rules.firstOrNull {
            it.appliesToBook(bookKey, normalizedBookTitle) &&
                it.type == ReaderHighlightRuleType.FixedText &&
                it.pattern == pattern
        }?.let { existing ->
            val updated = existing.copy(
                enabled = true,
                bookKey = bookKey,
                bookTitle = normalizedBookTitle.ifBlank { existing.bookTitle },
            )
            if (updated != existing) setReaderHighlightRule(updated)
            return updated
        }
        val rule = ReaderHighlightRule(
            id = "book_${System.currentTimeMillis()}_${Integer.toHexString(pattern.hashCode())}",
            name = pattern.take(24).ifBlank { "本书高亮" },
            type = ReaderHighlightRuleType.FixedText,
            styleId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
            darkStyleId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
            enabled = true,
            pattern = pattern,
            bookKey = bookKey,
            bookTitle = normalizedBookTitle,
        )
        setReaderHighlightRule(rule)
        return rule
    }

    private fun readableReaderBookTitle(vararg candidates: String): String =
        candidates
            .asSequence()
            .map(::cleanReaderBookTitleCandidate)
            .firstOrNull { it.isNotBlank() && !isInternalReaderBookTitle(it) && !isGenericReaderBookTitle(it) }
            .orEmpty()

    private fun cleanReaderBookTitleCandidate(value: String): String =
        value.trim()
            .substringAfterLast('|')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".epub")
            .removeSuffix(".EPUB")
            .trim()

    private fun isInternalReaderBookTitle(value: String): Boolean =
        value.contains('|') ||
            value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
            value.matches(Regex("^[0-9a-fA-F]{16,}$"))

    private fun isGenericReaderBookTitle(value: String): Boolean =
        value == "\u672c\u4e66" || value == "\u56fe\u4e66"

    fun removeReaderHighlightRule(ruleId: String) {
        if (ruleId == ModuleSettings.DEFAULT_READER_DOUBLE_QUOTE_RULE_ID ||
            ruleId == ModuleSettings.DEFAULT_READER_SINGLE_QUOTE_RULE_ID
        ) {
            setReaderHighlightRuleEnabled(ruleId, false)
            return
        }
        val current = highlightSettings()
        putString(
            ModuleSettings.KEY_READER_HIGHLIGHT_RULES,
            encodeHighlightRules(current.rules.filterNot { it.id == ruleId }),
        )
        notifyReaderHighlightChanged("highlight-rule-remove")
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

    private fun putInt(key: String, value: Int) {
        prefs()?.edit()?.putInt(key, value)?.commit()
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

    private fun putHighlightSettings(
        styles: List<ReaderHighlightStyle>,
        rules: List<ReaderHighlightRule>,
        defaultLightStyleId: String? = null,
        defaultDarkStyleId: String? = null,
    ) {
        prefs()?.edit()
            ?.putString(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES, encodeHighlightStyles(styles))
            ?.putString(ModuleSettings.KEY_READER_HIGHLIGHT_RULES, encodeHighlightRules(rules))
            ?.apply {
                defaultLightStyleId?.let { putString(ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID, it) }
                defaultDarkStyleId?.let { putString(ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID, it) }
            }
            ?.commit()
        cachedSnapshot = null
        cachedAtMs = 0L
        cachedDialogueHighlightSettings = null
        cachedDialogueHighlightSettingsAtMs = 0L
        cachedHighlightSettings = null
        cachedHighlightSettingsAtMs = 0L
        snapshot()
    }

    private fun notifyReaderHighlightChanged(source: String) {
        ReaderHighlightBookContext.bumpVersion(source)
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
        val readerReadAloudEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_READ_ALOUD_ENABLED,
            ModuleSettings.DEFAULT_READER_READ_ALOUD_ENABLED,
        )
        val readerReadAloudIgnoreAudioFocus = prefs.getBoolean(
            ModuleSettings.KEY_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS,
            ModuleSettings.DEFAULT_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS,
        )
        val readerReadAloudRestartOnPageTurn = prefs.getBoolean(
            ModuleSettings.KEY_READER_READ_ALOUD_RESTART_ON_PAGE_TURN,
            ModuleSettings.DEFAULT_READER_READ_ALOUD_RESTART_ON_PAGE_TURN,
        )
        val readerReadAloudSelectionEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_READ_ALOUD_SELECTION_ENABLED,
            ModuleSettings.DEFAULT_READER_READ_ALOUD_SELECTION_ENABLED,
        )
        val readerReadAloudLyriconEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_READ_ALOUD_LYRICON_ENABLED,
            ModuleSettings.DEFAULT_READER_READ_ALOUD_LYRICON_ENABLED,
        )
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
        val readerSelectionHighlightEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_SELECTION_HIGHLIGHT_ENABLED,
            ModuleSettings.DEFAULT_READER_SELECTION_HIGHLIGHT_ENABLED,
        )
        val readerHighlightPerformanceLogEnabled = prefs.getBoolean(
            ModuleSettings.KEY_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED,
            ModuleSettings.DEFAULT_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED,
        )
        val conciseLogEnabled = prefs.getBoolean(
            ModuleSettings.KEY_CONCISE_LOG_ENABLED,
            ModuleSettings.DEFAULT_CONCISE_LOG_ENABLED,
        )
        val inlineSearchIconEnabled = prefs.getBoolean(
            ModuleSettings.KEY_INLINE_SEARCH_ICON_ENABLED,
            ModuleSettings.DEFAULT_INLINE_SEARCH_ICON_ENABLED,
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
                    readerReadAloudEnabled ||
                    readerReadAloudIgnoreAudioFocus ||
                    readerReadAloudRestartOnPageTurn ||
                    readerReadAloudSelectionEnabled ||
                    readerReadAloudLyriconEnabled ||
                    readerAutoPageEnabled ||
                    readerOverwriteCheckEnabled ||
                    readerEditOverwriteEnabled ||
                    editFileEnabled ||
                    readerDialogueHighlightEnabled ||
                    readerSelectionHighlightEnabled ||
                    ModuleSettings.DEFAULT_READER_ENABLED,
            ),
            readerLongPressEnabled = readerLongPressEnabled,
            readerReadAloudEnabled = readerReadAloudEnabled,
            readerReadAloudIgnoreAudioFocus = readerReadAloudIgnoreAudioFocus,
            readerReadAloudRestartOnPageTurn = readerReadAloudRestartOnPageTurn,
            readerReadAloudSelectionEnabled = readerReadAloudSelectionEnabled,
            readerReadAloudLyriconEnabled = readerReadAloudLyriconEnabled,
            readerAutoPageEnabled = readerAutoPageEnabled,
            readerOverwriteCheckEnabled = readerOverwriteCheckEnabled,
            readerEditOverwriteEnabled = readerEditOverwriteEnabled,
            readerDictionaryEnabled = readerDictionaryEnabled,
            readerCompactSelectionMenuEnabled = readerCompactSelectionMenuEnabled,
            readerDialogueHighlightEnabled = readerDialogueHighlightEnabled,
            readerSelectionHighlightEnabled = readerSelectionHighlightEnabled,
            readerHighlightPerformanceLogEnabled = readerHighlightPerformanceLogEnabled,
            conciseLogEnabled = conciseLogEnabled,
            inlineSearchIconEnabled = inlineSearchIconEnabled,
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
            profileBackgroundEnabled = prefs.getBoolean(
                ModuleSettings.KEY_PROFILE_BACKGROUND_ENABLED,
                ModuleSettings.DEFAULT_PROFILE_BACKGROUND_ENABLED,
            ),
            profileBackgroundColor = normalizeProfileBackgroundColor(
                prefs.getString(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_COLOR,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR,
                ) ?: ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR,
            ),
            profileBackgroundUseImage = prefs.getBoolean(
                ModuleSettings.KEY_PROFILE_BACKGROUND_USE_IMAGE,
                ModuleSettings.DEFAULT_PROFILE_BACKGROUND_USE_IMAGE,
            ),
            profileBackgroundImage = prefs.getString(
                ModuleSettings.KEY_PROFILE_BACKGROUND_IMAGE,
                ModuleSettings.DEFAULT_PROFILE_BACKGROUND_IMAGE,
            ) ?: ModuleSettings.DEFAULT_PROFILE_BACKGROUND_IMAGE,
            profileBackgroundCropPosition = normalizeProfileBackgroundCropPosition(
                prefs.getString(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_CROP_POSITION,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CROP_POSITION,
                ) ?: ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CROP_POSITION,
            ),
            profileBackgroundDisplayMode = normalizeProfileBackgroundDisplayMode(
                prefs.getString(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_DISPLAY_MODE,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_DISPLAY_MODE,
                ) ?: ModuleSettings.DEFAULT_PROFILE_BACKGROUND_DISPLAY_MODE,
            ),
            profileBackgroundBlur = normalizePercent(
                prefs.getInt(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_BLUR,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_BLUR,
                ),
            ),
            profileBackgroundTransparency = normalizePercent(
                prefs.getInt(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_TRANSPARENCY,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_TRANSPARENCY,
                ),
            ),
            profileBackgroundCardBlur = normalizePercent(
                prefs.getInt(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_BLUR,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CARD_BLUR,
                ),
            ),
            profileBackgroundCardTransparency = normalizePercent(
                prefs.getInt(
                    ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_TRANSPARENCY,
                    ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CARD_TRANSPARENCY,
                ),
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

    private fun normalizeSavedHighlightStyle(style: ReaderHighlightStyle): ReaderHighlightStyle {
        val normalizedId = normalizeHighlightStyleId(style.id)
        val builtInFallback = ReaderHighlightStyle.builtIns().firstOrNull { it.id == normalizedId }
        return style.copy(
            id = normalizedId,
            name = style.name.ifBlank { builtInFallback?.name ?: normalizedId },
            color = normalizeHighlightColor(style.color),
            fontFamily = style.fontFamily,
            css = sanitizeHighlightCss(style.css).ifBlank { builtInFallback?.css.orEmpty() },
            ninePatchPath = style.ninePatchPath.ifBlank { builtInFallback?.ninePatchPath.orEmpty() },
            ninePatchSlice = style.ninePatchSlice.ifBlank { builtInFallback?.ninePatchSlice.orEmpty() },
            darkColor = style.darkColor.takeIf { it.isNotBlank() }?.let(::normalizeHighlightColor).orEmpty(),
            darkCss = sanitizeHighlightCss(style.darkCss),
        )
    }

    private fun normalizeProfileBackgroundColor(value: String): String {
        val trimmed = value.trim()
        val hex = when {
            trimmed.matches(Regex("^#[0-9a-fA-F]{8}$")) -> trimmed
            trimmed.matches(Regex("^#[0-9a-fA-F]{6}$")) -> "#FF${trimmed.drop(1)}"
            trimmed.matches(Regex("^[0-9a-fA-F]{8}$")) -> "#$trimmed"
            trimmed.matches(Regex("^[0-9a-fA-F]{6}$")) -> "#FF$trimmed"
            else -> ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR
        }
        return hex.uppercase()
    }

    private fun normalizeProfileBackgroundCropPosition(value: String): String =
        when (value.trim().lowercase()) {
            ModuleSettings.PROFILE_BACKGROUND_CROP_TOP -> ModuleSettings.PROFILE_BACKGROUND_CROP_TOP
            ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER -> ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER
            ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM -> ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM
            else -> ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CROP_POSITION
        }

    private fun normalizeProfileBackgroundDisplayMode(value: String): String =
        when (value.trim().lowercase()) {
            ModuleSettings.PROFILE_BACKGROUND_DISPLAY_COVER -> ModuleSettings.PROFILE_BACKGROUND_DISPLAY_COVER
            ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_WIDTH -> ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_WIDTH
            ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT -> ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT
            else -> ModuleSettings.DEFAULT_PROFILE_BACKGROUND_DISPLAY_MODE
        }

    private fun normalizePercent(value: Int): Int =
        value.coerceIn(0, 100)

    private fun updateDefaultHighlightStyle(update: (ReaderHighlightStyle) -> ReaderHighlightStyle) {
        val current = highlightSettings()
        val defaultStyle = current.styleById(ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID)
        setReaderHighlightStyle(update(defaultStyle.copy(id = ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID)))
    }

    private fun readHighlightStyles(
        prefs: SharedPreferences,
        defaultColor: String,
        defaultFontFamily: String,
    ): List<ReaderHighlightStyle> {
        val raw = prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES, "").orEmpty()
        val decoded = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = normalizeHighlightStyleId(item.optString("id"))
                    if (id.isBlank()) continue
                    add(
                        ReaderHighlightStyle(
                            id = id,
                            name = item.optString("name").ifBlank { id },
                            color = normalizeHighlightColor(item.optString("color")),
                            fontFamily = item.optString("fontFamily"),
                            css = sanitizeHighlightCss(item.optString("css")),
                            ninePatchPath = item.optString("ninePatchPath"),
                            ninePatchSlice = item.optString("ninePatchSlice"),
                            darkUsesLight = item.optBoolean("darkUsesLight", true),
                            darkColor = item.optString("darkColor"),
                            darkFontFamily = item.optString("darkFontFamily"),
                            darkCss = sanitizeHighlightCss(item.optString("darkCss")),
                            darkNinePatchPath = item.optString("darkNinePatchPath"),
                            darkNinePatchSlice = item.optString("darkNinePatchSlice"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        val referencedStyleIds = readerHighlightReferencedStyleIds(prefs)
        val builtIns = ReaderHighlightStyle.builtIns().map { builtIn ->
            when (builtIn.id) {
                ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID -> builtIn.copy(color = defaultColor, fontFamily = defaultFontFamily)
                else -> builtIn
            }
        }
        val decodedById = decoded.associateBy { it.id }
        val mergedBuiltIns = builtIns.map { builtIn ->
            decodedById[builtIn.id]?.let { saved ->
                when (builtIn.id) {
                    ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID -> normalizeSavedHighlightStyle(
                        saved.copy(
                            color = saved.color.ifBlank { defaultColor },
                            fontFamily = saved.fontFamily.ifBlank { defaultFontFamily },
                        ),
                    )
                    else -> normalizeSavedHighlightStyle(saved).copy(
                        color = saved.color.ifBlank { builtIn.color },
                        fontFamily = saved.fontFamily.ifBlank { builtIn.fontFamily },
                        css = saved.css.ifBlank { builtIn.css },
                        ninePatchPath = saved.ninePatchPath.ifBlank { builtIn.ninePatchPath },
                        ninePatchSlice = saved.ninePatchSlice.ifBlank { builtIn.ninePatchSlice },
                    )
                }
            } ?: builtIn
        }
        val custom = decoded.filterNot { style ->
            mergedBuiltIns.any { it.id == style.id }
        }
        val finalStyles = dedupeReaderHighlightStyles(mergedBuiltIns + custom, referencedStyleIds.map(::normalizeHighlightStyleId).toSet())
        val encoded = encodeHighlightStyles(finalStyles)
        if (raw.isNotBlank() && encoded != raw) {
            prefs.edit()
                .putString(ModuleSettings.KEY_READER_HIGHLIGHT_STYLES, encoded)
                .apply()
        }
        return finalStyles
    }

    private fun dedupeReaderHighlightStyles(
        styles: List<ReaderHighlightStyle>,
        referencedStyleIds: Set<String>,
    ): List<ReaderHighlightStyle> {
        val result = ArrayList<ReaderHighlightStyle>(styles.size)
        val indexByKey = HashMap<String, Int>()
        styles.forEach { style ->
            val key = readerHighlightStyleImportKey(style)
            if (key == null) {
                result.add(style)
                return@forEach
            }
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = result.size
                result.add(style)
                return@forEach
            }
            val existing = result[existingIndex]
            if (style.id in referencedStyleIds && existing.id !in referencedStyleIds) {
                result[existingIndex] = style
            }
        }
        return result
    }

    private fun readerHighlightReferencedStyleIds(prefs: SharedPreferences): Set<String> {
        val raw = prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_RULES, "").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val styleId = array.optJSONObject(index)
                        ?.optString("styleId")
                        .orEmpty()
                    if (styleId.isNotBlank()) add(normalizeHighlightStyleId(styleId))
                    val darkStyleId = array.optJSONObject(index)
                        ?.optString("darkStyleId")
                        .orEmpty()
                    if (darkStyleId.isNotBlank()) add(normalizeHighlightStyleId(darkStyleId))
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun normalizeHighlightStyleId(id: String): String =
        when (id) {
            ModuleSettings.LEGACY_READER_HIGHLIGHT_LIGHT_STYLE_ID -> ModuleSettings.BUILTIN_READER_HIGHLIGHT_RAINBOW_GLASS_STYLE_ID
            ModuleSettings.LEGACY_READER_HIGHLIGHT_DARK_STYLE_ID -> ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID
            else -> id
        }

    private fun normalizeHighlightRuleStyleId(
        id: String,
        defaultReferenceId: String,
    ): String {
        val trimmed = id.trim()
        if (trimmed.isBlank() || trimmed == defaultReferenceId) return defaultReferenceId
        return normalizeHighlightStyleId(trimmed)
    }

    private fun builtinReaderHighlightStyleIds(): Set<String> =
        setOf(
            ModuleSettings.BUILTIN_READER_HIGHLIGHT_RAINBOW_GLASS_STYLE_ID,
            ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID,
        )

    private fun validHighlightStyleId(candidate: String, styles: List<ReaderHighlightStyle>, fallback: String): String {
        val normalizedCandidate = normalizeHighlightStyleId(candidate)
        val normalizedFallback = normalizeHighlightStyleId(fallback)
        return normalizedCandidate.takeIf { id -> styles.any { it.id == id } }
            ?: normalizedFallback.takeIf { id -> styles.any { it.id == id } }
            ?: styles.firstOrNull()?.id.orEmpty()
    }

    private fun readerHighlightStyleImportKey(style: ReaderHighlightStyle): String? {
        val css = sanitizeHighlightCss(style.css)
        val path = style.ninePatchPath.trim()
        if (css.isBlank() || path.isBlank() || path.startsWith("asset://")) return null
        val file = File(path)
        if (!file.isFile) return null
        val hash = runCatching { md5Hex(file.readBytes()) }.getOrNull() ?: return null
        return "$css|$hash"
    }

    private fun sanitizeHighlightCss(css: String): String =
        css.split(';')
            .mapNotNull { part ->
                val index = part.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val key = part.substring(0, index).trim().lowercase()
                val value = part.substring(index + 1).trim()
                if (key.isBlank() || value.isBlank() || !isSupportedHighlightCssProperty(key, value)) {
                    null
                } else {
                    "$key: $value"
                }
            }
            .joinToString("; ")

    private fun isSupportedHighlightCssProperty(key: String, value: String): Boolean {
        if (value.isBlank()) return false
        if (value.contains("url(", ignoreCase = true)) return false
        return when (key) {
            "color", "background", "background-color", "border-color" ->
                isHighlightCssColorValue(value)
            "background-size" ->
                isSupportedHighlightBackgroundSize(value)
            "border" ->
                highlightCssSizeRegex.containsMatchIn(value) || highlightCssColorRegex.containsMatchIn(value)
            "font-size" ->
                isHighlightFontSizeValue(value)
            "border-width", "border-radius",
            "padding-left", "padding-top", "padding-right", "padding-bottom",
            "margin-left", "margin-top", "margin-right", "margin-bottom" ->
                isHighlightCssSizeValue(value)
            "padding", "margin" ->
                isHighlightCssBoxValue(value)
            "reeden-background-nine-slice", "--reeden-background-nine-slice" ->
                Regex("""-?\d+(?:\.\d+)?""").findAll(value).count() >= 4
            else -> false
        }
    }

    private val highlightCssSizeRegex = Regex("""\b\d+(?:\.\d+)?(?:px|dp|em|rem)?\b""", RegexOption.IGNORE_CASE)
    private val highlightCssColorRegex = Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""")

    private fun isHighlightCssColorValue(value: String): Boolean =
        value.trim().matches(highlightCssColorRegex)

    private fun isHighlightCssSizeValue(value: String): Boolean =
        value.trim().matches(Regex("""\d+(?:\.\d+)?(?:px|dp|em|rem)?""", RegexOption.IGNORE_CASE))

    private fun isHighlightFontSizeValue(value: String): Boolean =
        value.trim().matches(Regex("""\d+(?:\.\d+)?\s*(?:px|sp|em|rem|pt)?""", RegexOption.IGNORE_CASE))

    private fun isHighlightCssBoxValue(value: String): Boolean {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.size in 1..4 && parts.all(::isHighlightCssSizeValue)
    }

    private fun isSupportedHighlightBackgroundSize(value: String): Boolean {
        val normalized = value.trim().lowercase().replace(Regex("\\s+"), " ")
        return normalized == "100% 100%" || normalized == "stretch"
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }

    private fun readHighlightRules(
        prefs: SharedPreferences,
        defaultLightStyleId: String,
        defaultDarkStyleId: String,
    ): List<ReaderHighlightRule> {
        val defaults = ReaderHighlightRule.defaults()
        val raw = prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_RULES, "").orEmpty()
        val decoded = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank() || id.startsWith("rule_reeden_")) continue
                    val type = runCatching {
                        ReaderHighlightRuleType.valueOf(item.optString("type"))
                    }.getOrNull() ?: defaults.firstOrNull { it.id == id }?.type ?: continue
                    add(
                        ReaderHighlightRule(
                            id = id,
                            name = item.optString("name").ifBlank { id },
                            type = type,
                            styleId = normalizeHighlightRuleStyleId(
                                id = item.optString("styleId"),
                                defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
                            ),
                            darkStyleId = normalizeHighlightRuleStyleId(
                                id = item.optString("darkStyleId"),
                                defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
                            ),
                            enabled = item.optBoolean("enabled", true),
                            pattern = item.optString("pattern"),
                            bookKey = item.optString("bookKey"),
                            bookTitle = item.optString("bookTitle"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        return defaults.map { default -> decoded.firstOrNull { it.id == default.id } ?: default } +
            decoded.filterNot { item -> defaults.any { it.id == item.id } }
    }

    private fun readBookGlobalRules(prefs: SharedPreferences): Map<String, Set<String>> {
        val raw = prefs.getString(ModuleSettings.KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES, "").orEmpty()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { bookKey ->
                    val array = root.optJSONArray(bookKey) ?: return@forEach
                    put(bookKey, buildSet {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    })
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeBookGlobalRules(values: Map<String, Set<String>>): String =
        JSONObject().apply {
            values.forEach { (bookKey, ruleIds) ->
                if (bookKey.isBlank()) return@forEach
                put(bookKey, JSONArray().apply { ruleIds.forEach(::put) })
            }
        }.toString()

    private fun encodeHighlightStyles(styles: List<ReaderHighlightStyle>): String =
        JSONArray().apply {
            styles.forEach { style ->
                put(
                    JSONObject()
                        .put("id", style.id)
                        .put("name", style.name)
                        .put("color", normalizeHighlightColor(style.color))
                        .put("fontFamily", style.fontFamily)
                        .put("css", sanitizeHighlightCss(style.css))
                        .put("ninePatchPath", style.ninePatchPath)
                        .put("ninePatchSlice", style.ninePatchSlice)
                        .put("darkUsesLight", style.darkUsesLight)
                        .put("darkColor", normalizeHighlightColor(style.darkColor))
                        .put("darkFontFamily", style.darkFontFamily)
                        .put("darkCss", sanitizeHighlightCss(style.darkCss))
                        .put("darkNinePatchPath", style.darkNinePatchPath)
                        .put("darkNinePatchSlice", style.darkNinePatchSlice),
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
                        .put("darkStyleId", rule.darkStyleId)
                        .put("enabled", rule.enabled)
                        .put("pattern", rule.pattern)
                        .put("bookKey", rule.bookKey)
                        .put("bookTitle", rule.bookTitle),
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
            snapshot.readerReadAloudEnabled,
            snapshot.readerReadAloudIgnoreAudioFocus,
            snapshot.readerReadAloudRestartOnPageTurn,
            snapshot.readerReadAloudSelectionEnabled,
            snapshot.readerReadAloudLyriconEnabled,
            snapshot.readerAutoPageEnabled,
            snapshot.readerOverwriteCheckEnabled,
            snapshot.readerEditOverwriteEnabled,
            snapshot.readerDictionaryEnabled,
            snapshot.readerCompactSelectionMenuEnabled,
            snapshot.readerDialogueHighlightEnabled,
            snapshot.readerSelectionHighlightEnabled,
            snapshot.readerHighlightPerformanceLogEnabled,
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
            snapshot.profileBackgroundEnabled,
            snapshot.profileBackgroundColor,
            snapshot.profileBackgroundUseImage,
            snapshot.profileBackgroundImage,
            snapshot.profileBackgroundCropPosition,
            snapshot.profileBackgroundDisplayMode,
            snapshot.profileBackgroundBlur,
            snapshot.profileBackgroundTransparency,
            snapshot.profileBackgroundCardBlur,
            snapshot.profileBackgroundCardTransparency,
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
                    "readerReadAloud=${snapshot.readerReadAloudEnabled}, " +
                    "readerReadAloudIgnoreFocus=${snapshot.readerReadAloudIgnoreAudioFocus}, " +
                    "readerReadAloudRestartOnPageTurn=${snapshot.readerReadAloudRestartOnPageTurn}, " +
                    "readerReadAloudSelection=${snapshot.readerReadAloudSelectionEnabled}, " +
                    "readerReadAloudLyricon=${snapshot.readerReadAloudLyriconEnabled}, " +
                    "readerAutoPage=${snapshot.readerAutoPageEnabled}, " +
                    "readerOverwriteCheck=${snapshot.readerOverwriteCheckEnabled}, " +
                    "readerEditOverwrite=${snapshot.readerEditOverwriteEnabled}, " +
                    "readerDictionary=${snapshot.readerDictionaryEnabled}, " +
                    "readerCompactMenu=${snapshot.readerCompactSelectionMenuEnabled}, " +
                    "readerDialogueHighlight=${snapshot.readerDialogueHighlightEnabled}, " +
                    "readerSelectionHighlight=${snapshot.readerSelectionHighlightEnabled}, " +
                    "readerHighlightPerfLog=${snapshot.readerHighlightPerformanceLogEnabled}, " +
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
                    "profileBackground=${snapshot.profileBackgroundEnabled}, " +
                    "profileBackgroundColor=${snapshot.profileBackgroundColor}, " +
                    "profileBackgroundUseImage=${snapshot.profileBackgroundUseImage}, " +
                    "profileBackgroundImage=${snapshot.profileBackgroundImage}, " +
                    "profileBackgroundCropPosition=${snapshot.profileBackgroundCropPosition}, " +
                    "profileBackgroundDisplayMode=${snapshot.profileBackgroundDisplayMode}, " +
                    "profileBackgroundBlur=${snapshot.profileBackgroundBlur}, " +
                    "profileBackgroundTransparency=${snapshot.profileBackgroundTransparency}, " +
                    "profileBackgroundCardBlur=${snapshot.profileBackgroundCardBlur}, " +
                    "profileBackgroundCardTransparency=${snapshot.profileBackgroundCardTransparency}, " +
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
