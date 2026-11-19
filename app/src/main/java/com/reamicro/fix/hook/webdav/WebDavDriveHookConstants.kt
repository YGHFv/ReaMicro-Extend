package com.reamicro.fix.hook.webdav

import com.reamicro.fix.core.HostClasses
import java.util.concurrent.atomic.AtomicBoolean

internal const val FEATURE_ID = "WebDavDriveHook"
internal const val LOG_TAG = "ReaMicroWebDAV"
internal const val LOG_PREFIX = "ReaMicro LSP"

internal const val ONLINE_PARAGRAPH_COMMENTS_RUNTIME_ENABLED = false

internal const val BOOK_LIBRARY_SHEET_CLASS = HostClasses.Host.BOOK_LIBRARY_SHEET
internal const val CLOUD_AUTHORIZED_ROW_METHOD = "CloudAuthorizedRow"
internal const val CLOUD_UNAUTH_ROW_METHOD = "CloudUnauthRow"
internal const val CLOUD_STORAGE_UI_STATE_CLASS = HostClasses.Host.CLOUD_STORAGE_UI_STATE
internal const val CLOUD_BOOK_CLASS = HostClasses.Host.CLOUD_BOOK
internal const val CLOUD_FOLDER_CLASS = HostClasses.Host.CLOUD_FOLDER
internal const val WORKER_MANAGER_CLASS = HostClasses.Host.WORKER_MANAGER
internal const val WORKER_ENQUEUE_DOWNLOAD_METHOD = "enqueueDownload"
internal const val FLOW_CLASS = "kotlinx.coroutines.flow.Flow"
internal const val PLATFORM_FILE_GET_PATH_METHOD = "getPath"
internal const val PLATFORM_FILE_ABSOLUTE_PATH_METHOD = "absolutePath"
internal const val AUTH_BAIDU_CLASS = "app.zhendong.reamicro.data.third.Auth\$BaiduAuth"
internal const val AUTH_YUN115_CLASS = "app.zhendong.reamicro.data.third.Auth\$Yun115Auth"
internal const val BAIDU_ACCOUNT_SCREEN_CLASS = HostClasses.Host.BAIDU_ACCOUNT_SCREEN
internal const val BAIDU_ACCOUNT_SCREEN_METHOD = "BaiduNetDiskAccountScreen"
internal const val BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD = "DefaultFolder"
internal const val BAIDU_ACCOUNT_LOGOUT_METHOD = "LogOut"
internal const val BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD = "QueryOrderBy"
internal const val BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD = "QueryOrderDirection"
internal const val Y115_ACCOUNT_SCREEN_CLASS = HostClasses.Host.Y115_ACCOUNT_SCREEN
internal const val Y115_ACCOUNT_SCREEN_METHOD = "Y115NetDiskAccountScreen"
internal const val Y115_ACCOUNT_DEFAULT_FOLDER_METHOD = "DefaultFolder"
internal const val Y115_ACCOUNT_LOGOUT_METHOD = "LogOut"
internal const val Y115_ACCOUNT_QUERY_ORDER_BY_METHOD = "QueryOrderBy"
internal const val Y115_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD = "QueryOrderDirection"
internal const val DIR_CLASS = HostClasses.Host.DIR
internal const val CLOUD_USER_INFO_CLASS = HostClasses.Host.CLOUD_USER_INFO
internal const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
internal const val FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
internal const val FUNCTION2_CLASS = HostClasses.Kotlin.FUNCTION2
internal const val FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3
internal const val IMAGE_VECTOR_CLASS = HostClasses.Compose.IMAGE_VECTOR
internal const val DIVIDER_KT_CLASS = HostClasses.Host.DIVIDER_KT
internal const val SIMPLE_DIVIDER_METHOD = "SimpleDivider-iJQMabo"
internal const val NETWORK_SECURITY_POLICY_CLASS = "android.security.NetworkSecurityPolicy"
internal const val ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS = "com.android.okhttp.HttpHandler\$CleartextURLFilter"
internal const val ANDROID_OKHTTP_PLATFORM_CLASS = "com.android.okhttp.internal.Platform"
internal const val BACKUP_TYPE_WEBDAV = 8
internal const val BACKUP_TYPE_LOCAL_LIBRARY = 9
internal const val BACKUP_TYPE_ONLINE_COMPLETION = 10
internal const val ONLINE_COMPLETION_RENDER_TYPE_BASE = 10000
internal const val ONLINE_COMPLETION_RENDER_TYPE_BUCKETS = 100000
internal const val BACKUP_TYPE_BAIDU = 1
internal const val BACKUP_TYPE_YUN115 = 2
internal const val BACKUP_TYPE_ALIYUN = 4
internal const val ONLINE_COMPLETION_SOURCE_PREFIX = "reamicro-online-source://"
internal const val ONLINE_COMPLETION_BOOK_PREFIX = "reamicro-online-book://"
internal const val ONLINE_COMPLETION_UUID_PREFIX = "reamicro-online-"
internal const val ONLINE_COMPLETION_CACHE_ROOT = "reamicro-online-completion"
internal const val ONLINE_COMPLETION_DEFAULT_STYLE_PATH = "OEBPS/Styles/default.css"
internal const val ONLINE_COMPLETION_CHAPTER_INDEX = "reamicro-online-chapters.json"
internal const val ONLINE_COMPLETION_HEADER_IMAGE = "header.png"
internal const val ONLINE_COMPLETION_FAILED_CHAPTER_LOG = "reamicro-online-failed-chapters.json"
internal const val ONLINE_COMPLETION_NOTIFICATION_CHANNEL = "reamicro_online_completion_download"
internal const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
internal const val ONLINE_COMPLETION_NOTIFICATION_ACTION = "com.reamicro.fix.ONLINE_COMPLETION_NOTIFICATION"
internal const val ONLINE_COMPLETION_CANCEL_ACTION = "com.reamicro.fix.ONLINE_COMPLETION_CANCEL"
internal const val ONLINE_COMPLETION_HEARTBEAT_ACTION = "com.reamicro.fix.ONLINE_COMPLETION_HEARTBEAT"
internal const val ONLINE_COMPLETION_NOTIFICATION_ACTIVITY_CLASS =
    "com.reamicro.fix.notification.OnlineCompletionNotificationActivity"
internal const val ONLINE_COMPLETION_NOTIFICATION_RECEIVER_CLASS =
    "com.reamicro.fix.notification.OnlineCompletionNotificationReceiver"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID = "id"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY = "key"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_CANCELLABLE = "cancellable"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_TITLE = "title"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_TEXT = "text"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_PROGRESS = "progress"
internal const val ONLINE_COMPLETION_NOTIFICATION_EXTRA_DONE = "done"
internal const val WEBDAV_TITLE = "WebDAV"
internal const val LOCAL_LIBRARY_TITLE = "\u672c\u5730\u4e66\u5e93"
internal const val ONLINE_COMPLETION_TITLE = "\u5728\u7ebf\u8865\u5168"
internal const val LOCAL_LIBRARY_BROWSE_TEXT = "\u6d4f\u89c8\u6587\u4ef6"
internal const val LOCAL_LIBRARY_FOLDER_TITLE = "\u4e66\u5e93\u6587\u4ef6\u5939"
internal const val LOCAL_LIBRARY_PICK_FOLDER_TEXT = "\u9009\u62e9\u76ee\u5f55"
internal const val LOCAL_LIBRARY_REMOVE_TEXT = "\u79fb\u9664"
internal const val LOCAL_LIBRARY_REMOVED_TOAST = "\u5df2\u79fb\u9664"
internal const val ACCOUNT_CONTEXT_GRACE_MS = 3000L
internal const val ROOT_DIR_NAME = "\u6839\u76ee\u5f55"
internal const val EPUB_MIME_TYPE = "application/epub+zip"
internal const val WEBDAV_AUTH_TIPS = "登录 WebDAV 账号浏览网盘书籍"
internal const val WEBDAV_PREFS = "reamicro_fix_webdav"
internal const val LOCAL_LIBRARY_PREFS = "reamicro_fix_local_library"
internal const val KEY_URL = "url"
internal const val KEY_USERNAME = "username"
internal const val KEY_PASSWORD = "password"
internal const val KEY_DIR_LEGACY = "dir"
internal const val KEY_BROWSE_DIR = "browse_dir"
internal const val KEY_BACKUP_DIR = "backup_dir"
internal const val KEY_AUTHORIZED = "authorized"
internal const val KEY_ORDER_BY = "order_by"
internal const val KEY_ORDER_DIRECTION = "order_direction"
internal const val KEY_LOCAL_FOLDER_URIS = "folder_uris"
internal const val KEY_LOCAL_BROWSE_DIR = "browse_dir"
internal const val KEY_LOCAL_ORDER_BY = "order_by"
internal const val KEY_LOCAL_ORDER_DIRECTION = "order_direction"
internal const val DEFAULT_DIR = "/"
internal const val WEBDAV_DEFAULT_ORDER_BY = "time"
internal const val LOCAL_LIBRARY_DEFAULT_ORDER_BY = "file_name"
internal const val DEFAULT_ORDER_DIRECTION_ASC = "0"
internal const val DEFAULT_ORDER_DIRECTION_DESC = "1"
internal const val WEBDAV_SOURCE_PREFIX = "webdav://reamicro"
internal const val LOCAL_LIBRARY_SOURCE_PREFIX = "local-library://reamicro/"
internal const val LOCAL_LIBRARY_ROOT_PATH = "/"
internal const val LOCAL_LIBRARY_PATH_PREFIX = "local:"
internal const val WEBDAV_SEARCH_MAX_DIRS = 300
internal const val ALIST_SEARCH_PAGE_SIZE = 100
internal const val ALIST_SEARCH_MAX_PAGES = 5
internal const val ALIST_TOKEN_CACHE_TTL_MS = 10 * 60_000L
internal const val ALIST_UNSUPPORTED_CACHE_TTL_MS = 10 * 60_000L
internal const val LOCAL_LIBRARY_SEARCH_MAX_DIRS = 500
internal const val LOCAL_LIBRARY_LIST_CACHE_TTL_MS = 30_000L
internal const val LOCAL_LIBRARY_SEARCH_INDEX_TTL_MS = 5 * 60_000L
internal const val LOCAL_LIBRARY_SEARCH_SYNC_BUDGET_MS = 1_800L
internal const val LOCAL_LIBRARY_SEARCH_BACKGROUND_BUDGET_MS = 8_000L
internal const val HOME_SEARCH_RESULT_LIMIT = 10
internal const val ONLINE_COMPLETION_RESULT_LIMIT = 8
internal const val ONLINE_COMPLETION_SEARCH_METADATA_TIMEOUT_MS = 6_000L
internal const val ONLINE_COMPLETION_SEARCH_METADATA_ENRICH_LIMIT = 8
internal const val ONLINE_COMPLETION_ON_DEMAND_INITIAL_CHAPTERS = 3
internal const val ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT = 3
internal const val ONLINE_COMPLETION_PARTIAL_IMPORT_THRESHOLD = 200
internal const val ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS = 100
internal const val ONLINE_COMPLETION_REPOSITORY_WAIT_TIMEOUT_MS = 30_000L
internal const val ONLINE_COMPLETION_REPOSITORY_WAIT_STEP_MS = 400L
internal const val ONLINE_COMPLETION_RETRY_DELAY_MS = 6_000L
internal const val ONLINE_COMPLETION_RETRY_DELAY_MAX_MS = 60_000L
internal const val ONLINE_COMPLETION_NOTIFICATION_MIN_INTERVAL_MS = 1_000L
internal const val ONLINE_COMPLETION_MODULE_ACTIVITY_RETRY_MS = 15_000L
internal const val ONLINE_COMPLETION_METADATA_REPAIR_INTERVAL_MS = 5 * 60_000L
internal const val ONLINE_COMPLETION_PINNED_TIMESTAMP_FLOOR = 1_000_000_000_000L
internal const val CLOUD_STORAGE_SCREEN_REFRESH_DEBOUNCE_MS = 1_000L
internal const val STRING_KEY_UPLOAD_TO_115 = "upload_to_115"
internal const val HOME_SEARCH_DEBOUNCE_MS = 250L
internal const val DOWNLOAD_CANCEL_CONFIRM_WINDOW_MS = 2_500L
internal const val STARTUP_CACHE_CLEANUP_DELAY_MS = 1_500L
internal const val STALE_IMPORT_CACHE_MIN_AGE_MS = 60 * 60_000L

internal const val COVER_STAGE_CACHE_DIR = "reamicro_cover_stage"
internal const val PROFILE_BACKGROUND_FILES_DIR = "profile_background"
internal const val REQUEST_LOCAL_LIBRARY_DIR = 8931
internal val startupCacheCleanupStarted = AtomicBoolean(false)
internal val NATIVE_CLOUD_DOWNLOAD_TYPES = setOf(BACKUP_TYPE_BAIDU, BACKUP_TYPE_YUN115, BACKUP_TYPE_ALIYUN)
internal val ONLINE_WORD_COUNT_FIELDS = listOf(
    "wordCount",
    "word_count",
    "wordText",
    "word_text",
    "word_number",
    "wordNum",
    "word_num",
    "words",
    "total_words",
    "totalWords",
)
internal val ONLINE_UPDATE_TIME_FIELDS = listOf(
    "updateTime",
    "updated_at",
    "update_time",
    "last_chapter_update_time",
    "last_chapter_first_pass_time",
    "latest_chapter_update_time",
    "latest_update_time",
    "firstPassTime",
    "first_pass_time",
)
internal val ONLINE_CHAPTER_COUNT_FIELDS = listOf(
    "chapterCount",
    "chapter_count",
    "chapter_count_total",
    "total_chapters",
    "totalChapter",
    "total_chapter",
    "chapter_num",
    "chapterNum",
    "chapters_count",
    "latest_chapter_index",
    "serial_count",
    "content_chapter_number",
    "real_chapter_order",
    "estimated_chapter_count",
)
internal val ONLINE_STATUS_TEXT_FIELDS = listOf(
    "status",
    "bookStatus",
    "book_status",
    "creation_status",
    "tomato_book_status",
    "serial_status",
    "is_finish",
    "isFinished",
    "finished",
    "complete",
    "completed",
)
internal val UUID_DIR_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
internal val ONLINE_CHAPTER_TITLE_REGEX = Regex("(第\\s*[0-9０-９一二三四五六七八九十百千万〇零两]+\\s*[章节卷回集部篇]|chapter\\s*\\d+)", RegexOption.IGNORE_CASE)
internal val ONLINE_CHAPTER_HEADING_SPLIT_REGEX =
    Regex("^(第\\s*[0-9０-９一二三四五六七八九十百千万〇零两]+\\s*[章节卷回集部篇])\\s*[:：/／、，,。.!！?？\\-—_；;]*\\s*(.+)$")
internal val ONLINE_SPECIAL_HEADING_SPLIT_REGEX =
    Regex("^(番外|后日谈|后记|序章|楔子|终章)\\s*[:：/／、，,。.!！?？\\-—_；;]*\\s*(.+)$")
internal val ONLINE_COMPLETION_PROGRESS_CHAPTER_REGEX =
    Regex("(?:下载|重试)?章节\\s*(\\d+)\\s*/\\s*(\\d+)")
internal val ONLINE_COMPLETION_FAILED_COUNT_REGEX = Regex("失败\\s*(\\d+)\\s*章")
internal val ONLINE_COMPLETION_CHAPTER_FILE_REGEX = Regex("""chapter_(\d+)\.xhtml""", RegexOption.IGNORE_CASE)
internal val ONLINE_COMPLETION_VOLUME_FILE_REGEX = Regex("""volume_(\d+)\.xhtml""", RegexOption.IGNORE_CASE)
// 英文省略号须由至少三个句点独占一行，避免把小数、正文或路径识别为分割线。
internal val ONLINE_DIVIDER_LINE_REGEX = Regex("""^(?:\.{3,}|[=_~*·•…⋯・◆◇■□●○※＊\-‐‑‒–—―]{2,})$""")
internal val ONLINE_COMPLETION_INLINE_STYLE_REGEX =
    Regex("""<style\s+type=["']text/css["']>[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
internal val ONLINE_COMPLETION_ESCAPED_ENTITY_REGEX = Regex(
    """&amp;(?:amp;)*(?:#(?:[xX][0-9A-Fa-f]+|\d+)|amp|lt|gt|quot|apos|nbsp|ldquo|rdquo|lsquo|rsquo|laquo|raquo|hellip|mdash|ndash|middot|bull|colon|semi|comma|period|excl|quest);""",
    RegexOption.IGNORE_CASE,
)
internal val ONLINE_COMPLETION_CHAPTER_HEADING_HTML_REGEX =
    Regex("""<h1([^>]*)>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
internal const val ONLINE_CHAPTER_TITLE_SCAN_LINES = 8
internal val BOOK_EXTENSIONS = setOf(".epub", ".mobi", ".azw3", ".txt")
internal val WEBDAV_UPLOAD_RETRY_CODES = setOf(405, 409, 412, 423)
internal const val WEBDAV_ICON_BODY_PATH = "M5 3.8h14c0.7 0 1.2 0.5 1.2 1.2v14c0 0.7-0.5 1.2-1.2 1.2H5c-0.7 0-1.2-0.5-1.2-1.2V5c0-0.7 0.5-1.2 1.2-1.2z"

/** 在线补全「更新」行使用的 Material refresh 24dp 路径。 */
internal const val REFRESH_ICON_PATH =
    "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8" +
        "c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6" +
        "s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
internal const val WEBDAV_ICON_CLOUD_PATH = "M8.6 13.8c-1.2 0-2.1-0.9-2.1-2s0.9-2 2.1-2c0.2 0 0.4 0 0.6 0.1C9.8 8.7 10.9 8 12.2 8c1.6 0 3 1.1 3.3 2.6c1 0.1 1.8 1 1.8 2c0 1.1-0.9 2-2 2H8.6z"
internal const val WEBDAV_ICON_SLOT_PATH = "M7 17h10v1.1H7z"
