package com.reamicro.fix.hook.webdav

import android.graphics.drawable.Icon
import com.reamicro.fix.core.HostClasses
import java.util.concurrent.atomic.AtomicBoolean

// WebDavDriveHook 与其外移出去的扩展函数共用的常量。
//
// 原先这些是 WebDavDriveHook 的 private companion object 成员。功能簇拆成同包扩展
// 函数后，companion 的 private 成员对扩展函数不可见，因此提升为顶层 internal 声明。
//
// 单独开一个子包而不是放在 com.reamicro.fix.hook 顶层：该包里已有文件用 private
// 顶层常量（如 LOG_PREFIX），顶层 internal 会与之冲突；Kotlin 又不允许对 object
// 做 star import，所以走「子包 + 包级 star import」这条路。
internal const val FEATURE_ID = "WebDavDriveHook"
internal const val LOG_TAG = "ReaMicroWebDAV"
internal const val LOG_PREFIX = "ReaMicro LSP"
// 暂停段评网络请求与缓存写入，保留实现供后续继续修复。
internal const val ONLINE_PARAGRAPH_COMMENTS_RUNTIME_ENABLED = false
// 关闭常规运行日志向 LSPosed 面板输出，避免刷屏；错误日志不受影响。
internal const val VERBOSE_WEBDAV_LOG = false
internal const val BACKUP_TYPE_CLASS = HostClasses.Host.BACKUP_TYPE
internal const val BACKUP_TYPE_NAME_METHOD = "getName"
internal const val AUTH_CARD_CLASS = HostClasses.Host.AUTH_CARD
internal const val AUTH_CARD_METHOD = "AuthCard"
internal const val AUTH_CARD_CONTENT_METHOD = "AuthCard\$lambda\$5"
internal const val DRIVE_AUTH_CARD_METHOD = "DriveAuthCard"
internal const val DRIVE_OTHER_AVAILABLE_CARD_METHOD = "DriveOtherAvailableCard"
internal const val BOOK_LIBRARY_SHEET_CLASS = HostClasses.Host.BOOK_LIBRARY_SHEET
internal const val BOOK_LIBRARY_AUTH_LIST_METHOD = "BookLibrarySheet\$lambda\$9\$0\$1"
internal const val BOOK_LIBRARY_AUTH_ROW_CLICK_METHOD = "BookLibrarySheet\$lambda\$9\$0\$1\$0\$1\$0\$0"
internal const val BOOK_LIBRARY_LOCAL_METHOD = "LocalLibrary"
internal const val BOOK_LIBRARY_UNAUTH_LIST_METHOD = "BookLibrarySheet\$lambda\$9\$0\$2\$0\$1"
internal const val CLOUD_AUTHORIZED_ROW_METHOD = "CloudAuthorizedRow"
internal const val CLOUD_AUTHORIZED_LABEL_METHOD = "CloudAuthorizedRow\$lambda\$1"
internal const val CLOUD_UNAUTH_ROW_METHOD = "CloudUnauthRow"
internal const val CLOUD_UNAUTH_LABEL_METHOD = "CloudUnauthRow\$lambda\$1"
internal const val CLOUD_AUTHORIZED_DETAIL_METHOD = "CloudAuthorizedRow\$lambda\$3"
internal const val CLOUD_STORAGE_SCREEN_CLASS = HostClasses.Host.CLOUD_STORAGE_SCREEN
internal const val CLOUD_STORAGE_SCREEN_METHOD = "CloudStorageScreen"
internal const val CLOUD_STORAGE_BAR_METHOD = "CloudStorageBar"
internal const val CLOUD_STORAGE_VIEW_MODEL_CLASS = HostClasses.Host.CLOUD_STORAGE_VIEW_MODEL
internal const val CLOUD_STORAGE_UI_STATE_CLASS = HostClasses.Host.CLOUD_STORAGE_UI_STATE
internal const val CLOUD_STORAGE_UI_EVENT_CLASS = HostClasses.Host.CLOUD_STORAGE_UI_EVENT
internal const val CLOUD_STORAGE_UI_EVENT_TAP_CLASS = "app.zhendong.reamicro.ui.storage.CloudStorageUIEvent\$Tap"
internal const val CLOUD_STORAGE_ON_INTENT_METHOD = "onIntent"
internal const val CLOUD_STORAGE_REPOSITORY_CLASS = HostClasses.Host.CLOUD_STORAGE_REPOSITORY
internal const val CLOUD_STORAGE_GET_AUTH = "getAuth"
internal const val CLOUD_STORAGE_GET_USER_INFO = "getUserInfo"
internal const val CLOUD_STORAGE_GET_LIBRARY = "getLibrary"
internal const val CLOUD_TREE_CLASS = HostClasses.Host.CLOUD_TREE
internal const val CLOUD_TREE_METHOD = "CloudTree"
internal const val HOME_VIEW_MODEL_CLASS = HostClasses.Host.HOME_VIEW_MODEL
internal const val HOME_SEARCH_METHOD = "search"
internal const val HOME_SEARCH_BAR_CLASS = HostClasses.Host.HOME_SEARCH_BAR
internal const val HOME_SEARCH_RESULT_LAZY_METHOD = "SearchResult\$lambda\$0\$0"
internal const val HOME_CLOUD_RESULT_LIST_METHOD = "CloudResultList"
internal const val HOME_CLOUD_BOOK_ROW_METHOD = "CloudBookRow"
internal const val HOME_SEARCH_TAP_METHOD = "SearchResult\$lambda\$0\$0\$1\$0\$0\$0\$0"
internal const val INTENT_RECEIVER_CLASS = HostClasses.Host.INTENT_RECEIVER
internal const val BOOK_LOCAL_SHEET_CLASS = HostClasses.Host.BOOK_LOCAL_SHEET
internal const val BOOK_LOCAL_SHEET_METHOD = "BookLocalSheet"
internal const val BOOK_LOCAL_SHEET_CONTENT_METHOD = "BookLocalSheet\$lambda\$2"
internal const val FILE_BACKUP_METHOD = "FileBackup"
internal const val BOOK_COMMUNITY_METHOD = "BookCommunity"
internal const val BOOK_PUBLISHER_METHOD = "BookPublisher"
internal const val BOOK_META_ACTION_ROW_METHOD = "BookMetaActionRow"
internal const val BOOK_ACTION_CELL_METHOD = "BookActionCell"
internal const val FOOTER_CLASS = HostClasses.Host.FOOTER
internal const val FOOTER_METHOD = "footer"
internal const val CLOUD_BOOK_LIST_CLASS = HostClasses.Host.CLOUD_BOOK_LIST
internal const val CLOUD_BOOK_ROW_METHOD = "CloudBookRow"
internal const val CLOUD_BOOK_CLASS = HostClasses.Host.CLOUD_BOOK
internal const val CLOUD_FOLDER_CLASS = HostClasses.Host.CLOUD_FOLDER
internal const val BOOK_CLASS = HostClasses.Host.BOOK
internal const val FILE_SOURCE_CLASS = HostClasses.Host.FILE_SOURCE
internal const val FILE_SOURCE_QUERY_NAME_METHOD = "queryName"
internal const val BOOK_ROW_INFO_CLASS = HostClasses.Host.BOOK_ROW_INFO
internal const val BOOK_ROW_INFO_METHOD = "BookRowInfo"
internal const val TIME_EXT_KT_CLASS = HostClasses.Host.TIME_EXT_KT
internal const val SECOND_TO_HOURS_METHOD = "secondToHours"
internal const val DRIVE_CARD_CLASS = HostClasses.Host.DRIVE_CARD
internal const val YUN115_NET_DISK_CARD_METHOD = "Yun115NetDiskCard"
internal const val BOOK_BACKUP_VIEW_MODEL_CLASS = HostClasses.Host.BOOK_BACKUP_VIEW_MODEL
internal const val BOOK_BACKUP_SCREEN_CLASS = HostClasses.Host.BOOK_BACKUP_SCREEN
internal const val BOOK_BACKUP_CONTENT_METHOD = "BookBackupContent"
internal const val BOOKSHELF_REPOSITORY_CLASS = HostClasses.Host.BOOKSHELF_REPOSITORY
internal const val BOOKSHELF_IMPORT_BOOK_METHOD = "importBook"
internal const val BOOKSHELF_UPDATE_BOOK_METHOD = "updateBook"
internal const val OPF_CLASS = HostClasses.Epub.OPF
internal const val EPUB_FILE_MANAGER_CLASS = HostClasses.Host.EPUB_FILE_MANAGER
internal const val EPUB_IMPORT_METHOD = "import"
internal const val OKIO_PATH_CLASS = HostClasses.ThirdParty.OKIO_PATH
internal const val WORKER_MANAGER_CLASS = HostClasses.Host.WORKER_MANAGER
internal const val WORK_TRACKER_CLASS = HostClasses.Host.WORK_TRACKER
internal const val WORKER_ENQUEUE_DOWNLOAD_METHOD = "enqueueDownload"
internal const val WORKER_ENQUEUE_BACKUP_METHOD = "enqueueBackup"
internal const val WORKER_ENQUEUE_IMPORT_METHOD = "enqueueImport"
internal const val WORK_HANDLE_CLASS = HostClasses.Host.WORK_HANDLE
internal const val WORK_STATE_CLASS = HostClasses.Host.WORK_STATE
internal const val WORK_STATUS_CLASS = HostClasses.Host.WORK_STATUS
internal const val NOT_AUTH_CLASS = HostClasses.Host.NOT_AUTH
internal const val NOT_AUTH_METHOD = "NotAuth"
internal const val APP_KT_CLASS = HostClasses.Host.APP_KT
internal const val NAV_GRAPH_SCOPE_CLASS = HostClasses.Host.NAV_GRAPH_SCOPE
internal const val NAVIGATE_METHOD = "navigate"
internal const val SETUP_ROUTE_METHOD_PREFIX = "setup\$lambda\$0\$"
internal const val ROUTE_HOME_CLASS = "app.zhendong.reamicro.Route\$Home"
internal const val ROUTE_STORAGE_CLASS = "app.zhendong.reamicro.Route\$Storage"
internal const val ROUTE_CLOUD_FOLDER_CLASS = "app.zhendong.reamicro.Route\$CloudFolder"
internal const val ROUTE_THIRD_LOGIN_CLASS = "app.zhendong.reamicro.Route\$ThirdLogin"
internal const val ROUTE_THIRD_ACCOUNT_CLASS = "app.zhendong.reamicro.Route\$ThirdAccount"
internal const val NAV_BACK_STACK_ENTRY_KT_CLASS = HostClasses.AndroidX.NAV_BACK_STACK_ENTRY_KT
internal const val NAV_BACK_STACK_ENTRY_TO_ROUTE_METHOD = "toRoute"
internal const val KOTLIN_REFLECTION_CLASS = HostClasses.Kotlin.KOTLIN_REFLECTION
internal const val STRING_RESOURCES_KT_CLASS = HostClasses.Compose.STRING_RESOURCES
internal const val STRING_RESOURCE_METHOD = "stringResource"
internal const val APP_TOP_BAR_CLASS = HostClasses.Host.APP_TOP_BAR
internal const val APP_TOP_BAR_METHOD = "AppTopBar"
internal const val KOTLIN_RESULT_CLASS = HostClasses.Kotlin.KOTLIN_RESULT
internal const val KOTLIN_RESULT_KT_CLASS = HostClasses.Kotlin.KOTLIN_RESULT_KT
internal const val KOTLIN_CONTINUATION_CLASS = HostClasses.Kotlin.KOTLIN_CONTINUATION
internal const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = HostClasses.Kotlin.KOTLIN_EMPTY_COROUTINE_CONTEXT
internal const val KOTLIN_INTRINSICS_CLASS = HostClasses.Kotlin.KOTLIN_INTRINSICS
internal const val KOTLIN_COROUTINE_SINGLETONS_CLASS = HostClasses.Kotlin.KOTLIN_COROUTINE_SINGLETONS
internal const val FLOW_KT_CLASS = "kotlinx.coroutines.flow.FlowKt"
internal const val FLOW_CLASS = "kotlinx.coroutines.flow.Flow"
internal const val STATE_FLOW_CLASS = "kotlinx.coroutines.flow.StateFlow"
internal const val STATE_FLOW_KT_CLASS = "kotlinx.coroutines.flow.StateFlowKt"
internal const val FLOW_OF_METHOD = "flowOf"
internal const val LOAD_STATES_CLASS = HostClasses.AndroidX.LOAD_STATES
internal const val PLATFORM_FILE_ANDROID_KT_CLASS = "io.github.vinceglb.filekit.PlatformFile_androidKt"
internal const val PLATFORM_FILE_METHOD = "PlatformFile"
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
internal const val BAIDU_ACCOUNT_DEFAULT_FOLDER_LAMBDA_METHOD = "BaiduNetDiskAccountScreen\$lambda\$0\$0\$1\$0\$0\$0"
internal const val BAIDU_VIEW_MODEL_CLASS = HostClasses.Host.BAIDU_VIEW_MODEL
internal const val BAIDU_VIEW_MODEL_GET_AUTH_METHOD = "getAuth"
internal const val Y115_ACCOUNT_SCREEN_CLASS = HostClasses.Host.Y115_ACCOUNT_SCREEN
internal const val Y115_ACCOUNT_SCREEN_METHOD = "Y115NetDiskAccountScreen"
internal const val Y115_ACCOUNT_DEFAULT_FOLDER_METHOD = "DefaultFolder"
internal const val Y115_ACCOUNT_LOGOUT_METHOD = "LogOut"
internal const val Y115_ACCOUNT_QUERY_ORDER_BY_METHOD = "QueryOrderBy"
internal const val Y115_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD = "QueryOrderDirection"
internal const val Y115_ACCOUNT_LIBRARY_BLOCK_METHOD = "Y115NetDiskAccountScreen\$lambda\$0\$0\$1\$1"
internal const val Y115_VIEW_MODEL_CLASS = HostClasses.Host.Y115_VIEW_MODEL
internal const val Y115_VIEW_MODEL_GET_AUTH_METHOD = "getAuth"
internal const val DIR_CLASS = HostClasses.Host.DIR
internal const val CLOUD_USER_INFO_CLASS = HostClasses.Host.CLOUD_USER_INFO
internal const val PAGING_DATA_CLASS = HostClasses.AndroidX.PAGING_DATA
internal const val PAGING_DATA_EMPTY_METHOD = "empty"
internal const val BAIDU_ICON_CLASS = HostClasses.Host.BAIDU_ICON
internal const val BAIDU_ICON_METHOD = "getBaiduNetdisk"
internal const val YUN115_ICON_CLASS = HostClasses.Host.YUN115_ICON
internal const val YUN115_ICON_METHOD = "getYun115"
internal const val FILE_FOLDER_ICON_CLASS = HostClasses.Host.FILE_FOLDER_ICON
internal const val FILE_FOLDER_ICON_METHOD = "getFileFolder"
internal const val CLOUD_ROW_ICON_CLASS = HostClasses.Host.BOOK_ROW_INFO
internal const val CLOUD_ROW_ICON_METHOD = "getIconForFileType"
internal const val ANDROID_OS_ICON_CLASS = HostClasses.Host.ANDROID_OS_ICON
internal const val ANDROID_OS_ICON_METHOD = "getAndroidOs"
internal const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
internal const val FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
internal const val FUNCTION2_CLASS = HostClasses.Kotlin.FUNCTION2
internal const val FUNCTION3_CLASS = HostClasses.Kotlin.FUNCTION3
internal const val COMPOSER_CLASS = HostClasses.Compose.COMPOSER
internal const val KOTLIN_PAIR_CLASS = HostClasses.Kotlin.KOTLIN_PAIR
internal const val ROW_KT_CLASS = HostClasses.Compose.ROW_KT
internal const val ROW_METHOD = "Row"
internal const val ARRANGEMENT_CLASS = HostClasses.Compose.ARRANGEMENT
internal const val ALIGNMENT_CLASS = HostClasses.Compose.ALIGNMENT
internal const val TEXT_OVERFLOW_CLASS = HostClasses.Compose.TEXT_OVERFLOW
internal const val ICON_KT_CLASS = HostClasses.Compose.ICON_KT
internal const val ICON_METHOD = "Icon-ww6aTOc"
internal const val IMAGE_VECTOR_CLASS = HostClasses.Compose.IMAGE_VECTOR
internal const val EDIT_ICON_CLASS = HostClasses.Compose.EDIT_ICON
internal const val NAVIGATE_NEXT_ICON_CLASS = HostClasses.Compose.NAVIGATE_NEXT_ICON
internal const val ICONS_FILLED_CLASS = "androidx.compose.material.icons.Icons\$Filled"
internal const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
internal const val ICONS_AUTO_MIRRORED_FILLED_CLASS = "androidx.compose.material.icons.Icons\$AutoMirrored\$Filled"
internal const val MATERIAL3_TEXT_METHOD = "Text-Nvy7gAk"
internal const val MATERIAL_THEME_CLASS = HostClasses.Compose.MATERIAL_THEME
internal const val THEME_KT_CLASS = HostClasses.Host.THEME_KT
internal const val CLICKABLE_KT_CLASS = HostClasses.Compose.CLICKABLE_KT
internal const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
internal const val IMAGE_VECTOR_BUILDER_CLASS = "androidx.compose.ui.graphics.vector.ImageVector\$Builder"
internal const val VECTOR_KT_CLASS = HostClasses.Compose.VECTOR_KT
internal const val COLOR_KT_CLASS = HostClasses.Compose.COLOR_KT
internal const val SOLID_COLOR_CLASS = HostClasses.Compose.SOLID_COLOR
internal const val DEFAULT_CONSTRUCTOR_MARKER_CLASS = HostClasses.Kotlin.DEFAULT_CONSTRUCTOR_MARKER
internal const val ANDROID_VIEW_KT_CLASS = HostClasses.Compose.ANDROID_VIEW_KT
internal const val ANDROID_VIEW_METHOD = "AndroidView"
internal const val MATERIAL3_TEXT_CLASS = HostClasses.Compose.TEXT_KT
internal const val DIVIDER_KT_CLASS = HostClasses.Host.DIVIDER_KT
internal const val SIMPLE_DIVIDER_METHOD = "SimpleDivider-iJQMabo"
internal const val BOX_KT_CLASS = HostClasses.Compose.BOX_KT
internal const val BOX_METHOD = "Box"
internal const val BACKGROUND_KT_CLASS = HostClasses.Compose.BACKGROUND_KT
internal const val MODIFIER_CLASS = HostClasses.Compose.MODIFIER
internal const val SIZE_KT_CLASS = HostClasses.Compose.SIZE_KT
internal const val SIZE_METHOD = "size-3ABfNKs"
internal const val FILL_MAX_SIZE_METHOD = "fillMaxSize"
internal const val FILL_MAX_WIDTH_METHOD = "fillMaxWidth"
internal const val PADDING_KT_CLASS = HostClasses.Compose.PADDING_KT
internal const val PADDING_METHOD = "padding-qDBjuR0"
internal const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
internal const val UNIT_EXT_KT_CLASS = HostClasses.Host.UNIT_EXT_KT
internal const val UDP_METHOD = "getUdp"
internal const val TEXT_DEFAULT_MASK_WITH_MODIFIER = 131064
internal const val TEXT_SECONDARY_SINGLE_LINE_MASK = 110586
internal const val OKHTTP_CLIENT_CLASS = HostClasses.ThirdParty.OKHTTP_CLIENT
internal const val OKHTTP_REQUEST_CLASS = HostClasses.ThirdParty.OKHTTP_REQUEST
internal const val OKHTTP_REQUEST_BUILDER_CLASS = "okhttp3.Request\$Builder"
internal const val OKHTTP_REQUEST_BODY_CLASS = HostClasses.ThirdParty.OKHTTP_REQUEST_BODY
internal const val OKHTTP_MEDIA_TYPE_CLASS = HostClasses.ThirdParty.OKHTTP_MEDIA_TYPE
internal const val NETWORK_SECURITY_POLICY_CLASS = "android.security.NetworkSecurityPolicy"
internal const val ANDROID_OKHTTP_CLEARTEXT_FILTER_CLASS = "com.android.okhttp.HttpHandler\$CleartextURLFilter"
internal const val ANDROID_OKHTTP_PLATFORM_CLASS = "com.android.okhttp.internal.Platform"
internal const val OKHTTP_PLATFORM_CLASS = HostClasses.ThirdParty.OKHTTP_PLATFORM
internal const val OKHTTP_ANDROID_PLATFORM_CLASS = HostClasses.ThirdParty.OKHTTP_ANDROID_PLATFORM
internal const val OKHTTP_ANDROID10_PLATFORM_CLASS = HostClasses.ThirdParty.OKHTTP_ANDROID10_PLATFORM
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
internal const val LOCAL_LIBRARY_SEARCH_REFRESH_DELAY_MS = 1_600L
internal const val LOCAL_LIBRARY_SEARCH_LATE_REFRESH_DELAY_MS = 4_500L
internal const val HOME_SEARCH_RESULT_LIMIT = 10
internal const val ONLINE_COMPLETION_RESULT_LIMIT = 8
internal const val ONLINE_COMPLETION_MAX_CHAPTERS = 500
internal const val ONLINE_COMPLETION_SEARCH_TIMEOUT_MS = 8_000L
internal const val ONLINE_COMPLETION_SEARCH_METADATA_TIMEOUT_MS = 6_000L
internal const val ONLINE_COMPLETION_SEARCH_METADATA_ENRICH_LIMIT = 8
internal const val ONLINE_COMPLETION_PARTIAL_IMPORT_THRESHOLD = 200
internal const val ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS = 100
internal const val ONLINE_COMPLETION_ON_DEMAND_INITIAL_CHAPTERS = 3
internal const val ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT = 15
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
internal val ONLINE_DIVIDER_LINE_REGEX = Regex("""^[=_~*·•…⋯・◆◇■□●○※＊\-‐‑‒–—―]{2,}$""")
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
internal const val WEBDAV_PROPFIND_BODY =
    """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
internal const val WEBDAV_ICON_BODY_PATH = "M5 3.8h14c0.7 0 1.2 0.5 1.2 1.2v14c0 0.7-0.5 1.2-1.2 1.2H5c-0.7 0-1.2-0.5-1.2-1.2V5c0-0.7 0.5-1.2 1.2-1.2z"
internal const val WEBDAV_ICON_CLOUD_PATH = "M8.6 13.8c-1.2 0-2.1-0.9-2.1-2s0.9-2 2.1-2c0.2 0 0.4 0 0.6 0.1C9.8 8.7 10.9 8 12.2 8c1.6 0 3 1.1 3.3 2.6c1 0.1 1.8 1 1.8 2c0 1.1-0.9 2-2 2H8.6z"
internal const val WEBDAV_ICON_SLOT_PATH = "M7 17h10v1.1H7z"
