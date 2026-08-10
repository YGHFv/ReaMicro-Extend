package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.View
import com.reamicro.fix.core.ComposeInterop
import com.reamicro.fix.online.OnlineConcurrentRateLimiter
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.webdav.CleartextScope
import com.reamicro.fix.webdav.CloudBookRowExtendedDisplayContext
import com.reamicro.fix.webdav.CancellableWebDavDownload
import com.reamicro.fix.webdav.OnlineFanqieBatchProtocolException
import com.reamicro.fix.webdav.OnlineSourceHttpException
import com.reamicro.fix.webdav.buildOnlineFanqieBatchBody
import com.reamicro.fix.webdav.buildOnlineFanqieBatchEndpoint
import com.reamicro.fix.webdav.parseOnlineFanqieBatchChapterRef
import com.reamicro.fix.webdav.parseOnlineFanqieBatchResponse
import com.reamicro.fix.webdav.supportsOnlineFanqieBatch
import com.reamicro.fix.webdav.HomeSearchRenderContext
import com.reamicro.fix.webdav.ImportLocalLibraryRowContext
import com.reamicro.fix.webdav.ImportUnauthRenderContext
import com.reamicro.fix.webdav.NativeCloudDownload
import com.reamicro.fix.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.webdav.SyncAuthCardRenderContext
import com.reamicro.fix.webdav.WebDavBackupSnapshot
import com.reamicro.fix.webdav.WebDavImportSource
import com.reamicro.fix.webdav.WebDavRemoteClient
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav

/**
 * Cloud/local-library integration hook.
 *
 * This class is intentionally broad because it coordinates host cloud UI injection,
 * WebDAV/Alist access, import/export jobs, and online-completion downloads that share
 * the same reflected host repositories and notification flow.
 */
class WebDavDriveHook(
    internal val classLoader: ClassLoader,
    internal val activityProvider: () -> Activity?,
    internal val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    // Compose 反射互操作的共用实现，避免各 hook 各存一份逐渐漂移的副本。
    internal val composeInterop = ComposeInterop(
        classLoader = classLoader,
        resolveClass = ::cls,
        unitInstance = ::targetUnit,
        logPrefix = LOG_PREFIX,
    )

    internal val methodCache = mutableMapOf<String, Method>()
    // Compose renders nested lambdas without passing enough host context to later hooks.
    // ThreadLocal render contexts mark the current host row/screen while its children compose.
    internal val syncAuthCardRender = ThreadLocal<SyncAuthCardRenderContext?>()
    internal val importUnauthRender = ThreadLocal<ImportUnauthRenderContext?>()
    internal val importLocalLibraryRow = ThreadLocal<ImportLocalLibraryRowContext?>()
    internal val webDavIconDepth = ThreadLocal<Int>()
    internal val syncAvailableTypes = ThreadLocal<List<Int>?>()
    internal val syncAvailableRowIndex = ThreadLocal<Int>()
    internal val webDavCloudTitleDepth = ThreadLocal<Int>()
    internal val webDavCloudScreenDepth = ThreadLocal<Int>()
    internal val webDavCloudTreeDepth = ThreadLocal<Int>()
    internal val webDavAccountScreenDepth = ThreadLocal<Int>()
    internal val webDavAccountNavGraphScope = ThreadLocal<Any?>()
    internal val localLibraryIconDepth = ThreadLocal<Int>()
    internal val localLibraryCloudTitleDepth = ThreadLocal<Int>()
    internal val localLibraryCloudScreenDepth = ThreadLocal<Int>()
    internal val onlineCompletionCloudTitleDepth = ThreadLocal<Int>()
    internal val onlineCompletionCloudTitleText = ThreadLocal<String?>()
    internal val onlineCompletionRenderTypesBySource = ConcurrentHashMap<String, Int>()
    internal val onlineCompletionRenderSourceByType = ConcurrentHashMap<Int, String>()
    internal val onlineCompletionRenderTitles = ConcurrentHashMap<Int, String>()
    internal val localLibraryCloudTreeDepth = ThreadLocal<Int>()
    internal val localLibraryAccountScreenDepth = ThreadLocal<Int>()
    internal val localLibraryAccountNavGraphScope = ThreadLocal<Any?>()
    internal val localLibraryAuthorizedDetailDepth = ThreadLocal<Int>()
    internal val localLibraryDefaultFolderDepth = ThreadLocal<Int>()
    internal val localLibraryDefaultFolderStringIndex = ThreadLocal<Int>()
    internal val webDavNotAuthDepth = ThreadLocal<Int>()
    internal val webDavNotAuthTipPending = ThreadLocal<Boolean>()
    internal val syncWebDavAuthRenderedBeforeAvailable = ThreadLocal<Boolean>()
    internal val webDavRenderingSyncAuthCard = ThreadLocal<Boolean>()
    internal val syncAuthCardContentOwnsContext = ThreadLocal<Boolean>()
    internal val webDavRunningBackupPending = ThreadLocal<Boolean>()
    internal val webDavRunningSyncAuthCardDepth = ThreadLocal<Int>()
    internal val homeWebDavSearchRender = ThreadLocal<HomeSearchRenderContext?>()
    internal val cloudBookRowExtendedDisplay = ThreadLocal<CloudBookRowExtendedDisplayContext?>()
    internal val onlineCompletionBookRowInfoDepth = ThreadLocal<Int>()
    internal val onlineCompletionLocalSheetBook = ThreadLocal<Any?>()
    internal val onlineCompletionLocalSheetDepth = ThreadLocal<Int>()
    internal val onlineCompletionUpdateRowInjecting = ThreadLocal<Boolean>()
    @Volatile internal var onlineCompletionCombinedGroupRowHooked = false
    @Volatile internal var onlineCompletionSourceLabelHooked = false
    internal val webDavBackupCardDepth = ThreadLocal<Int>()
    internal var webDavAccountNavGraphScopeStrong: Any? = null
    internal var localLibraryAccountNavGraphScopeStrong: Any? = null
    internal var webDavAccountNavGraphScopeRef: WeakReference<Any>? = null
    internal var localLibraryAccountNavGraphScopeRef: WeakReference<Any>? = null
    @Volatile internal var webDavAccountRouteRenderAtMs: Long = 0L
    @Volatile internal var localLibraryAccountRouteRenderAtMs: Long = 0L
    internal var composeWebDavVector: Any? = null
    internal var webDavLoginDialog: Dialog? = null
    internal var webDavAuthDialog: Dialog? = null
    internal val webDavLibraryLock = Any()
    internal var webDavLibraryFlow: Any? = null
    internal val localLibraryLock = Any()
    internal var localLibraryFlow: Any? = null
    internal val webDavAccountAuthLock = Any()
    internal var webDavAccountAuthFlow: Any? = null
    internal val localLibraryAccountAuthLock = Any()
    internal var localLibraryAccountAuthFlow: Any? = null
    internal val webDavCleartextAllowed = ThreadLocal<Boolean>()
    internal val webDavCleartextAllowedHosts = ConcurrentHashMap<String, Boolean>()
    internal val webDavCleartextAllowedLoggedHosts = ConcurrentHashMap<String, Boolean>()
    internal val webDavRemoteClient by lazy {
        WebDavRemoteClient(
            classLoader = classLoader,
            log = ::logWebDav,
            cleartextScope = object : CleartextScope {
                override fun <T> run(block: () -> T): T = withWebDavCleartextAllowed(block)
            },
        )
    }
    internal val webDavStorageViewModels = mutableListOf<WeakReference<Any>>()
    internal var cloudStorageRepositoryRef: WeakReference<Any>? = null
    internal var bookshelfRepositoryRef: WeakReference<Any>? = null
    internal var workerManagerRef: WeakReference<Any>? = null
    internal var workTrackerRef: WeakReference<Any>? = null
    internal val pendingWebDavImports = ConcurrentHashMap<String, WebDavImportSource>()
    internal val runningWebDavDownloads = ConcurrentHashMap<String, CancellableWebDavDownload>()
    internal val runningNativeCloudDownloads = ConcurrentHashMap<String, NativeCloudDownload>()
    internal val runningNativeCloudDownloadsById = ConcurrentHashMap<String, NativeCloudDownload>()
    internal val webDavDownloadCancelPrompts = ConcurrentHashMap<String, Long>()
    internal val webDavHomeSearchSeq = AtomicLong(0L)
    internal val localLibraryHomeSearchSeq = AtomicLong(0L)
    internal val onlineCompletionHomeSearchSeq = AtomicLong(0L)
    internal val onlineCompletionSearchTargets = ConcurrentHashMap<String, OnlineDownloadTarget>()
    internal val onlineCompletionSearchRowViews = ConcurrentHashMap<String, WeakReference<View>>()
    internal val onlineCompletionRunningDownloads = ConcurrentHashMap<String, OnlineCompletionDownloadTask>()
    internal val onlineCompletionRunningDownloadsByNotificationId = ConcurrentHashMap<Int, OnlineCompletionDownloadTask>()
    internal val onlineCompletionRunningUpdates = ConcurrentHashMap<String, Boolean>()
    internal val onlineCompletionImportLock = ReentrantLock(true)
    internal val onlineCompletionNotificationIds = AtomicInteger(4300)
    internal val onlineCompletionNotificationBlockedLogged = AtomicBoolean(false)
    internal val onlineCompletionCancelReceiverRegistered = AtomicBoolean(false)
    internal val onlineCompletionModuleActivityPromptAt = ConcurrentHashMap<Int, Long>()
    internal val onlineCompletionMetadataRepairAt = AtomicLong(0L)
    @Volatile internal var lastHomeSearchWebDavResults: List<Any> = emptyList()
    @Volatile internal var lastHomeSearchLocalResults: List<Any> = emptyList()
    internal val cloudStorageScreenRefreshAt = ConcurrentHashMap<String, Long>()
    internal val localLibraryListCache = ConcurrentHashMap<String, LocalLibraryListCache>()
    internal val localLibrarySearchIndexLock = Any()
    @Volatile internal var localLibrarySearchIndex: LocalLibrarySearchIndex? = null
    @Volatile internal var localLibrarySearchIndexBuilding: Boolean = false
    internal val webDavBackupViewModels = mutableListOf<WeakReference<Any>>()
    internal val webDavRunningBackupBookIds = ConcurrentHashMap<Long, Boolean>()
    internal val webDavRecentBackups = ConcurrentHashMap<Long, WebDavBackupSnapshot>()
    internal val bookBackupContentLogSeq = AtomicLong(0L)

    internal val HTTP_HEADER_NAME_REGEX = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")

    internal val FANQIE_IMAGE_PATH_REGEX = Regex("""(?i)(?:^|/)novel-(?:images|pic|static)/""")

    internal inner class OnlineCompletionProgressNotifier(
        private val context: Context,
        private val notificationId: Int,
        private val notificationKey: String,
        private val cancellable: Boolean,
        private val bookName: String,
        private val tracker: Any?,
        private val workId: String?,
    ) {
        private var lastNotificationAtMs = 0L
        private var lastProgress = -1
        private var lastMessage = ""

        @Synchronized
        fun running(progress: Int, message: String, force: Boolean = false): Boolean {
            val now = System.currentTimeMillis()
            val compactMessage = message.replace(Regex("\\s+"), " ").trim()
            val displayProgress = onlineCompletionDisplayProgress(progress, compactMessage)
            val progressChanged = displayProgress != lastProgress
            val failedCountChanged = onlineCompletionFailedCount(compactMessage) != onlineCompletionFailedCount(lastMessage)
            val phaseChanged = compactMessage != lastMessage &&
                !compactMessage.startsWith("下载章节 ") &&
                !compactMessage.startsWith("重试章节 ")
            val shouldSend = force ||
                progressChanged ||
                failedCountChanged ||
                phaseChanged ||
                now - lastNotificationAtMs >= ONLINE_COMPLETION_NOTIFICATION_MIN_INTERVAL_MS ||
                displayProgress >= 100
            if (!shouldSend) {
                return true
            }
            lastNotificationAtMs = now
            lastProgress = displayProgress
            lastMessage = compactMessage
            if (tracker != null && workId != null) {
                setTrackedWorkState(tracker, workId, "Running", displayProgress, null, null, "$bookName：$compactMessage")
            }
            logWebDav(
                "online completion progress notify id=$notificationId progress=$displayProgress raw=$progress " +
                    "force=$force progressChanged=$progressChanged failedCountChanged=$failedCountChanged " +
                    "phaseChanged=$phaseChanged text=$compactMessage",
            )
            return updateOnlineCompletionNotification(
                context,
                notificationId,
                notificationKey,
                cancellable,
                bookName,
                compactMessage,
                displayProgress,
                false,
            )
        }

        @Synchronized
        fun finish(message: String, data: String?, success: Boolean) {
            if (tracker != null && workId != null) {
                setTrackedWorkState(
                    tracker,
                    workId,
                    if (success) "Success" else "Error",
                    100,
                    if (success) null else message,
                    data,
                    bookName,
                )
            }
            updateOnlineCompletionNotification(context, notificationId, notificationKey, cancellable, bookName, message, 100, true)
        }
    }

    internal inner class OnlineChapterBatchSession(
        private val target: OnlineDownloadTarget,
        private val chapters: List<OnlineChapter>,
        private val eligibleIndices: Set<Int>,
    ) {
        private val cachedBodies = mutableMapOf<String, String>()
        private val attemptedItemIds = mutableSetOf<String>()
        private var disabled = !supportsOnlineFanqieBatch(
            sourceUrl = target.source.sourceUrl,
            endpointTemplate = target.source.chapterBatchEndpoint,
            batchSize = target.source.chapterBatchSize,
        )

        fun bodyFor(index: Int, chapter: OnlineChapter): String? {
            if (disabled || index !in eligibleIndices) return null
            val current = parseOnlineFanqieBatchChapterRef(chapter.url) ?: return null
            cachedBodies.remove(current.itemId)?.let { return it }
            if (current.itemId in attemptedItemIds) return null
            val batch = eligibleIndices.asSequence()
                .filter { it >= index && it in chapters.indices }
                .sorted()
                .mapNotNull { chapterIndex ->
                    parseOnlineFanqieBatchChapterRef(chapters[chapterIndex].url)
                        ?.takeIf { it.bookId == current.bookId }
                }
                .distinctBy { it.itemId }
                .take(target.source.chapterBatchSize)
                .toList()
            if (batch.isEmpty()) return null
            val endpoint = buildOnlineFanqieBatchEndpoint(
                sourceUrl = target.source.sourceUrl,
                endpointTemplate = target.source.chapterBatchEndpoint,
                bookId = current.bookId,
            )
            try {
                attemptedItemIds.addAll(batch.map { it.itemId })
                val response = OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                    requestOnlineSearch(
                        target.source,
                        OnlineSearchRequest(
                            url = endpoint,
                            method = "POST",
                            body = buildOnlineFanqieBatchBody(batch.map { it.itemId }),
                            headers = mapOf(
                                "Accept" to "application/json",
                                "Content-Type" to "application/json",
                            ),
                        ),
                    )
                }
                val requestedItemIds = batch.mapTo(hashSetOf()) { it.itemId }
                val parsed = parseOnlineFanqieBatchResponse(response.body)
                    .filterKeys { it in requestedItemIds }
                cachedBodies.putAll(parsed)
                logWebDav(
                    "online completion fanqie batch downloaded requested=${batch.size} " +
                        "returned=${parsed.size} bookId=${current.bookId} " +
                        "range=${index + 1}-${index + batch.size}",
                )
                return cachedBodies.remove(current.itemId) ?: run {
                    logWebDav(
                        "online completion fanqie batch missing current itemId=${current.itemId}; " +
                            "falling back to single chapter",
                    )
                    null
                }
            } catch (error: Throwable) {
                val unsupported = error is OnlineFanqieBatchProtocolException ||
                    (error is OnlineSourceHttpException && error.statusCode in setOf(400, 404, 405, 415))
                if (!unsupported) {
                    attemptedItemIds.removeAll(batch.map { it.itemId }.toSet())
                    throw error
                }
                disabled = true
                cachedBodies.clear()
                logWebDav(
                    "online completion fanqie batch disabled for current task " +
                        "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                return null
            }
        }
    }

}
