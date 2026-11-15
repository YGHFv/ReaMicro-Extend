package com.reamicro.fix.hook

import com.reamicro.fix.core.hostMethod

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Base64
import android.util.Xml
import androidx.core.content.ContextCompat
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.online.OnlineConcurrentRateLimiter
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceScriptCompat
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.online.download.OnlineOnDemandBridge
import com.reamicro.fix.xposed.HookRuntime
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import org.xmlpull.v1.XmlPullParser
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.onlineFirstJsonString
import com.reamicro.fix.online.search.onlineCompletionSearchMetaLine
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.parseOnlineJsonRoot
import com.reamicro.fix.online.search.onlineJsonString
import com.reamicro.fix.online.download.ensureNcxSpineToc
import com.reamicro.fix.online.download.cancelOnlineCompletionNotification
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.cloud.webdav.normalizeWebDavPath
import com.reamicro.fix.cloud.webdav.webDavBookUrl
import com.reamicro.fix.logging.logWebDav

internal fun WebDavDriveHook.install() {
    logWebDav("install start")
    WebDavDriveHook.activeInstance = this
    val parserModeObserver = com.reamicro.fix.online.OnlineParserMode.observeChanges {
        onlineSearchTasks.cancelAll()
        onlineCompletionSearchTargets.clear()
        onlineCompletionSearchRowViews.clear()
    }
    val onDemandAttachment = OnlineOnDemandBridge.attach(::downloadOnlineCompletionOnDemandChapter)
    com.reamicro.fix.reload.ModuleResources.cleanup {
        try {
            parserModeObserver.close()
            closeStorageForReload()
        } finally {
            onDemandAttachment.close()
            if (WebDavDriveHook.activeInstance === this) WebDavDriveHook.activeInstance = null
        }
    }

    HookInstallReport.installAll(
        FEATURE_ID,
        listOf(
            "webDavCleartextPolicy" to ::hookWebDavCleartextPolicy,
            "backupTypeName" to ::hookBackupTypeName,
            "onlineCompletionBookLocalSheet" to ::hookOnlineCompletionBookLocalSheet,
            "homeCloudResultListRenderContext" to ::hookHomeCloudResultListRenderContext,
            "onlineCompletionHomeCloudBookRow" to ::hookOnlineCompletionHomeCloudBookRow,
            "homeViewModelDependencies" to ::hookHomeViewModelDependencies,
            "webDavRowIcon" to ::hookWebDavRowIcon,
            "webDavYun115Icon" to ::hookWebDavYun115Icon,
            "storagePresentation" to { StoragePresentationBridge(this).install() },
            "localLibraryFolderPickerResult" to ::hookLocalLibraryFolderPickerResult,
            "webDavAccountScreenReuse" to ::hookWebDavAccountScreenReuse,
            "webDavAccountSettingComponents" to ::hookWebDavAccountSettingComponents,
            "webDavAccountAuthFlow" to ::hookWebDavAccountAuthFlow,
            "webDavAccountTopBarTitle" to ::hookWebDavAccountTopBarTitle,
            "webDavAccountDefaultFolderLambda" to ::hookWebDavAccountDefaultFolderLambda,
            "cloudStorageWebDavData" to ::hookCloudStorageWebDavData,
            "cloudStorageWebDavStrings" to ::hookCloudStorageWebDavStrings,
            "cloudStorageWebDavTitle" to ::hookCloudStorageWebDavTitle,
            "cloudStorageWebDavRefresh" to ::hookCloudStorageWebDavRefresh,
            "cloudStorageWebDavAuthTips" to ::hookCloudStorageWebDavAuthTips,
            "cloudStorageWebDavViewModelState" to ::hookCloudStorageWebDavViewModelState,
            "webDavCloudTap" to ::hookWebDavCloudTap,
            "nativeCloudDownloadCancellation" to ::hookNativeCloudDownloadCancellation,
            "syncAuthCard" to ::hookSyncAuthCard,
            "importCloudAuthorizedRow" to ::hookImportCloudAuthorizedRow,
            "importCloudUnauthRow" to ::hookImportCloudUnauthRow,
            "webDavCloudDownload" to ::hookWebDavCloudDownload,
            "webDavImportBookSource" to ::hookWebDavImportBookSource,
            "webDavCloudBackup" to ::hookWebDavCloudBackup,
            "bookBackupViewModelState" to ::hookBookBackupViewModelState,
            "bookBackupWebDavCard" to ::hookBookBackupWebDavCard,
            "webDavYun115BackupCard" to ::hookWebDavYun115BackupCard,
            "homeWebDavSearch" to ::hookHomeWebDavSearch,
            "homeSearchTapCancellation" to ::hookHomeSearchTapCancellation,
            "homeSearchResultWebDavSection" to ::hookHomeSearchResultWebDavSection,
        ),
    )
    HookInstallReport.install(FEATURE_ID, "storageRoutes") { HostStorageRouteBridge(this).install() }
    HookInstallReport.install(FEATURE_ID, "storageOperations") { HostStorageOperations(this).install() }
    HookInstallReport.install(FEATURE_ID, "storageFolderSelection") { HostFolderSelectionBridge(this).install() }
    HookInstallReport.install(FEATURE_ID, "storageUi") { HostStorageUiBridge(this).install() }
}

internal fun WebDavDriveHook.ensureOnlineCompletionCancelReceiver(context: Context) {
    if (!onlineCompletionCancelReceiverRegistered.compareAndSet(false, true)) return
    runCatching {
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    ONLINE_COMPLETION_CANCEL_ACTION -> {
                        val id = intent.getIntExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, 0)
                        val key = intent.getStringExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY).orEmpty()
                        requestOnlineCompletionDownloadCancel(receiverContext, id, key)
                    }
                    com.reamicro.fix.notification.OnlineCompletionNotificationActions.PAUSE -> {
                        val id = intent.getIntExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, 0)
                        val key = intent.getStringExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY).orEmpty()
                        requestOnlineCompletionDownloadPause(receiverContext, id, key)
                    }
                    ONLINE_COMPLETION_HEARTBEAT_ACTION -> {
                        val id = intent.getIntExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, 0)
                        val key = intent.getStringExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY).orEmpty()
                        val download = onlineCompletionRunningDownloadsByNotificationId[id]
                        val alive = download?.let { it.key == key && !it.control.isFinished } == true ||
                            (key.startsWith("update:") &&
                                onlineCompletionUpdateNotificationIds[key.removePrefix("update:")] == id &&
                                onlineCompletionUpdateControls[key.removePrefix("update:")]?.isFinished == false)
                        if (alive) runCatching {
                            receiverContext.sendBroadcast(Intent("com.reamicro.fix.ONLINE_COMPLETION_HEARTBEAT_ACK").apply {
                                setPackage(MODULE_PACKAGE_NAME)
                                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
                                putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
                            })
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ONLINE_COMPLETION_CANCEL_ACTION)
            addAction(com.reamicro.fix.notification.OnlineCompletionNotificationActions.PAUSE)
            addAction(ONLINE_COMPLETION_HEARTBEAT_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        com.reamicro.fix.reload.ModuleResources.cleanup {
            try {
                appContext.unregisterReceiver(receiver)
            } finally {
                onlineCompletionCancelReceiverRegistered.set(false)
            }
        }
        logWebDav("online completion cancel receiver registered package=${appContext.packageName}")
    }.onFailure {
        onlineCompletionCancelReceiverRegistered.set(false)
        HookRuntime.log("$LOG_PREFIX online completion cancel receiver failed: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.onHostActivityCreated(activity: Activity) {
    if (storageClosed || storageActivityRef?.get() === activity) return
    if (storageActivityRef != null) {
        pauseStorageQueries()
        releaseStorageUiReferences()
    }
    storageActivityRef = WeakReference(activity)
    storagePaused = true
}

internal fun WebDavDriveHook.onHostActivityResumed(activity: Activity) {
    if (storageClosed) return
    onHostActivityCreated(activity)
    val wasPaused = storagePaused
    storagePaused = false
    if (!settingsProvider().moduleEnabled) return
    if (!wasPaused) return
    if (onlineSearchTasksState.isInitialized()) onlineSearchTasks.resume()
    if (homeSearchState.isInitialized()) homeSearch.resume()
    if (synchronized(webDavLibraryLock) { webDavLibraryFlow != null }) refreshWebDavLibraryAsync()
    if (synchronized(localLibraryLock) { localLibraryFlow != null }) refreshLocalLibraryAsync()
    webDavRecentBackups.forEach { (bookId, value) ->
        updateWebDavBackupViewModels(bookId, value.book, value.backup)
    }
}

/** Immediately depart the old execution, without retiring the installed hooks or touching UI. */
internal fun WebDavDriveHook.invalidateExecution() {
    if (storageClosed) return
    storageExecutionGeneration.incrementAndGet()
    storageUiGeneration.incrementAndGet()
    localLibraryRefreshSequence.incrementAndGet()
    webDavRefreshSequence.incrementAndGet()
    webDavConnectionGeneration.incrementAndGet()
    synchronized(localLibrarySearchIndexLock) {
        localLibraryCacheGeneration.incrementAndGet()
        localLibraryIndexRequest = null
        localLibrarySearchIndex = null
        localLibraryListCache.clear()
    }
    if (homeSearchState.isInitialized()) homeSearch.invalidateExecution()
    else webDavHomeSearchSeq.incrementAndGet()
    if (localRefreshWorkerState.isInitialized()) localRefreshWorker.cancel()
    if (webDavRefreshWorkerState.isInitialized()) webDavRefreshWorker.cancel()
    if (localIndexWorkerState.isInitialized()) localIndexWorker.cancel()
    if (onlineSearchTasksState.isInitialized()) onlineSearchTasks.pause()
    runningWebDavDownloads.values.forEach { token ->
        token.cancelRequested = true
        token.thread?.interrupt()
    }
    onlineCompletionRunningDownloads.values.forEach { task ->
        task.cancelRequested = true
        task.cancellation.cancel()
        task.thread?.interrupt()
    }
    onlineCompletionRunningUpdates.values.forEach(TaskCancellation::cancel)
}

/** Pause this feature without retiring its hook generation, so enabling can start fresh work. */
internal fun WebDavDriveHook.onModuleEnabledChanged(enabled: Boolean) {
    if (storageClosed) return
    if (!enabled) {
        invalidateExecution()
    }
    val action = {
        if (!storageClosed) {
            if (enabled) {
                if (!storagePaused && settingsProvider().moduleEnabled) {
                    if (onlineSearchTasksState.isInitialized()) onlineSearchTasks.resume()
                    if (homeSearchState.isInitialized()) homeSearch.resume()
                }
            } else {
                if (homeSearchState.isInitialized()) homeSearch.disable()
                if (localRefreshWorkerState.isInitialized()) localRefreshWorker.cancel()
                if (webDavRefreshWorkerState.isInitialized()) webDavRefreshWorker.cancel()
                if (localIndexWorkerState.isInitialized()) localIndexWorker.cancel()
                localLibraryRefreshSequence.incrementAndGet()
                webDavRefreshSequence.incrementAndGet()
                storageUiGeneration.incrementAndGet()
                onlineCompletionSearchTargets.clear()
                onlineCompletionSearchRowViews.clear()
                currentContext()?.let { context ->
                    onlineCompletionRunningDownloads.values.forEach { task ->
                        cancelOnlineCompletionNotification(context, task.notificationId, task.key)
                    }
                }
            }
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) action() else storageHandler.post(action)
}

internal fun WebDavDriveHook.onHostActivityPaused(activity: Activity) {
    if (storageActivityRef?.get() === activity) pauseStorageQueries()
}

internal fun WebDavDriveHook.onHostActivityDestroyed(activity: Activity) {
    if (storageActivityRef?.get() !== activity) return
    pauseStorageQueries()
    releaseStorageUiReferences()
    storageActivityRef = null
}

private fun WebDavDriveHook.pauseStorageQueries() {
    storagePaused = true
    storageUiGeneration.incrementAndGet()
    localLibraryRefreshSequence.incrementAndGet()
    webDavRefreshSequence.incrementAndGet()
    synchronized(localLibrarySearchIndexLock) { localLibraryIndexRequest = null }
    if (homeSearchState.isInitialized()) homeSearch.pause()
    if (localRefreshWorkerState.isInitialized()) localRefreshWorker.cancel()
    if (webDavRefreshWorkerState.isInitialized()) webDavRefreshWorker.cancel()
    if (localIndexWorkerState.isInitialized()) localIndexWorker.cancel()
    if (onlineSearchTasksState.isInitialized()) onlineSearchTasks.pause()
    storageHandler.removeCallbacksAndMessages(null)
}

internal fun WebDavDriveHook.postStorageUi(delayMillis: Long = 0L, action: () -> Unit): Boolean {
    if (storagePaused || storageClosed) return false
    val generation = storageUiGeneration.get()
    val owner = storageActivityRef?.get() ?: return false
    val ownerRef = WeakReference(owner)
    return storageHandler.postDelayed({
        val activity = ownerRef.get() ?: return@postDelayed
        if (storagePaused || storageClosed || generation != storageUiGeneration.get() ||
            storageActivityRef?.get() !== activity || activity.isFinishing || activity.isDestroyed
        ) return@postDelayed
        action()
    }, delayMillis)
}

private fun WebDavDriveHook.releaseStorageUiReferences() {
    if (homeSearchState.isInitialized()) homeSearch.clear()
    runCatching { webDavLoginDialog?.dismiss() }
    runCatching { webDavAuthDialog?.dismiss() }
    webDavLoginDialog = null
    webDavAuthDialog = null
    clearWebDavAccountContext()
    clearLocalLibraryAccountContext()
    synchronized(webDavStorageViewModels) { webDavStorageViewModels.clear() }
    synchronized(webDavBackupViewModels) { webDavBackupViewModels.clear() }
    synchronized(webDavLibraryLock) { webDavLibraryFlow = null }
    synchronized(localLibraryLock) { localLibraryFlow = null }
    synchronized(webDavAccountAuthLock) { webDavAccountAuthFlow = null }
    synchronized(localLibraryAccountAuthLock) { localLibraryAccountAuthFlow = null }
    cloudStorageRepositoryRef = null
    bookshelfRepositoryRef = null
    workerManagerRef = null
    workTrackerRef = null
    onlineCompletionSearchTargets.clear()
    onlineCompletionSearchRowViews.clear()
    cloudStorageScreenRefreshAt.clear()
    onlineCompletionModuleActivityPromptAt.clear()
    listOf(
        syncAuthCardRender, importUnauthRender, importLocalLibraryRow, webDavIconDepth,
        syncAvailableProvider, webDavCloudTitleDepth, webDavAccountScreenDepth, webDavAccountNavGraphScope,
        localLibraryIconDepth, localLibraryCloudTitleDepth, onlineCompletionCloudTitleDepth,
        onlineCompletionCloudTitleText, localLibraryAccountScreenDepth, localLibraryAccountNavGraphScope,
        localLibraryDefaultFolderDepth, localLibraryDefaultFolderStringIndex, webDavNotAuthDepth,
        webDavNotAuthTipPending, webDavRenderingSyncAuthCard, webDavRunningSyncAuthCardDepth,
        homeWebDavSearchRender, webDavBackupCardDepth, webDavCleartextAllowed,
    ).forEach { it.remove() }
}

internal fun WebDavDriveHook.closeStorageForReload() {
    if (storageClosed) return
    storageClosed = true
    onlineCompletionRunningDownloads.values.forEach { task ->
        task.cancelRequested = true
        task.cancellation.cancel()
        task.thread?.interrupt()
    }
    onlineCompletionRunningUpdates.values.forEach(TaskCancellation::cancel)
    pauseStorageQueries()
    releaseStorageUiReferences()
    storageActivityRef = null
    if (homeSearchState.isInitialized()) homeSearch.close()
    if (localRefreshWorkerState.isInitialized()) localRefreshWorker.close()
    if (webDavRefreshWorkerState.isInitialized()) webDavRefreshWorker.close()
    if (localIndexWorkerState.isInitialized()) localIndexWorker.close()
    if (onlineSearchTasksState.isInitialized()) onlineSearchTasks.close()
    synchronized(localLibrarySearchIndexLock) {
        localLibraryCacheGeneration.incrementAndGet()
        localLibraryListCache.clear()
        localLibrarySearchIndex = null
    }
    webDavConnectionGeneration.incrementAndGet()
    webDavRecentBackups.clear()
    onlineCompletionRenderTypesBySource.clear()
    onlineCompletionRenderSourceByType.clear()
    onlineCompletionRenderTitles.clear()
    webDavCleartextAllowedHosts.clear()
    webDavCleartextAllowedLoggedHosts.clear()
    composeWebDavVector = null
    methodCache.clear()
}

internal fun WebDavDriveHook.canUseCloudExtendedDisplay(): Boolean =
    settingsProvider().canUseCloudExtendedDisplay

internal fun decodeOnlineDataImage(url: String): Bitmap? {
    if (!url.startsWith("data:image/", ignoreCase = true)) return null
    val commaIndex = url.indexOf(',')
    if (commaIndex <= 0 || commaIndex >= url.length - 1) return null
    val metadata = url.take(commaIndex).lowercase(Locale.ROOT)
    val payload = url.substring(commaIndex + 1)
    val bytes = if (metadata.contains(";base64")) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

internal fun WebDavDriveHook.setTrackedWorkState(
    tracker: Any,
    id: String,
    statusName: String,
    progress: Int,
    error: String?,
    result: String?,
    name: String?,
) {
    runCatching {
        com.reamicro.fix.core.HostWorkBindings.requireCurrent().update(
            tracker,
            id,
            newWorkState(statusName, progress.coerceIn(0, 100), error, result, name),
        )
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to update WebDAV work state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.cancelOnlineCompletionTrackedWork(task: OnlineCompletionDownloadTask) {
    val tracker = task.tracker ?: return
    val workId = task.workId ?: return
    cancelTrackedWork(tracker, workId)
}

internal fun WebDavDriveHook.cancelTrackedWork(tracker: Any, id: String) {
    runCatching {
        com.reamicro.fix.core.HostWorkBindings.requireCurrent().cancel(tracker, id)
    }.onFailure {
        HookRuntime.logError("$LOG_PREFIX failed to cancel native work $id", it)
    }
}

internal fun isChildPath(child: File, parent: File): Boolean {
    val parentPath = parent.path.trimEnd(File.separatorChar) + File.separator
    return child.path.startsWith(parentPath)
}

internal fun workStateStatusName(state: Any): String =
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().stateName(state)

internal fun cloudTypeTitle(type: Int): String =
    when (type) {
        BACKUP_TYPE_BAIDU -> "\u767e\u5ea6\u7f51\u76d8"
        BACKUP_TYPE_YUN115 -> "115"
        BACKUP_TYPE_ALIYUN -> "\u963f\u91cc\u4e91\u76d8"
        else -> "\u4e91\u76d8"
    }

internal fun WebDavDriveHook.findLocalBookById(
    bookId: Long,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())) {
        "账号状态正在变化，请稍后重新操作"
    },
    bookshelf: Any? = null,
    checkCurrent: () -> Unit = {},
): Any? {
    checkCurrent()
    check(AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) { "账号已切换，请重新操作" }
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    val shelf = bookshelf ?: currentBookshelfRepository() ?: error("BookshelfRepository not available for WebDAV backup")
    val book = invokeSuspendBlocking(host.method("findId"), host.field("repository.dao").get(shelf), bookId)
    checkCurrent()
    check(AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) { "账号已切换，请重新操作" }
    return book?.takeIf { it.callLong("getId") == bookId && it.callLong("getUid") == storageIdentity.accountId }
}

internal fun WebDavDriveHook.findLocalBookByUuid(uid: Long, uuid: String): Any? {
    if (uid < 0L || uuid.isBlank()) return null
    val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return null
    if (identity.accountId != uid) return null
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    val shelf = currentBookshelfRepository() ?: return null
    val method = host.method("findUuid")
    val values = mapOf<Int, Any?>(host.value("findUuid.uid") to uid, host.value("findUuid.uuid") to uuid)
    val args = method.parameterTypes.indices.filter { it != host.value("findUuid.continuation") }.map(values::getValue)
    val book = invokeSuspendBlocking(method, host.field("repository.dao").get(shelf), *args.toTypedArray())
    if (!AccountHostRuntime.isCurrentStorage(identity, activityProvider())) return null
    return book?.takeIf { it.callLong("getUid") == uid && it.callString("getUuid") == uuid }
}

internal fun WebDavDriveHook.updateLocalBook(
    book: Any,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())) {
        "账号状态正在变化，请稍后重新操作"
    },
    bookshelf: Any? = null,
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    check(AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider()) &&
        book.callLong("getUid") == storageIdentity.accountId) { "图书账号已变化，请重新操作" }
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    check(host.type("book").isInstance(book))
    val shelf = bookshelf ?: currentBookshelfRepository() ?: error("BookshelfRepository not available for WebDAV update")
    val stored = findLocalBookById(book.callLong("getId"), storageIdentity, shelf, checkCurrent)
    check(stored != null && stored.callString("getUuid") == book.callString("getUuid")) { "图书记录已变化，请重新操作" }
    checkCurrent()
    // UID、UUID 和原 repository 已固定；不跨 DAO 挂起持有账号锁。
    invokeSuspendBlocking(host.method("update"), shelf, book)
    checkCurrent()
    check(AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) { "账号已切换，请重新操作" }
}

internal fun copyBookWithBackup(book: Any, backupType: Int, backupId: String, backupCode: String): Any {
    return copyBookWithBackupAndPublisher(
        book = book,
        backupType = backupType,
        backupId = backupId,
        backupCode = backupCode,
        publisher = book.callString("getPublisher"),
    )
}

internal fun copyBookWithBackupAndPublisher(
    book: Any,
    backupType: Int,
    backupId: String,
    backupCode: String,
    publisher: String,
    cover: String? = null,
    size: Long? = null,
    updated: Long? = null,
    pinnedAt: Long? = null,
    cloudId: Long? = null,
): Any {
    return BookCopyCompat.copy(
        book,
        BookCopyPatch(
            cover = cover,
            size = size,
            updated = updated,
            pinnedAt = pinnedAt,
            cloudId = cloudId,
            backupType = backupType,
            backupId = backupId,
            backupCode = backupCode,
            publisher = publisher,
        ),
    )
}

internal fun WebDavDriveHook.copyCloudBookWithType(book: Any, type: Int): Any {
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.records)
    check(host.type("book").isInstance(book))
    val properties = com.reamicro.fix.core.HostStorageQueries.RECORDS.getValue("book").second.split(' ')
    val before = properties.associateWith { host.field("book.$it").get(book) }
    val values = before + ("type" to type)
    return host.record("book", values).also { copy ->
        for (property in properties) {
            val actual = host.field("book.$property").get(copy)
            val expected = values.getValue(property)
            check(if (property == "type") (actual as Number).toInt() == (expected as Number).toInt() else actual == expected)
            check(host.field("book.$property").get(book) == before.getValue(property))
        }
    }
}

internal fun WebDavDriveHook.zipBookDirectory(book: Any, output: OutputStream, checkActive: () -> Unit = {}) {
    checkActive()
    val root = bookDirectory(book)
    check(root.isDirectory) { "Local book data not found" }
    ZipOutputStream(output.buffered()).use { zip ->
        val mimetype = File(root, "mimetype")
        if (mimetype.isFile) {
            addStoredFile(zip, mimetype, "mimetype")
        }
        root.walkTopDown()
            .filter { it.isFile && it != mimetype }
            .forEach { file ->
                checkActive()
                addDeflatedFile(zip, file, root.toPath().relativize(file.toPath()).toString().replace('\\', '/'), checkActive)
            }
    }
}

internal fun addStoredFile(zip: ZipOutputStream, file: File, name: String) {
    val bytes = file.readBytes()
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        this.crc = crc.value
    }
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}

internal fun addDeflatedFile(zip: ZipOutputStream, file: File, name: String, checkActive: () -> Unit = {}) {
    checkActive()
    val patchedOpf = if (name.endsWith(".opf", ignoreCase = true)) patchedOpfBytes(file) else null
    if (patchedOpf != null) {
        addDeflatedBytes(zip, patchedOpf, name)
        return
    }
    zip.putNextEntry(ZipEntry(name))
    BufferedInputStream(FileInputStream(file)).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            zip.write(buffer, 0, count)
        }
    }
    zip.closeEntry()
}

internal fun addDeflatedBytes(zip: ZipOutputStream, bytes: ByteArray, name: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(bytes)
    zip.closeEntry()
}

internal fun patchedOpfBytes(file: File): ByteArray? {
    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
    val patched = ensureNcxSpineToc(content)
    return patched.takeIf { it != content }?.toByteArray(Charsets.UTF_8)
}

internal fun WebDavDriveHook.bookDirectory(book: Any): File {
    val context = currentContext() ?: error("Android context not available")
    val uid = book.callLong("getUid")
    val uuid = book.callString("getUuid")
    return File(context.filesDir, "$uid/books/$uuid")
}

internal fun exportFileName(book: Any): String =
    "${book.callString("getTitle").safeWebDavFileName()}.epub"

internal fun isBaiduTitle(title: String): Boolean =
    title.contains("百度") || title.contains("Baidu")

internal fun WebDavDriveHook.newThirdLoginRoute(): Any =
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.routes).constructor("login").newInstance(BACKUP_TYPE_WEBDAV)
internal fun WebDavDriveHook.newStorageRoute(type: Int = BACKUP_TYPE_WEBDAV): Any =
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.routes).constructor("storage").newInstance(type)
internal fun WebDavDriveHook.newHomeRoute(): Any =
    requireNotNull(requireNotNull(com.reamicro.fix.core.HostStorageBindings.routes).field("home").get(null))
internal fun WebDavDriveHook.newCloudFolderRoute(path: String? = null, bookId: Long? = null, type: Int = BACKUP_TYPE_WEBDAV): Any =
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.routes).constructor("folder").newInstance(type, path, bookId, 0)

internal fun WebDavDriveHook.navigateStorage(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
    com.reamicro.fix.core.HostNavigation.navigate(classLoader, navGraphScope, newStorageRoute(type))
}

internal fun WebDavDriveHook.navigateCloudFolder(navGraphScope: Any, type: Int = BACKUP_TYPE_WEBDAV) {
    com.reamicro.fix.core.HostNavigation.navigate(classLoader, navGraphScope, newCloudFolderRoute(type = type))
}

internal fun WebDavDriveHook.navigateHome(navGraphScope: Any): Boolean =
    runCatching {
        com.reamicro.fix.core.HostNavigation.navigate(classLoader, navGraphScope, newHomeRoute())
        true
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to navigate WebDAV logout home: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun popBackStack(navGraphScope: Any) {
    com.reamicro.fix.core.HostNavigation.pop(navGraphScope)
}

internal fun WebDavDriveHook.flowOf(value: Any): Any =
    com.reamicro.fix.core.HostPagingData(classLoader).singleFlow(value)

internal fun WebDavDriveHook.emptyBaiduAuth(): Any =
    newBaiduAuth(
        "",
        "time",
        "1",
        "",
        0L,
        0L,
        "/",
        "",
    )

internal fun WebDavDriveHook.emptyCloudUserInfo(): Any =
    newCloudUserInfo("", "", "", "", "", null, 0L, 0L)

internal fun WebDavDriveHook.emptyPagingData(): Any = pagingDataFrom(emptyList())

internal fun WebDavDriveHook.pagingDataFrom(items: List<Any>): Any =
    com.reamicro.fix.core.HostPagingData(classLoader).from(items)



internal fun WebDavDriveHook.emptyFlow(): Any = com.reamicro.fix.core.HostPagingData(classLoader).emptyFlow()

internal fun WebDavDriveHook.kotlinResultSuccess(value: Any?): Any? = value

internal fun WebDavDriveHook.kotlinResultFailure(throwable: Throwable): Any =
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.records).constructor("failure").newInstance(throwable)

internal fun WebDavDriveHook.createMutableStateFlow(value: Any): Any =
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.records).constructor("stateFlow").newInstance(value)

internal fun setMutableStateFlowValue(flow: Any?, value: Any) {
    flow ?: return
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.records)
    require(host.type("stateFlow").isInstance(flow)) { "Unexpected mutable state flow" }
    host.method("setValue").invoke(flow, value)
}

internal fun WebDavDriveHook.refreshCloudStorageScreen(type: Int) {
    val path = when (type) {
        BACKUP_TYPE_WEBDAV -> currentWebDavBrowseDir()
        BACKUP_TYPE_LOCAL_LIBRARY -> currentLocalLibraryBrowseDir()
        else -> return
    }
    val key = "$type:$path"
    val now = System.currentTimeMillis()
    val last = cloudStorageScreenRefreshAt[key] ?: 0L
    if (now - last < CLOUD_STORAGE_SCREEN_REFRESH_DEBOUNCE_MS) return
    cloudStorageScreenRefreshAt[key] = now
    when (type) {
        BACKUP_TYPE_WEBDAV -> {
            updateWebDavStorageTrees(path)
            refreshWebDavLibraryAsync(path)
        }
        BACKUP_TYPE_LOCAL_LIBRARY -> {
            updateLocalLibraryStorageTrees(path)
            refreshLocalLibraryAsync(path, force = true)
        }
    }
    logWebDav("cloud storage screen refresh scheduled type=$type path=$path")
}

internal fun enabledOnlineCompletionSources(context: Context): List<OnlineSourceEntry> {
    val prefs = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
    return OnlineSourceStore.list(context)
        .filter { source -> prefs.getBoolean(ModuleSettings.onlineSourceKey(source.id), false) }
}

internal fun logOnlineMetadataGaps(
    source: OnlineSourceEntry,
    node: Any?,
    status: String,
    wordCount: String,
    updateTime: String,
    chapterCount: Int,
) {
    if (status.isNotBlank() && wordCount.isNotBlank() && updateTime.isNotBlank() && chapterCount > 0) return
    val keys = (node as? JSONObject)?.keys()?.asSequence()?.take(24)?.joinToString(",").orEmpty()
    logWebDav(
        "online metadata partial source=${source.name} " +
            "status=${status.isNotBlank()} words=${wordCount.isNotBlank()} " +
            "update=${updateTime.isNotBlank()} chapters=${chapterCount > 0} keys=$keys",
    )
}

internal fun onlineCompletionPlatformName(node: Any?, kind: String): String {
    val raw = onlineFirstJsonString(
        node,
        listOf(
            "platform",
            "sourcePlatform",
            "source_platform",
            "displaySourceName",
            "display_source_name",
            "sourceName",
            "source_name",
            "bookSource",
            "origin",
        ),
    ).ifBlank {
        kind.cleanOnlineText().split(Regex("\\s+")).firstOrNull().orEmpty()
    }
    return raw.normalizedAssociationPlatformName()
}

internal fun copyHomeUiStateWithCloudResults(state: Any, cloudResults: Map<Any?, Any?>?): Any =
    com.reamicro.fix.core.HostHomeSearchState.copyCloudResults(state, cloudResults)

internal fun WebDavDriveHook.newCloudFolder(entry: WebDavEntry): Any =
    newStorageFolder(
        entry.name,
        normalizeWebDavPath(entry.path),
        false,
        entry.updatedAt,
    )

internal fun WebDavDriveHook.newDir(id: String, name: String): Any =
    com.reamicro.fix.core.HostStorageRecords.dir(cls(DIR_CLASS), id, name)

internal fun WebDavDriveHook.newCloudBook(entry: WebDavEntry): Any {
    val path = normalizeWebDavPath(entry.path)
    val url = webDavBookUrl(path)
    return com.reamicro.fix.core.HostStorageRecords.book(cls(CLOUD_BOOK_CLASS), cls(FLOW_CLASS), arrayOf(
        path,
        entry.name,
        BACKUP_TYPE_WEBDAV,
        url,
        entry.size,
        entry.updatedAt,
        path,
        "",
        webDavLocalBookFlow(url),
        webDavWorkFlow(path),
    ))
}

internal fun WebDavDriveHook.newOnlineCompletionSourceCloudBook(group: OnlineSearchGroup): Any {
    val encodedQuery = URLEncoder.encode(group.query, "UTF-8")
    val path = "$ONLINE_COMPLETION_SOURCE_PREFIX${group.source.id}?q=$encodedQuery"
    val best = group.results.firstOrNull()
    if (best != null) {
        updateOnlineCompletionSearchTarget(path, OnlineDownloadTarget(group.source, group.query, best), group.sequence)
    }
    val status = when {
        group.error.isNotBlank() -> group.error
        best == null -> "无结果"
        else -> best.name
    }
    val subtitle = listOfNotNull(
        best?.author?.takeIf { it.isNotBlank() }?.let { "作者 $it" },
        group.source.name.takeIf { it.isNotBlank() },
        if (best != null && group.results.size > 1) "${group.results.size} 条结果" else null,
        if (best == null) group.source.sourceUrl.takeIf { it.isNotBlank() } else null,
    ).joinToString(" · ")
    return newOnlineCompletionCloudBook(
        path = path,
        name = status,
        subtitle = subtitle,
        result = best,
    )
}

internal fun onlineCompletionGroupTitle(group: OnlineSearchGroup): String =
    "$ONLINE_COMPLETION_TITLE-${group.source.name.ifBlank { "未知来源" }}"

internal fun WebDavDriveHook.onlineCompletionTitleForType(type: Int, fallback: String = ONLINE_COMPLETION_TITLE): String =
    onlineCompletionRenderTitles[type].orEmpty()
        .ifBlank { onlineCompletionCloudTitleText.get().orEmpty() }
        .ifBlank { fallback }
        .ifBlank { ONLINE_COMPLETION_TITLE }

internal fun WebDavDriveHook.onlineCompletionGroupVisibleCloudBooks(group: OnlineSearchGroup): List<Any> {
    if (group.results.isEmpty()) return listOf(newOnlineCompletionSourceCloudBook(group))
    return group.results.take(ONLINE_COMPLETION_RESULT_LIMIT).mapIndexedNotNull { index, result ->
        runCatching { newOnlineCompletionResultCloudBook(group.source, group.query, result, index, group.sequence) }
            .onFailure { error ->
                HookRuntime.log(
                    "$LOG_PREFIX online completion cloud book failed source=${group.source.name} " +
                        "name=${result.name} error=${error.stackTraceToString()}",
                )
            }
            .getOrNull()
    }.ifEmpty { listOf(newOnlineCompletionSourceCloudBook(group)) }
}

internal fun WebDavDriveHook.newOnlineCompletionResultCloudBook(
    source: OnlineSourceEntry,
    query: String,
    result: OnlineBookSearchResult,
    index: Int,
    sequence: Long,
): Any {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val path = "$ONLINE_COMPLETION_BOOK_PREFIX${source.id}/$index?q=$encodedQuery"
    updateOnlineCompletionSearchTarget(path, OnlineDownloadTarget(source, query, result), sequence)
    return newOnlineCompletionCloudBook(
        path = path,
        name = result.name,
        subtitle = onlineCompletionSearchMetaLine(result),
        result = result,
    )
}

internal fun WebDavDriveHook.newOnlineCompletionCloudBook(
    path: String,
    name: String,
    subtitle: String,
    result: OnlineBookSearchResult? = null,
): Any {
    val localFlow = result?.let {
        runCatching { flowOf(newOnlineCompletionLocalBook(path, it)) }
            .onFailure { error ->
                HookRuntime.log("$LOG_PREFIX online completion synthetic book failed: ${error.stackTraceToString()}")
            }
            .getOrNull()
    } ?: emptyFlow()
    return com.reamicro.fix.core.HostStorageRecords.book(cls(CLOUD_BOOK_CLASS), cls(FLOW_CLASS), arrayOf(
        path,
        name,
        BACKUP_TYPE_ONLINE_COMPLETION,
        path,
        0L,
        System.currentTimeMillis(),
        path,
        subtitle,
        localFlow,
        webDavWorkFlow(path),
    ))
}

internal fun WebDavDriveHook.newOnlineCompletionLocalBook(path: String, result: OnlineBookSearchResult): Any {
    val host = requireNotNull(com.reamicro.fix.core.HostBookBindings.current)
    val values = mapOf<String, Any?>(
        "id" to 0L, "uuid" to UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8)).toString(), "uid" to 0L,
        "title" to result.name, "subtitle" to "", "author" to result.author, "cover" to result.coverUrl,
        "size" to 0L, "uri" to result.detailUrl.ifBlank { path }, "group" to "", "created" to System.currentTimeMillis(),
        "cfiVersion" to 1, "embeddedFonts" to 0, "epubcfi" to "", "chapter" to "", "progress" to 0f,
        "total" to 0L, "listeningTotal" to 0L, "finished" to 0L, "updated" to 0L, "pinnedAt" to 0L,
        "cloudId" to 0L, "backupType" to BACKUP_TYPE_ONLINE_COMPLETION, "backupId" to path, "backupCode" to "",
        "publisher" to result.sourceName, "contentDownloaded" to false,
    )
    check(host.properties.keys.all { it in values }) { "Synthetic book has unknown properties" }
    return host.create(values.filterKeys { it in host.properties })
}

internal fun WebDavDriveHook.emptyCloudBook(): Any =
    requireNotNull(requireNotNull(com.reamicro.fix.core.HostStorageBindings.records).field("book.empty").get(null))

internal fun isSupportedBookFile(name: String): Boolean =
    BOOK_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

internal fun isOnlineCompletionPath(path: String): Boolean =
    path.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX) || path.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)

internal fun isOnlineCompletionLocalBook(book: Any): Boolean =
    isOnlineCompletionPath(bookBackupIdOf(book)) ||
        isOnlineCompletionPath(book.callString("getUri")) ||
        isOnlineCompletionUuid(book.callString("getUuid")) ||
        (bookBackupTypeOf(book) == BACKUP_TYPE_ONLINE_COMPLETION &&
            book.callString("getBackupCode").startsWith("online_"))

internal fun isOnlineCompletionUuid(uuid: String): Boolean =
    uuid.startsWith(ONLINE_COMPLETION_UUID_PREFIX)

internal fun WebDavDriveHook.onlineCompletionGeneratedEpubMetadata(book: Any): OnlineCompletionEpubMetadata =
    runCatching {
        val contentOpf = File(bookDirectory(book).canonicalFile, "OEBPS/content.opf")
        if (!contentOpf.isFile) return@runCatching OnlineCompletionEpubMetadata()
        val parser = Xml.newPullParser()
        contentOpf.inputStream().buffered().use { input ->
            parser.setInput(input, null)
            var sourceId = ""
            var sourceName = ""
            var detailUrl = ""
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "meta") {
                    when (parser.getAttributeValue(null, "name").orEmpty()) {
                        "reamicro-online-source-id" -> sourceId = parser.getAttributeValue(null, "content").orEmpty()
                        "reamicro-online-source-name" -> sourceName = parser.getAttributeValue(null, "content").orEmpty()
                        "reamicro-online-detail-url" -> detailUrl = parser.getAttributeValue(null, "content").orEmpty()
                    }
                }
                eventType = parser.next()
            }
            OnlineCompletionEpubMetadata(
                sourceId = sourceId,
                sourceName = sourceName,
                detailUrl = detailUrl,
            )
        }
    }.getOrDefault(OnlineCompletionEpubMetadata())

internal fun WebDavDriveHook.refreshOnlineCompletionCatalogTables(
    book: Any,
    bookDir: File,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    check(!storageClosed && settingsProvider().moduleEnabled &&
        AccountHostRuntime.isCurrentStorage(storageIdentity) && book.callLong("getUid") == storageIdentity.accountId) {
        "图书或账号状态已变化，已停止目录更新"
    }
    val bookId = book.callLong("getId")
    require(bookId > 0L) { "阅微书籍 ID 无效，无法刷新目录表" }
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
    val shelf = currentBookshelfRepository() ?: error("阅微书架服务暂不可用，无法刷新目录")
    val itemDao = requireNotNull(host.field("repository.item").get(shelf))
    val chapterDao = requireNotNull(host.field("repository.chapter").get(shelf))
    val path = okioPath(bookDir.canonicalFile)
    val opf = obtainOnlineCompletionOpf(path)
    val items = host.method("items").invoke(null, bookId, opf, path) as List<*>
    check(items.isNotEmpty()) { "阅微目录条目解析为空，已阻止刷新目录表" }
    val chapters = host.method("chapters").invoke(null, items, opf, path, null) as List<*>
    check(chapters.isNotEmpty()) { "阅微章节目录解析为空，已阻止刷新目录表" }
    checkCurrent()
    syncOnlineCatalogAtomically(itemDao, chapterDao, bookId, items, chapters, book.callString("getUuid"), storageIdentity, checkCurrent)
    logWebDav("online completion catalog tables refreshed bookId=$bookId itemRefs=${items.size} chapters=${chapters.size}")
}

internal fun onlineSourceIdFromEncodedValue(value: String): String {
    val text = value.trim()
    if (text.isBlank()) return ""
    if (text.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) {
        return runCatching { Uri.parse(text).host.orEmpty() }.getOrDefault("")
    }
    return text.takeUnless { it.contains("://") }.orEmpty()
}

internal fun onlineCompletionQueryParameter(value: String, name: String): String =
    runCatching {
        if (!value.startsWith(ONLINE_COMPLETION_BOOK_PREFIX)) return@runCatching ""
        Uri.parse(value).getQueryParameter(name).orEmpty()
    }.getOrDefault("")

internal fun isPathLikeOnlineSourceName(value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    if (text.contains("://")) return true
    if (text.startsWith("/") || text.startsWith("\\") || text.startsWith("／")) return true
    if (Regex("""^[A-Za-z]:[\\/].*""").matches(text)) return true
    return text.endsWith(".epub", ignoreCase = true) ||
        text.endsWith(".txt", ignoreCase = true) ||
        text.endsWith(".pdf", ignoreCase = true)
}

internal fun onlineCompletionFailureSuffix(failedCount: Int): String =
    if (failedCount > 0) " · 失败 $failedCount 章" else ""

internal fun logOnlineCompletionFinalFailures(
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
    stage: String,
) {
    failures.forEach { failure ->
        logWebDav(
            "online completion $stage final failed chapter " +
                "${failure.index + 1} source=${target.source.name} book=${target.result.name} " +
                "attempts=${failure.attempts} title=${failure.title} url=${failure.url.take(160)} " +
                "error=${failure.message}",
        )
    }
}

internal fun WebDavDriveHook.onlineContentProxyTemplate(source: OnlineSourceEntry): String? {
    val contentRule = runCatching { JSONObject(source.ruleContent).optString("content", "") }.getOrNull().orEmpty()
    return extractOnlineBacktickTemplates(contentRule)
        .firstOrNull { it.contains("\${itemId}") }
        ?: OnlineSourceScriptCompat.resolveContentRequestTemplate(
            sourceUrl = sourceBaseUrl(source),
            rawScript = contentRule,
            loginInfo = OnlineSourceAuth.loginInfo(currentApplicationContext() ?: currentContext(), source),
        )
}

internal fun extractOnlineBacktickTemplates(rule: String): List<String> {
    if (rule.isBlank()) return emptyList()
    val templates = mutableListOf<String>()
    var index = 0
    while (index < rule.length) {
        val start = rule.indexOf('`', index)
        if (start < 0) break
        val builder = StringBuilder()
        var cursor = start + 1
        var escaped = false
        while (cursor < rule.length) {
            val char = rule[cursor]
            when {
                escaped -> {
                    builder.append(char)
                    escaped = false
                }
                char == '\\' -> {
                    builder.append(char)
                    escaped = true
                }
                char == '`' -> break
                else -> builder.append(char)
            }
            cursor++
        }
        if (cursor >= rule.length) break
        templates.add(builder.toString())
        index = cursor + 1
    }
    return templates
}

internal fun WebDavDriveHook.evaluateOnlineContentJs(
    source: OnlineSourceEntry,
    contentRule: String,
    result: String,
): String? {
    if (!contentRule.contains("java.ajax", ignoreCase = true)) return null
    val itemId = if (contentRule.contains("java.hexDecodeToString(result)", ignoreCase = true)) {
        result.hexDecodeToString()
    } else {
        result
    }.trim()
    if (itemId.isBlank()) return null
    val template = onlineContentProxyTemplate(source) ?: return null
    val requestUrl = template.replace("\${itemId}", itemId)
    val response = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
        requestOnlineSearch(source, requestUrl)
    }
    val root = parseOnlineJsonRoot(response.body) ?: return response.body
    return onlineJsonString(root, "$.data.content")
        .ifBlank { onlineJsonString(root, "$.chapter.text") }
        .ifBlank { onlineJsonString(root, "$..content") }
        .ifBlank { response.body }
}

internal fun onlineReadTimeoutMillis(source: OnlineSourceEntry): Int =
    source.respondTime.coerceIn(15_000, 300_000)

internal fun onlineConnectTimeoutMillis(source: OnlineSourceEntry): Int =
    onlineReadTimeoutMillis(source).coerceAtMost(30_000).coerceAtLeast(10_000)

internal fun onlineCompletionFailurePlaceholder(attempts: Int, message: String): String =
    if (message.contains("今日已达到章节下载限额")) {
        "本章尚未下载，当前在线源今日额度已用完。\n\n请明日点击更新继续补全。"
    } else {
        "本章下载失败，已按在线源重试 $attempts 次。\n\n$message"
    }

internal fun bookDirectorySize(bookDir: File): Long =
    bookDir.walkTopDown()
        .filter { it.isFile }
        .fold(0L) { total, file -> total + file.length() }

internal fun onlineCompletionFailedLogFile(bookDir: File): File =
    File(bookDir, "OEBPS/$ONLINE_COMPLETION_FAILED_CHAPTER_LOG")

internal fun WebDavDriveHook.findLocalBookByUrl(bookshelf: Any, url: String): Any? {
    if (url.isBlank()) return null
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    val flow = requireNotNull(host.method("liveUri").invoke(bookshelf, url))
    return firstFlowValue(flow)
}

internal fun currentOnlineCompletionBooksDir(bookshelf: Any): Any {
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    val storage = requireNotNull(host.field("repository.storage").get(bookshelf))
    val uid = (host.field("storage.uid").get(storage) as? Number)?.toLong()?.takeIf { it > 0L }
        ?: error("阅微书库目录不可用：当前用户 UID 未找到")
    return requireNotNull(host.method("books").invoke(storage, uid)) { "阅微书库目录不可用" }
}

internal fun onlineCompletionCancelPendingIntent(
    context: Context, id: Int, key: String, pause: Boolean = false,
): PendingIntent {
    val intent = Intent(if (pause) com.reamicro.fix.notification.OnlineCompletionNotificationActions.PAUSE
        else ONLINE_COMPLETION_CANCEL_ACTION).apply {
        setPackage(context.packageName)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
        putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    return PendingIntent.getBroadcast(context, id, intent, flags)
}

internal fun sendOnlineCompletionCancelBroadcastToModule(context: Context, id: Int, key: String): Boolean =
    runCatching {
        val intent = Intent(ONLINE_COMPLETION_CANCEL_ACTION).apply {
            setClassName(MODULE_PACKAGE_NAME, ONLINE_COMPLETION_NOTIFICATION_RECEIVER_CLASS)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
            putExtra(com.reamicro.fix.notification.OnlineCompletionNotificationActions.DISMISS_ONLY, true)
        }
        context.sendBroadcast(intent)
        true
    }.getOrElse {
        logWebDav("online completion module notification cancel failed id=$id: ${it.message}")
        false
    }

internal fun WebDavDriveHook.rememberCloudStorageRepository(repository: Any?) {
    if (repository == null) return
    cloudStorageRepositoryRef = WeakReference(repository)
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().field("storage.bookshelf").get(repository)?.let {
        bookshelfRepositoryRef = WeakReference(it)
    }
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().field("storage.manager").get(repository)?.let {
        rememberWorkerManager(it)
    }
}

internal fun WebDavDriveHook.rememberHomeViewModelDependencies(viewModel: Any?) {
    if (viewModel == null) return
    val repository = com.reamicro.fix.core.HostWorkBindings.requireCurrent().field("home.bookshelf").get(viewModel)
    if (repository != null && bookshelfRepositoryRef?.get() !== repository) {
        rememberBookshelfRepository(repository)
        logWebDav("home viewModel bookshelf repository registered")
    }
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().field("home.storage").get(viewModel)?.let { cloudRepository ->
        if (cloudStorageRepositoryRef?.get() !== cloudRepository) {
            rememberCloudStorageRepository(cloudRepository)
            logWebDav("home viewModel cloud repository registered")
        }
    }
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().field("home.manager").get(viewModel)?.let { workerManager ->
        if (workerManagerRef?.get() !== workerManager) {
            rememberWorkerManager(workerManager)
            logWebDav("home viewModel worker manager registered")
        }
    }
}

internal fun WebDavDriveHook.rememberBookshelfRepository(repository: Any?) {
    if (repository == null) return
    bookshelfRepositoryRef = WeakReference(repository)
    repairOnlineCompletionBookMetadataSoon(repository)
}

internal fun WebDavDriveHook.rememberWorkerManager(workerManager: Any?) {
    if (workerManager == null) return
    val work = com.reamicro.fix.core.HostWorkBindings.requireCurrent()
    check(work.type("manager").isInstance(workerManager))
    workerManagerRef = WeakReference(workerManager)
    rememberBookshelfRepository(work.bookshelf(workerManager))
    workTrackerRef = WeakReference(work.tracker(workerManager))
}

internal fun WebDavDriveHook.currentBookshelfRepository(): Any? =
    bookshelfRepositoryRef?.get()
        ?: cloudStorageRepositoryRef?.get()?.let { com.reamicro.fix.core.HostWorkBindings.requireCurrent().field("storage.bookshelf").get(it) }
        ?: workerManagerRef?.get()?.let { com.reamicro.fix.core.HostWorkBindings.requireCurrent().bookshelf(it) }

internal fun WebDavDriveHook.awaitBookshelfRepository(timeoutMs: Long = ONLINE_COMPLETION_REPOSITORY_WAIT_TIMEOUT_MS): Any? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var logged = false
    while (true) {
        currentBookshelfRepository()?.let { return it }
        if (System.currentTimeMillis() >= deadline) return null
        if (!logged) {
            logWebDav("online completion waiting for bookshelf repository after activity recreation")
            logged = true
        }
        Thread.sleep(ONLINE_COMPLETION_REPOSITORY_WAIT_STEP_MS)
    }
}

internal fun WebDavDriveHook.currentWorkTracker(): Any? =
    workTrackerRef?.get()
        ?: workerManagerRef?.get()?.let { com.reamicro.fix.core.HostWorkBindings.requireCurrent().tracker(it) }

internal fun WebDavDriveHook.repairOnlineCompletionBookMetadataSoon(repository: Any) {
    val now = System.currentTimeMillis()
    val last = onlineCompletionMetadataRepairAt.get()
    if (last > 0L && now - last < ONLINE_COMPLETION_METADATA_REPAIR_INTERVAL_MS) return
    if (!onlineCompletionMetadataRepairAt.compareAndSet(last, now)) return
    com.reamicro.fix.reload.ModuleResources.thread({
        runCatching {
            repairOnlineCompletionBookMetadata(repository)
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX online completion metadata repair failed: ${it.stackTraceToString()}")
        }
    }, "ReaMicroOnlineCompletionRepair").start()
}

internal fun WebDavDriveHook.repairOnlineCompletionBookMetadata(repository: Any) {
    val books = listLocalBooks(repository) ?: return
    var repaired = 0
    books.forEach { book ->
        if (book == null) return@forEach
        if (bookBackupTypeOf(book) != BACKUP_TYPE_ONLINE_COMPLETION) return@forEach
        val currentPinnedAt = book.callLong("getPinnedAt")
        val currentCloudId = book.callLong("getCloudId")
        val needsPinnedCloudRepair = currentCloudId == 0L &&
            currentPinnedAt in 1 until ONLINE_COMPLETION_PINNED_TIMESTAMP_FLOOR
        if (!needsPinnedCloudRepair) return@forEach
        logWebDav(
            "online completion repairing swapped pinned/cloud title=${book.callString("getTitle")} " +
                "pinnedAt=$currentPinnedAt cloudId=$currentCloudId",
        )
        val updatedBook = copyBookWithBackupAndPublisher(
            book = book,
            backupType = BACKUP_TYPE_ONLINE_COMPLETION,
            backupId = bookBackupIdOf(book),
            backupCode = book.callString("getBackupCode"),
            publisher = book.callString("getPublisher"),
            cover = book.callString("getCover"),
            pinnedAt = 0L,
            cloudId = currentPinnedAt,
        )
        updateLocalBook(updatedBook)
        repaired++
    }
    if (repaired > 0) {
        logWebDav("online completion repaired book metadata count=$repaired")
    }
}

internal fun WebDavDriveHook.listLocalBooks(repository: Any): Iterable<*>? {
    val uid = currentUserId(repository) ?: return null
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    val flow = host.method("list").invoke(host.field("repository.dao").get(repository), uid) ?: return null
    return firstFlowValue(flow) as? Iterable<*>
}

internal fun WebDavDriveHook.currentUserId(repository: Any): Long? =
    runCatching {
        val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
        val storage = host.field("repository.storage").get(repository)
        (host.field("storage.uid").get(storage) as? Number)?.toLong()?.takeIf { it > 0L }
    }.onFailure {
        logWebDav("online completion current user lookup failed: ${it.message ?: it.javaClass.name}")
    }.getOrNull()

internal fun WebDavDriveHook.firstFlowValue(flow: Any): Any? =
    invokeSuspendBlocking(requireNotNull(com.reamicro.fix.core.HostStorageBindings.library).method("first"), null, flow)

internal fun bookBackupTypeOf(book: Any?): Int = book?.let {
    (requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup).field("book.backupType").get(it) as Number).toInt()
} ?: 0
internal fun bookBackupIdOf(book: Any?): String = book?.let {
    requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup).field("book.backupId").get(it) as String
}.orEmpty()
internal fun WebDavDriveHook.currentContext(): Context? =
    activityProvider() ?: currentApplicationContext()

internal fun currentApplicationContext(): Context? =
    runCatching {
        Class.forName("android.app.ActivityThread")
            .hostMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext

internal fun isDescendingOrder(direction: String): Boolean =
    direction == "1" || direction.equals("DESC", ignoreCase = true) || direction.equals("desc", ignoreCase = true)

internal fun orderKind(orderBy: String): String =
    when (orderBy) {
        "size", "file_size" -> "size"
        "time", "user_utime", "updated_at", "created_at" -> "time"
        else -> "name"
    }

internal fun <T> sortStorageEntries(
    entries: List<T>,
    orderBy: String,
    direction: String,
    isDirectory: (T) -> Boolean,
    name: (T) -> String,
    size: (T) -> Long,
    updatedAt: (T) -> Long,
): List<T> {
    val fieldComparator = when (orderKind(orderBy)) {
        "size" -> compareBy<T> { size(it) }.thenBy { name(it).lowercase(Locale.ROOT) }
        "time" -> compareBy<T> { updatedAt(it) }.thenBy { name(it).lowercase(Locale.ROOT) }
        else -> compareBy { name(it).lowercase(Locale.ROOT) }
    }
    val comparator = if (isDescendingOrder(direction)) fieldComparator.reversed() else fieldComparator
    return entries.filter(isDirectory).sortedWith(comparator) + entries.filterNot(isDirectory).sortedWith(comparator)
}

internal fun skeletonForMatch(value: String): String {
    val nfc = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
    val sb = StringBuilder(nfc.length)
    for (ch in nfc) {
        if (ch.isLetterOrDigit()) sb.append(ch)
    }
    return sb.toString()
}

internal fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

internal fun WebDavDriveHook.pushOnlineCompletionCloudTitle(title: String = ONLINE_COMPLETION_TITLE) {
    onlineCompletionCloudTitleDepth.set((onlineCompletionCloudTitleDepth.get() ?: 0) + 1)
    onlineCompletionCloudTitleText.set(title.ifBlank { ONLINE_COMPLETION_TITLE })
}

internal fun WebDavDriveHook.popOnlineCompletionCloudTitle() {
    val next = (onlineCompletionCloudTitleDepth.get() ?: 0) - 1
    if (next <= 0) {
        onlineCompletionCloudTitleDepth.remove()
        onlineCompletionCloudTitleText.remove()
    } else {
        onlineCompletionCloudTitleDepth.set(next)
    }
}

internal fun WebDavDriveHook.getNativeAndroidOsVector(): Any =
    requireNotNull(requireNotNull(com.reamicro.fix.core.HostBookUiBindings.metadata).method("local").invoke(null))
internal fun formatCloudUpdatedTime(updatedAt: Long): String =
    runCatching {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(java.util.Date(updatedAt))
    }.getOrDefault("")

internal fun cloudBookExtensionLabel(extension: String): String =
    if (extension.equals("epub", ignoreCase = true)) "ePub" else extension.uppercase(Locale.ROOT)

internal fun WebDavDriveHook.method(className: String, methodName: String, parameterCount: Int): Method {
    val accountKey = when (className) {
        BAIDU_ACCOUNT_SCREEN_CLASS -> "webdav"
        Y115_ACCOUNT_SCREEN_CLASS -> "local"
        else -> null
    }
    if (accountKey != null) {
        val member = when (methodName) {
            BAIDU_ACCOUNT_SCREEN_METHOD, Y115_ACCOUNT_SCREEN_METHOD -> "screen"
            BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD -> "folder"
            BAIDU_ACCOUNT_LOGOUT_METHOD -> "logout"
            BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD -> "order"
            BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD -> "direction"
            else -> null
        }
        if (member != null) return requireNotNull(com.reamicro.fix.core.HostStorageBindings.account) {
            "Native account contract unavailable"
        }.method("$accountKey.$member").also { check(it.parameterCount == parameterCount) }
    }
    val method = when (className to methodName) {
        BOOK_LIBRARY_SHEET_CLASS to CLOUD_AUTHORIZED_ROW_METHOD ->
            requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui).method("authorized")
        BOOK_LIBRARY_SHEET_CLASS to CLOUD_UNAUTH_ROW_METHOD ->
            requireNotNull(com.reamicro.fix.core.HostStorageBindings.ui).method("unauthorized")
        DIVIDER_KT_CLASS to SIMPLE_DIVIDER_METHOD ->
            requireNotNull(com.reamicro.fix.core.HostUiBindings.current).method("divider")
        else -> error("Unbound native component: $className#$methodName")
    }
    check(method.parameterCount == parameterCount) { "$className#$methodName parameter contract changed" }
    return method
}

internal fun nativeOpfMetadataText(opf: Any, role: String): String {
    require(role == "title" || role == "uuid")
    val host = requireNotNull(com.reamicro.fix.core.HostReaderExtrasBindings.importMetadata)
    check(host.type("opf").isInstance(opf))
    val metadata = host.read("opf.metadata", opf)
    val entry = host.read("metadata.$role", metadata)
    return host.read(if (role == "title") "title.value" else "identifier.value", entry)?.toString().orEmpty()
}
internal fun WebDavDriveHook.newKotlinPair(first: Any?, second: Any?): Any =
    requireNotNull(com.reamicro.fix.core.HostStorageBindings.imports).constructor("pair").newInstance(first, second)
internal fun WebDavDriveHook.platformFile(file: File): Any =
    requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.files).from(file)

internal fun WebDavDriveHook.platformFileKeys(platformFile: Any): List<String> =
    listOfNotNull(
        platformFileString(platformFile, PLATFORM_FILE_GET_PATH_METHOD),
        platformFileString(platformFile, PLATFORM_FILE_ABSOLUTE_PATH_METHOD),
    ).filter { it.isNotBlank() }.distinct()

internal fun WebDavDriveHook.platformFileString(platformFile: Any, methodName: String): String? {
    val contract = requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.files)
    val file = contract.file(platformFile) ?: return null
    return if (methodName == PLATFORM_FILE_GET_PATH_METHOD) file.path else file.absolutePath
}

internal fun WebDavDriveHook.invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? =
        com.reamicro.fix.core.HostCoroutines.invoke(classLoader, method, target, *args)

internal fun WebDavDriveHook.emptyCoroutineContext(): Any =
    com.reamicro.fix.core.HostCoroutines.emptyContext(classLoader)
internal fun WebDavDriveHook.coroutineSuspended(): Any =
    com.reamicro.fix.core.HostCoroutines.suspended(classLoader)
internal fun WebDavDriveHook.newWorkHandle(id: String, state: Any): Any =
    newNativeWorkHandle(id, com.reamicro.fix.core.HostWorkBindings.requireCurrent().stateFlow(state))

internal fun WebDavDriveHook.newWorkState(statusName: String, progress: Int, error: String?, result: String?, name: String?): Any =
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().state(statusName, progress, error, result, name)

internal fun WebDavDriveHook.functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, block)
internal fun WebDavDriveHook.functionProxy(name: String, functionClassName: String, fallback: (Array<Any?>?) -> Any?, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, fallback, block)

internal fun WebDavDriveHook.cls(className: String): Class<*> {
    val records = requireNotNull(com.reamicro.fix.core.HostStorageBindings.records)
    val role = when (className) {
        AUTH_BAIDU_CLASS -> "auth"
        AUTH_YUN115_CLASS -> "localAuth"
        DIR_CLASS -> "dir"
        CLOUD_USER_INFO_CLASS -> "info"
        CLOUD_FOLDER_CLASS -> "folder"
        CLOUD_BOOK_CLASS -> "book"
        CLOUD_STORAGE_UI_STATE_CLASS -> "state"
        FLOW_CLASS -> return requireNotNull(com.reamicro.fix.core.HostStorageBindings.data).type("flow")
        else -> error("Unbound storage type: $className")
    }
    return records.type(role)
}

internal fun WebDavDriveHook.targetUnit(): Any =
    com.reamicro.fix.core.HostCoroutines.unit(classLoader)
