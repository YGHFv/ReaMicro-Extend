package com.reamicro.fix.hook.settings

import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.settings.ModuleSettings

internal const val FEATURE_ID = "ReaMicroSettingsHook"

internal const val LOG_PREFIX = "ReaMicro LSP"



internal const val WINDOW_INSETS_CLASS = HostClasses.Compose.WINDOW_INSETS
internal const val IMAGE_VECTOR_CLASS = HostClasses.Compose.IMAGE_VECTOR


internal const val DIVIDER_KT_CLASS = HostClasses.Host.DIVIDER_KT



internal const val READER_RULE_SHEET_HEIGHT_DP = 355
internal const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
internal const val FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
internal const val FUNCTION2_CLASS = HostClasses.Kotlin.FUNCTION2
internal const val FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3


internal const val ACCOUNT_SWITCH_ITEM_KEY = 0x524D4659
internal const val MODULE_SETTINGS_ITEM_KEY = 0x524D4658
internal const val MODULE_TOP_BAR_KEY = 0x524D4659
internal const val MODULE_CONTENT_KEY = 0x524D465A
internal const val ASSOCIATION_SWITCHES_ITEM_KEY = 0x524D465C
internal const val READER_SWITCHES_ITEM_KEY = 0x524D465D
internal const val ROTATION_SWITCHES_ITEM_KEY = 0x524D465E
internal const val CLOUD_SWITCHES_ITEM_KEY = 0x524D465F
internal const val FONT_SETTINGS_CONTENT_ITEM_KEY = 0x524D4662
internal const val FONT_PICKER_CONTENT_ITEM_KEY = 0x524D4663
internal const val FONT_LIBRARY_CONTENT_ITEM_KEY = 0x524D4664
internal const val ACCOUNT_EXPORT_ACTION_ITEM_KEY = 0x524D4667
internal const val ACCOUNT_SWITCH_ACTION_ITEM_KEY = 0x524D4669
internal const val ONLINE_COMPLETION_CONTENT_ITEM_KEY = 0x524D466A
internal const val AI_CONFIG_CONTENT_ITEM_KEY = 0x524D466C
internal const val DICTIONARY_SETTINGS_CONTENT_ITEM_KEY = 0x524D466D
internal const val DICTIONARY_THINKING_SWITCH_ITEM_KEY = 0x524D466E
internal const val DICTIONARY_API_PICKER_CONTENT_ITEM_KEY = 0x524D466F
internal const val DICTIONARY_PRESET_PICKER_CONTENT_ITEM_KEY = 0x524D4670
internal const val IMAGE_SETTINGS_CONTENT_ITEM_KEY = 0x524D4671
internal const val IMAGE_API_PICKER_CONTENT_ITEM_KEY = 0x524D4672
internal const val IMAGE_PRESET_PICKER_CONTENT_ITEM_KEY = 0x524D4673
internal const val READER_HIGHLIGHT_MANAGEMENT_ITEM_KEY = 0x524D4674
internal const val READER_HIGHLIGHT_SETTINGS_ITEM_KEY = 0x524D4675
internal const val READER_HIGHLIGHT_COLOR_PICKER_ITEM_KEY = 0x524D4676
internal const val READER_HIGHLIGHT_CONFIG_ITEM_KEY = 0x524D4677
internal const val READER_HIGHLIGHT_TEXT_ITEM_KEY = 0x524D4678
internal const val READER_HIGHLIGHT_BOOK_RULES_ITEM_KEY = 0x524D4679
internal const val READER_HIGHLIGHT_BOOK_GROUPS_ITEM_KEY = 0x524D467A
internal const val ABOUT_COMPLETION_ENTRY_ITEM_KEY = 0x524D467B
internal const val ABOUT_COMPLETION_CONTENT_ITEM_KEY = 0x524D467C
internal const val READER_SELECTION_MENU_SETTINGS_ITEM_KEY = 0x524D4681
internal const val PROFILE_BACKGROUND_ENABLE_ITEM_KEY = 0x524D4682
internal const val PROFILE_BACKGROUND_CONTENT_ITEM_KEY = 0x524D4683
internal const val ONLINE_DOWNLOAD_STYLE_ENTRY_ITEM_KEY = 0x524D4684
internal const val ONLINE_DOWNLOAD_STYLE_CONTENT_ITEM_KEY = 0x524D4685
internal const val ONLINE_EPUB_STYLE_LIST_ITEM_KEY = 0x524D4687
internal const val ABOUT_COMPLETION_PROJECT_ITEM_KEY = 0x524D4688
internal const val DISCOVER_EMPTY_ITEM_KEY = 0x524D468C
internal const val ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE = 0x524D47
internal const val ACCOUNT_DATA_DOCUMENT_REQUEST_CODE = 0x524D48

internal const val PROJECT_URL = "https://github.com/YGHFv/ReaMicro-Extend"

internal const val ONLINE_SOURCE_DOCUMENT_REQUEST_CODE = 0x524D49
internal const val HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE = 0x524D4A
internal const val HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE = 0x524D4B
internal const val PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE = 0x524D4C
internal const val ONLINE_EPUB_STYLE_DOCUMENT_REQUEST_CODE = 0x524D4E
internal const val ONLINE_EPUB_STYLE_IMAGE_DOCUMENT_REQUEST_CODE = 0x524D4F
internal const val ONLINE_EPUB_STYLE_ASSET_DIR = "reamicro_epub_style_assets"
internal const val ONLINE_DOWNLOAD_STYLE_TITLE = "下载配置"
internal const val ONLINE_EPUB_PREVIEW_DEBOUNCE_MS = 250L
internal const val SWITCH_TRAILING_KEY_MASK = 0x13579BDF
internal const val ACTION_SUPPORTING_KEY_MASK = 0x2468ACE0
internal const val ACTION_TRAILING_KEY_MASK = 0x0F0F0F0F
internal const val TEXT_DEFAULT_MASK = 131066
internal const val TEXT_WITH_FONT_FAMILY_MASK = 130938
internal const val TEXT_SINGLE_LINE_MASK = 73722
internal const val PRESET_PROMPT_PREVIEW_MAX_CHARS = 32
internal const val PROFILE_BACKGROUND_IMAGE_URL_MAX_LENGTH = 2048
internal const val PREVIEW_REEDEN_BOX_EDGE_SCALE = 0.78f
internal const val FAMILY_SYSTEM = "system"
internal const val FAMILY_SOURCE_HAN_SERIF = "serif"
internal const val ONLINE_COMPLETION_TITLE = "在线补全"
internal const val AI_CONFIG_TITLE = "API \u914d\u7f6e"
internal const val DICTIONARY_SETTINGS_TITLE = "\u8bcd\u5178\u7ba1\u7406"
internal const val IMAGE_SETTINGS_TITLE = "\u751f\u56fe\u7ba1\u7406"
internal const val READER_HIGHLIGHT_SETTINGS_TITLE = "\u9ad8\u4eae\u7ba1\u7406"
internal const val MODULE_ENTRY_TITLE = "补全计划"
internal const val ABOUT_COMPLETION_TITLE = "关于补全"
internal const val FONT_LIBRARY_TITLE = "字体库"
internal const val DISCOVER_TITLE = "发现"
internal const val FONT_DOCUMENT_REQUEST_CODE = 0x524D46
internal const val FONT_IMPORT_DEDUPE_WINDOW_MS = 2_500L
internal const val ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS = 2_500L
internal const val EXTRA_IMPORT_PAYLOAD = "com.reamicro.fix.import.PAYLOAD"
internal const val EXTRA_IMPORT_NAME = "com.reamicro.fix.import.NAME"
internal const val FONT_FILES_CACHE_WINDOW_MS = 500L
internal val READER_HIGHLIGHT_COLOR_OPTIONS = listOf(
    HighlightColorOption("#FF9800", "\u6a59\u8272"),
    HighlightColorOption("#F59E0B", "\u6696\u6a59"),
    HighlightColorOption("#D97706", "\u6df1\u6a59"),
    HighlightColorOption("#16A34A", "\u7eff\u8272"),
    HighlightColorOption("#2563EB", "\u84dd\u8272"),
    HighlightColorOption("#9333EA", "\u7d2b\u8272"),
    HighlightColorOption("#DC2626", "\u7ea2\u8272"),
)
internal val PROFILE_BACKGROUND_CROP_POSITION_OPTIONS = listOf(
    HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_CROP_TOP, "\u9760\u4e0a"),
    HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER, "\u5c45\u4e2d"),
    HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM, "\u9760\u4e0b"),
)
internal val PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS = listOf(
    HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_DISPLAY_COVER, "\u586b\u5145\u5168\u5c4f"),
    HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_WIDTH, "\u9002\u5408\u5bbd\u5ea6"),
    HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT, "\u9002\u5408\u9ad8\u5ea6"),
)
internal const val YOUSHU_LOGIN_URL = "https://m.youshu.me/login.php"
internal const val YOUSHU_FAST_LOGIN_VERIFY_ATTEMPTS = 1
internal const val YOUSHU_LOGIN_VERIFY_ATTEMPTS = 4
internal const val YOUSHU_LOGIN_VERIFY_DELAY_MS = 800L
internal const val YOUSHU_LOGIN_STATE_TIMEOUT_MS = 2_000L
internal const val ROTATION_SNAPSHOT_SYNC_SUPPRESS_MS = 1_000L
internal const val ACCOUNT_SWITCH_TITLE = "切换账号"
internal const val YOUSHU_LOGIN_STATE_JS = """
            (function(){
                var body = document.body;
                var text = body ? (body.innerText || body.textContent || '') : '';
                var hasPassword = !!document.querySelector('input[type="password"],input[name="password"]');
                var hasLogoutLink = !!document.querySelector('a[href*="logout"],a[href*="login.php?action=logout"],a[href*="login.php?act=logout"]');
                var hasAccountLink = !!document.querySelector('a[href*="userdetail.php"],a[href*="useredit.php"],a[href*="setavatar.php"],a[href*="message.php?box="]');
                var hasLogoutText = /退出|退出登录|登出|注销|logout/i.test(text);
                if (hasPassword) return false;
                if (hasLogoutLink || hasLogoutText || hasAccountLink) return true;
                return null;
            })();
        """
