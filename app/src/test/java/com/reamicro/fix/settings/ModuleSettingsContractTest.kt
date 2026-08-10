package com.reamicro.fix.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置项存储契约的锁定。
 *
 * KEY_* 的字符串值就是 SharedPreferences 里的键名，改一个字符等于把用户已有的设置
 * 全部丢弃，而编译器完全看不出来。DEFAULT_* 决定用户从未设置过时的行为。
 *
 * 这里把两者逐一钉死。确实要改键名或默认值时同步改期望值——这一步摩擦是故意的，
 * 用来把「有意的行为变更」与「重构时手滑」区分开。
 *
 * 只覆盖字面量常量；由其它常量推导出来的默认值不在此列。
 *
 * 本文件由 tools/gen-settings-contract-test.mjs 生成。
 */
class ModuleSettingsContractTest {

    @Test
    fun `偏好文件名锁定`() {
        assertEquals("reamicro_fix_module_settings", ModuleSettings.PREFS_NAME)
    }

    @Test
    fun `设置键名锁定 1`() {
        assertEquals("module_enabled", ModuleSettings.KEY_MODULE_ENABLED)
        assertEquals("association_enabled", ModuleSettings.KEY_ASSOCIATION_ENABLED)
        assertEquals("association_manual_edit_enabled", ModuleSettings.KEY_ASSOCIATION_MANUAL_EDIT_ENABLED)
        assertEquals("association_unlink_enabled", ModuleSettings.KEY_ASSOCIATION_UNLINK_ENABLED)
        assertEquals("association_cover_fix_enabled", ModuleSettings.KEY_ASSOCIATION_COVER_FIX_ENABLED)
        assertEquals("reader_enabled", ModuleSettings.KEY_READER_ENABLED)
        assertEquals("reader_background_enabled", ModuleSettings.KEY_READER_BACKGROUND_ENABLED)
        assertEquals("reader_long_press_enabled", ModuleSettings.KEY_READER_LONG_PRESS_ENABLED)
        assertEquals("reader_read_aloud_enabled", ModuleSettings.KEY_READER_READ_ALOUD_ENABLED)
        assertEquals("reader_read_aloud_ignore_audio_focus", ModuleSettings.KEY_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS)
        assertEquals("reader_read_aloud_restart_on_page_turn", ModuleSettings.KEY_READER_READ_ALOUD_RESTART_ON_PAGE_TURN)
        assertEquals("reader_read_aloud_selection_enabled", ModuleSettings.KEY_READER_READ_ALOUD_SELECTION_ENABLED)
        assertEquals("reader_read_aloud_lyricon_enabled", ModuleSettings.KEY_READER_READ_ALOUD_LYRICON_ENABLED)
        assertEquals("reader_auto_page_enabled", ModuleSettings.KEY_READER_AUTO_PAGE_ENABLED)
        assertEquals("reader_overwrite_check_enabled", ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED)
        assertEquals("reader_edit_overwrite_enabled", ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED)
        assertEquals("reader_dictionary_enabled", ModuleSettings.KEY_READER_DICTIONARY_ENABLED)
        assertEquals("reader_compact_selection_menu_enabled", ModuleSettings.KEY_READER_COMPACT_SELECTION_MENU_ENABLED)
        assertEquals("reader_dialogue_highlight_enabled", ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED)
        assertEquals("reader_selection_highlight_enabled", ModuleSettings.KEY_READER_SELECTION_HIGHLIGHT_ENABLED)
        assertEquals("reader_highlight_performance_log_enabled", ModuleSettings.KEY_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED)
        assertEquals("concise_log_enabled", ModuleSettings.KEY_CONCISE_LOG_ENABLED)
        assertEquals("inline_search_icon_enabled", ModuleSettings.KEY_INLINE_SEARCH_ICON_ENABLED)
        assertEquals("font_enabled", ModuleSettings.KEY_FONT_ENABLED)
        assertEquals("font_settings_enabled", ModuleSettings.KEY_FONT_SETTINGS_ENABLED)
    }

    @Test
    fun `设置键名锁定 2`() {
        assertEquals("account_enabled", ModuleSettings.KEY_ACCOUNT_ENABLED)
        assertEquals("account_export_enabled", ModuleSettings.KEY_ACCOUNT_EXPORT_ENABLED)
        assertEquals("account_cache_cleanup_enabled", ModuleSettings.KEY_ACCOUNT_CACHE_CLEANUP_ENABLED)
        assertEquals("edit_enabled", ModuleSettings.KEY_EDIT_ENABLED)
        assertEquals("edit_file_enabled", ModuleSettings.KEY_EDIT_FILE_ENABLED)
        assertEquals("cloud_enabled", ModuleSettings.KEY_CLOUD_ENABLED)
        assertEquals("cloud_webdav_enabled", ModuleSettings.KEY_CLOUD_WEBDAV_ENABLED)
        assertEquals("cloud_local_library_enabled", ModuleSettings.KEY_CLOUD_LOCAL_LIBRARY_ENABLED)
        assertEquals("cloud_extended_display_enabled", ModuleSettings.KEY_CLOUD_EXTENDED_DISPLAY_ENABLED)
        assertEquals("cloud_download_cancel_enabled", ModuleSettings.KEY_CLOUD_DOWNLOAD_CANCEL_ENABLED)
        assertEquals("rotation_enabled", ModuleSettings.KEY_ROTATION_ENABLED)
        assertEquals("rotation_auto_enabled", ModuleSettings.KEY_ROTATION_AUTO_ENABLED)
        assertEquals("rotation_portrait_lock_enabled", ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED)
        assertEquals("rotation_landscape_lock_enabled", ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED)
        assertEquals("rotation_reverse_enabled", ModuleSettings.KEY_ROTATION_REVERSE_ENABLED)
        assertEquals("profile_background_enabled", ModuleSettings.KEY_PROFILE_BACKGROUND_ENABLED)
        assertEquals("profile_background_color", ModuleSettings.KEY_PROFILE_BACKGROUND_COLOR)
        assertEquals("profile_background_use_image", ModuleSettings.KEY_PROFILE_BACKGROUND_USE_IMAGE)
        assertEquals("profile_background_image", ModuleSettings.KEY_PROFILE_BACKGROUND_IMAGE)
        assertEquals("profile_background_image_url", ModuleSettings.KEY_PROFILE_BACKGROUND_IMAGE_URL)
        assertEquals("profile_background_crop_position", ModuleSettings.KEY_PROFILE_BACKGROUND_CROP_POSITION)
        assertEquals("profile_background_display_mode", ModuleSettings.KEY_PROFILE_BACKGROUND_DISPLAY_MODE)
        assertEquals("profile_background_blur", ModuleSettings.KEY_PROFILE_BACKGROUND_BLUR)
        assertEquals("profile_background_transparency", ModuleSettings.KEY_PROFILE_BACKGROUND_TRANSPARENCY)
        assertEquals("profile_background_card_blur", ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_BLUR)
    }

    @Test
    fun `设置键名锁定 3`() {
        assertEquals("profile_background_card_transparency", ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_TRANSPARENCY)
        assertEquals("reader_bg_light_images", ModuleSettings.KEY_READER_BG_LIGHT_IMAGES)
        assertEquals("reader_bg_dark_images", ModuleSettings.KEY_READER_BG_DARK_IMAGES)
        assertEquals("reader_bg_light_current", ModuleSettings.KEY_READER_BG_LIGHT_CURRENT)
        assertEquals("reader_bg_dark_current", ModuleSettings.KEY_READER_BG_DARK_CURRENT)
        assertEquals("font_global_family", ModuleSettings.KEY_FONT_GLOBAL_FAMILY)
        assertEquals("font_mapping_song", ModuleSettings.KEY_FONT_MAPPING_SONG)
        assertEquals("font_mapping_kai", ModuleSettings.KEY_FONT_MAPPING_KAI)
        assertEquals("reader_dialogue_highlight_color", ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_COLOR)
        assertEquals("reader_dialogue_highlight_font", ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_FONT)
        assertEquals("reader_highlight_styles", ModuleSettings.KEY_READER_HIGHLIGHT_STYLES)
        assertEquals("reader_highlight_rules", ModuleSettings.KEY_READER_HIGHLIGHT_RULES)
        assertEquals("reader_highlight_default_light_style_id", ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID)
        assertEquals("reader_highlight_default_dark_style_id", ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID)
        assertEquals("reader_highlight_book_global_rules", ModuleSettings.KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES)
        assertEquals("online_epub_styles", ModuleSettings.KEY_ONLINE_EPUB_STYLES)
        assertEquals("online_epub_styles_removed", ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED)
        assertEquals("online_epub_style_selection", ModuleSettings.KEY_ONLINE_EPUB_STYLE_SELECTION)
        assertEquals("online_epub_header_scope", ModuleSettings.KEY_ONLINE_EPUB_HEADER_SCOPE)
        assertEquals("wanfengli_hidden_source_migrated", ModuleSettings.KEY_WANFENGLI_HIDDEN_MIGRATED)
    }

    @Test
    fun `键名互不重复`() {
        val all = listOf(
            ModuleSettings.KEY_MODULE_ENABLED,
            ModuleSettings.KEY_ASSOCIATION_ENABLED,
            ModuleSettings.KEY_ASSOCIATION_MANUAL_EDIT_ENABLED,
            ModuleSettings.KEY_ASSOCIATION_UNLINK_ENABLED,
            ModuleSettings.KEY_ASSOCIATION_COVER_FIX_ENABLED,
            ModuleSettings.KEY_READER_ENABLED,
            ModuleSettings.KEY_READER_BACKGROUND_ENABLED,
            ModuleSettings.KEY_READER_LONG_PRESS_ENABLED,
            ModuleSettings.KEY_READER_READ_ALOUD_ENABLED,
            ModuleSettings.KEY_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS,
            ModuleSettings.KEY_READER_READ_ALOUD_RESTART_ON_PAGE_TURN,
            ModuleSettings.KEY_READER_READ_ALOUD_SELECTION_ENABLED,
            ModuleSettings.KEY_READER_READ_ALOUD_LYRICON_ENABLED,
            ModuleSettings.KEY_READER_AUTO_PAGE_ENABLED,
            ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED,
            ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED,
            ModuleSettings.KEY_READER_DICTIONARY_ENABLED,
            ModuleSettings.KEY_READER_COMPACT_SELECTION_MENU_ENABLED,
            ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED,
            ModuleSettings.KEY_READER_SELECTION_HIGHLIGHT_ENABLED,
            ModuleSettings.KEY_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED,
            ModuleSettings.KEY_CONCISE_LOG_ENABLED,
            ModuleSettings.KEY_INLINE_SEARCH_ICON_ENABLED,
            ModuleSettings.KEY_FONT_ENABLED,
            ModuleSettings.KEY_FONT_SETTINGS_ENABLED,
            ModuleSettings.KEY_ACCOUNT_ENABLED,
            ModuleSettings.KEY_ACCOUNT_EXPORT_ENABLED,
            ModuleSettings.KEY_ACCOUNT_CACHE_CLEANUP_ENABLED,
            ModuleSettings.KEY_EDIT_ENABLED,
            ModuleSettings.KEY_EDIT_FILE_ENABLED,
            ModuleSettings.KEY_CLOUD_ENABLED,
            ModuleSettings.KEY_CLOUD_WEBDAV_ENABLED,
            ModuleSettings.KEY_CLOUD_LOCAL_LIBRARY_ENABLED,
            ModuleSettings.KEY_CLOUD_EXTENDED_DISPLAY_ENABLED,
            ModuleSettings.KEY_CLOUD_DOWNLOAD_CANCEL_ENABLED,
            ModuleSettings.KEY_ROTATION_ENABLED,
            ModuleSettings.KEY_ROTATION_AUTO_ENABLED,
            ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
            ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
            ModuleSettings.KEY_ROTATION_REVERSE_ENABLED,
            ModuleSettings.KEY_PROFILE_BACKGROUND_ENABLED,
            ModuleSettings.KEY_PROFILE_BACKGROUND_COLOR,
            ModuleSettings.KEY_PROFILE_BACKGROUND_USE_IMAGE,
            ModuleSettings.KEY_PROFILE_BACKGROUND_IMAGE,
            ModuleSettings.KEY_PROFILE_BACKGROUND_IMAGE_URL,
            ModuleSettings.KEY_PROFILE_BACKGROUND_CROP_POSITION,
            ModuleSettings.KEY_PROFILE_BACKGROUND_DISPLAY_MODE,
            ModuleSettings.KEY_PROFILE_BACKGROUND_BLUR,
            ModuleSettings.KEY_PROFILE_BACKGROUND_TRANSPARENCY,
            ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_BLUR,
            ModuleSettings.KEY_PROFILE_BACKGROUND_CARD_TRANSPARENCY,
            ModuleSettings.KEY_READER_BG_LIGHT_IMAGES,
            ModuleSettings.KEY_READER_BG_DARK_IMAGES,
            ModuleSettings.KEY_READER_BG_LIGHT_CURRENT,
            ModuleSettings.KEY_READER_BG_DARK_CURRENT,
            ModuleSettings.KEY_FONT_GLOBAL_FAMILY,
            ModuleSettings.KEY_FONT_MAPPING_SONG,
            ModuleSettings.KEY_FONT_MAPPING_KAI,
            ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_COLOR,
            ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_FONT,
            ModuleSettings.KEY_READER_HIGHLIGHT_STYLES,
            ModuleSettings.KEY_READER_HIGHLIGHT_RULES,
            ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_LIGHT_STYLE_ID,
            ModuleSettings.KEY_READER_HIGHLIGHT_DEFAULT_DARK_STYLE_ID,
            ModuleSettings.KEY_READER_HIGHLIGHT_BOOK_GLOBAL_RULES,
            ModuleSettings.KEY_ONLINE_EPUB_STYLES,
            ModuleSettings.KEY_ONLINE_EPUB_STYLES_REMOVED,
            ModuleSettings.KEY_ONLINE_EPUB_STYLE_SELECTION,
            ModuleSettings.KEY_ONLINE_EPUB_HEADER_SCOPE,
            ModuleSettings.KEY_WANFENGLI_HIDDEN_MIGRATED,
        )
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `默认值锁定 1`() {
        assertEquals(true, ModuleSettings.DEFAULT_MODULE_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_ASSOCIATION_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ASSOCIATION_MANUAL_EDIT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ASSOCIATION_UNLINK_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ASSOCIATION_COVER_FIX_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_READER_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_READER_BACKGROUND_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_LONG_PRESS_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_READER_READ_ALOUD_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_READ_ALOUD_IGNORE_AUDIO_FOCUS)
        assertEquals(false, ModuleSettings.DEFAULT_READER_READ_ALOUD_RESTART_ON_PAGE_TURN)
        assertEquals(false, ModuleSettings.DEFAULT_READER_READ_ALOUD_SELECTION_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_READ_ALOUD_LYRICON_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_AUTO_PAGE_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_OVERWRITE_CHECK_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_EDIT_OVERWRITE_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_READER_DICTIONARY_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_COMPACT_SELECTION_MENU_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_SELECTION_HIGHLIGHT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_CONCISE_LOG_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_INLINE_SEARCH_ICON_ENABLED)
        assertEquals("#FF9800", ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR)
        assertEquals("", ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_FONT)
    }

    @Test
    fun `默认值锁定 2`() {
        assertEquals(true, ModuleSettings.DEFAULT_FONT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_FONT_SETTINGS_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_ACCOUNT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ACCOUNT_EXPORT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ACCOUNT_CACHE_CLEANUP_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_EDIT_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_EDIT_FILE_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_CLOUD_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_CLOUD_WEBDAV_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_CLOUD_LOCAL_LIBRARY_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_CLOUD_EXTENDED_DISPLAY_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_CLOUD_DOWNLOAD_CANCEL_ENABLED)
        assertEquals(true, ModuleSettings.DEFAULT_ROTATION_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ROTATION_AUTO_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ROTATION_PORTRAIT_LOCK_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ROTATION_LANDSCAPE_LOCK_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_ROTATION_REVERSE_ENABLED)
        assertEquals(false, ModuleSettings.DEFAULT_PROFILE_BACKGROUND_ENABLED)
        assertEquals("#80000000", ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR)
        assertEquals(false, ModuleSettings.DEFAULT_PROFILE_BACKGROUND_USE_IMAGE)
        assertEquals("", ModuleSettings.DEFAULT_PROFILE_BACKGROUND_IMAGE)
        assertEquals("", ModuleSettings.DEFAULT_PROFILE_BACKGROUND_IMAGE_URL)
        assertEquals(50, ModuleSettings.DEFAULT_PROFILE_BACKGROUND_BLUR)
        assertEquals(0, ModuleSettings.DEFAULT_PROFILE_BACKGROUND_TRANSPARENCY)
        assertEquals(50, ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CARD_BLUR)
    }

    @Test
    fun `默认值锁定 3`() {
        assertEquals(4, ModuleSettings.DEFAULT_PROFILE_BACKGROUND_CARD_TRANSPARENCY)
        assertEquals("", ModuleSettings.DEFAULT_READER_BG_CURRENT)
        assertEquals("default", ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID)
        assertEquals("builtin_rainbow_glass", ModuleSettings.DEFAULT_READER_HIGHLIGHT_LIGHT_STYLE_ID)
        assertEquals("double_quote_dialogue", ModuleSettings.DEFAULT_READER_DOUBLE_QUOTE_RULE_ID)
        assertEquals("single_quote_phrase", ModuleSettings.DEFAULT_READER_SINGLE_QUOTE_RULE_ID)
    }

}
