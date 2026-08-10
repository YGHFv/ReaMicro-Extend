package com.reamicro.fix.hook.reader

import com.reamicro.fix.core.HostClasses

// ReaderHook 与其外移出去的扩展函数共用的常量。
//
// 原先是 ReaderHook 的 companion object 成员。功能簇拆成同包扩展函数后，
// companion 的 private 成员对扩展函数不可见，因此提升为子包顶层 internal 声明，
// 由各簇文件通过包级 star import 引用。companion 里的函数没有搬——它们是给其它
// hook 调用的对外入口。
internal const val FEATURE_ID = "ReaderHook"
internal const val READER_VIEW_MODEL_CLASS = HostClasses.Host.READER_VIEW_MODEL
internal const val ON_DEMAND_CACHE_LOCK_RETRIES = 20
internal const val ON_DEMAND_CACHE_LOCK_RETRY_MS = 100L
internal const val ON_DEMAND_PREFETCH_429_RETRIES = 2
internal const val ON_DEMAND_PREFETCH_429_RETRY_MS = 10_000L
internal const val READER_UI_INTENT_CLASS = HostClasses.Host.READER_UI_INTENT
internal const val NAV_GRAPH_SCOPE_CLASS = HostClasses.Host.NAV_GRAPH_SCOPE
internal const val READER_CATALOG_CLASS = HostClasses.Host.READER_CATALOG
internal const val READER_TYPE_SETTING_CLASS = HostClasses.Host.READER_TYPE_SETTING
internal const val READER_HIGHLIGHT_SCREEN_CLASS = HostClasses.Host.READER_HIGHLIGHT_SCREEN
internal const val LAZY_LIST_SCOPE_CLASS = HostClasses.Compose.LAZY_LIST_SCOPE
internal const val READER_FAMILY_EPUB_CLASS = HostClasses.Host.READER_FAMILY_EPUB
internal const val READER_FAMILY_USER_CLASS = HostClasses.Host.READER_FAMILY_USER
internal const val READER_FAMILY_BUILD_IN_CLASS = HostClasses.Host.READER_FAMILY_BUILD_IN
internal const val READER_BOTTOM_BAR_CLASS = HostClasses.Host.READER_BOTTOM_BAR
internal const val UI_SHEET_STATUS_CLASS = HostClasses.Host.UI_SHEET_STATUS
internal const val READER_SHARED_STATE_CLASS = HostClasses.Host.READER_SHARED_STATE
internal const val SCROLL_PAGER_KT_CLASS = HostClasses.Host.SCROLL_PAGER_KT
internal const val SESSION_CLASS = HostClasses.Host.SESSION
internal const val PREF_KEYS_CLASS = HostClasses.Host.PREF_KEYS
internal const val EPUB_PAGE_CLASS = HostClasses.Host.EPUB_PAGE
internal const val HTML_DOCUMENT_CLASS = HostClasses.Epub.HTML_DOCUMENT
internal const val EPUB_CFI_CLASS = HostClasses.Epub.EPUB_CFI
internal const val CONTENT_DOM_CLASS = HostClasses.Epub.CONTENT_DOM
internal const val UI_EPUB_WINDOW_CLASS = HostClasses.Epub.UI_EPUB_WINDOW
internal const val HOME_SCREEN_CLASS = HostClasses.Host.HOME_SCREEN
internal const val BOOKSHELF_SCREEN_CLASS = HostClasses.Host.BOOKSHELF_SCREEN
internal const val BOOKMARK_CLASS = HostClasses.Host.BOOKMARK
internal const val MARK_CLASS = HostClasses.Host.MARK
internal const val CATALOG_CHAPTER_ITEM_CLASS = HostClasses.Host.CATALOG_CHAPTER_ITEM
internal const val EDIT_ICON_CLASS = HostClasses.Compose.EDIT_ICON
internal const val DICTIONARY_ICON_TRANSLATE_CLASS = HostClasses.Compose.DICTIONARY_ICON_TRANSLATE
internal const val DICTIONARY_ICON_MENU_BOOK_CLASS = HostClasses.Compose.DICTIONARY_ICON_MENU_BOOK
internal const val DICTIONARY_ICON_AUTO_STORIES_CLASS = HostClasses.Compose.DICTIONARY_ICON_AUTO_STORIES
internal const val DICTIONARY_ICON_BOOK_CLASS = HostClasses.Compose.DICTIONARY_ICON_BOOK
internal const val HIGHLIGHT_ICON_BORDER_COLOR_CLASS = HostClasses.Compose.HIGHLIGHT_ICON_BORDER_COLOR
internal const val HIGHLIGHT_ICON_FORMAT_COLOR_FILL_CLASS = HostClasses.Compose.HIGHLIGHT_ICON_FORMAT_COLOR_FILL
internal const val HIGHLIGHT_ICON_MODE_EDIT_CLASS = HostClasses.Compose.HIGHLIGHT_ICON_MODE_EDIT
internal const val READ_ALOUD_ICON_VOLUME_UP_CLASS = HostClasses.Compose.READ_ALOUD_ICON_VOLUME_UP
internal const val READ_ALOUD_ICON_RECORD_VOICE_OVER_CLASS = HostClasses.Compose.READ_ALOUD_ICON_RECORD_VOICE_OVER
internal const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
internal const val LOG_PREFIX = "ReaMicro LSP"
internal const val FLIP_STYLE_TRANSLATE = 0
internal const val SCROLL_CRASH_PREFS = "reamicro_scroll_crash_guard"
internal const val SCROLL_CRASH_PENDING_KEY = "scroll_crash_pending"
internal const val KOTLIN_FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
internal const val KOTLIN_FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
internal const val KOTLIN_FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3
internal const val DARK_MODE_ICON_CLASS = HostClasses.Compose.DARK_MODE_ICON
internal const val LIGHT_MODE_ICON_CLASS = HostClasses.Compose.LIGHT_MODE_ICON
internal const val ARROW_BACK_ICON_CLASS = HostClasses.Compose.ARROW_BACK_ICON
internal const val SEARCH_ICON_CLASS = HostClasses.Compose.SEARCH_ICON
internal const val KOTLIN_UNIT_CLASS = HostClasses.Kotlin.KOTLIN_UNIT
internal const val KOTLIN_CONTINUATION_CLASS = HostClasses.Kotlin.KOTLIN_CONTINUATION
internal const val COMPOSER_CLASS = HostClasses.Compose.COMPOSER
internal const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = HostClasses.Kotlin.KOTLIN_EMPTY_COROUTINE_CONTEXT
internal const val KOTLIN_INTRINSICS_CLASS = HostClasses.Kotlin.KOTLIN_INTRINSICS
internal const val KOTLIN_COROUTINE_SINGLETONS_CLASS = HostClasses.Kotlin.KOTLIN_COROUTINE_SINGLETONS
internal const val KOTLIN_RESULT_KT_CLASS = HostClasses.Kotlin.KOTLIN_RESULT_KT
internal const val FLOW_LAYOUT_KT_CLASS = HostClasses.Compose.FLOW_LAYOUT_KT
internal const val FLOW_ROW_METHOD = "FlowRow"
internal const val ROW_KT_CLASS = HostClasses.Compose.ROW_KT
internal const val ROW_METHOD = "Row"
internal const val PADDING_KT_CLASS = HostClasses.Compose.PADDING_KT
internal const val PADDING_METHOD = "padding-qDBjuR0"
internal const val PADDING_DEFAULT_METHOD = "padding-qDBjuR0\$default"
internal const val ARRANGEMENT_CLASS = HostClasses.Compose.ARRANGEMENT
internal const val ALIGNMENT_CLASS = HostClasses.Compose.ALIGNMENT
internal const val MATERIAL_THEME_CLASS = HostClasses.Compose.MATERIAL_THEME
internal const val MATERIAL3_TEXT_CLASS = HostClasses.Compose.TEXT_KT
internal const val THEME_KT_CLASS = HostClasses.Host.THEME_KT
internal const val COLOR_KT_CLASS = HostClasses.Compose.COLOR_KT
internal const val MODIFIER_CLASS = HostClasses.Compose.MODIFIER
internal const val SIZE_KT_CLASS = HostClasses.Compose.SIZE_KT
internal const val HEIGHT_METHOD = "height-3ABfNKs"
internal const val FILL_MAX_WIDTH_METHOD = "fillMaxWidth"
internal const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
internal const val UNIT_EXT_KT_CLASS = HostClasses.Host.UNIT_EXT_KT
internal const val UDP_METHOD = "getUdp"
internal const val TYPE_SETTING_FAMILY_METHOD = "TypeSettingFamily"
internal const val HOST_READER_FAMILY_SHEET_HEIGHT_DP = 305
internal const val READER_RULE_SHEET_HEIGHT_DP = 355
internal const val READER_HIGHLIGHT_CONTEXT_REFRESH_DELAY_MS = 250L
internal const val READER_TYPE_SETTING_INSERT_AFTER_ROW_INDEX = 0
internal const val NATIVE_TYPE_GROUP_TOP_PADDING = 14
internal const val NATIVE_SECTION_TITLE_BOTTOM_PADDING = 6
@Volatile internal var latestThemeColors: ThemeColors? = null
internal val READER_TYPE_SETTING_TITLE_TEXTS = setOf("\u5b57\u53f7", "\u95f4\u8ddd", "\u5b57\u4f53")
internal val ARRANGEMENT_SPACED_BY_METHOD_CANDIDATES = listOf(
    "spacedBy-0680j_4",
    "m837spacedBy0680j_4",
    "m874spacedBy0680j_4",
)
internal const val MAX_SEARCH_RESULTS = 2000
internal const val MAX_MATCHES_PER_FILE = 200
internal const val SEARCH_SNIPPET_RADIUS = 16
internal const val SEARCH_CJK_SNIPPET_RADIUS = 7
internal const val SEARCH_SNIPPET_EXTRA_RADIUS = 3
internal const val SEARCH_EMIT_BATCH = 24
internal const val SEARCH_EMIT_INTERVAL_MS = 320L
internal const val SEARCH_NAV_BAR_TAG = 0x524d5331
internal const val SEARCH_MENU_BUTTON_TAG = 0x524d5333
internal const val READ_ALOUD_MENU_BUTTON_TAG = 0x524d5334
internal const val SEARCH_MENU_BUTTON_SIZE_DP = 44
internal const val SEARCH_MENU_BUTTON_RIGHT_MARGIN_DP = 28
internal const val READ_ALOUD_MENU_BUTTON_RIGHT_MARGIN_DP = 84
internal const val SEARCH_MENU_BUTTON_BOTTOM_MARGIN_DP = 166
internal const val READ_ALOUD_SEGMENT_TARGET_CHARS = 160
internal const val READ_ALOUD_SEGMENT_MAX_CHARS = 260
internal const val READ_ALOUD_INITIAL_SEGMENTS = 24
internal const val READ_ALOUD_SEGMENTS_PER_CHUNK = 48
internal const val READ_ALOUD_CHUNK_MAX_CHARS = 18_000
internal const val READ_ALOUD_BACKGROUND_APPEND_DELAY_MS = 8_000L
internal const val READ_ALOUD_REMAINDER_CHUNK_DELAY_MS = 80L
internal const val MAX_READ_ALOUD_SEGMENTS = 5000
internal const val READ_ALOUD_PAGE_RESTART_DELAY_MS = 450L
internal const val READ_ALOUD_RESTART_SUPPRESS_MS = 1_500L
internal const val READ_ALOUD_FOLLOW_MIN_INTERVAL_MS = 650L
internal const val READ_ALOUD_COVER_MAX_EDGE_PX = 512
internal const val READ_ALOUD_PROGRESS_RESTORE_DELAY_MS = 420L
internal const val READ_ALOUD_PROGRESS_SYNC_MIN_INTERVAL_MS = 1_500L
internal const val READ_ALOUD_PROGRESS_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
internal const val EPUB_PAGE_CONTENT = 0
internal const val SEARCH_NAVIGATION_READER_BOTTOM_MARGIN_DP = 8
internal const val SEARCH_NAVIGATION_MENU_BOTTOM_MARGIN_DP = 190
internal const val SEARCH_JUMP_SINGLE_CORRECTION_DELAY_MS = 760L
internal const val SEARCH_JUMP_SINGLE_CORRECTION_FALLBACK_DELAY_MS = 1250L
internal const val SEARCH_HIGHLIGHT_OFFSET_TOLERANCE = 8
internal const val SEARCH_ORIGIN_PREFS = "reamicro_search_origin"
internal const val SEARCH_ORIGIN_KEY_TIMESTAMP = "timestamp"
internal const val SEARCH_ORIGIN_KEY_BOOK = "book"
internal const val SEARCH_ORIGIN_KEY_EPUB_ROOT = "epub_root"
internal const val SEARCH_ORIGIN_KEY_CFI = "cfi"
internal const val SEARCH_ORIGIN_KEY_CHAPTER_INDEX = "chapter_index"
internal const val SEARCH_ORIGIN_KEY_TITLE = "title"
internal const val SEARCH_ORIGIN_KEY_SUMMARY = "summary"
internal const val SEARCH_ORIGIN_RESTORE_DELAY_MS = 360L
internal const val SEARCH_ORIGIN_MAX_AGE_MS = 24L * 60L * 60L * 1000L
internal const val READ_ALOUD_PROGRESS_PREFS = "reamicro_read_aloud_progress"
internal const val READ_ALOUD_PROGRESS_KEY_TIMESTAMP = "timestamp"
internal const val READ_ALOUD_PROGRESS_KEY_SESSION = "session"
internal const val READ_ALOUD_PROGRESS_KEY_BOOK = "book"
internal const val READ_ALOUD_PROGRESS_KEY_BOOK_IDENTITY = "book_identity"
internal const val READ_ALOUD_PROGRESS_KEY_EPUB_ROOT = "epub_root"
internal const val READ_ALOUD_PROGRESS_KEY_BOOK_TITLE = "book_title"
internal const val READ_ALOUD_PROGRESS_KEY_CFI = "cfi"
internal const val READ_ALOUD_PROGRESS_KEY_END_CFI = "end_cfi"
internal const val READ_ALOUD_PROGRESS_KEY_PARAGRAPH_INDEX = "paragraph_index"
internal const val READ_ALOUD_PROGRESS_KEY_CHAPTER_INDEX = "chapter_index"
internal const val READ_ALOUD_PROGRESS_KEY_TITLE = "title"
internal const val READ_ALOUD_PROGRESS_KEY_SUMMARY = "summary"
internal const val READ_ALOUD_PROGRESS_KEY_ELAPSED_MS = "elapsed_ms"
internal const val READ_ALOUD_PROGRESS_KEY_RECORDED_ELAPSED_MS = "recorded_elapsed_ms"
internal const val READ_ALOUD_PROGRESS_KEY_PLAYBACK_STARTED = "playback_started"
internal const val MARK_KIND_HIGHLIGHT = 0
internal const val MARK_STYLE_FILL = 0
internal const val MARK_STYLE_LINE = 1
internal const val MARK_SYNCED_NO = 0
internal const val MARK_COLOR_RED = "red"
internal const val MARK_COLOR_YELLOW = "yellow"
internal const val SEARCH_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_854_000_000L
internal const val SEARCH_HIGHLIGHT_MARK_ID_RANGE = 100_000L
internal const val SELECTION_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_853_800_000L
internal const val SELECTION_HIGHLIGHT_MARK_ID_RANGE = 100_000L
internal const val READ_ALOUD_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_853_700_000L
internal const val READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE = 100_000L
internal val BLOCK_SEARCH_TAGS = setOf(
    "p", "div", "section", "article", "li", "blockquote", "pre",
    "h1", "h2", "h3", "h4", "h5", "h6",
)
internal val SKIPPED_SEARCH_TAGS = setOf("head", "script", "style", "title", "svg", "math")
internal val SEARCH_VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
)
internal val READ_ALOUD_CHUNK_SOFT_BREAK_CHARS = setOf(
    '\u3002', '\uff01', '\uff1f', '\uff1b', '\uff0c', '\u3001', '\uff1a',
    '.', '!', '?', ';', ',', ':',
)
internal val READ_ALOUD_CHUNK_TRAILING_CHARS = setOf(
    '\u3002', '\uff01', '\uff1f', '\uff1b', '\uff0c', '\u3001', '\uff1a',
    '.', '!', '?', ';', ',', ':',
    '\u2019', '\u201d', '\u3011', '\uff09', '\u300b', '\u3009',
    '\'', '"', ')', ']', '}', '>',
)
internal val READ_ALOUD_OPEN_WRAPPER_CHARS = setOf(
    '\u2018', '\u201c', '\u3010', '\uff08', '(', '[', '{', '<', '\u300a', '\u3008', '\'', '"',
)
internal val READ_ALOUD_CLOSE_WRAPPER_CHARS = setOf(
    '\u2019', '\u201d', '\u3011', '\uff09', ')', ']', '}', '>', '\u300b', '\u3009', '\'', '"',
)
internal val READ_ALOUD_WRAPPER_CHARS = charArrayOf(
    '\u2018', '\u2019', '\u201c', '\u201d', '\u3010', '\u3011', '\uff08', '\uff09',
    '(', ')', '[', ']', '{', '}', '<', '>', '\u300a', '\u300b', '\u3008', '\u3009', '\'', '"',
)
internal val READ_ALOUD_INNER_PUNCTUATION_CHARS = setOf(
    '\u3002', '\uff01', '\uff1f', '\uff1b', '\uff0c', '\u3001', '\uff1a',
    '.', '!', '?', ';', ',', ':',
)
internal val COVER_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
