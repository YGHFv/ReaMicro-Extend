package com.reamicro.fix.hook.settings

import android.widget.Switch
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.settings.ModuleSettings

// ReaMicroSettingsHook 与其外移出去的扩展函数共用的常量。
//
// 原先是 ReaMicroSettingsHook 的 companion object 成员。功能簇拆成同包扩展函数后，
// companion 的 private 成员对扩展函数不可见，因此提升为子包顶层 internal 声明，
// 由各簇文件通过包级 star import 引用。companion 里的函数没有搬——它们是给其它
// hook 调用的对外入口。
internal const val FEATURE_ID = "ReaMicroSettingsHook"

internal const val LOG_PREFIX = "ReaMicro LSP"

internal const val SETTINGS_SCREEN_CLASS = HostClasses.Host.SETTINGS_SCREEN
internal const val SETTINGS_LIST_BUILDER_METHOD = "SettingsScreen\$lambda\$0\$1\$0\$0"
internal const val ACCOUNT_SECURITY_SCREEN_CLASS = HostClasses.Host.ACCOUNT_SECURITY_SCREEN
internal const val ACCOUNT_SECURITY_DELETE_CONTENT_METHOD = "AccountSecurityScreen\$lambda\$0\$0\$4\$0\$2"
internal const val ACCOUNT_SECURITY_DELETE_ITEM_METHOD = "DeleteAccountItem"
internal const val NAV_GRAPH_SCOPE_CLASS = HostClasses.Host.NAV_GRAPH_SCOPE
internal const val NAV_CONTROLLER_CLASS = HostClasses.AndroidX.NAV_CONTROLLER
internal const val ROUTE_ABOUT_CLASS = "app.zhendong.reamicro.Route\$About"
internal const val BACK_HANDLER_KT_CLASS = HostClasses.AndroidX.BACK_HANDLER_KT
internal const val BACK_HANDLER_METHOD = "BackHandler"
internal const val NAVIGATION_EVENT_INFO_NONE_CLASS = "androidx.navigationevent.NavigationEventInfo\$None"
internal const val REMEMBER_NAVIGATION_EVENT_STATE_KT_CLASS = HostClasses.AndroidX.REMEMBER_NAVIGATION_EVENT_STATE_KT
internal const val REMEMBER_NAVIGATION_EVENT_STATE_METHOD = "rememberNavigationEventState"
internal const val NAVIGATION_EVENT_HANDLER_KT_CLASS = HostClasses.AndroidX.NAVIGATION_EVENT_HANDLER_KT
internal const val NAVIGATION_BACK_HANDLER_METHOD = "NavigationBackHandler"
internal const val ABOUT_SCREEN_CLASS = HostClasses.Host.ABOUT_SCREEN
internal const val ABOUT_SCREEN_METHOD = "AboutScreen"
internal const val APP_ABOUT_CLASS = HostClasses.Host.APP_ABOUT
internal const val APP_ABOUT_METHOD = "AppAbout"
internal const val APP_TOP_BAR_CLASS = HostClasses.Host.APP_TOP_BAR
internal const val APP_TOP_BAR_METHOD = "AppTopBar"
internal const val TOP_APP_BAR_DEFAULTS_CLASS = HostClasses.Compose.TOP_APP_BAR_DEFAULTS
internal const val WINDOW_INSETS_CLASS = HostClasses.Compose.WINDOW_INSETS
internal const val IMAGE_VECTOR_CLASS = HostClasses.Compose.IMAGE_VECTOR
internal const val WINDOW_INSETS_KT_CLASS = HostClasses.Compose.WINDOW_INSETS_KT
internal const val WINDOW_INSETS_EXT_ANDROID_KT_CLASS = HostClasses.Host.WINDOW_INSETS_EXT_ANDROID_KT
internal const val EVA_ICONS_CLASS = "compose.icons.EvaIcons"
internal const val EVA_OUTLINE_KT_CLASS = "compose.icons.evaicons.__OutlineKt"
internal const val EVA_CLOSE_KT_CLASS = "compose.icons.evaicons.outline.CloseKt"

internal const val SCAFFOLD_KT_CLASS = HostClasses.Compose.SCAFFOLD_KT
internal const val SCAFFOLD_METHOD = "Scaffold-TvnljyQ"
internal const val LAZY_DSL_KT_CLASS = HostClasses.Compose.LAZY_DSL_KT
internal const val LAZY_COLUMN_METHOD = "LazyColumn"
internal const val LAZY_LIST_SCOPE_CLASS = HostClasses.Compose.LAZY_LIST_SCOPE
internal const val LAZY_ITEM_DEFAULT_METHOD = "item\$default"
internal const val COLUMN_KT_CLASS = HostClasses.Compose.COLUMN_KT
internal const val COLUMN_METHOD = "Column"
internal const val LIST_ITEM_KT_CLASS = HostClasses.Compose.LIST_ITEM_KT
internal const val LIST_ITEM_METHOD = "ListItem-HXNGIdc"
internal const val LIST_ITEM_DEFAULTS_CLASS = HostClasses.Compose.LIST_ITEM_DEFAULTS
internal const val LIST_ITEM_COLORS_METHOD = "colors-J08w3-E"
internal const val SWITCH_KT_CLASS = HostClasses.Compose.SWITCH_KT
internal const val SWITCH_METHOD = "Switch"
internal const val SWITCH_DEFAULTS_CLASS = HostClasses.Compose.SWITCH_DEFAULTS
internal const val SWITCH_COLORS_METHOD = "colors-V1nXRL4"
internal const val TEXT_KT_CLASS = HostClasses.Compose.TEXT_KT
internal const val TEXT_METHOD = "Text-Nvy7gAk"
internal const val DIVIDER_KT_CLASS = HostClasses.Host.DIVIDER_KT
internal const val DASHED_DIVIDER_METHOD = "DashedHorizontalDivider-aM-cp0Q"

internal const val SIZE_KT_CLASS = HostClasses.Compose.SIZE_KT
internal const val FILL_MAX_SIZE_DEFAULT_METHOD = "fillMaxSize\$default"
internal const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
internal const val HEIGHT_METHOD = "height-3ABfNKs"
internal const val PADDING_KT_CLASS = HostClasses.Compose.PADDING_KT
internal const val PADDING_VALUES_METHOD = "padding"
internal const val PADDING_HORIZONTAL_DEFAULT_METHOD = "padding-VpY3zN4\$default"
internal const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
internal const val BACKGROUND_KT_CLASS = HostClasses.Compose.BACKGROUND_KT
internal const val BACKGROUND_DEFAULT_METHOD = "background-bw27NRU\$default"
internal const val BORDER_KT_CLASS = HostClasses.Compose.BORDER_KT
internal const val BORDER_METHOD = "border-xT4_qwU"
internal const val CLIP_KT_CLASS = HostClasses.Compose.CLIP_KT
internal const val CLIP_METHOD = "clip"
internal const val SCALE_KT_CLASS = HostClasses.Compose.SCALE_KT
internal const val SCALE_METHOD = "scale"
internal const val ALPHA_KT_CLASS = HostClasses.Compose.ALPHA_KT
internal const val ALPHA_METHOD = "alpha"
internal const val CLICKABLE_KT_CLASS = HostClasses.Compose.CLICKABLE_KT
internal const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
internal const val COMBINED_CLICKABLE_DEFAULT_METHOD = "combinedClickable-hoGz1lA\$default"
internal const val SHAPE_KT_CLASS = HostClasses.Host.SHAPE_KT
internal const val ROUNDED_SHAPE_METHOD = "getRoundedShape"
internal const val THEME_KT_CLASS = HostClasses.Host.THEME_KT
internal const val BACKGROUND_AUTO_METHOD = "getBackgroundAuto"
internal const val BACKGROUND_DIM_METHOD = "getBackgroundDim"
internal const val BORDER_VARIANT_METHOD = "getBorderVariant"
internal const val MATERIAL_THEME_CLASS = HostClasses.Compose.MATERIAL_THEME
internal const val COLOR_CLASS = HostClasses.Compose.COLOR
internal const val COLOR_KT_CLASS = HostClasses.Compose.COLOR_KT
internal const val COLOR_TRANSPARENT_METHOD = "getTransparent-0d7_KjU"
internal const val FONT_PROVIDER_CLASS = HostClasses.Epub.FONT_PROVIDER
internal const val FONT_FAMILY_CLASS = HostClasses.Compose.FONT_FAMILY
internal const val FONT_FAMILY_KT_CLASS = HostClasses.Compose.FONT_FAMILY_KT
internal const val FONT_WEIGHT_CLASS = HostClasses.Compose.FONT_WEIGHT
internal const val MODIFIER_CLASS = HostClasses.Compose.MODIFIER
internal const val READER_RULE_SHEET_HEIGHT_DP = 355
internal const val ARRANGEMENT_CLASS = HostClasses.Compose.ARRANGEMENT
internal const val SPACED_BY_METHOD = "spacedBy-0680j_4"
internal const val ALIGNMENT_CLASS = HostClasses.Compose.ALIGNMENT
internal const val UNIT_EXT_KT_CLASS = HostClasses.Host.UNIT_EXT_KT
internal const val UDP_METHOD = "getUdp"
internal const val COMPOSER_CLASS = HostClasses.Compose.COMPOSER
internal const val SNAPSHOT_STATE_KT_CLASS = HostClasses.Compose.SNAPSHOT_STATE_KT
internal const val MUTABLE_STATE_OF_DEFAULT_METHOD = "mutableStateOf\$default"
internal const val COMPOSABLE_LAMBDA_KT_CLASS = HostClasses.Compose.COMPOSABLE_LAMBDA_KT
internal const val COMPOSABLE_LAMBDA_METHOD = "composableLambdaInstance"
internal const val STRING_RESOURCES_CLASS = HostClasses.Compose.STRING_RESOURCES
internal const val STRING_RESOURCE_METHOD = "stringResource"
internal const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
internal const val FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
internal const val FUNCTION2_CLASS = HostClasses.Kotlin.FUNCTION2
internal const val FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3
internal const val USER_REPOSITORY_CLASS = HostClasses.Host.USER_REPOSITORY
internal const val USER_REPOSITORY_SIGN_OUT_METHOD = "signOut"

internal const val INSERT_AFTER_SETTINGS_ITEM_COUNT = 2
internal const val INSERT_BEFORE_SIGN_OUT_ITEM_COUNT = 3
internal const val ACCOUNT_SETTINGS_ITEM_KEY = 0x524D4657
internal const val MODULE_SETTINGS_ITEM_KEY = 0x524D4658
internal const val MODULE_TOP_BAR_KEY = 0x524D4659
internal const val MODULE_CONTENT_KEY = 0x524D465A
internal const val MODULE_SWITCH_ITEM_KEY = 0x524D465B
internal const val ASSOCIATION_SWITCHES_ITEM_KEY = 0x524D465C
internal const val READER_SWITCHES_ITEM_KEY = 0x524D465D
internal const val ROTATION_SWITCHES_ITEM_KEY = 0x524D465E
internal const val CLOUD_SWITCHES_ITEM_KEY = 0x524D465F
internal const val FONT_SETTINGS_ITEM_KEY = 0x524D4660
internal const val FONT_SWITCHES_ITEM_KEY = 0x524D4661
internal const val FONT_SETTINGS_CONTENT_ITEM_KEY = 0x524D4662
internal const val FONT_PICKER_CONTENT_ITEM_KEY = 0x524D4663
internal const val FONT_LIBRARY_CONTENT_ITEM_KEY = 0x524D4664
internal const val ONLINE_COMPLETION_SETTINGS_ITEM_KEY = 0x524D4665
internal const val ACCOUNT_COMPLETION_SWITCHES_ITEM_KEY = 0x524D4666
internal const val ACCOUNT_EXPORT_ACTION_ITEM_KEY = 0x524D4667
internal const val ACCOUNT_IMPORT_ACTION_ITEM_KEY = 0x524D4668
internal const val ACCOUNT_SWITCH_ACTION_ITEM_KEY = 0x524D4669
internal const val ONLINE_COMPLETION_CONTENT_ITEM_KEY = 0x524D466A
internal const val AI_CONFIG_SETTINGS_ITEM_KEY = 0x524D466B
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
internal const val PROFILE_BACKGROUND_SWITCHES_ITEM_KEY = 0x524D467D
internal const val PROFILE_BACKGROUND_COLOR_PICKER_ITEM_KEY = 0x524D467E
internal const val READER_READ_ALOUD_SETTINGS_ITEM_KEY = 0x524D467F
internal const val READER_READ_ALOUD_SOURCES_ITEM_KEY = 0x524D4680
internal const val READER_SELECTION_MENU_SETTINGS_ITEM_KEY = 0x524D4681
internal const val PROFILE_BACKGROUND_ENABLE_ITEM_KEY = 0x524D4682
internal const val PROFILE_BACKGROUND_CONTENT_ITEM_KEY = 0x524D4683
internal const val ONLINE_DOWNLOAD_STYLE_ENTRY_ITEM_KEY = 0x524D4684
internal const val ONLINE_DOWNLOAD_STYLE_CONTENT_ITEM_KEY = 0x524D4685
internal const val ONLINE_EPUB_STYLE_LIST_ITEM_KEY = 0x524D4687
internal const val ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE = 0x524D47
internal const val ACCOUNT_DATA_DOCUMENT_REQUEST_CODE = 0x524D48
internal const val ONLINE_SOURCE_DOCUMENT_REQUEST_CODE = 0x524D49
internal const val HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE = 0x524D4A
internal const val HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE = 0x524D4B
internal const val PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE = 0x524D4C
internal const val READ_ALOUD_SOURCE_DOCUMENT_REQUEST_CODE = 0x524D4D
internal const val ONLINE_EPUB_STYLE_DOCUMENT_REQUEST_CODE = 0x524D4E
internal const val ONLINE_EPUB_STYLE_IMAGE_DOCUMENT_REQUEST_CODE = 0x524D4F
internal const val ONLINE_EPUB_STYLE_ASSET_DIR = "reamicro_epub_style_assets"
internal const val ONLINE_DOWNLOAD_STYLE_TITLE = "下载配置"
internal const val ONLINE_EPUB_PREVIEW_DEBOUNCE_MS = 250L
internal const val ACCOUNT_RESTART_DELAY_MS = 1_400L
internal const val ACCOUNT_RESTART_KILL_DELAY_MS = 250L
internal const val ACCOUNT_RESTART_COMMAND_DELAY_SECONDS = "0.8"
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
internal const val HOST_ABOUT_TITLE = "关于阅微"
internal const val MODULE_ENTRY_TITLE = "补全计划"
internal const val ABOUT_COMPLETION_TITLE = "关于补全"
internal const val FONT_SETTINGS_TITLE = "字体设置"
internal const val FONT_LIBRARY_TITLE = "字体库"
internal const val FONT_DOCUMENT_REQUEST_CODE = 0x524D46
internal const val FONT_IMPORT_DEDUPE_WINDOW_MS = 2_500L
internal const val ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS = 2_500L
internal const val HOST_PACKAGE_NAME = HostClasses.Host.HOST_PACKAGE_NAME
internal const val EXTRA_IMPORT_PAYLOAD = "com.reamicro.fix.import.PAYLOAD"
internal const val EXTRA_IMPORT_NAME = "com.reamicro.fix.import.NAME"
internal const val ONLINE_SOURCE_REMOVE_CONFIRM_WINDOW_MS = 3_000L
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
                var cookie = document.cookie || '';
                if (/(^|;\s*)jieqiUserInfo=/.test(cookie)) return true;
                var body = document.body;
                var text = body ? (body.innerText || body.textContent || '') : '';
                var hasPassword = !!document.querySelector('input[type="password"],input[name="password"]');
                var hasLogoutLink = !!document.querySelector('a[href*="logout"],a[href*="login.php?action=logout"],a[href*="login.php?act=logout"]');
                var hasAccountLink = !!document.querySelector('a[href*="userdetail.php"],a[href*="useredit.php"],a[href*="setavatar.php"],a[href*="message.php?box="]');
                var hasLogoutText = /退出|退出登录|登出|注销|logout/i.test(text);
                return (hasLogoutLink || hasLogoutText || hasAccountLink) && !hasPassword;
            })();
        """
