package com.reamicro.fix.hook.reader

import com.reamicro.fix.core.HostClasses

internal const val FEATURE_ID = "ReaderHook"
internal const val ON_DEMAND_PREFETCH_429_RETRIES = 2
internal const val ON_DEMAND_PREFETCH_429_RETRY_MS = 10_000L
internal const val PREF_KEYS_CLASS = HostClasses.Host.PREF_KEYS
internal const val LOG_PREFIX = "ReaMicro LSP"
internal const val FLIP_STYLE_TRANSLATE = 0
internal const val SCROLL_CRASH_PREFS = "reamicro_scroll_crash_guard"
internal const val SCROLL_CRASH_PENDING_KEY = "scroll_crash_pending"
internal const val KOTLIN_FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3
internal const val READER_RULE_SHEET_HEIGHT_DP = 355
internal const val READER_HIGHLIGHT_CONTEXT_REFRESH_DELAY_MS = 250L
@Volatile internal var latestThemeColors: ThemeColors? = null
internal val READER_TYPE_SETTING_TITLE_TEXTS = setOf("\u5b57\u53f7", "\u95f4\u8ddd", "\u5b57\u4f53")
internal val ARRANGEMENT_SPACED_BY_METHOD_CANDIDATES = listOf(
    "spacedBy-0680j_4",
    "m837spacedBy0680j_4",
    "m874spacedBy0680j_4",
)
internal const val MAX_SEARCH_RESULTS = 2000
internal const val MAX_MATCHES_PER_FILE = 200
internal const val SEARCH_SNIPPET_RADIUS = 32
internal const val SEARCH_CJK_SNIPPET_RADIUS = 16
internal const val SEARCH_SNIPPET_EXTRA_RADIUS = 3
internal const val SEARCH_EMIT_INTERVAL_MS = 120L
internal const val SEARCH_NAV_BAR_TAG = 0x524d5331
internal const val SEARCH_HIGHLIGHT_OFFSET_TOLERANCE = 8
internal const val SEARCH_ORIGIN_PREFS = "reamicro_search_origin"
internal const val SEARCH_ORIGIN_KEY_TIMESTAMP = "timestamp"
internal const val SEARCH_ORIGIN_KEY_BOOK = "book"
internal const val SEARCH_ORIGIN_KEY_EPUB_ROOT = "epub_root"
internal const val SEARCH_ORIGIN_KEY_CFI = "cfi"
internal const val SEARCH_ORIGIN_KEY_CHAPTER_INDEX = "chapter_index"
internal const val SEARCH_ORIGIN_KEY_TITLE = "title"
internal const val SEARCH_ORIGIN_KEY_SUMMARY = "summary"
internal const val SEARCH_ORIGIN_KEY_SCROLL_OFFSET = "scroll_offset_px"
internal const val SEARCH_ORIGIN_RESTORE_DELAY_MS = 360L
internal const val SEARCH_ORIGIN_MAX_AGE_MS = 24L * 60L * 60L * 1000L
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
internal val BLOCK_SEARCH_TAGS = setOf(
    "p", "div", "section", "article", "li", "blockquote", "pre",
    "h1", "h2", "h3", "h4", "h5", "h6",
)
internal val SKIPPED_SEARCH_TAGS = setOf("head", "script", "style", "title", "svg", "math")
internal val SEARCH_VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
)
internal val COVER_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
