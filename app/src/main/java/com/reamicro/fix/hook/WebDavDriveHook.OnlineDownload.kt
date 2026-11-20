package com.reamicro.fix.hook

import com.reamicro.fix.hook.WebDavDriveHook.OnlineChapterBatchSession

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Build
import com.reamicro.fix.online.FanqieParagraphCommentApi
import com.reamicro.fix.online.OnlineConcurrentRateLimiter
import com.reamicro.fix.online.OnlineParagraphCommentCache
import com.reamicro.fix.online.OnlineParagraphCommentCacheStore
import com.reamicro.fix.online.OnlineParagraphCommentCount
import com.reamicro.fix.online.OnlineParagraphIndexMapper
import com.reamicro.fix.online.OnlineSourceDailyLimitException
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceScriptCompat
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceTrxsCompat
import com.reamicro.fix.notification.cancelOnlineCompletionNotificationIfDone
import com.reamicro.fix.notification.onlineCompletionDownloadBigText
import com.reamicro.fix.notification.onlineCompletionDownloadText
import com.reamicro.fix.notification.onlineCompletionDownloadTitle
import com.reamicro.fix.logging.ModuleLogState
import com.reamicro.fix.cloud.webdav.CacheDeleteStat
import com.reamicro.fix.online.search.evaluateOnlineChapterUrlRule
import com.reamicro.fix.online.download.isPermanentOnlineChapterFailure
import com.reamicro.fix.online.download.onlineHttpRetryDelayMs
import com.reamicro.fix.online.download.onlineHttpRetryKind
import com.reamicro.fix.online.download.OnlineHttpRetryKind
import com.reamicro.fix.cloud.webdav.NativeCloudDownload
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadCancelledException
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.cloud.webdav.OnlineDownloadedChapter
import com.reamicro.fix.online.download.OnlineBookDownloadMode
import com.reamicro.fix.online.download.OnlineChapterState
import com.reamicro.fix.online.download.OnlineOnDemandChapter
import com.reamicro.fix.online.download.OnlineOnDemandChapterRequest
import com.reamicro.fix.online.download.OnlineOnDemandMetadata
import com.reamicro.fix.online.download.OnlineOnDemandMetadataStore
import com.reamicro.fix.online.download.OnlineChapterContentValidator
import com.reamicro.fix.online.download.OnlineChapterUpdatePlanner
import com.reamicro.fix.online.download.onlineChapterUpdateMessage
import com.reamicro.fix.online.download.OnlineChapterUpdateOutcome
import com.reamicro.fix.xposed.HookRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.epub.onlineSourceIdFromUuid
import com.reamicro.fix.online.epub.safeOnlineFileName
import com.reamicro.fix.online.epub.defaultOnlineChapterHrefs
import com.reamicro.fix.online.search.parseOnlineJsonRoot
import com.reamicro.fix.online.search.resolveOnlineUrl
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.onlineJsonRuleValues
import com.reamicro.fix.online.search.onlineJsonString
import com.reamicro.fix.online.epub.onlineVolumeSegments
import com.reamicro.fix.online.epub.onlineCompletionExistingCoverExt
import com.reamicro.fix.online.epub.onlineTocNcx
import com.reamicro.fix.online.epub.onlineContentOpf
import com.reamicro.fix.online.epub.existingOnlineChapterImageHrefs
import com.reamicro.fix.online.epub.chapterXhtml
import com.reamicro.fix.online.epub.onlineBookUuid
import com.reamicro.fix.online.download.orphanProfileBackgroundFiles
import com.reamicro.fix.online.download.paragraphCommentCacheDir
import com.reamicro.fix.online.download.staleTopLevelImportCacheDirs
import com.reamicro.fix.online.download.deleteCachePath
import com.reamicro.fix.online.download.nativeCloudDownloadKey
import com.reamicro.fix.online.download.cleanupOnlineCompletionDownloadTask
import com.reamicro.fix.online.download.writeOnlineCompletionFailedChapters
import com.reamicro.fix.online.download.onlineCompletionFailedChapter
import com.reamicro.fix.online.download.sleepOnlineCompletionRetryDelay
import com.reamicro.fix.online.download.onlineCompletionRetryDelayMs
import com.reamicro.fix.online.download.retryOnlineCompletionChapters
import com.reamicro.fix.online.download.throwIfOnlineCompletionDownloadCancelled
import com.reamicro.fix.online.download.withOnlineCompletionTaskLock
import com.reamicro.fix.online.download.onlineCompletionOnDemandPendingChapter
import com.reamicro.fix.online.download.fanqieContentBody
import com.reamicro.fix.online.download.evaluateOnlineChapterUrlJs
import com.reamicro.fix.online.download.normalizeOnlineChapterText
import com.reamicro.fix.online.download.extractOnlineHtmlChapterContent
import com.reamicro.fix.online.download.writeOnlineCompletionTextAtomically
import com.reamicro.fix.online.download.writeOnlineCompletionChapterIndex
import com.reamicro.fix.online.download.unzipOnlineCompletionEpub
import com.reamicro.fix.online.download.findImportBookMethod
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.logging.logWebDav

internal fun WebDavDriveHook.cleanupStartupCacheIfNeeded(context: Context) {
    ensureOnlineCompletionCancelReceiver(context)
    if (!settingsProvider().canRunStartupCacheCleanup) return
    if (!startupCacheCleanupStarted.compareAndSet(false, true)) return
    val cutoff = System.currentTimeMillis() - STALE_IMPORT_CACHE_MIN_AGE_MS
    com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper()).postDelayed({
        com.reamicro.fix.reload.ModuleResources.thread({
            runCatching {
                val appContext = context.applicationContext ?: context
                val cacheDir = appContext.cacheDir
                val filesDir = appContext.filesDir
                val cacheRoots = listOf(
                    File(cacheDir, "downloads"),
                    File(cacheDir, "reamicro-webdav"),
                    File(cacheDir, "reamicro-local-library"),
                    File(cacheDir, "reamicro-webdav-backup"),
                    File(cacheDir, ONLINE_COMPLETION_CACHE_ROOT),

                    File(cacheDir, COVER_STAGE_CACHE_DIR),

                    paragraphCommentCacheDir(filesDir),
                )
                // Keep aggregate roots and recent work. New tasks can appear after this snapshot.
                val targets = cacheRoots.flatMap { it.listFiles()?.toList().orEmpty() } +
                    staleTopLevelImportCacheDirs(cacheDir) +
                    orphanProfileBackgroundFiles(filesDir, settingsProvider().profileBackgroundImage)
                var deletedFiles = 0
                var deletedBytes = 0L
                targets.forEach { target ->
                    if (!target.exists()) return@forEach
                    if (target.lastModified() !in 1 until cutoff || isActiveDownloadCacheDir(target)) return@forEach
                    val stat = CacheDeleteStat()
                    deleteCachePath(target, stat)
                    deletedFiles += stat.files
                    deletedBytes += stat.bytes
                }
                logWebDav("startup cache cleanup files=$deletedFiles bytes=$deletedBytes")
            }.onFailure {
                HookRuntime.log("$LOG_PREFIX startup cache cleanup failed: ${it.stackTraceToString()}")
            }
        }, "ReaMicroCacheCleanup").start()
    }, STARTUP_CACHE_CLEANUP_DELAY_MS)
}

internal fun WebDavDriveHook.isActiveDownloadCacheDir(target: File): Boolean {
    val activeDirs = onlineCompletionRunningDownloads.values.map { it.cacheDir } +
        runningNativeCloudDownloads.values.map { it.cacheDir } +
        runningWebDavDownloads.values.map { it.localFile } +
        pendingWebDavImports.values.map { it.localFile } +
        ImportCompletionState.activeFiles() + onlineCompletionUpdateCacheDirs.values
    if (activeDirs.isEmpty()) return false
    val canonicalTarget = runCatching { target.canonicalFile }.getOrDefault(target.absoluteFile)
    return activeDirs.any { active ->
        val canonicalActive = runCatching { active.canonicalFile }.getOrDefault(active.absoluteFile)
        canonicalActive == canonicalTarget || isChildPath(canonicalActive, canonicalTarget) ||
            isChildPath(canonicalTarget, canonicalActive)
    }
}

internal fun WebDavDriveHook.canCancelCloudDownload(): Boolean =
    settingsProvider().canCancelCloudDownload

internal fun WebDavDriveHook.tryHandleRunningCloudDownloadTap(book: Any, type: Int): Boolean {
    if (type in NATIVE_CLOUD_DOWNLOAD_TYPES && tryHandleRunningNativeCloudDownloadTap(book, type)) {
        return true
    }
    if (type == BACKUP_TYPE_WEBDAV && tryHandleRunningWebDavDownloadTap(book)) {
        return true
    }
    return false
}

internal fun WebDavDriveHook.tryHandleRunningNativeCloudDownloadTap(book: Any, type: Int): Boolean {
    if (!canCancelCloudDownload()) return false
    val name = book.callString("getName").ifBlank { cloudTypeTitle(type) }
    val token = runningNativeCloudDownloads[nativeCloudDownloadKey(book, type)] ?: return false
    val now = System.currentTimeMillis()
    val last = webDavDownloadCancelPrompts[token.id] ?: 0L
    if (now - last > DOWNLOAD_CANCEL_CONFIRM_WINDOW_MS) {
        webDavDownloadCancelPrompts[token.id] = now
        showToast("\u518d\u70b9\u4e00\u6b21\u53d6\u6d88\u4e0b\u8f7d")
        return true
    }
    val trackerLock = com.reamicro.fix.core.HostWorkBindings.requireCurrent().trackerLock(token.tracker)
    trackerLock.lock()
    try {
        token.cancelRequested = true
        cancelTrackedWork(token.tracker, token.id)
    } finally {
        trackerLock.unlock()
    }
    // The native worker owns these files until its completion callback releases them.
    webDavDownloadCancelPrompts.remove(token.id)
    showToast("已请求取消下载")
    logWebDav("native download cancel requested type=$type name=$name id=${token.id}")
    return true
}

internal fun WebDavDriveHook.registerNativeCloudDownload(workerManager: Any?, book: Any, handle: Any, type: Int) {
    val id = com.reamicro.fix.core.HostWorkBindings.requireCurrent().handleId(handle)
    if (id.isBlank()) return
    val tracker = runCatching {
        workerManager?.let { com.reamicro.fix.core.HostWorkBindings.requireCurrent().tracker(it) }
    }.getOrNull() ?: workTrackerRef?.get() ?: return
    rememberWorkerManager(workerManager)
    val cacheDir = File(currentContext()?.cacheDir ?: File("/data/local/tmp"), "downloads/$id")
    val token = NativeCloudDownload(
        id = id,
        key = nativeCloudDownloadKey(book, type),
        name = book.callString("getName").ifBlank { cloudTypeTitle(type) },
        type = type,
        tracker = tracker,
        cacheDir = cacheDir,
    )
    runningNativeCloudDownloads[token.key]?.let(::unregisterNativeCloudDownload)
    runningNativeCloudDownloads[token.key] = token
    runningNativeCloudDownloadsById[id] = token
    logWebDav("native download registered type=$type id=$id key=${token.key}")
}

internal fun WebDavDriveHook.unregisterNativeCloudDownload(token: NativeCloudDownload) {
    runningNativeCloudDownloads.remove(token.key, token)
    runningNativeCloudDownloadsById.remove(token.id, token)
    webDavDownloadCancelPrompts.remove(token.id)
}

internal fun WebDavDriveHook.requestOnlineCompletionDownloadCancel(context: Context, notificationId: Int, key: String) =
    requestOnlineCompletionDownloadStop(context, notificationId, key, pause = false)

internal fun WebDavDriveHook.requestOnlineCompletionDownloadPause(context: Context, notificationId: Int, key: String) =
    requestOnlineCompletionDownloadStop(context, notificationId, key, pause = true)

private fun WebDavDriveHook.requestOnlineCompletionDownloadStop(
    context: Context, notificationId: Int, key: String, pause: Boolean,
) {
    val task = onlineCompletionRunningDownloadsByNotificationId[notificationId]?.takeIf { it.key == key }
    val updateKey = key.takeIf { it.startsWith("update:") }?.removePrefix("update:")
    val control = task?.control ?: updateKey?.takeIf {
        onlineCompletionUpdateNotificationIds[it] == notificationId
    }?.let { onlineCompletionUpdateControls[it] }
    if (control == null) {
        logWebDav("online completion stale stop ignored id=$notificationId")
        return
    }
    val result = if (pause) control.requestPause() else control.requestCancel()
    val text = when (result) {
        com.reamicro.fix.online.download.OnlineDownloadControl.RequestResult.ACCEPTED -> {
            if (!pause) task?.cancelRequested = true
            if (pause) "正在暂停，保存已完成章节" else "正在取消，清理本次缓存"
        }
        com.reamicro.fix.online.download.OnlineDownloadControl.RequestResult.TOO_LATE -> "正在完成导入，提交后不能取消"
        else -> return
    }
    onlineCompletionProgressNotifiers[notificationId]?.running(0, text, force = true)
    logWebDav("online completion stop requested id=$notificationId pause=$pause result=$result")
}

internal fun WebDavDriveHook.onlineImportedBookSourceInfo(book: Any): OnlineImportedBookSourceInfo? {
    val context = currentContext()
    val sources = context?.let { appContext -> OnlineSourceStore.list(appContext) }.orEmpty()
    val backupId = bookBackupIdOf(book)
    val backupCode = book.callString("getBackupCode")
    val uuid = book.callString("getUuid")
    val publisher = book.callString("getPublisher")
    val uri = book.callString("getUri")
    val epubMetadata = onlineCompletionGeneratedEpubMetadata(book)
    logWebDav(
        "online completion source probe title=${book.callString("getTitle")} " +
            "dirOk=${runCatching { bookDirectory(book).canonicalFile.isDirectory }.getOrDefault(false)} " +
            "opfOk=${runCatching { File(bookDirectory(book).canonicalFile, "OEBPS/content.opf").isFile }.getOrDefault(false)} " +
            "marker=${epubMetadata.hasMarker} srcId=${epubMetadata.sourceId} detail=${epubMetadata.detailUrl.take(60)} " +
            "uuid=${book.callString("getUuid")} backupId=${bookBackupIdOf(book).take(60)}",
    )
    val sourceIdCandidates = listOf(
        epubMetadata.sourceId,
        onlineSourceIdFromEncodedValue(backupCode),
        onlineSourceIdFromEncodedValue(backupId),
        onlineSourceIdFromUuid(uuid),
    ).filter { it.isNotBlank() }.distinct()
    val identityCandidates = if (epubMetadata.sourceId.isNotBlank()) listOf(epubMetadata.sourceId) else sourceIdCandidates
    val sourceById = identityCandidates.firstNotNullOfOrNull { candidate ->
        sources.firstOrNull { source -> OnlineSourceStore.matchesIdentity(source, candidate) }
    }
    val sourceByPublisher = sources.firstOrNull { source ->
        source.name == publisher || source.id == publisher
    }
    val sourceByMetadataName = sources.firstOrNull { source ->
        source.name == epubMetadata.sourceName
    }
    val detailUrl = epubMetadata.detailUrl
        .ifBlank { onlineCompletionQueryParameter(backupId, "detail") }
        .ifBlank { uri.takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty() }
    val source = sourceById ?: if (epubMetadata.sourceId.isBlank()) sourceByMetadataName ?: sourceByPublisher else null
    val sourceId = source?.id
        ?: epubMetadata.sourceId
            .ifBlank { sourceIdCandidates.firstOrNull { it.startsWith("online_") }.orEmpty() }

    val hasOnlineCompletionMarker = epubMetadata.hasMarker &&
        (isOnlineCompletionLocalBook(book) ||
            sourceId.isNotBlank() ||
            detailUrl.isNotBlank())
    if (!hasOnlineCompletionMarker) return null
    val sourceName = source?.name.orEmpty()
        .ifBlank { epubMetadata.sourceName }
        .ifBlank { onlineCompletionQueryParameter(backupId, "name") }
        .ifBlank { publisher.takeUnless(::isPathLikeOnlineSourceName).orEmpty() }
        .ifBlank { sourceId }
    return OnlineImportedBookSourceInfo(
        sourceId = source?.id ?: sourceId,
        sourceName = sourceName.ifBlank { "未知源" },
        detailUrl = detailUrl,
        source = source,
    )
}

internal fun WebDavDriveHook.startOnlineCompletionChapterUpdate(
    book: Any,
    info: OnlineImportedBookSourceInfo,
    silent: Boolean = false,
    skipCompleted: Boolean = false,
    parentControl: com.reamicro.fix.online.download.OnlineDownloadControl? = null,
    onProgress: ((Int, String) -> Unit)? = null,
    onComplete: ((OnlineChapterUpdateOutcome) -> Unit)? = null,
) {
    val execution = storageExecutionGeneration.get()
    val title = book.callString("getTitle").ifBlank { "未命名图书" }
    if (storageClosed || !settingsProvider().moduleEnabled) {
        onComplete?.invoke(OnlineChapterUpdateOutcome(title, error = "章节更新已停止"))
        return
    }
    fun reject(message: String) {
        if (!silent) showToast(message)
        onComplete?.invoke(OnlineChapterUpdateOutcome(title, error = message))
    }
    val context = currentContext()
    if (context == null) {
        reject("无法启动章节更新：缺少 Context")
        return
    }
    val appContext = context.applicationContext ?: context
    val storageIdentity = AccountHostRuntime.storageSnapshot(activityProvider())
    if (storageIdentity == null || book.callLong("getUid") != storageIdentity.accountId) {
        reject("账号状态正在变化，请稍后重新更新")
        return
    }
    val source = (OnlineSourceStore.list(appContext).firstOrNull { source ->
        OnlineSourceStore.matchesIdentity(source, info.sourceId)
    })?.let { OnlineSourceDownloadPolicyStore.attach(appContext, it) }
    if (source == null) {
        reject("在线补全源不可用：${info.sourceName}")
        return
    }
    if (OnlineSourceDownloadPolicyStore.remainingToday(appContext, source) == 0) {
        reject(OnlineSourceDownloadPolicyStore.limitReachedMessage(source))
        return
    }
    val detailUrl = info.detailUrl
        .ifBlank { book.callString("getUri").takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty() }
    if (detailUrl.isBlank()) {
        reject("缺少在线源详情地址，无法更新章节")
        return
    }
    val key = "${storageIdentity.accountId}:${storageIdentity.generation}|${book.callLong("getId")}|${book.callString("getUuid")}"
    val cancellation = if (parentControl != null) TaskCancellation() else TaskCancellation.current()?.child() ?: TaskCancellation()
    val control = com.reamicro.fix.online.download.OnlineDownloadControl(cancellation)
    if (onlineCompletionRunningUpdates.putIfAbsent(key, cancellation) != null) {
        cancellation.cancel()
        reject("正在更新：$title")
        return
    }
    onlineCompletionUpdateControls[key] = control
    val parentStop = parentControl?.let(control::attachParent)
    val sourceRegistration = OnlineSourceStore.observeChanges {
        if (!OnlineSourceStore.isCurrent(source)) cancellation.cancel()
    }
    val completed = java.util.concurrent.atomic.AtomicBoolean()
    var interruptRegistration: AutoCloseable? = null
    val notificationFailureReported = java.util.concurrent.atomic.AtomicBoolean()
    var progressNotifier: WebDavDriveHook.OnlineCompletionProgressNotifier? = null
    fun complete(outcome: OnlineChapterUpdateOutcome) {
        if (!completed.compareAndSet(false, true)) return
        onlineCompletionRunningUpdates.remove(key, cancellation)
        onlineCompletionUpdateControls.remove(key, control)
        onlineCompletionUpdateNotificationIds.remove(key)?.let { onlineCompletionProgressNotifiers.remove(it) }
        sourceRegistration.close()
        interruptRegistration?.close()
        parentStop?.close()
        control.finish()
        cancellation.cancel()
        runCatching { onComplete?.invoke(outcome) }
            .onFailure { HookRuntime.logError("$LOG_PREFIX online update completion callback failed", it) }
    }
    fun notifySafely(action: () -> Unit) {
        runCatching(action).onFailure {
            if (notificationFailureReported.compareAndSet(false, true)) {
                HookRuntime.logError("$LOG_PREFIX online update progress notification failed", it)
            }
        }
    }
    fun reportFailure(error: Throwable) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        HookRuntime.logError("$LOG_PREFIX online completion update failed", error)
        notifySafely {
            progressNotifier?.finish(error.message ?: "章节更新失败", null, success = false)
            if (!silent) showToast("章节更新失败：${error.message ?: title}")
        }
    }
    try {
        ensureOnlineCompletionCancelReceiver(appContext)
        val tracker = currentWorkTracker()
        val workId = tracker?.let { createOnlineCompletionTrackedTask(it, "$title 更新") }
        val notificationId = onlineCompletionNotificationIds.incrementAndGet()
        onlineCompletionUpdateNotificationIds[key] = notificationId
        val notifier = OnlineCompletionProgressNotifier(
            context = appContext,
            notificationId = notificationId,
            notificationKey = "update:$key",
            cancellable = true,
            bookName = title,
            tracker = tracker,
            workId = workId,
            notificationsEnabled = !silent,
        )
        progressNotifier = notifier
        notifySafely {
            notifier.running(0, "准备更新", force = true)
            if (!silent) showToast("开始检查更新：$title")
        }
        val thread = com.reamicro.fix.reload.ModuleResources.thread({
            var outcome = OnlineChapterUpdateOutcome(title, error = "章节更新未完成")
            try {
                cancellation.run {
                fun checkCurrent() {
                    cancellation.check()
                    OnlineSourceStore.requireCurrent(source)
                    check(!storageClosed && settingsProvider().moduleEnabled &&
                        execution == storageExecutionGeneration.get() &&
                        AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) {
                        "账号或模块状态已变化，已停止章节更新"
                    }
                }
                val latest = findLocalBookById(book.callLong("getId"), storageIdentity, checkCurrent = ::checkCurrent)
                    ?.takeIf { it.callString("getUuid") == book.callString("getUuid") }
                    ?: error("图书记录已变化，已停止章节更新")
                val target = OnlineDownloadTarget(
                    source = source,
                    query = title,
                    result = OnlineBookSearchResult(
                        sourceName = source.name,
                        name = title,
                        author = latest.callString("getAuthor").ifBlank { book.callString("getAuthor") },
                        coverUrl = latest.callString("getCover").ifBlank { book.callString("getCover") },
                        detailUrl = detailUrl,
                        intro = "",
                    ),
                )
                val result = updateOnlineCompletionBookIncrementally(
                    book = latest,
                    target = target,
                    skipCompleted = skipCompleted,
                    storageIdentity = storageIdentity,
                    control = control,
                    onProgress = { progress, message ->
                        notifySafely {
                            notifier.running(progress, message)
                            onProgress?.invoke(progress.coerceIn(0, 99), message)
                        }
                    },
                )
                outcome = OnlineChapterUpdateOutcome(title, result.added, result.failed, result.retried,
                    skipped = result.skipped, paused = result.paused, pending = result.pending)
                val message = if (result.paused) {
                    "已暂停，已保存 ${result.added + result.retried} 章，可长按更新继续"
                } else if (result.pending > 0) {
                    "已保存 ${result.added + result.retried} 章，剩余 ${result.pending} 章待下载${onlineCompletionFailureSuffix(result.failed)}"
                } else onlineChapterUpdateMessage(result.added, result.failed, result.retried, result.styled)
                notifySafely {
                    notifier.finish(message, detailUrl, success = true)
                    if (!silent && !cancellation.isCancelled) showToast("$message：$title")
                }
                }
            } catch (error: Throwable) {
                outcome = OnlineChapterUpdateOutcome(title, error = error.message ?: "章节更新失败")
                if (error is java.util.concurrent.CancellationException || cancellation.isCancelled) {
                    val message = if (control.shouldPause && !control.isCancelled) "更新已暂停，未新增完整章节" else "更新已取消，本次未提交修改已清理"
                    notifySafely { notifier.finish(message, null, success = false) }
                } else reportFailure(error)
            } finally {
                complete(outcome)
            }
        }, "ReaMicroOnlineCompletionUpdate")
        val networkInterrupt = control.networkCancellation.register(thread::interrupt)
        val lifetimeInterrupt = cancellation.register(thread::interrupt)
        interruptRegistration = AutoCloseable { networkInterrupt.close(); lifetimeInterrupt.close() }
        thread.start()
    } catch (error: Throwable) {
        try {
            reportFailure(error)
        } finally {
            complete(OnlineChapterUpdateOutcome(title, error = error.message ?: "章节更新失败"))
        }
    }
}

internal fun WebDavDriveHook.retryOnlineCompletionLoggedFailures(
    bookDir: File,
    target: OnlineDownloadTarget,
    tocSnapshot: OnlineTocSnapshot,
    failures: List<OnlineFailedChapter>,
    chapterHrefs: List<String>,
    maxChapterAttempts: Int? = null,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
    onProgress: (Int, String) -> Unit,
): OnlineFailedRetryResult {
    checkCurrent()
    if (failures.isEmpty()) return OnlineFailedRetryResult(0, 0, false, 0)
    val remoteChapters = tocSnapshot.chapters
    val candidates = failures
        .filter { it.index in remoteChapters.indices }
        .distinctBy { it.index }
        .let { values -> maxChapterAttempts?.let { values.take(it.coerceAtLeast(0)) } ?: values }
    if (candidates.isEmpty()) {
        writeOnlineCompletionFailedChapters(bookDir, target, failures, commit = commit, checkCurrent = checkCurrent)
        return OnlineFailedRetryResult(0, failures.size, false, 0)
    }
    logWebDav(
        "online completion retry logged failures start title=${target.result.name} " +
            "candidates=${candidates.size} logged=${failures.size}",
    )
    val remaining = failures.associateBy { it.index }.toMutableMap()
    val initialAttempts = failures.associate { it.index to it.attempts }
    val roundAttemptsByIndex = mutableMapOf<Int, Int>()
    val failed = candidates.mapTo(linkedSetOf()) { it.index }
    val nonRetryable = linkedSetOf<Int>()
    val failureMessages = candidates.associate { it.index to it.message }.toMutableMap()
    var dailyLimitMessage: String? = null
    var successCount = 0
    var consecutiveServiceFailures = 0
    val batchSession = onlineChapterBatchSession(target, remoteChapters, candidates.map { it.index })

    fun attemptLoggedChapter(index: Int, immediateRetry: Boolean): Boolean {
        checkCurrent()
        val chapter = remoteChapters[index]
        dailyLimitMessage?.let { message ->
            nonRetryable.add(index)
            failureMessages[index] = message
            remaining[index] = onlineCompletionFailedChapter(
                target = target,
                chapter = chapter,
                index = index,
                attempts = initialAttempts[index] ?: 0,
                message = message,
                href = chapterHrefs.getOrNull(index).orEmpty(),
            )
            return false
        }
        val maxAttemptsThisRound = if (immediateRetry) 2 else 1
        var roundAttempts = 0
        while ((roundAttemptsByIndex[index] ?: 0) < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT &&
            roundAttempts < maxAttemptsThisRound && index !in nonRetryable
        ) {
            roundAttemptsByIndex[index] = (roundAttemptsByIndex[index] ?: 0) + 1
            roundAttempts++
            val localAttempt = roundAttemptsByIndex[index] ?: 0
            val totalAttempts = (initialAttempts[index] ?: 0) + localAttempt
            runCatching {
                checkCurrent()
                downloadOnlineChapter(
                    target,
                    tocSnapshot.detail.url,
                    tocSnapshot.detail.body,
                    chapter,
                    index,
                    batchSession,
                )
            }.onSuccess { chapterContent ->
                writeOnlineCompletionChapterToBookDir(
                    bookDir = bookDir,
                    index = index,
                    chapter = chapterContent,
                    href = chapterHrefs.getOrNull(index).orEmpty(),
                    target = target,
                    commit = commit,
                    checkCurrent = checkCurrent,
                )
                failed.remove(index)
                remaining.remove(index)
                failureMessages.remove(index)
                val recoveredFromServiceFailure = consecutiveServiceFailures > 0
                consecutiveServiceFailures = 0
                if (recoveredFromServiceFailure) {
                    onProgress(
                        12,
                        "上游已恢复，继续重试第 ${index + 1}/${remoteChapters.size} 章",
                    )
                }
                successCount++
                logWebDav(
                    "online completion logged failed chapter retried ${index + 1}/${remoteChapters.size} " +
                        "transport=${chapterContent.transport} attempt=$localAttempt " +
                        "totalAttempts=$totalAttempts content=${chapterContent.content.length}",
                )
                return true
            }.onFailure { error ->
                TaskCancellation.rethrowIfCancelled(error)
                checkCurrent()
                val message = error.message.orEmpty().ifBlank { error.javaClass.name }
                if (error is OnlineSourceDailyLimitException) {
                    dailyLimitMessage = message
                    nonRetryable.add(index)
                }
                if (isPermanentOnlineChapterFailure(error)) {
                    nonRetryable.add(index)
                }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE && index !in nonRetryable &&
                    localAttempt < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT
                ) {
                    consecutiveServiceFailures++
                    val waitMs = onlineHttpRetryDelayMs(error, consecutiveServiceFailures)
                    val reason = if (retryKind == OnlineHttpRetryKind.CIRCUIT_OPEN) "上游熔断" else "上游暂时异常"
                    onProgress(12, "$reason，等待 ${waitMs / 1_000} 秒后继续重试第 ${index + 1} 章")
                    logWebDav(
                        "online completion logged retry service backoff chapter=${index + 1}/${remoteChapters.size} " +
                            "kind=$retryKind consecutive=$consecutiveServiceFailures wait=${waitMs}ms",
                    )
                    sleepOnlineCompletionRetryDelay(null, waitMs)
                }
                failureMessages[index] = message
                remaining[index] = onlineCompletionFailedChapter(
                    target = target,
                    chapter = chapter,
                    index = index,
                    attempts = totalAttempts,
                    message = message,
                    href = chapterHrefs.getOrNull(index).orEmpty(),
                )
                logWebDav(
                    "online completion logged failed chapter retry failed ${index + 1}/${remoteChapters.size} " +
                        "attempt=$localAttempt/${ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT} " +
                        "totalAttempts=$totalAttempts title=${chapter.title} " +
                        "error=${error.javaClass.name}: ${error.message.orEmpty()}",
                )
            }
        }
        return false
    }

    candidates.forEachIndexed { retryIndex, failure ->
        val progress = 12 + ((retryIndex + 1) * 10 / candidates.size.coerceAtLeast(1))
        onProgress(progress, "重试失败章节 ${retryIndex + 1}/${candidates.size}${onlineCompletionFailureSuffix(failed.size)}")
        attemptLoggedChapter(failure.index, immediateRetry = true)
    }
    retryOnlineCompletionChapters(
        candidates = { failed.filter { it !in nonRetryable && (roundAttemptsByIndex[it] ?: 0) < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT } },
        beforeRound = { round, remaining ->
            onProgress(23, "等待重试失败章节 ${remaining.size} 章${onlineCompletionFailureSuffix(failed.size)}")
            sleepOnlineCompletionRetryDelay(null, onlineCompletionRetryDelayMs(round))
        },
        attempt = { index, retryIndex, count ->
            val progress = 23 + ((retryIndex + 1) * 8 / count.coerceAtLeast(1))
            onProgress(progress, "重试失败章节 ${retryIndex + 1}/$count${onlineCompletionFailureSuffix(failed.size)}")
            attemptLoggedChapter(index, immediateRetry = false)
        },
    )
    val unresolved = remaining.values.sortedBy { it.index }
    logOnlineCompletionFinalFailures(target, unresolved.filter { it.index in failed }, "update-retry")
    writeOnlineCompletionFailedChapters(bookDir, target, unresolved, commit = commit, checkCurrent = checkCurrent)
    return OnlineFailedRetryResult(
        retried = successCount,
        failed = unresolved.size,
        changed = successCount > 0 || unresolved.size != failures.size,
        attempted = candidates.size,
    )
}

internal fun WebDavDriveHook.showOnlineCompletionDownloadModeDialog(target: OnlineDownloadTarget) {
    if (storageClosed || !settingsProvider().moduleEnabled) return
    val context = currentContext()
    if (context == null) {
        showToast("无法启动在线补全下载：缺少 Context")
        return
    }
    target.source = OnlineSourceDownloadPolicyStore.attach(context, target.source)
    val mode = if (target.source.preferOnDemandLoading) {
        OnlineBookDownloadMode.ON_DEMAND
    } else {
        OnlineBookDownloadMode.FULL
    }
    startOnlineCompletionDownload(target, mode)
}

internal fun WebDavDriveHook.startOnlineCompletionDownload(
    selection: OnlineDownloadTarget,
    mode: OnlineBookDownloadMode = OnlineBookDownloadMode.FULL,
) {
    // Each invocation owns its parser session and disk cache, including parallel mode choices.
    val target = selection.copy()
    val execution = storageExecutionGeneration.get()
    if (storageClosed || !settingsProvider().moduleEnabled) return
    val context = currentContext()
    if (context == null) {
        showToast("无法启动在线补全下载：缺少 Context")
        return
    }
    val appContext = context.applicationContext ?: context
    val storageIdentity = AccountHostRuntime.storageSnapshot(activityProvider())
    if (storageIdentity == null) {
        showToast("账号状态正在变化，请稍后重新下载")
        return
    }
    target.source = OnlineSourceDownloadPolicyStore.attach(appContext, target.source)
    if (!OnlineSourceStore.isCurrent(target.source)) {
        showToast("在线源已更新或删除，请重新搜索后下载")
        return
    }
    if (OnlineSourceDownloadPolicyStore.remainingToday(appContext, target.source) == 0) {
        showToast(OnlineSourceDownloadPolicyStore.limitReachedMessage(target.source))
        return
    }
    ensureOnlineCompletionCancelReceiver(appContext)
    val key = "${storageIdentity.accountId}:${storageIdentity.generation}|${target.source.id}|${target.result.detailUrl}|${target.result.name}|${mode.name}"
    if (onlineCompletionRunningDownloads.containsKey(key)) {
        showToast("正在下载：${target.result.name}")
        return
    }
    val notificationId = onlineCompletionNotificationIds.incrementAndGet()
    val tracker = currentWorkTracker()
    val workId = tracker?.let { createOnlineCompletionTrackedTask(it, target.result.name) }
    val cacheDir = File(
        appContext.cacheDir ?: File("/data/local/tmp"),
        "$ONLINE_COMPLETION_CACHE_ROOT/${System.currentTimeMillis()}_${UUID.randomUUID()}",
    )
    val task = OnlineCompletionDownloadTask(
        storageIdentity = storageIdentity,
        notificationId = notificationId,
        key = key,
        name = target.result.name,
        cacheDir = cacheDir,
        tracker = tracker,
        workId = workId,
        checkActive = { !storageClosed && settingsProvider().moduleEnabled &&
            execution == storageExecutionGeneration.get() &&
            com.reamicro.fix.reload.ModuleResources.accepting && OnlineSourceStore.isCurrent(target.source) },
    )
    if (onlineCompletionRunningDownloads.putIfAbsent(key, task) != null) {
        cancelOnlineCompletionTrackedWork(task)
        showToast("正在下载：${target.result.name}")
        return
    }
    onlineCompletionRunningDownloadsByNotificationId[notificationId] = task
    target.imageCache = com.reamicro.fix.online.download.OnlineChapterImageCache(File(cacheDir, "images"))
    val sourceRegistration = OnlineSourceStore.observeChanges {
        if (!OnlineSourceStore.isCurrent(target.source)) task.cancellation.cancel()
    }
    val modeLabel = if (mode == OnlineBookDownloadMode.ON_DEMAND) "逐章加载" else "整本下载"
    showToast("已开始$modeLabel：${target.result.name}")
    logWebDav(
        "online completion download start source=${target.source.name} " +
            "book=${target.result.name} detail=${target.result.detailUrl} mode=${mode.name}",
    )
    val progressNotifier = OnlineCompletionProgressNotifier(
        context = context,
        notificationId = notificationId,
        notificationKey = key,
        cancellable = true,
        bookName = target.result.name,
        tracker = tracker,
        workId = workId,
    )
    val notificationReady = progressNotifier.running(0, "准备下载", force = true)
    if (!notificationReady) {
        showToast("阅微通知权限未开启，下载继续在后台进行")
    }
    val lifetime = com.reamicro.fix.online.download.OnlineDownloadLifetime {
        sourceRegistration.close()
        cleanupOnlineCompletionDownloadTask(task)
        target.imageCache = null
        onlineCompletionRunningDownloads.remove(key, task)
        onlineCompletionRunningDownloadsByNotificationId.remove(notificationId, task)
        onlineCompletionProgressNotifiers.remove(notificationId, progressNotifier)
    }
    try {
        val thread = com.reamicro.fix.reload.ModuleResources.thread({
            val localFile = File(task.cacheDir, "${safeOnlineFileName(target.result.name)}.epub")
            try {
                task.cacheDir.mkdirs()
                val failedChapters = task.networkCancellation.run {
                    throwIfOnlineCompletionDownloadCancelled(task)
                    if (mode == OnlineBookDownloadMode.ON_DEMAND) {
                        downloadOnlineCompletionOnDemandBook(target, localFile, task) { progress, message ->
                            throwIfOnlineCompletionDownloadCancelled(task)
                            progressNotifier.running(progress, message)
                        }
                    } else {
                        downloadOnlineCompletionBook(target, localFile, task,
                            onPartialReady = { partialFile, progress ->
                                task.cancellation.run {
                                    importOnlineCompletionBook(task, partialFile, target, importProgress = progress,
                                        onWaiting = { progressNotifier.running(progress, "首批章节等待导入", force = true) },
                                        onStarted = { progressNotifier.running(progress, "正在导入首批章节", force = true) })
                                    task.publishedChapterIndices.addAll(task.downloadedChapters.keys)
                                    task.control.resumeAfterCheckpoint()
                                }
                                throwIfOnlineCompletionDownloadCancelled(task)
                                progressNotifier.running(progress, "首批章节已导入，继续下载其余章节", force = true)
                            },
                            onProgress = { progress, message ->
                                throwIfOnlineCompletionDownloadCancelled(task)
                                progressNotifier.running(progress, message)
                            },
                        )
                    }
                }
                task.cancellation.run {
                    throwIfOnlineCompletionDownloadCancelled(task)
                    progressNotifier.running(94,
                        if (task.importedBookDir != null) "正在保存下载结果" else "正在导入完整章节", force = true)
                    if (task.importedBookDir != null) {
                        saveOnlineCompletionImportedChapters(task, target, final = true)
                    } else {
                        importOnlineCompletionBook(task, localFile, target,
                            onWaiting = { progressNotifier.running(94, "等待导入队列", force = true) },
                            onStarted = { progressNotifier.running(94, "正在导入", force = true) })
                    }
                    val completionMessage = if (mode == OnlineBookDownloadMode.ON_DEMAND) {
                        "已导入逐章加载书籍"
                    } else "已导入阅微${onlineCompletionFailureSuffix(failedChapters)}"
                    progressNotifier.finish(completionMessage, target.result.detailUrl, success = true)
                }
            } catch (error: Throwable) {
                val cancelledMessage = if (task.importedBookDir != null) "下载已取消，已保留导入章节" else "下载已取消，未导入书架"
                if (task.control.shouldPause && !task.control.isCancelled && !task.control.isPublishing) {
                    try {
                        if (task.control.preparePausedPublication()) {
                            task.cancellation.run {
                                publishPausedOnlineCompletionBook(target, localFile, task, mode, progressNotifier)
                            }
                        } else {
                            progressNotifier.finish(cancelledMessage, null, success = false)
                        }
                    } catch (pauseError: Throwable) {
                        if (task.cancelRequested || task.cancellation.isCancelled || pauseError is java.util.concurrent.CancellationException) {
                            progressNotifier.finish(cancelledMessage, null, success = false)
                        } else {
                            progressNotifier.finish(pauseError.message ?: "暂停保存失败", null, success = false)
                            HookRuntime.logError("$LOG_PREFIX paused download publication failed", pauseError)
                        }
                    }
                } else if (error is java.util.concurrent.CancellationException || task.cancelRequested || task.cancellation.isCancelled) {
                    progressNotifier.finish(cancelledMessage, null, success = false)
                } else {
                    progressNotifier.finish(error.message ?: "下载失败", null, success = false)
                    HookRuntime.logError("$LOG_PREFIX online completion download failed", error)
                }
            } finally {
                task.control.finish()
                task.cancellation.cancel()
                lifetime.close()
            }
        }, "ReaMicroOnlineCompletionDownload")
        task.thread = thread
        task.networkCancellation.register(thread::interrupt)
        task.cancellation.register(thread::interrupt)
        thread.start()
    } catch (error: Throwable) {
        task.control.finish()
        task.cancellation.cancel()
        lifetime.close()
        progressNotifier.finish(error.message ?: "无法启动下载", null, success = false)
        HookRuntime.logError("$LOG_PREFIX online download could not start", error)
    }
}

private fun WebDavDriveHook.publishPausedOnlineCompletionBook(
    target: OnlineDownloadTarget,
    file: File,
    task: OnlineCompletionDownloadTask,
    mode: OnlineBookDownloadMode,
    notifier: WebDavDriveHook.OnlineCompletionProgressNotifier,
) {
    throwIfOnlineCompletionDownloadCancelled(task)
    val chapters = task.tocSnapshot?.chapters.orEmpty()
    val completed = task.downloadedChapters.toMap().filterKeys { it in chapters.indices }
    if (task.importedBookDir != null) {
        notifier.running(94, "保存已下载章节", force = true)
        saveOnlineCompletionImportedChapters(task, target, final = false)
        notifier.finish("已暂停，已保存 ${completed.size} 章，可长按更新继续", target.result.detailUrl, success = true)
        return
    }
    if (completed.isEmpty()) {
        notifier.finish("已暂停，尚无完整章节，未导入书架", null, success = false)
        return
    }
    val hrefs = defaultOnlineChapterHrefs(chapters.size)
    val metadata = OnlineOnDemandMetadata(
        sourceId = target.source.id, detailUrl = target.result.detailUrl, bookName = target.result.name,
        mode = mode,
        chapters = chapters.mapIndexed { index, chapter ->
            OnlineOnDemandChapter(
                sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
                url = chapter.url, title = chapter.title, volumeTitle = chapter.volumeTitle,
                href = hrefs[index],
                state = if (index in completed) OnlineChapterState.READY else OnlineChapterState.PENDING,
                downloadedAtMs = if (index in completed) System.currentTimeMillis() else 0L,
            )
        },
    )
    notifier.running(88, "已停止网络，保存 ${completed.size} 章", force = true)
    writeOnlineCompletionEpub(file, target,
        chapters.mapIndexed { index, chapter -> completed[index] ?: if (mode == OnlineBookDownloadMode.ON_DEMAND) {
            onlineCompletionOnDemandPendingChapter(chapter, index)
        } else com.reamicro.fix.online.download.onlineCompletionPausedPendingChapter(chapter, index) },
        task.downloadedCover, onDemandMetadata = metadata, allowNetworkImages = false,
        checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) })
    throwIfOnlineCompletionDownloadCancelled(task)
    importOnlineCompletionBook(task, file, target,
        onWaiting = { notifier.running(94, "暂停保存：等待导入队列", force = true) },
        onStarted = { notifier.running(94, "暂停保存：正在导入已完成章节", force = true) })
    notifier.finish("已暂停，已保存 ${completed.size} 章，可长按更新继续", target.result.detailUrl, success = true)
}

internal fun WebDavDriveHook.createOnlineCompletionTrackedTask(tracker: Any, bookName: String): String? =
    runCatching {
        val id = "online-${UUID.randomUUID()}"
        com.reamicro.fix.core.HostWorkBindings.requireCurrent().create(
            tracker,
            id,
            "download",
            "$ONLINE_COMPLETION_TITLE-$bookName",
            newWorkState("Running", 0, null, null, bookName),
        )
        logWebDav("online completion tracked task created id=$id book=$bookName")
        id
    }.getOrElse {
        logWebDav("online completion tracked task failed: ${it.message ?: it.javaClass.name}")
        null
    }

internal fun WebDavDriveHook.onlineChapterBatchSession(
    target: OnlineDownloadTarget,
    chapters: List<OnlineChapter>,
    eligibleIndices: Iterable<Int>,
): OnlineChapterBatchSession =
    OnlineChapterBatchSession(target, chapters, eligibleIndices.toSet())

internal fun WebDavDriveHook.downloadOnlineCompletionOnDemandBook(
    target: OnlineDownloadTarget,
    outputFile: File,
    task: OnlineCompletionDownloadTask,
    onProgress: (Int, String) -> Unit,
): Int {
    throwIfOnlineCompletionDownloadCancelled(task)
    val tocSnapshot = loadOnlineCompletionToc(target, onProgress)
    task.tocSnapshot = tocSnapshot
    val chapters = tocSnapshot.chapters
    val initialCount = ONLINE_COMPLETION_ON_DEMAND_INITIAL_CHAPTERS.coerceAtMost(chapters.size)
    val allowance = OnlineSourceDownloadPolicyStore.remainingToday(currentContext(), target.source)
    if (allowance != null && allowance < initialCount) {
        error("今日剩余下载额度不足 $initialCount 章，请明日再试")
    }
    onProgress(8, "下载首批章节 0/$initialCount")
    val downloaded = MutableList<OnlineDownloadedChapter?>(chapters.size) { null }
    val states = MutableList(chapters.size) { OnlineChapterState.PENDING }
    val attempts = IntArray(chapters.size)
    val errors = MutableList(chapters.size) { "" }
    val batchSession = onlineChapterBatchSession(target, chapters, 0 until initialCount)
    var consecutiveServiceFailures = 0
    for (index in 0 until initialCount) {
        throwIfOnlineCompletionDownloadCancelled(task)
        onProgress(10 + ((index + 1) * 45 / initialCount.coerceAtLeast(1)), "下载首批章节 ${index + 1}/$initialCount")
        var lastError: Throwable? = null
        repeat(2) { retry ->
            if (downloaded[index] != null) return@repeat
            if (lastError?.let(::isPermanentOnlineChapterFailure) == true ||
                lastError is OnlineSourceDailyLimitException
            ) return@repeat
            attempts[index]++
            runCatching {
                downloadOnlineChapter(
                    target,
                    tocSnapshot.detail.url,
                    tocSnapshot.detail.body,
                    chapters[index],
                    index,
                    batchSession,
                )
            }.onSuccess { content ->
                downloaded[index] = content
                task.downloadedChapters[index] = content
                states[index] = OnlineChapterState.READY
                val recoveredFromServiceFailure = consecutiveServiceFailures > 0
                consecutiveServiceFailures = 0
                if (recoveredFromServiceFailure) {
                    onProgress(
                        10 + ((index + 1) * 45 / initialCount.coerceAtLeast(1)),
                        "上游已恢复，继续下载首批章节 ${index + 1}/$initialCount",
                    )
                }
            }.onFailure { error ->
                TaskCancellation.rethrowIfCancelled(error)
                throwIfOnlineCompletionDownloadCancelled(task)
                lastError = error
                errors[index] = error.message.orEmpty().ifBlank { error.javaClass.name }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE && retry == 0 && !isPermanentOnlineChapterFailure(error)) {
                    consecutiveServiceFailures++
                    val waitMs = onlineHttpRetryDelayMs(error, consecutiveServiceFailures)
                    val reason = if (retryKind == OnlineHttpRetryKind.CIRCUIT_OPEN) "上游熔断" else "上游暂时异常"
                    onProgress(
                        10 + ((index + 1) * 45 / initialCount.coerceAtLeast(1)),
                        "$reason，等待 ${waitMs / 1_000} 秒后继续首批第 ${index + 1} 章",
                    )
                    sleepOnlineCompletionRetryDelay(task, waitMs)
                }
            }
        }
        if (downloaded[index] == null) {
            states[index] = OnlineChapterState.FAILED
            throw lastError ?: error("第 ${index + 1} 章下载失败")
        }
    }
    val cover = target.result.coverUrl.takeIf { it.isNotBlank() }?.let { coverUrl ->
        runCatching {
            OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                downloadOnlineImageBytes(target, coverUrl, isCover = true)
            }
        }.onFailure { error ->
            TaskCancellation.rethrowIfCancelled(error)
            com.reamicro.fix.xposed.HookRuntime.logAlways(
                "[ReaMicro] online completion on-demand cover failed url=${coverUrl.take(160)} " +
                    "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }.getOrNull()

            ?: run {
                if (!isFanqieCoverUrl(coverUrl)) return@run null
                runCatching { downloadDiscoverCoverViaWebPage(this, target.result.detailUrl) }
                    .onFailure { error ->
                        com.reamicro.fix.xposed.HookRuntime.logAlways(
                            "[ReaMicro] online completion cover web fallback failed " +
                                "detail=${target.result.detailUrl.take(160)} " +
                                "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                        )
                    }
                    .getOrNull()?.second
            }
    }
    task.downloadedCover = cover
    val chapterHrefs = defaultOnlineChapterHrefs(chapters.size)
    val metadata = OnlineOnDemandMetadata(
        sourceId = target.source.id,
        detailUrl = target.result.detailUrl,
        bookName = target.result.name,
        mode = OnlineBookDownloadMode.ON_DEMAND,
        chapters = chapters.mapIndexed { index, chapter ->
            OnlineOnDemandChapter(
                sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
                url = chapter.url,
                title = chapter.title,
                volumeTitle = chapter.volumeTitle,
                href = chapterHrefs[index],
                state = states[index],
                downloadedAtMs = if (states[index] == OnlineChapterState.READY) System.currentTimeMillis() else 0L,
                attempts = attempts[index],
                lastError = errors[index],
            )
        },
    )
    val epubChapters = chapters.mapIndexed { index, chapter ->
        downloaded[index] ?: onlineCompletionOnDemandPendingChapter(chapter, index)
    }
    onProgress(62, "生成完整目录 EPUB")
    writeOnlineCompletionEpub(
        file = outputFile,
        target = target,
        chapters = epubChapters,
        cover = cover,
        onDemandMetadata = metadata,
        checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) },
    )
    throwIfOnlineCompletionDownloadCancelled(task)
    return 0
}

internal fun WebDavDriveHook.downloadOnlineCompletionBook(
    target: OnlineDownloadTarget,
    outputFile: File,
    task: OnlineCompletionDownloadTask,
    onPartialReady: (File, Int) -> Unit,
    onProgress: (Int, String) -> Unit,
): Int {
    throwIfOnlineCompletionDownloadCancelled(task)
    val tocSnapshot = loadOnlineCompletionToc(target, onProgress)
    task.tocSnapshot = tocSnapshot
    throwIfOnlineCompletionDownloadCancelled(task)
    val detail = tocSnapshot.detail
    val chapters = tocSnapshot.chapters
    val chapterAllowance = OnlineSourceDownloadPolicyStore.remainingToday(currentContext(), target.source)
    val allowedChapterCount = chapterAllowance?.coerceAtMost(chapters.size) ?: chapters.size
    val quotaPendingMessage = OnlineSourceDownloadPolicyStore.limitReachedMessage(target.source)
    logWebDav(
        "online completion chapters=${chapters.size} " +
            "allowed=$allowedChapterCount first=${chapters.firstOrNull()?.title.orEmpty()} " +
            "firstUrl=${chapters.firstOrNull()?.url.orEmpty()}",
    )
    onProgress(5, "开始下载章节 0/${chapters.size}")
    val downloaded = MutableList<OnlineDownloadedChapter?>(chapters.size) { null }
    val attempts = IntArray(chapters.size)
    val failed = linkedSetOf<Int>()
    val nonRetryable = linkedSetOf<Int>()
    val failureMessages = mutableMapOf<Int, String>()
    var dailyLimitMessage: String? = null
    for (index in allowedChapterCount until chapters.size) {
        failed.add(index)
        nonRetryable.add(index)
        failureMessages[index] = quotaPendingMessage
        task.failedChapters[index] = onlineCompletionFailedChapter(target, chapters[index], index, 0, quotaPendingMessage)
    }
    val cover = target.result.coverUrl.takeIf { it.isNotBlank() }?.let { coverUrl ->
        runCatching {
            throwIfOnlineCompletionDownloadCancelled(task)
            OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                throwIfOnlineCompletionDownloadCancelled(task)
                downloadOnlineImageBytes(target, coverUrl, isCover = true)
            }
        }.onSuccess { payload ->
            logWebDav(
                "online completion cover downloaded url=${coverUrl.take(160)} " +
                    "bytes=${payload.bytes.size} type=${payload.mimeType.ifBlank { "unknown" }}",
            )
        }.onFailure { error ->
            TaskCancellation.rethrowIfCancelled(error)
            logWebDav(
                "online completion cover failed url=${coverUrl.take(160)} " +
                    "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )

            com.reamicro.fix.xposed.HookRuntime.logAlways(
                "[ReaMicro] online completion cover failed url=${coverUrl.take(160)} " +
                    "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }.getOrNull()

            ?: run {
                if (!isFanqieCoverUrl(coverUrl)) return@run null
                runCatching { downloadDiscoverCoverViaWebPage(this, target.result.detailUrl) }
                    .onFailure { error ->
                        com.reamicro.fix.xposed.HookRuntime.logAlways(
                            "[ReaMicro] online completion cover web fallback failed " +
                                "detail=${target.result.detailUrl.take(160)} " +
                                "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                        )
                    }
                    .getOrNull()?.second
            }
    }
    task.downloadedCover = cover
    var firstBatchImported = false
    var consecutiveServiceFailures = 0
    val batchSession = onlineChapterBatchSession(target, chapters, 0 until allowedChapterCount)
    fun downloadedCount(): Int = downloaded.count { it != null }

    fun maybeImportFirstBatch(progress: Int, processedCount: Int) {
        if (chapters.size <= ONLINE_COMPLETION_PARTIAL_IMPORT_THRESHOLD || firstBatchImported ||
            processedCount < ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS ||
            downloaded.take(ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS).none { it != null }
        ) return
        throwIfOnlineCompletionDownloadCancelled(task)
        val partialFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_first_100.epub")
        onProgress(progress, "生成首批章节 EPUB")
        val partialChapters = chapters.mapIndexed { index, chapter ->
            downloaded[index] ?: com.reamicro.fix.online.download.onlineCompletionPausedPendingChapter(chapter, index)
        }
        writeOnlineCompletionEpub(partialFile, target, partialChapters, cover,
            failedChapters = task.failedChapters.values.toList(),
            onDemandMetadata = onlineCompletionTaskMetadata(task, target, OnlineBookDownloadMode.FULL),
            allowNetworkImages = false, checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) })
        onPartialReady(partialFile, progress)
        firstBatchImported = true
    }

    fun attemptChapter(index: Int, immediateRetry: Boolean): Boolean {
        throwIfOnlineCompletionDownloadCancelled(task)
        val chapter = chapters[index]
        dailyLimitMessage?.let { message ->
            failed.add(index)
            nonRetryable.add(index)
            failureMessages[index] = message
            task.failedChapters[index] = onlineCompletionFailedChapter(target, chapter, index, attempts[index], message)
            return false
        }
        val maxAttemptsThisRound = if (immediateRetry) 2 else 1
        var roundAttempts = 0
        while (attempts[index] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT &&
            roundAttempts < maxAttemptsThisRound && index !in nonRetryable
        ) {
            throwIfOnlineCompletionDownloadCancelled(task)
            attempts[index]++
            task.chapterAttempts[index] = attempts[index]
            roundAttempts++
            val attempt = attempts[index]
            runCatching {
                throwIfOnlineCompletionDownloadCancelled(task)
                downloadOnlineChapter(target, detail.url, detail.body, chapter, index, batchSession)
            }.onSuccess { chapterContent ->
                downloaded[index] = chapterContent
                task.downloadedChapters[index] = chapterContent
                task.failedChapters.remove(index)
                writeOnlineCompletionChapterToImportedBookIfReady(task, target, index, chapterContent)
                val recoveredFromServiceFailure = consecutiveServiceFailures > 0
                consecutiveServiceFailures = 0
                if (recoveredFromServiceFailure) {
                    onProgress(
                        5 + ((index + 1) * 78 / chapters.size.coerceAtLeast(1)),
                        "上游已恢复，继续下载章节 ${index + 1}/${chapters.size}",
                    )
                }
                failed.remove(index)
                failureMessages.remove(index)
                if (index == 0 || (index + 1) % 20 == 0 || index == chapters.lastIndex || attempt > 1) {
                    logWebDav(
                        "online completion chapter downloaded ${index + 1}/${chapters.size} " +
                            "transport=${chapterContent.transport} attempt=$attempt " +
                            "content=${chapterContent.content.length} sourceUrl=${chapter.url.take(120)}",
                    )
                }
                return true
            }.onFailure { error ->
                TaskCancellation.rethrowIfCancelled(error)
                throwIfOnlineCompletionDownloadCancelled(task)
                failed.add(index)
                val message = error.message.orEmpty().ifBlank { error.javaClass.name }
                failureMessages[index] = message
                task.failedChapters[index] = onlineCompletionFailedChapter(target, chapter, index, attempts[index], message)
                if (error is OnlineSourceDailyLimitException) {
                    dailyLimitMessage = message
                    nonRetryable.add(index)
                }
                if (isPermanentOnlineChapterFailure(error)) {
                    nonRetryable.add(index)
                }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE && index !in nonRetryable &&
                    attempts[index] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT
                ) {
                    consecutiveServiceFailures++
                    val waitMs = onlineHttpRetryDelayMs(error, consecutiveServiceFailures)
                    val reason = if (retryKind == OnlineHttpRetryKind.CIRCUIT_OPEN) "上游熔断" else "上游暂时异常"
                    onProgress(
                        5 + ((index + 1) * 78 / chapters.size.coerceAtLeast(1)),
                        "$reason，等待 ${waitMs / 1_000} 秒后继续第 ${index + 1} 章",
                    )
                    logWebDav(
                        "online completion service backoff chapter=${index + 1}/${chapters.size} " +
                            "kind=$retryKind consecutive=$consecutiveServiceFailures wait=${waitMs}ms",
                    )
                    sleepOnlineCompletionRetryDelay(task, waitMs)
                }
                logWebDav(
                    "online completion chapter failed ${index + 1}/${chapters.size} " +
                        "attempt=$attempt/${ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT} " +
                        "title=${chapter.title} url=${chapter.url.take(120)} " +
                        "error=${error.javaClass.name}: ${error.message.orEmpty()}",
                )
            }
        }
        return false
    }

    chapters.forEachIndexed { index, chapter ->
        throwIfOnlineCompletionDownloadCancelled(task)
        val progress = 5 + ((index + 1) * 78 / chapters.size.coerceAtLeast(1))
        onProgress(progress, "下载章节 ${index + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
        attemptChapter(index, immediateRetry = true)
        maybeImportFirstBatch(progress, index + 1)
        if (failed.isNotEmpty()) {
            onProgress(progress, "下载章节 ${index + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
        }
    }
    retryOnlineCompletionChapters(
        candidates = { failed.filter { it !in nonRetryable && attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT } },
        beforeRound = { round, remaining ->
            val waitMs = onlineCompletionRetryDelayMs(round)
            onProgress(84, "等待重试失败章节 ${remaining.size} 章${onlineCompletionFailureSuffix(failed.size)}")
            logWebDav(
                "online completion delayed retry round=$round wait=${waitMs}ms " +
                    "candidates=${remaining.size} failed=${failed.size} downloaded=${downloadedCount()}/${chapters.size}",
            )
            sleepOnlineCompletionRetryDelay(task, waitMs)
        },
        attempt = { chapterIndex, retryIndex, count ->
            throwIfOnlineCompletionDownloadCancelled(task)
            val progress = 84 + ((retryIndex + 1) * 4 / count.coerceAtLeast(1))
            onProgress(progress, "重试章节 ${chapterIndex + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
            attemptChapter(chapterIndex, immediateRetry = false)
            if (failed.isNotEmpty()) {
                onProgress(progress, "重试章节 ${chapterIndex + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
            }
        },
    )
    val successCount = downloadedCount()
    if (failed.isNotEmpty()) {
        onProgress(88, "仍有 ${failed.size} 章失败，保存已完成章节")
        logWebDav(
            "online completion chapters still failed count=${failed.size} " +
                "success=$successCount/${chapters.size} failures=" +
                failed.take(20).joinToString { "${it + 1}:${failureMessages[it].orEmpty()}" },
        )
    }
    val finalFailures = failed.sorted().map { index ->
        onlineCompletionFailedChapter(
            target = target,
            chapter = chapters[index],
            index = index,
            attempts = attempts[index],
            message = failureMessages[index].orEmpty(),
        )
    }
    task.failedChapters.clear()
    task.failedChapters.putAll(finalFailures.associateBy { it.index })
    logOnlineCompletionFinalFailures(target, finalFailures, "download")
    val finalChapters = chapters.mapIndexed { index, chapter ->
        downloaded[index] ?: OnlineDownloadedChapter(
            title = chapter.title.ifBlank { "第 ${index + 1} 章" },
            content = onlineCompletionFailurePlaceholder(attempts[index], failureMessages[index].orEmpty()),
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
            sourceUrl = chapter.url,
        )
    }
    if (task.importedBookDir != null) {
        onProgress(88, "保存下载结果")
        return failed.size
    }
    onProgress(88, "生成 EPUB")
    throwIfOnlineCompletionDownloadCancelled(task)
    writeOnlineCompletionEpub(outputFile, target, finalChapters, cover, finalFailures,
        onDemandMetadata = onlineCompletionTaskMetadata(task, target, OnlineBookDownloadMode.FULL),
        checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) })
    logWebDav(
        "online completion epub ready path=${outputFile.absolutePath} " +
            "chapters=${finalChapters.size} success=$successCount failed=${failed.size}",
    )
    return failed.size
}

internal fun WebDavDriveHook.loadOnlineCompletionToc(
    target: OnlineDownloadTarget,
    onProgress: (Int, String) -> Unit,
): OnlineTocSnapshot {
    val startedAt = System.nanoTime()
    val detailUrl = target.result.detailUrl.ifBlank { error("搜索结果缺少详情页地址") }
    onProgress(3, "读取详情页")
    val detailStartedAt = System.nanoTime()
    val detail = OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
        requestOnlineSearch(target.source, detailUrl)
    }
    val detailElapsedMs = (System.nanoTime() - detailStartedAt) / 1_000_000
    logWebDav(
        "online completion detail ok url=${detail.url} bytes=${detail.body.length} " +
            "elapsedMs=$detailElapsedMs",
    )
    val tocUrl = buildOnlineTocUrl(target.source, detail.url, detail.body)
    logWebDav("online completion toc url=${tocUrl.ifBlank { detail.url }}")
    val tocStartedAt = System.nanoTime()
    val toc = if (tocUrl.isBlank() || tocUrl == detail.url) {
        detail
    } else {
        onProgress(4, "读取目录")
        OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
            requestOnlineSearch(target.source, tocUrl)
        }
    }
    if (tocUrl.isNotBlank() && tocUrl != detail.url) {
        logWebDav(
            "online completion toc request ok bytes=${toc.body.length} " +
                "elapsedMs=${(System.nanoTime() - tocStartedAt) / 1_000_000}",
        )
    }
    val parseStartedAt = System.nanoTime()
    val chapters = parseOnlineToc(target.source, target.result, toc.url, toc.body)
    if (chapters.isEmpty()) {
        val hint = if (target.result.chapterCount > 0) {
            "，搜索结果标记 ${target.result.chapterCount} 章，可能未登录或源端未缓存章节"
        } else {
            ""
        }
        error("在线源目录为空$hint，已停止导入：${target.source.name}")
    }
    val parseElapsedMs = (System.nanoTime() - parseStartedAt) / 1_000_000
    val enrichStartedAt = System.nanoTime()
    enrichOnlineTargetFromDetail(target, detail.body, detail.url, chapters.size)
    logWebDav(
        "online completion toc loaded chapters=${chapters.size} parseMs=$parseElapsedMs " +
            "enrichMs=${(System.nanoTime() - enrichStartedAt) / 1_000_000} " +
            "totalMs=${(System.nanoTime() - startedAt) / 1_000_000}",
    )
    return OnlineTocSnapshot(detail = detail, toc = toc, chapters = chapters)
}

internal fun WebDavDriveHook.downloadOnlineChapter(
    target: OnlineDownloadTarget,
    detailUrl: String,
    detailBody: String,
    chapter: OnlineChapter,
    index: Int,
    batchSession: OnlineChapterBatchSession? = null,
): OnlineDownloadedChapter =
    OnlineSourceDownloadPolicyStore.withChapterDownload(currentContext(), target.source) {
        val batchBody = batchSession?.bodyFor(index, chapter)
        val transport: String
        val body = if (batchBody != null) {
            transport = "batch"
            batchBody
        } else if (chapter.url == detailUrl) {
            transport = "detail"
            detailBody
        } else {
            transport = "single"
            OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                requestOnlineSearch(target.source, chapter.url)
            }.body
        }

        val content = extractOnlineChapterContent(target.source, chapter.url, body)
        OnlineChapterContentValidator.downloadedFailureReason(content)?.let { reason ->
            error("章节正文无效：$reason body=${body.length}")
        }
        cacheOnlineParagraphComments(
            source = target.source,
            detailUrl = target.result.detailUrl,
            chapterUrl = chapter.url,
            chapterIndex = index,
            chapterContent = content,
        )
        OnlineDownloadedChapter(
            title = chapter.title.ifBlank { "第 ${index + 1} 章" },
            content = content,
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
            sourceUrl = chapter.url,
            transport = transport,
        ).also { completed -> cacheOnlineChapterImages(target, completed) }
    }

internal fun WebDavDriveHook.cacheOnlineParagraphComments(
    source: OnlineSourceEntry,
    detailUrl: String,
    chapterUrl: String,
    chapterIndex: Int,
    chapterContent: String,
) {
    if (!ONLINE_PARAGRAPH_COMMENTS_RUNTIME_ENABLED) return
    if (!source.paragraphCommentsEnabled || !source.supportsParagraphComments) return
    runCatching {
        val context = currentApplicationContext() ?: currentContext() ?: return
        val bookId = FanqieParagraphCommentApi.bookId(detailUrl)
        val itemId = FanqieParagraphCommentApi.itemId(chapterUrl)
        if (bookId.isBlank() || itemId.isBlank()) {
            logWebDav(
                "online paragraph comments skipped missing identifiers " +
                    "source=${source.name} chapter=${chapterIndex + 1}",
            )
            return
        }
        val apiKey = OnlineSourceAuth.resolveApiKey(source.header, OnlineSourceAuth.loginInfo(context, source))
        val countsResponse = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            requestOnlineSearch(source, FanqieParagraphCommentApi.countsUrl(bookId, itemId, apiKey))
        }
        val counts = FanqieParagraphCommentApi.parseCounts(countsResponse.body)
        val now = System.currentTimeMillis()
        if (counts.isEmpty()) {
            OnlineParagraphCommentCacheStore.write(
                context.filesDir,
                OnlineParagraphCommentCache(
                    sourceId = source.id,
                    bookId = bookId,
                    itemId = itemId,
                    chapterIndex = chapterIndex,
                    comments = emptyList(),
                    updatedAtMs = now,
                ),
            )
            return
        }
        val fullV2Response = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            requestOnlineSearch(source, FanqieParagraphCommentApi.fullV2ContentUrl(bookId, itemId, apiKey))
        }
        val fullV2Content = fanqieContentBody(fullV2Response.body).ifBlank { chapterContent }
        val comments = OnlineParagraphIndexMapper.commentsForCounts(fullV2Content, counts).map { (paragraph, count) ->
            OnlineParagraphCommentCount(
                sourceId = source.id,
                bookId = bookId,
                itemId = itemId,
                chapterIndex = chapterIndex,
                paraIndex = paragraph.paraIndex,
                paragraphText = paragraph.text.ifBlank { "[图片]" },
                count = count,
                updatedAtMs = now,
            )
        }
        OnlineParagraphCommentCacheStore.write(
            context.filesDir,
            OnlineParagraphCommentCache(
                sourceId = source.id,
                bookId = bookId,
                itemId = itemId,
                chapterIndex = chapterIndex,
                comments = comments,
                updatedAtMs = now,
            ),
        )
        logWebDav(
            "online paragraph comments cached source=${source.name} " +
                "chapter=${chapterIndex + 1} counts=${counts.size} mapped=${comments.size}",
        )
    }.onFailure { error ->
        logWebDav(
            "online paragraph comments failed source=${source.name} " +
                "chapter=${chapterIndex + 1}: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
        )
    }
}

internal fun WebDavDriveHook.buildOnlineTocUrl(
    source: OnlineSourceEntry,
    detailUrl: String,
    detailBody: String,
): String {
    OnlineSourceTrxsCompat.catalogUrl(source, detailUrl, detailBody)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val rule = runCatching { JSONObject(source.ruleBookInfo) }.getOrNull() ?: return ""
    val tocRule = rule.optString("tocUrl", "").trim()
    if (tocRule.isBlank()) return ""
    OnlineSourceScriptCompat.resolveShuqiTocUrl(
        rawRule = tocRule,
        detailUrl = detailUrl,
        nowSeconds = System.currentTimeMillis() / 1000L,
    )?.let { return it }
    val root = parseOnlineJsonRoot(detailBody) ?: return resolveOnlineUrl(detailUrl, tocRule)
    val node = rule.optString("init", "").trim()
        .takeIf { it.isNotBlank() }
        ?.let { onlineJsonRuleValues(root, it).firstOrNull() }
        ?: root
    return resolveOnlineUrl(detailUrl, onlineRuleValue(node, tocRule, detailUrl, source = source))
}

internal fun WebDavDriveHook.isOnlineTocVolumeNode(
    source: OnlineSourceEntry,
    node: Any?,
    isVolumeRule: String,
    title: String,
    hasChapterUrl: Boolean,
    baseUrl: String,
    loginInfo: Map<String, String>? = null,
): Boolean {
    val explicitVolume = isVolumeRule.trim()
        .takeIf { it.isNotBlank() }
        ?.let {
            onlineRuleValue(
                node,
                it,
                baseUrl,
                source = source,
                loginInfo = loginInfo,
            ).trim().lowercase(Locale.ROOT)
        }
        ?.let { value ->
            when (value) {
                "true", "1", "yes", "volume", "vol", "part", "group", "section" -> true
                "false", "0", "no", "chapter" -> false
                else -> null
            }
        }
    val nodeType = listOf("$.type", "$.kind", "$.nodeType", "$.node_type", "type", "kind", "nodeType", "node_type")
        .asSequence()
        .mapNotNull { rule -> onlineJsonString(node, rule).takeIf { it.isNotBlank() }?.cleanOnlineText() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()
    return OnlineChapterUpdatePlanner.isVolumeNode(explicitVolume, nodeType, title, hasChapterUrl)
}

internal fun WebDavDriveHook.buildOnlineChapterUrl(
    source: OnlineSourceEntry,
    baseUrl: String,
    urlRule: String,
    rawUrl: String,
    loginInfo: Map<String, String>,
): String {
    val value = rawUrl.trim()
    if (value.isBlank()) return ""
    if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
        return value
    }
    OnlineSourceScriptCompat.resolveShuqiChapterUrl(
        baseUrl = baseUrl,
        rawScript = urlRule,
        chapterId = value,
        loginInfo = loginInfo,
    )?.let { return resolveOnlineUrl(baseUrl, it) }
    if (urlRule.contains("<js>", ignoreCase = true) || urlRule.contains("@js:", ignoreCase = true)) {
        evaluateOnlineChapterUrlRule(urlRule, value, baseUrl)
            ?.let { return resolveOnlineUrl(baseUrl, it) }
        evaluateOnlineChapterUrlJs(urlRule, value)
            ?.let { return resolveOnlineUrl(baseUrl, it) }
    }
    if (value.isNotBlank()) {
        onlineContentProxyTemplate(source)?.let { template ->
            return resolveOnlineUrl(baseUrl, template.replace("\${itemId}", value))
        }
    }
    return resolveOnlineUrl(baseUrl, value)
}

internal fun WebDavDriveHook.extractOnlineChapterContent(source: OnlineSourceEntry, baseUrl: String, body: String): String {
    val contentRule = runCatching { JSONObject(source.ruleContent).optString("content", "") }.getOrNull().orEmpty()
    if (contentRule.startsWith("<js>", ignoreCase = true) || contentRule.startsWith("@js:", ignoreCase = true)) {
        evaluateOnlineContentJs(source, contentRule, body)?.let { return normalizeOnlineChapterText(it) }
    }
    val text = body.trim()
    if (OnlineSourceTrxsCompat.isSource(source)) {
        val data = OnlineSourceTrxsCompat.responseData(body)
        val content = (data as? JSONObject)?.optString("content", "").orEmpty()
        if (content.isNotBlank()) return normalizeOnlineChapterText(content)
    }
    if (text.startsWith("{") || text.startsWith("[")) {
        val root = parseOnlineJsonRoot(text)
        if (root != null) {
            val rule = runCatching { JSONObject(source.ruleContent) }.getOrNull()
            val jsonContentRule = rule?.optString("content", "").orEmpty()
            val rawContent = when {
                jsonContentRule.isNotBlank() && !jsonContentRule.startsWith("<js>", ignoreCase = true) ->
                    onlineRuleValue(root, jsonContentRule, baseUrl, source = source)
                else -> ""
            }.ifBlank {
                onlineJsonString(root, "$.data.content")
                    .ifBlank { onlineJsonString(root, "$.chapter.text") }
                    .ifBlank { onlineJsonString(root, "$..content") }
                    .ifBlank { onlineJsonString(root, "$..text") }
            }
            return normalizeOnlineChapterText(rawContent)
        }
    }
    return extractOnlineHtmlChapterContent(body)
}

internal fun WebDavDriveHook.downloadOnlineBytes(source: OnlineSourceEntry, requestUrl: String): OnlineBinaryPayload {
    return com.reamicro.fix.online.OnlineSourceTask(source).use { operation ->
        operation.run {
            downloadOnlineBytesCurrent(source, requestUrl)
        }
    }
}

internal fun WebDavDriveHook.downloadOnlineImageBytes(
    target: OnlineDownloadTarget, url: String, isCover: Boolean = false,
): OnlineBinaryPayload =
    downloadOnlineBytes(target.source,
        if (isCover) normalizeOnlineCoverUrl(target.source, sourceBaseUrl(target.source), url) else url)

private fun WebDavDriveHook.downloadOnlineBytesCurrent(source: OnlineSourceEntry, requestUrl: String): OnlineBinaryPayload {
    TaskCancellation.checkCurrent()
    OnlineSourceStore.requireCurrent(source)
    return withOnlineCleartextAllowed(requestUrl) {
        fun execute(authRetried: Boolean): OnlineBinaryPayload {
            TaskCancellation.checkCurrent()
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = onlineConnectTimeoutMillis(source)
                readTimeout = onlineReadTimeoutMillis(source)
                setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                parseOnlineHeaders(source.header).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                }
                OnlineSourceAuth.requestHeaders(currentApplicationContext() ?: currentContext(), source, requestUrl).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                }
            }
            val cancellation = TaskCancellation.current()?.register(connection::disconnect)
            try {
                TaskCancellation.checkCurrent()
                val code = connection.responseCode
                TaskCancellation.checkCurrent()
                if (code in 200..299) {
                    return OnlineBinaryPayload(
                        bytes = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                TaskCancellation.checkCurrent()
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(count <= 16 * 1024 * 1024 - output.size()) { "在线图片响应超过 16 MiB" }
                                output.write(buffer, 0, count)
                            }
                            TaskCancellation.checkCurrent()
                            output.toByteArray()
                        },
                        mimeType = connection.contentType?.substringBefore(';')?.trim().orEmpty(),
                        url = connection.url.toString(),
                    )
                }
                if ((code == 401 || code == 403) && !authRetried && source.hasLoginConfig) {
                    TaskCancellation.checkCurrent()
                    val login = OnlineSourceAuth.loginWithSavedCredentials(currentApplicationContext() ?: currentContext(), source)
                    TaskCancellation.checkCurrent()
                    logWebDav(
                        "online completion binary auth retry source=${source.name} code=$code " +
                            "success=${login.success} message=${login.message}",
                    )
                    if (login.success) return execute(authRetried = true)
                }
                val authHint = if (code == 401 || code == 403) "：未登录或会员权限不足" else ""
                error("HTTP $code$authHint")
            } catch (error: Throwable) {
                TaskCancellation.rethrowIfCancelled(error)
                throw error
            } finally {
                cancellation?.close()
                connection.disconnect()
            }
        }
        execute(authRetried = false)
    }
}

internal fun WebDavDriveHook.appendOnlineCompletionChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    remoteChapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
    newChapters: Map<Int, OnlineDownloadedChapter>,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
    allowNetworkImages: Boolean = true,
): Boolean {
    checkCurrent()
    val root = bookDir.canonicalFile
    writeOnlineCompletionDefaultStyle(root, commit = commit, checkCurrent = checkCurrent)
    var changed = false
    val decor = onlineCompletionBookDirDecor(root)
    val volumeFirstChapters = onlineVolumeSegments(
        remoteChapters.map { OnlineDownloadedChapter(title = it.title, content = "", volumeTitle = it.volumeTitle) },
    ).mapTo(hashSetOf()) { it.startIndex }
    newChapters.forEach { (remoteIndex, chapter) ->
        checkCurrent()
        val href = chapterHrefs.getOrNull(remoteIndex)
            ?: error("EPUB chapter href missing for remote index $remoteIndex")
        writeOnlineCompletionChapterToBookDir(
            bookDir = root,
            index = remoteIndex,
            chapter = chapter,
            href = href,
            target = target,
            decor = decor,
            isVolumeFirstChapter = remoteIndex in volumeFirstChapters,
            commit = commit,
            checkCurrent = checkCurrent,
            allowNetworkImages = allowNetworkImages,
        )
        changed = true
    }
    val tocChapters = remoteChapters.map { chapter ->
        OnlineDownloadedChapter(
            title = chapter.title,
            content = "",
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
        )
    }
    val coverExt = onlineCompletionExistingCoverExt(root)
    if (writeOnlineCompletionVolumePages(root, tocChapters, decor, commit = commit, checkCurrent = checkCurrent)) changed = true
    val tocFile = File(root, "OEBPS/toc.ncx")
    val nextToc = onlineTocNcx(target, tocChapters, chapterHrefs)
    if (!tocFile.isFile || tocFile.readText(Charsets.UTF_8) != nextToc) {
        writeOnlineCompletionTextAtomically(tocFile, nextToc, commit = commit, checkCurrent = checkCurrent)
        changed = true
    }
    val opfFile = File(root, "OEBPS/content.opf")
    val nextOpf = onlineContentOpf(
        target = target,
        chapters = tocChapters,
        coverExt = coverExt ?: "jpg",
        hasCover = coverExt != null,
        chapterHrefs = chapterHrefs,
    )
    if (!opfFile.isFile || opfFile.readText(Charsets.UTF_8) != nextOpf) {
        writeOnlineCompletionTextAtomically(opfFile, nextOpf, commit = commit, checkCurrent = checkCurrent)
        changed = true
    }
    synchronizeOnlineImageManifest(root, commit = commit, checkCurrent = checkCurrent)
    if (writeOnlineCompletionChapterIndex(root, target, remoteChapters, chapterHrefs, commit = commit, checkCurrent = checkCurrent)) changed = true
    return changed
}

internal fun WebDavDriveHook.downloadOnlineCompletionOnDemandChapter(
    request: OnlineOnDemandChapterRequest,
) {
    val execution = storageExecutionGeneration.get()
    val context = currentContext() ?: error("按需下载缺少 Context")
    val identity = request.storageIdentity
    var operationSource: OnlineSourceEntry? = null
    fun checkCurrent() {
        TaskCancellation.checkCurrent()
        operationSource?.let(OnlineSourceStore::requireCurrent)
        check(!storageClosed && settingsProvider().moduleEnabled && execution == storageExecutionGeneration.get() &&
            !Thread.currentThread().isInterrupted && AccountHostRuntime.isCurrentStorage(identity)) {
            "账号已切换，已停止旧账号章节下载"
        }
    }
    checkCurrent()
    val expectedRoot = File(context.filesDir, "${identity.accountId}/books").canonicalFile
    check(request.bookDir.canonicalFile.parentFile == expectedRoot) { "按需下载目录与当前账号不一致" }
    val commit: (() -> Unit) -> Unit = { action ->
        AccountHostRuntime.withCurrentStorage(identity) { checkCurrent(); action() }
    }
    val source = OnlineSourceStore.list(context)
        .firstOrNull { OnlineSourceStore.matchesIdentity(it, request.metadata.sourceId) }
        ?.let { OnlineSourceDownloadPolicyStore.attach(context, it) }
        ?: error("在线书源已不存在：${request.metadata.sourceId}")
    operationSource = source
    val metadata = OnlineOnDemandMetadataStore.read(request.bookDir)
        ?: error("按需章节元数据不存在")
    checkCurrent()
    val current = metadata.chapter(request.chapterIndex)
        ?: error("按需章节索引越界：${request.chapterIndex}")
    if (current.state == OnlineChapterState.READY) return
    val downloadingMetadata = OnlineOnDemandMetadataStore.update(request.bookDir, commit = commit, checkCurrent = ::checkCurrent) { latest ->
        val latestChapter = latest.chapter(request.chapterIndex)
            ?: error("按需章节索引越界：${request.chapterIndex}")
        if (latestChapter.state == OnlineChapterState.READY) latest else {
            latest.updateChapter(request.chapterIndex, OnlineOnDemandChapter::beginDownload)
        }
    }
    if (downloadingMetadata.chapter(request.chapterIndex)?.state == OnlineChapterState.READY) return
    val chapter = OnlineChapter(
        title = current.title,
        url = current.url,
        volumeTitle = current.volumeTitle,
    )
    val target = OnlineDownloadTarget(
        source = source,
        query = request.metadata.bookName,
        result = OnlineBookSearchResult(
            sourceName = source.name,
            name = request.metadata.bookName,
            author = "",
            coverUrl = "",
            detailUrl = request.metadata.detailUrl,
            intro = "",
        ),
    )
    val result = runCatching {
        val detail = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            checkCurrent()
            requestOnlineSearch(source, request.metadata.detailUrl)
        }
        checkCurrent()
        downloadOnlineChapter(
            target = target,
            detailUrl = detail.url,
            detailBody = detail.body,
            chapter = chapter,
            index = request.chapterIndex,
        )
    }
    result.onSuccess { content ->
        checkCurrent()
        writeOnlineCompletionChapterToBookDir(
            bookDir = request.bookDir,
            index = request.chapterIndex,
            chapter = content,
            href = current.href,
            target = target,
            commit = commit,
            checkCurrent = ::checkCurrent,
        )
        markOnlineEpubChanged(request.bookDir.name, identity, ::checkCurrent)
        OnlineOnDemandMetadataStore.update(request.bookDir, commit = commit, checkCurrent = ::checkCurrent) { latest ->
            latest.updateChapter(request.chapterIndex) {
                it.markReady(System.currentTimeMillis())
            }
        }
        logWebDav(
            "on-demand chapter ready book=${request.metadata.bookName} " +
                "index=${request.chapterIndex + 1} href=${current.href}",
        )
    }.onFailure { error ->
        // A departed request must not publish a failure into a later guest/account view of this path.
        checkCurrent()
        OnlineOnDemandMetadataStore.update(request.bookDir, commit = commit, checkCurrent = ::checkCurrent) { latest ->
            latest.updateChapter(request.chapterIndex) {
                it.markFailed(error.message.orEmpty().ifBlank { error.javaClass.name })
            }
        }
        throw error
    }
}

private fun onlineCompletionTaskMetadata(
    task: OnlineCompletionDownloadTask,
    target: OnlineDownloadTarget,
    mode: OnlineBookDownloadMode,
): OnlineOnDemandMetadata {
    val chapters = task.tocSnapshot?.chapters.orEmpty()
    val hrefs = defaultOnlineChapterHrefs(chapters.size)
    return OnlineOnDemandMetadata(target.source.id, target.result.detailUrl, target.result.name, mode,
        chapters.mapIndexed { index, chapter ->
            val failure = task.failedChapters[index]
            val ready = task.downloadedChapters.containsKey(index)
            OnlineOnDemandChapter(
                OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url), chapter.url,
                chapter.title, chapter.volumeTitle, hrefs[index],
                when { ready -> OnlineChapterState.READY; failure != null -> OnlineChapterState.FAILED; else -> OnlineChapterState.PENDING },
                if (ready) System.currentTimeMillis() else 0L,
                task.chapterAttempts[index] ?: 0, failure?.message.orEmpty(),
            )
        })
}

internal fun WebDavDriveHook.writeOnlineCompletionChapterToImportedBookIfReady(
    task: OnlineCompletionDownloadTask, target: OnlineDownloadTarget,
    index: Int, chapter: OnlineDownloadedChapter,
) {
    val directory = task.importedBookDir ?: return
    withOnlineCompletionTaskLock(task, task.bookDirWriteLock) {
        throwIfOnlineCompletionDownloadCancelled(task)
        val guard = com.reamicro.fix.online.download.OnlineBookWriteGuard(directory,
            isCurrent = { !task.control.isCancelled && task.checkActive() &&
                AccountHostRuntime.isCurrentStorage(task.storageIdentity) && OnlineSourceStore.isCurrent(target.source) },
            commitCurrent = { action -> AccountHostRuntime.withCurrentStorage(task.storageIdentity, action = action) })
        fun checkCurrent() { throwIfOnlineCompletionDownloadCancelled(task); guard.checkCurrent() }
        com.reamicro.fix.online.download.OnlineOnDemandBridge.withIdleBook(directory) {
            OnlineOnDemandMetadataStore.resourceEdit(directory) {
                // Preserve the index written at first import; update states together at completion or pause.
                writeOnlineCompletionChapterToBookDir(directory, index, chapter, target = target,
                    isVolumeFirstChapter = chapter.volumeTitle.isNotBlank() && (index == 0 ||
                        task.tocSnapshot?.chapters?.getOrNull(index - 1)?.volumeTitle != chapter.volumeTitle),
                    commit = guard::commit, checkCurrent = ::checkCurrent, allowNetworkImages = false)
                task.publishedChapterIndices.add(index)
                markOnlineEpubChanged(directory.name, task.storageIdentity, ::checkCurrent)
            }
        }
    }
}

private fun WebDavDriveHook.saveOnlineCompletionImportedChapters(
    task: OnlineCompletionDownloadTask, target: OnlineDownloadTarget, final: Boolean,
) {
    val directory = task.importedBookDir ?: return
    withOnlineCompletionTaskLock(task, task.bookDirWriteLock) {
        throwIfOnlineCompletionDownloadCancelled(task)
        val guard = com.reamicro.fix.online.download.OnlineBookWriteGuard(directory,
            isCurrent = { task.checkActive() && AccountHostRuntime.isCurrentStorage(task.storageIdentity) &&
                OnlineSourceStore.isCurrent(target.source) },
            commitCurrent = { action -> AccountHostRuntime.withCurrentStorage(task.storageIdentity, action = action) })
        fun checkCurrent() { throwIfOnlineCompletionDownloadCancelled(task); guard.checkCurrent() }
        val metadata = onlineCompletionTaskMetadata(task, target, OnlineBookDownloadMode.FULL)
        val chapters = task.tocSnapshot?.chapters.orEmpty()
        val failures = task.failedChapters.values.sortedBy { it.index }
        val volumeStarts = onlineVolumeSegments(chapters.mapIndexed { index, chapter ->
            task.downloadedChapters[index] ?: com.reamicro.fix.online.download.onlineCompletionPausedPendingChapter(chapter, index)
        }).mapTo(hashSetOf()) { it.startIndex }
        com.reamicro.fix.online.download.OnlineOnDemandBridge.withIdleBook(directory) {
            OnlineOnDemandMetadataStore.resourceEdit(directory) {
                guard.commit {
                    if (!task.control.beginPublishing()) throw OnlineCompletionDownloadCancelledException("保存前已停止")
                }
                // First-batch files and later writes share the same imported directory, never a second import.
                chapters.forEachIndexed { index, chapter ->
                    checkCurrent()
                    if (index in task.publishedChapterIndices) return@forEachIndexed
                    val content = task.downloadedChapters[index] ?: if (final) OnlineDownloadedChapter(
                        chapter.title, onlineCompletionFailurePlaceholder(task.chapterAttempts[index] ?: 0,
                            task.failedChapters[index]?.message.orEmpty()),
                        volumeTitle = chapter.volumeTitle, level = chapter.level, sourceUrl = chapter.url,
                    ) else null
                    if (content != null) writeOnlineCompletionChapterToBookDir(directory, index, content,
                        target = target, isVolumeFirstChapter = index in volumeStarts,
                        commit = guard::commit, checkCurrent = ::checkCurrent, allowNetworkImages = false)
                    if (task.downloadedChapters.containsKey(index) && content != null) task.publishedChapterIndices.add(index)
                }
                OnlineOnDemandMetadataStore.write(directory, metadata, commit = guard::commit, checkCurrent = ::checkCurrent)
                writeOnlineCompletionFailedChapters(directory, target, failures,
                    commit = guard::commit, checkCurrent = ::checkCurrent)
                markOnlineEpubChanged(directory.name, task.storageIdentity, ::checkCurrent)
                task.control.markCommitted()
            }
        }
        // Content is already durable; a later DAO refresh failure must not discard downloaded chapters.
        runCatching {
            checkCurrent()
            val repository = currentBookshelfRepository() ?: error("BookshelfRepository unavailable")
            val book = findOnlineCompletionImportedBook(repository, target, target.result.detailUrl,
                storageIdentity = task.storageIdentity, expectedUuid = directory.name)
                ?: error("导入的图书记录已变化")
            updateOnlineContentMetadata(book, target, bookDirectorySize(directory), task.storageIdentity, repository, ::checkCurrent)
        }.onFailure { HookRuntime.logError("$LOG_PREFIX chapters saved; metadata refresh deferred", it) }
    }
}

internal fun WebDavDriveHook.writeOnlineCompletionChapterToBookDir(
    bookDir: File,
    index: Int,
    chapter: OnlineDownloadedChapter,
    href: String = "",
    target: OnlineDownloadTarget? = null,
    decor: OnlineEpubDecor? = null,
    isVolumeFirstChapter: Boolean = false,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
    allowNetworkImages: Boolean = true,
) {
    checkCurrent()
    val root = bookDir.canonicalFile
    val textDir = File(root, "OEBPS/Text").canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    if (!textDir.path.startsWith(rootPrefix)) {
        error("EPUB Text directory escapes book dir")
    }
    commit { checkCurrent(); textDir.mkdirs() }
    writeOnlineCompletionDefaultStyle(root, commit = commit, checkCurrent = checkCurrent)
    val relativeHref = href.trim().removePrefix("OEBPS/")
        .takeIf { it.startsWith("Text/") && it.endsWith(".xhtml", ignoreCase = true) }
        ?: "Text/chapter_${(index + 1).toString().padStart(4, '0')}.xhtml"
    val file = File(root, "OEBPS/$relativeHref").canonicalFile
    if (!file.path.startsWith(rootPrefix)) error("EPUB chapter path escapes book dir")
    val imageHrefs = if (target != null) {
        localizeOnlineChapterImages(root, target, chapter, commit = commit, checkCurrent = checkCurrent,
            allowNetworkImages = allowNetworkImages)
    } else {
        existingOnlineChapterImageHrefs(root, chapter)
    }
    checkCurrent()
    writeOnlineCompletionTextAtomically(
        file,
        chapterXhtml(
            title = chapter.title,
            content = chapter.content,
            imageHrefs = imageHrefs,
            decor = decor ?: onlineCompletionBookDirDecor(root),
            isVolumeFirstChapter = isVolumeFirstChapter,
        ),
        commit = commit,
        checkCurrent = checkCurrent,
    )
    if (index == 0 || (index + 1) % 20 == 0) {
        logWebDav("online completion chapter file updated ${index + 1} path=${file.absolutePath}")
    }
}

internal fun WebDavDriveHook.importOnlineCompletionBook(
    task: OnlineCompletionDownloadTask,
    file: File,
    target: OnlineDownloadTarget,
    onWaiting: (() -> Unit)? = null,
    onStarted: (() -> Unit)? = null,
    importProgress: Int = 95,
): OnlineCompletionImportResult = withOnlineCompletionTaskLock(
    task, onlineCompletionImportLock,
    onWaiting = {
        logWebDav("online completion import queued book=${target.result.name} file=${file.name}")
        onWaiting?.invoke()
    },
) {
    throwIfOnlineCompletionDownloadCancelled(task)
    onStarted?.invoke()
    importOnlineCompletionBookLocked(task, file, target, importProgress)
}

internal fun WebDavDriveHook.importOnlineCompletionBookLocked(
    task: OnlineCompletionDownloadTask, file: File, target: OnlineDownloadTarget, importProgress: Int = 95,
): OnlineCompletionImportResult {
    throwIfOnlineCompletionDownloadCancelled(task)
    val bookshelf = awaitBookshelfRepository()
        ?: error("阅微导入服务暂不可用，请先打开书架后重试")
    throwIfOnlineCompletionDownloadCancelled(task)
    val sourceUrl = target.result.detailUrl.ifBlank { target.source.sourceUrl }
    var importUuid = onlineBookUuid(target)
    var importTitle = target.result.name
    var importPlan: ModuleDirectoryImportPlan? = null
    var publication: com.reamicro.fix.online.download.OnlineCompletionDirectoryPublication? = null
    var committed: OnlineCompletionImportResult? = null
    return try {
        val unzipDirFile = unzipOnlineCompletionEpub(file) { throwIfOnlineCompletionDownloadCancelled(task) }
        throwIfOnlineCompletionDownloadCancelled(task)
        val plan = ModuleImportPrecheck.prepareDirectory(unzipDirFile, sourceUrl, importUuid, importTitle,
            task.importedBookDir?.name, bookshelf)
        if (!plan.allowed) throw OnlineCompletionDownloadCancelledException("已取消导入")
        importPlan = plan
        importUuid = plan.uuid
        importTitle = plan.title
        OnlineCompletionImportSignal.remember(importUuid, importTitle, sourceUrl, task.storageIdentity)
        val unzipDir = okioPath(unzipDirFile)
        val library = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
        val storage = requireNotNull(library.field("repository.storage").get(bookshelf))
        val booksDir = requireNotNull(library.method("books").invoke(storage, task.storageIdentity.accountId))
        val expectedBooksRoot = File(requireNotNull(currentContext()).filesDir, "${task.storageIdentity.accountId}/books").canonicalFile
        check(File(booksDir.toString()).canonicalFile == expectedBooksRoot) { "阅微导入目录与下载账号不一致" }
        throwIfOnlineCompletionDownloadCancelled(task)
        logWebDav(
            "online completion import target uid=${currentUserId(bookshelf) ?: -1L} " +
                "booksDir=$booksDir",
        )
        val stagingRoot = File(task.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        val imported = importOnlineCompletionEpubDirectory(unzipDir, okioPath(stagingRoot))
        throwIfOnlineCompletionDownloadCancelled(task)
        val stagedBook = File(imported.first.toString()).canonicalFile
        check(stagedBook.parentFile == stagingRoot && stagedBook.isDirectory) { "阅微 EPUB 导入暂存目录越界" }
        val opf = imported.second
        val publishedBook = File(expectedBooksRoot, stagedBook.name).canonicalFile
        check(publishedBook.parentFile == expectedBooksRoot) { "阅微 EPUB 导入目录越界" }
        publication = com.reamicro.fix.online.download.publishOnlineCompletionDirectory(
            stagedBook, publishedBook,
            commit = { action -> AccountHostRuntime.withCurrentStorage(task.storageIdentity, action = action) },
            checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) },
            checkRollbackCurrent = {
                check(AccountHostRuntime.isCurrentStorage(task.storageIdentity)) { "账号已切换，不能回滚其他账号图书目录" }
            },
        )
        val bookDir = okioPath(publishedBook)
        val platformFile = platformFile(file)
        rememberPendingWebDavImport(
            platformFile = platformFile,
            localFile = file,
            sourceUrl = sourceUrl,
            sourceSize = file.length(),
            storageIdentity = task.storageIdentity,
            checkActive = { throwIfOnlineCompletionDownloadCancelled(task) },
        )
        logWebDav(
            "online completion epub imported dir=$bookDir " +
                "title=${nativeOpfMetadataText(opf, "title")} " +
                "uuid=${nativeOpfMetadataText(opf, "uuid")}",
        )
        val importMethod = findImportBookMethod(bookshelf)
        val importArgs = importBookArgs(
            importMethod,
            platformFile,
            bookDir,
            opf,
            sourceUrl,
            java.lang.Long.valueOf(file.length()),
            checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) },
        )
        throwIfOnlineCompletionDownloadCancelled(task)
        logWebDav(
            "online completion importBook invoking uid=${currentUserId(bookshelf) ?: -1L} " +
                "method=${importMethod.name}/${importMethod.parameterTypes.size} " +
                "argTypes=${importArgs.joinToString { it?.javaClass?.simpleName ?: "null" }} " +
                "bookDir=${bookDir.toString().take(160)} opf=${opf.javaClass.simpleName}",
        )
        if (!task.control.beginPublishing()) throw OnlineCompletionDownloadCancelledException("导入前已停止")
        onlineCompletionProgressNotifiers[task.notificationId]
            ?.running(importProgress, "正在完成导入", force = true)
        val result = invokeSuspendBlocking(
            importMethod,
            bookshelf,
            *importArgs,
        )
        // Native import has committed its row and catalog before returning its authoritative row ID.
        // A later cancellation must not restore/remove the files underneath that committed record.
        val importedRowId = onlineCompletionImportRowId(result)
        if (importedRowId != null) {
            committed = OnlineCompletionImportResult(imported = true, bookDir = publishedBook)
            task.control.markCommitted()
            task.importedBookDir = publishedBook
            task.importedBookFileKey = com.reamicro.fix.online.download.onlineCompletionDirectoryKey(publishedBook)
            runCatching { requireNotNull(publication).complete() }
                .onFailure { HookRuntime.logError("$LOG_PREFIX import committed; backup cleanup failed", it) }
        }
        throwIfOnlineCompletionDownloadCancelled(task)
        logWebDav(
            "online completion importBook raw result=${result?.javaClass?.name ?: "null"} " +
                "value=$result",
        )
        logWebDav(
            "online completion importBook result parts=" +
                "first=${result?.let { onlineCompletionImportPart(it, "first") }?.let { "${it.javaClass.name}:$it" }} " +
                "second=${result?.let { onlineCompletionImportPart(it, "second") }?.let { "${it.javaClass.name}:$it" }} " +
                "rowId=${importedRowId ?: -1L}",
        )
        val importedBook = syncOnlineCompletionImportedBookMetadata(
            bookshelf = bookshelf,
            target = target,
            sourceUrl = sourceUrl,
            importedRowId = importedRowId,
            storageIdentity = task.storageIdentity,
            expectedUuid = importUuid,
            checkCurrent = { throwIfOnlineCompletionDownloadCancelled(task) },
        )
        throwIfOnlineCompletionDownloadCancelled(task)
        if (result != true && importedBook == null) {
            error("阅微导入未返回成功，且未找到书架记录：${target.result.name}")
        }
        logWebDav("online completion importBook result=$result file=${file.absolutePath} size=${file.length()}")
        if (result != true) {
            logWebDav(
                "online completion importBook returned non-true after epub directory import; " +
                    "treating as non-fatal result=$result file=${file.absolutePath}",
            )
        }
        val visibleBook = importedBook
            ?: findOnlineCompletionImportedBook(bookshelf, target, sourceUrl, storageIdentity = task.storageIdentity,
                expectedUuid = importUuid)
            ?: error("阅微导入后未找到书架记录：${target.result.name}")
        throwIfOnlineCompletionDownloadCancelled(task)
        check(visibleBook.callLong("getUid") == task.storageIdentity.accountId) { "阅微导入书籍与下载账号不一致" }
        val visibleDirectory = runCatching { bookDirectory(visibleBook).canonicalFile }
            .getOrElse { File(bookDir.toString()).canonicalFile }
        AccountHostRuntime.withCurrentStorage(task.storageIdentity) {
            throwIfOnlineCompletionDownloadCancelled(task)
            task.importedBookFileKey = com.reamicro.fix.online.download.onlineCompletionDirectoryKey(visibleDirectory)
            task.importedBookDir = visibleDirectory
        }
        committed = OnlineCompletionImportResult(imported = true, bookDir = visibleDirectory)
        task.control.markCommitted()
        runCatching { requireNotNull(publication).complete() }
            .onFailure { HookRuntime.logError("$LOG_PREFIX import committed; backup cleanup failed", it) }
        requireNotNull(committed)
    } catch (failure: Throwable) {
        val result = committed
        if (result != null) {
            HookRuntime.logError("$LOG_PREFIX native import committed; post-import refresh failed", failure)
            result
        } else {
            runCatching { publication?.rollback() }.onFailure(failure::addSuppressed)
            throw failure
        }
    } finally {
        importPlan?.let(ModuleImportPrecheck::discard)
        OnlineCompletionImportSignal.forget(importUuid, importTitle, sourceUrl, task.storageIdentity)
    }
}

internal fun WebDavDriveHook.syncOnlineCompletionImportedBookMetadata(
    bookshelf: Any,
    target: OnlineDownloadTarget,
    sourceUrl: String,
    importedRowId: Long? = null,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    expectedUuid: String? = null,
    checkCurrent: () -> Unit = {},
): Any? =
    runCatching {
        checkCurrent()
        check(AccountHostRuntime.isCurrentStorage(storageIdentity)) { "账号已切换，请重新导入" }
        val imported = findOnlineCompletionImportedBook(
            bookshelf,
            target,
            sourceUrl,
            importedRowId,
            storageIdentity,
            expectedUuid,
        ) ?: return null
        check(AccountHostRuntime.isCurrentStorage(storageIdentity)) { "账号已切换，请重新导入" }
        val bookDir = runCatching { bookDirectory(imported).canonicalFile }.getOrNull()
        val size = bookDir?.takeIf { it.isDirectory }?.let { bookDirectorySize(it) }
        val updated = updateOnlineContentMetadata(
            imported, target, size ?: imported.callLong("getSize"), storageIdentity, bookshelf, checkCurrent,
        )
        check(AccountHostRuntime.isCurrentStorage(storageIdentity)) { "账号已切换，请重新导入" }
        logWebDav(
            "online completion metadata synced uuid=${updated.callString("getUuid")} " +
                "backupId=${bookBackupIdOf(updated)} source=${target.source.name} " +
                "size=${updated.callLong("getSize")} updated=${updated.callLong("getUpdated")} " +
                "cover=${updated.callString("getCover").take(120)}",
        )
        updated
    }.onFailure {
        TaskCancellation.rethrowIfCancelled(it)
        checkCurrent()
        HookRuntime.log("$LOG_PREFIX online completion metadata sync failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun WebDavDriveHook.findOnlineCompletionImportedBook(
    bookshelf: Any,
    target: OnlineDownloadTarget,
    sourceUrl: String,
    importedRowId: Long? = null,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    expectedUuid: String? = null,
): Any? {
    check(AccountHostRuntime.isCurrentStorage(storageIdentity)) { "账号已切换，请重新导入" }
    val library = requireNotNull(com.reamicro.fix.core.HostStorageBindings.library)
    val dao = requireNotNull(library.field("repository.dao").get(bookshelf))
    val byRow = importedRowId?.takeIf { it > 0L }?.let {
        invokeSuspendBlocking(library.method("findId"), dao, it)
    }?.takeIf { it.callLong("getUid") == storageIdentity.accountId &&
        (expectedUuid == null || it.callString("getUuid") == expectedUuid) }
    check(AccountHostRuntime.isCurrentStorage(storageIdentity)) { "账号已切换，请重新导入" }
    if (byRow != null) return byRow
    val flow = requireNotNull(library.method("list").invoke(dao, storageIdentity.accountId))
    val books = firstFlowValue(flow) as? Iterable<*> ?: return null
    check(AccountHostRuntime.isCurrentStorage(storageIdentity)) { "账号已切换，请重新导入" }
    val candidates = books.filterNotNull().filter { it.callLong("getUid") == storageIdentity.accountId }
    if (expectedUuid != null) return candidates.singleOrNull { it.callString("getUuid") == expectedUuid }
    val uuid = onlineBookUuid(target)
    return candidates.singleOrNull { it.callString("getUuid") == uuid }
        ?: candidates.singleOrNull { sourceUrl.isNotBlank() && it.callString("getUri") == sourceUrl }
        ?: candidates.singleOrNull { target.result.detailUrl.isNotBlank() && it.callString("getUri") == target.result.detailUrl }
}

internal fun WebDavDriveHook.importOnlineCompletionEpubDirectory(unzipDir: Any, booksDir: Any): Pair<Any, Any> {
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.imports)
    require(host.type("path").isInstance(unzipDir) && host.type("path").isInstance(booksDir))
    val pair = requireNotNull(host.method("epubDirectory").invoke(null, unzipDir, booksDir, null)) {
        "阅微 EPUB 导入返回空"
    }
    val directory = requireNotNull(host.field("pair.first").get(pair))
    val opf = requireNotNull(host.field("pair.second").get(pair))
    check(host.type("path").isInstance(directory) && host.type("opf").isInstance(opf)) { "阅微 EPUB 导入结果类型不匹配" }
    return directory to opf
}
internal fun onlineCompletionImportPart(result: Any, role: String): Any? {
    require(role == "first" || role == "second")
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.imports)
    require(host.type("pair").isInstance(result)) { "阅微导入结果不是 Pair" }
    return host.field("pair.$role").get(result)
}
internal fun onlineCompletionImportRowId(result: Any?): Long? {
    if (result == null) return null
    val first = onlineCompletionImportPart(result, "first")
    val second = onlineCompletionImportPart(result, "second")
    // 导入结果 Pair 的第二项是新书架记录行 ID。
    return (second as? Number)
        ?.toLong()
        ?.takeIf { first is Boolean && it > 0L }
}

internal fun WebDavDriveHook.okioPath(file: File): Any =
    requireNotNull(requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog).method("path").invoke(null, file.absolutePath, false))

internal fun WebDavDriveHook.obtainOnlineCompletionOpf(bookDir: Any): Any {
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
    require(host.type("path").isInstance(bookDir)) { "EPUB 目录参数类型不匹配" }
    return requireNotNull(host.method("opf").invoke(null, bookDir)) { "EPUB OPF 解析失败" }
}

internal fun WebDavDriveHook.updateOnlineCompletionNotification(
    context: Context,
    id: Int,
    key: String,
    cancellable: Boolean,
    title: String,
    text: String,
    progress: Int,
    done: Boolean,
): Boolean {
    com.reamicro.fix.notification.OnlineNotificationEventOrder.accept(id, android.os.SystemClock.elapsedRealtimeNanos(), key)
    val moduleRelaySent = sendOnlineCompletionNotificationViaModule(
        context,
        id,
        key,
        cancellable,
        title,
        text,
        progress,
        done,
    )
    return runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (!manager.areNotificationsEnabled()) {
            if (onlineCompletionNotificationBlockedLogged.compareAndSet(false, true)) {
                logWebDav(
                    "online completion host notification blocked by system for ${context.packageName}, " +
                        "moduleRelaySent=$moduleRelaySent",
                )
            }
            return moduleRelaySent
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONLINE_COMPLETION_NOTIFICATION_CHANNEL,
                    ONLINE_COMPLETION_TITLE,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        setOnlineCompletionSmallIcon(builder)
        builder
            .setContentTitle(onlineCompletionDownloadTitle(progress, text))
            .setContentText(onlineCompletionDownloadText(title, text))
            .setStyle(Notification.BigTextStyle().bigText(onlineCompletionDownloadBigText(title, text, progress)))
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setProgress(100, progress.coerceIn(0, 100), false)
        if (!done && cancellable) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "暂停",
                onlineCompletionCancelPendingIntent(context, id, key, pause = true),
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "\u53d6\u6d88",
                onlineCompletionCancelPendingIntent(context, id, key),
            )
        }
        manager.notify(id, builder.build())
        cancelOnlineCompletionNotificationIfDone(manager, id, done, text)
        logWebDav("online completion host notification posted id=$id moduleRelaySent=$moduleRelaySent")
        true
    }.getOrElse {
        logWebDav("online completion notification failed: ${it.message ?: it.javaClass.name}")
        moduleRelaySent
    }
}

internal fun WebDavDriveHook.sendOnlineCompletionNotificationViaModule(
    context: Context,
    id: Int,
    key: String,
    cancellable: Boolean,
    title: String,
    text: String,
    progress: Int,
    done: Boolean,
): Boolean {
    val issuedAt = android.os.SystemClock.elapsedRealtimeNanos()
    val broadcastSent = sendOnlineCompletionNotificationBroadcast(context, id, key, cancellable, title, text, progress, done, issuedAt)
    val shouldStartActivity = shouldStartOnlineCompletionNotificationActivity(id, done, broadcastSent)
    val activitySent = if (shouldStartActivity) {
        startOnlineCompletionNotificationActivity(context, id, key, cancellable, title, text, progress, done, issuedAt)
    } else {
        false
    }
    if (done) onlineCompletionModuleActivityPromptAt.remove(id)
    if (broadcastSent) {
        return true
    }
    if (activitySent) {
        return true
    }
    return false
}

internal fun WebDavDriveHook.shouldStartOnlineCompletionNotificationActivity(
    id: Int,
    done: Boolean,
    broadcastSent: Boolean,
): Boolean {
    // 广播已提交时由接收器和通知服务处理，透明 Activity 会打断宿主搜索页面的生命周期。
    if (broadcastSent) return false
    if (done) return true
    val now = System.currentTimeMillis()
    val last = onlineCompletionModuleActivityPromptAt[id] ?: 0L
    if (last > 0L && now - last < ONLINE_COMPLETION_MODULE_ACTIVITY_RETRY_MS) return false
    onlineCompletionModuleActivityPromptAt[id] = now
    return true
}

internal fun WebDavDriveHook.startOnlineCompletionNotificationActivity(
    context: Context,
    id: Int,
    key: String,
    cancellable: Boolean,
    title: String,
    text: String,
    progress: Int,
    done: Boolean,
    issuedAt: Long,
): Boolean =
    runCatching {
        val intent = onlineCompletionNotificationIntent(id, key, cancellable, title, text, progress, done, issuedAt).apply {
            setClassName(MODULE_PACKAGE_NAME, ONLINE_COMPLETION_NOTIFICATION_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
        logWebDav(
            "online completion module notification activity start sent " +
                "id=$id progress=$progress done=$done",
        )
        true
    }.getOrElse {
        logWebDav("online completion module notification activity failed: ${it.message ?: it.javaClass.name}")
        false
    }

internal fun WebDavDriveHook.sendOnlineCompletionNotificationBroadcast(
    context: Context,
    id: Int,
    key: String,
    cancellable: Boolean,
    title: String,
    text: String,
    progress: Int,
    done: Boolean,
    issuedAt: Long,
): Boolean =
    runCatching {
        val intent = onlineCompletionNotificationIntent(id, key, cancellable, title, text, progress, done, issuedAt).apply {
            setClassName(MODULE_PACKAGE_NAME, ONLINE_COMPLETION_NOTIFICATION_RECEIVER_CLASS)
        }
        context.sendBroadcast(intent)
        logWebDav(
            "online completion module notification broadcast sent " +
                "id=$id progress=$progress done=$done",
        )
        true
    }.getOrElse {
        logWebDav("online completion module notification relay failed: ${it.message ?: it.javaClass.name}")
        false
    }

internal fun WebDavDriveHook.onlineCompletionNotificationIntent(
    id: Int,
    key: String,
    cancellable: Boolean,
    title: String,
    text: String,
    progress: Int,
    done: Boolean,
    issuedAt: Long,
): Intent =
    Intent(ONLINE_COMPLETION_NOTIFICATION_ACTION).apply {
            putExtra(com.reamicro.fix.notification.OnlineNotificationEventOrder.EXTRA_ISSUED_AT,
                issuedAt)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_ID, id)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_KEY, key)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_CANCELLABLE, cancellable)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_TITLE, title)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_TEXT, text)
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_PROGRESS, progress.coerceIn(0, 100))
            putExtra(ONLINE_COMPLETION_NOTIFICATION_EXTRA_DONE, done)
            putExtra(ModuleLogState.EXTRA_CONCISE_LOG_ENABLED, settingsProvider().conciseLogEnabled)
    }

internal fun WebDavDriveHook.importBookArgs(
    method: Method, file: Any?, bookDir: Any?, opf: Any?, url: Any?, size: Any?,
    checkCurrent: () -> Unit = {},
): Array<Any?> {
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.imports) { "Native import contract unavailable" }
    check(method == host.method("normal"))
    val arguments = arrayOfNulls<Any>(method.parameterCount - 1)
    val progress = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).callback(host.type("function1")) { checkCurrent() }
    for ((role, value) in mapOf("normalFile" to file, "normalPath" to bookDir, "normalOpf" to opf,
        "normalUrl" to url, "normalSize" to size, "normalProgress" to progress)) {
        val index = host.value(role)
        check(value == null || method.parameterTypes[index].isInstance(value)) { "Invalid native import argument $role" }
        arguments[index] = value
    }
    return arguments
}
