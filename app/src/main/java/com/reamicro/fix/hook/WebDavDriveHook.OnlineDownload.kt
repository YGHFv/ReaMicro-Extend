package com.reamicro.fix.hook

// OnlineChapterBatchSession 是 WebDavDriveHook 的 inner class（需要外部实例），
// 没有随其它嵌套类型提升到子包，因此这里显式导入类型名。
import com.reamicro.fix.hook.WebDavDriveHook.OnlineChapterBatchSession

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.util.Base64
import android.widget.Toast
import com.reamicro.fix.R
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
import com.reamicro.fix.webdav.CacheDeleteStat
import com.reamicro.fix.webdav.CancellableWebDavDownload
import com.reamicro.fix.webdav.OnlineChapterImageMarkup
import com.reamicro.fix.webdav.cleanOnlineChapterContentValue
import com.reamicro.fix.webdav.decodeOnlineHtmlEntities
import com.reamicro.fix.webdav.evaluateOnlineChapterUrlRule
import com.reamicro.fix.webdav.isPermanentOnlineChapterFailure
import com.reamicro.fix.webdav.onlineHttpRetryDelayMs
import com.reamicro.fix.webdav.onlineHttpRetryKind
import com.reamicro.fix.webdav.OnlineHttpRetryKind
import com.reamicro.fix.webdav.NativeCloudDownload
import com.reamicro.fix.webdav.OnlineCompletionDownloadCancelledException
import com.reamicro.fix.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.webdav.OnlineDownloadedChapter
import com.reamicro.fix.webdav.OnlineBookDownloadMode
import com.reamicro.fix.webdav.OnlineChapterState
import com.reamicro.fix.webdav.OnlineOnDemandChapter
import com.reamicro.fix.webdav.OnlineOnDemandChapterRequest
import com.reamicro.fix.webdav.OnlineOnDemandMetadata
import com.reamicro.fix.webdav.OnlineOnDemandMetadataStore
import com.reamicro.fix.webdav.OnlineChapterContentValidator
import com.reamicro.fix.webdav.OnlineChapterUpdatePlanner
import com.reamicro.fix.webdav.RemoteOnlineChapter
import com.reamicro.fix.webdav.StoredOnlineChapter
import de.robv.android.xposed.XposedBridge
import java.io.BufferedInputStream
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*

// WebDavDriveHook 的在线补全下载簇。
//
// 目录解析、章节并发下载与重试、按需下载、进度通知、写盘与导入宿主书架。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun WebDavDriveHook.cleanupStartupCacheIfNeeded(context: Context) {
    ensureOnlineCompletionCancelReceiver(context)
    if (!settingsProvider().canRunStartupCacheCleanup) return
    if (!startupCacheCleanupStarted.compareAndSet(false, true)) return
    Handler(Looper.getMainLooper()).postDelayed({
        Thread({
            runCatching {
                val cacheDir = context.applicationContext?.cacheDir ?: context.cacheDir
                val targets = listOf(
                    File(cacheDir, "downloads"),
                    File(cacheDir, "reamicro-webdav"),
                    File(cacheDir, "reamicro-local-library"),
                    File(cacheDir, "reamicro-webdav-backup"),
                    File(cacheDir, ONLINE_COMPLETION_CACHE_ROOT),
                ) + staleTopLevelImportCacheDirs(cacheDir)
                var deletedFiles = 0
                var deletedBytes = 0L
                targets.forEach { target ->
                    if (!target.exists()) return@forEach
                    if (isActiveOnlineCompletionCacheDir(target)) return@forEach
                    val stat = CacheDeleteStat()
                    deleteCachePath(target, stat)
                    deletedFiles += stat.files
                    deletedBytes += stat.bytes
                }
                logWebDav("startup cache cleanup files=$deletedFiles bytes=$deletedBytes")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX startup cache cleanup failed: ${it.stackTraceToString()}")
            }
        }, "ReaMicroCacheCleanup").start()
    }, STARTUP_CACHE_CLEANUP_DELAY_MS)
}

internal fun WebDavDriveHook.isActiveOnlineCompletionCacheDir(target: File): Boolean {
    val activeDirs = onlineCompletionRunningDownloads.values
        .map { it.cacheDir.absoluteFile }
        .toList()
    if (activeDirs.isEmpty()) return false
    val canonicalTarget = runCatching { target.canonicalFile }.getOrDefault(target.absoluteFile)
    return activeDirs.any { active ->
        val canonicalActive = runCatching { active.canonicalFile }.getOrDefault(active.absoluteFile)
        canonicalActive == canonicalTarget || isChildPath(canonicalActive, canonicalTarget)
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
    token.cancelRequested = true
    cancelTrackedWork(token.tracker, token.id)
    deleteCachePath(token.cacheDir, CacheDeleteStat())
    webDavDownloadCancelPrompts.remove(token.id)
    showToast("\u5df2\u53d6\u6d88\u4e0b\u8f7d\u5e76\u6e05\u7406\u7f13\u5b58")
    logWebDav("native download cancel requested type=$type name=$name id=${token.id}")
    return true
}

internal fun WebDavDriveHook.registerNativeCloudDownload(workerManager: Any?, book: Any, handle: Any, type: Int) {
    val id = handle.callString("getId")
    if (id.isBlank()) return
    val tracker = runCatching {
        workerManager?.javaClass?.getDeclaredField("tracker")?.apply { isAccessible = true }?.get(workerManager)
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

internal fun WebDavDriveHook.throwIfOnlineCompletionDownloadCancelled(task: OnlineCompletionDownloadTask) {
    if (task.cancelRequested || Thread.currentThread().isInterrupted) {
        throw OnlineCompletionDownloadCancelledException()
    }
}

internal fun WebDavDriveHook.requestOnlineCompletionDownloadCancel(context: Context, notificationId: Int, key: String) {
    val task = onlineCompletionRunningDownloadsByNotificationId[notificationId]
        ?: key.takeIf { it.isNotBlank() }?.let { onlineCompletionRunningDownloads[it] }
    if (task == null) {
        cancelOnlineCompletionHostNotification(context, notificationId)
        logWebDav("online completion cancel ignored missing task id=$notificationId key=$key")
        return
    }
    task.cancelRequested = true
    task.thread?.interrupt()
    cancelOnlineCompletionTrackedWork(task)
    cleanupOnlineCompletionDownloadTask(task)
    onlineCompletionRunningDownloads.remove(task.key, task)
    onlineCompletionRunningDownloadsByNotificationId.remove(task.notificationId, task)
    cancelOnlineCompletionNotification(context, task.notificationId, task.key)
    logWebDav("online completion cancel requested id=${task.notificationId} book=${task.name}")
}

internal fun WebDavDriveHook.cleanupDownloadToken(token: CancellableWebDavDownload) {
    runCatching {
        token.localFile.delete()
        token.localFile.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
    }.onFailure {
        logWebDav("failed to cleanup cancelled download cache: ${it.message}")
    }
}

internal fun WebDavDriveHook.cleanupOnlineCompletionDownloadTask(task: OnlineCompletionDownloadTask) {
    runCatching {
        val stat = CacheDeleteStat()
        deleteCachePath(task.cacheDir, stat)
        logWebDav(
            "online completion cache cleanup id=${task.notificationId} " +
                "files=${stat.files} bytes=${stat.bytes} dir=${task.cacheDir.absolutePath}",
        )
    }.onFailure {
        logWebDav("online completion cache cleanup failed id=${task.notificationId}: ${it.message}")
    }
}

internal fun WebDavDriveHook.deleteCachePath(file: File, stat: CacheDeleteStat) {
    if (!file.exists()) return
    if (file.isDirectory) {
        file.listFiles()?.forEach { deleteCachePath(it, stat) }
    } else {
        stat.files += 1
        stat.bytes += file.length().coerceAtLeast(0L)
    }
    file.delete()
}

internal fun WebDavDriveHook.nativeCloudDownloadKey(book: Any, type: Int): String {
    val id = book.callString("getId")
    val fallback = cloudPathOf(book).ifBlank { book.callString("getName") }
    return "$type:${id.ifBlank { fallback }}"
}

internal fun WebDavDriveHook.staleTopLevelImportCacheDirs(cacheDir: File): List<File> {
    val cutoff = System.currentTimeMillis() - STALE_IMPORT_CACHE_MIN_AGE_MS
    return cacheDir.listFiles()
        ?.filter { it.isDirectory && UUID_DIR_REGEX.matches(it.name) && it.lastModified() in 1 until cutoff }
        .orEmpty()
}

internal fun WebDavDriveHook.cancelExistingBackupTasks(tracker: Any, bookId: Long) {
    runCatching {
        val worksFlow = tracker.javaClass.methods.first {
            it.name == "works" && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(tracker)
        val works = worksFlow?.javaClass?.methods?.firstOrNull {
            it.name == "getValue" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(worksFlow) as? Iterable<*> ?: return
        val ids = works.mapNotNull { work ->
            val kind = work?.javaClass?.methods?.firstOrNull { it.name == "getKind" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(work)
                ?.toString()
            val label = work?.javaClass?.methods?.firstOrNull { it.name == "getLabel" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(work)
                ?.toString()
            if (kind?.startsWith("backup") == true && label == bookId.toString()) {
                work.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.invoke(work)
                    ?.toString()
            } else {
                null
            }
        }
        val cancel = tracker.javaClass.methods.firstOrNull {
            it.name == "cancelWorkById" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }
        ids.forEach { cancel?.invoke(tracker, it) }
        tracker.javaClass.methods.firstOrNull {
            it.name == "pruneWork" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(tracker)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to cancel old WebDAV backup tasks: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.ensureNcxSpineToc(content: String): String {
    if (Regex("""<spine\b[^>]*\btoc\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
        return content
    }
    val ncxId = Regex(
        """<item\b(?=[^>]*\bmedia-type\s*=\s*["']application/x-dtbncx\+xml["'])(?=[^>]*\bid\s*=\s*["']([^"']+)["'])[^>]*>""",
        RegexOption.IGNORE_CASE,
    ).find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return content
    return Regex("""<spine\b""", RegexOption.IGNORE_CASE)
        .replaceFirst(content, "<spine toc=\"$ncxId\"")
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
    val sourceIdCandidates = listOf(
        epubMetadata.sourceId,
        onlineSourceIdFromEncodedValue(backupCode),
        onlineSourceIdFromEncodedValue(backupId),
        onlineSourceIdFromUuid(uuid),
    ).filter { it.isNotBlank() }.distinct()
    val sourceById = sources.firstOrNull { source -> source.id in sourceIdCandidates }
    val sourceByPublisher = sources.firstOrNull { source ->
        source.name == publisher || source.id == publisher
    }
    val sourceByMetadataName = sources.firstOrNull { source ->
        source.name == epubMetadata.sourceName
    }
    val detailUrl = onlineCompletionQueryParameter(backupId, "detail")
        .ifBlank { epubMetadata.detailUrl }
        .ifBlank { uri.takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty() }
    val source = sourceById ?: sourceByMetadataName ?: sourceByPublisher
    val sourceId = source?.id
        ?: epubMetadata.sourceId
            .ifBlank { sourceIdCandidates.firstOrNull { it.startsWith("online_") }.orEmpty() }
    // Incremental updates only work for EPUBs generated by this module; weak DB markers can exist on normal books.
    val hasOnlineCompletionMarker = epubMetadata.hasMarker &&
        (isOnlineCompletionLocalBook(book) ||
            sourceId.isNotBlank() ||
            detailUrl.isNotBlank())
    if (!hasOnlineCompletionMarker) return null
    val sourceName = source?.name.orEmpty()
        .ifBlank { onlineCompletionQueryParameter(backupId, "name") }
        .ifBlank { epubMetadata.sourceName }
        .ifBlank { publisher.takeUnless(::isPathLikeOnlineSourceName).orEmpty() }
        .ifBlank { sourceId }
    return OnlineImportedBookSourceInfo(
        sourceId = source?.id ?: sourceId,
        sourceName = sourceName.ifBlank { "未知源" },
        detailUrl = detailUrl,
        source = source,
    )
}

internal fun WebDavDriveHook.startOnlineCompletionChapterUpdate(book: Any, info: OnlineImportedBookSourceInfo) {
    val context = currentContext()
    if (context == null) {
        showToast("无法启动章节更新：缺少 Context")
        return
    }
    val appContext = context.applicationContext ?: context
    val source = (info.source ?: OnlineSourceStore.list(appContext).firstOrNull { source ->
        source.id == info.sourceId
    })?.let { OnlineSourceDownloadPolicyStore.attach(appContext, it) }
    if (source == null) {
        showToast("在线补全源不可用：${info.sourceName}")
        return
    }
    if (OnlineSourceDownloadPolicyStore.remainingToday(appContext, source) == 0) {
        showToast(OnlineSourceDownloadPolicyStore.limitReachedMessage(source))
        return
    }
    val title = book.callString("getTitle").ifBlank { "未命名图书" }
    val detailUrl = info.detailUrl
        .ifBlank { book.callString("getUri").takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty() }
    if (detailUrl.isBlank()) {
        showToast("缺少在线源详情地址，无法更新章节")
        return
    }
    val key = "${book.callString("getUuid")}|${source.id}|$detailUrl"
    if (onlineCompletionRunningUpdates.putIfAbsent(key, true) == true) {
        showToast("正在更新：$title")
        return
    }
    val notificationId = onlineCompletionNotificationIds.incrementAndGet()
    val tracker = currentWorkTracker()
    val workId = tracker?.let { createOnlineCompletionTrackedTask(it, "$title 更新") }
    val progressNotifier = OnlineCompletionProgressNotifier(
        context = appContext,
        notificationId = notificationId,
        notificationKey = "update:$key",
        cancellable = false,
        bookName = title,
        tracker = tracker,
        workId = workId,
    )
    progressNotifier.running(0, "准备更新", force = true)
    showToast("开始检查更新：$title")
    Thread({
        runCatching {
            val latest = findLocalBookByUuid(book.callLong("getUid"), book.callString("getUuid")) ?: book
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
                onProgress = { progress, message ->
                    progressNotifier.running(progress, message)
                },
            )
            val message = when {
                result.added > 0 && result.retried > 0 && result.failed > 0 ->
                    "已重试 ${result.retried} 章，已更新 ${result.added} 章，${result.failed} 章失败"
                result.added > 0 && result.retried > 0 ->
                    "已重试 ${result.retried} 章，已更新 ${result.added} 章"
                result.added > 0 && result.failed > 0 -> "已更新 ${result.added} 章，${result.failed} 章失败"
                result.failed > 0 && result.retried > 0 -> "已重试 ${result.retried} 章，${result.failed} 章失败"
                result.failed > 0 -> "仍有 ${result.failed} 章失败"
                result.retried > 0 -> "已重试 ${result.retried} 章"
                result.styled -> "章节已是最新，默认样式已同步"
                result.added <= 0 -> "已是最新章节"
                else -> "已更新 ${result.added} 章"
            }
            progressNotifier.finish(message, detailUrl, success = true)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "$message：$title", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX online completion update failed: ${it.stackTraceToString()}")
            progressNotifier.finish(it.message ?: "章节更新失败", null, success = false)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "章节更新失败：${it.message ?: title}", Toast.LENGTH_SHORT).show()
            }
        }.also {
            onlineCompletionRunningUpdates.remove(key)
        }
    }, "ReaMicroOnlineCompletionUpdate").start()
}

internal fun WebDavDriveHook.retryOnlineCompletionLoggedFailures(
    bookDir: File,
    target: OnlineDownloadTarget,
    tocSnapshot: OnlineTocSnapshot,
    failures: List<OnlineFailedChapter>,
    chapterHrefs: List<String>,
    maxChapterAttempts: Int? = null,
    onProgress: (Int, String) -> Unit,
): OnlineFailedRetryResult {
    if (failures.isEmpty()) return OnlineFailedRetryResult(0, 0, false, 0)
    val remoteChapters = tocSnapshot.chapters
    val candidates = failures
        .filter { it.index in remoteChapters.indices }
        .distinctBy { it.index }
        .let { values -> maxChapterAttempts?.let { values.take(it.coerceAtLeast(0)) } ?: values }
    if (candidates.isEmpty()) {
        writeOnlineCompletionFailedChapters(bookDir, target, failures)
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
                val message = error.message.orEmpty().ifBlank { error.javaClass.name }
                if (error is OnlineSourceDailyLimitException) {
                    dailyLimitMessage = message
                    nonRetryable.add(index)
                }
                if (isPermanentOnlineChapterFailure(error)) {
                    nonRetryable.add(index)
                }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE && index !in nonRetryable) {
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
    var retryRound = 0
    while (failed.any { it !in nonRetryable && (roundAttemptsByIndex[it] ?: 0) < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }) {
        retryRound++
        val retryCandidates = failed.filter {
            it !in nonRetryable && (roundAttemptsByIndex[it] ?: 0) < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT
        }
        val waitMs = onlineCompletionRetryDelayMs(retryRound)
        onProgress(23, "等待重试失败章节 ${retryCandidates.size} 章${onlineCompletionFailureSuffix(failed.size)}")
        sleepOnlineCompletionRetryDelay(null, waitMs)
        retryCandidates.forEachIndexed { retryIndex, index ->
            val progress = 23 + ((retryIndex + 1) * 8 / retryCandidates.size.coerceAtLeast(1))
            onProgress(progress, "重试失败章节 ${retryIndex + 1}/${retryCandidates.size}${onlineCompletionFailureSuffix(failed.size)}")
            attemptLoggedChapter(index, immediateRetry = false)
        }
    }
    val unresolved = remaining.values.sortedBy { it.index }
    logOnlineCompletionFinalFailures(target, unresolved.filter { it.index in failed }, "update-retry")
    writeOnlineCompletionFailedChapters(bookDir, target, unresolved)
    return OnlineFailedRetryResult(
        retried = successCount,
        failed = unresolved.size,
        changed = successCount > 0 || unresolved.size != failures.size,
        attempted = candidates.size,
    )
}

internal fun WebDavDriveHook.onlineChapterUpdatePlan(
    bookDir: File,
    remoteChapters: List<OnlineChapter>,
): Pair<List<String>, List<Int>> {
    val indexed = readOnlineCompletionChapterIndex(bookDir)
    val stored = indexed.ifEmpty { readLegacyOnlineCompletionChapters(bookDir) }
    if (stored.isEmpty()) error("未找到本地图书章节索引，暂不能安全更新")
    val remote = remoteChapters.map { chapter ->
        RemoteOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
            title = chapter.title,
            volumeTitle = chapter.volumeTitle,
        )
    }
    val plan = OnlineChapterUpdatePlanner.plan(stored, remote)
    val matchedCount = plan.count { it.stored != null }
    if (remote.size < stored.size && matchedCount < stored.size) {
        error("远端目录少于本地目录，已停止更新以避免误删章节")
    }
    if (indexed.isEmpty() && stored.size >= 20 && matchedCount * 100 < stored.size * 80) {
        error("旧书目录与远端章节匹配率过低，已停止更新以避免重复下载")
    }
    val usedHrefs = stored.mapTo(linkedSetOf()) { it.href }
    val maxFileNumber = File(bookDir, "OEBPS/Text").listFiles()
        ?.mapNotNull { file ->
            ONLINE_COMPLETION_CHAPTER_FILE_REGEX.matchEntire(file.name)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        ?.maxOrNull()
        ?: 0
    var nextFileNumber = maxFileNumber + 1
    val newRemoteIndices = ArrayList<Int>()
    val hrefs = plan.map { slot ->
        val storedHref = slot.stored?.href
            ?.takeIf { href -> onlineCompletionChapterFile(bookDir, href).isFile }
        if (storedHref != null) return@map storedHref
        newRemoteIndices.add(slot.remoteIndex)
        var href: String
        do {
            href = "Text/chapter_${nextFileNumber.toString().padStart(4, '0')}.xhtml"
            nextFileNumber++
        } while (!usedHrefs.add(href) || onlineCompletionChapterFile(bookDir, href).exists())
        href
    }
    logWebDav(
        "online completion id plan local=${stored.size} remote=${remote.size} " +
            "matched=$matchedCount new=${newRemoteIndices.size} indexed=${indexed.isNotEmpty()}",
    )
    return hrefs to newRemoteIndices
}

internal fun WebDavDriveHook.onlineCompletionChapterFile(bookDir: File, href: String): File {
    val root = bookDir.canonicalFile
    val relative = href.trim().removePrefix("OEBPS/")
    if (!relative.startsWith("Text/") || !relative.endsWith(".xhtml", ignoreCase = true)) {
        error("Invalid online chapter href: $href")
    }
    val file = File(root, "OEBPS/$relative").canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    if (!file.path.startsWith(rootPrefix)) error("EPUB chapter path escapes book dir")
    return file
}

internal fun WebDavDriveHook.detectInvalidOnlineCompletionChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    remoteChapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
    ignoredIndices: Set<Int>,
): List<OnlineFailedChapter> {
    val failures = chapterHrefs.mapIndexedNotNull { index, href ->
        if (index !in remoteChapters.indices || index in ignoredIndices) return@mapIndexedNotNull null
        val chapterFile = onlineCompletionChapterFile(bookDir, href)
        if (!chapterFile.isFile) return@mapIndexedNotNull null
        val reason = runCatching {
            OnlineChapterContentValidator.storedXhtmlFailureReason(chapterFile.readText(Charsets.UTF_8))
        }.getOrElse { error ->
            "读取历史章节失败：${error.message.orEmpty().ifBlank { error.javaClass.name }}"
        } ?: return@mapIndexedNotNull null
        onlineCompletionFailedChapter(
            target = target,
            chapter = remoteChapters[index],
            index = index,
            attempts = 0,
            message = "检测到历史无效章节：$reason",
            href = href,
        )
    }
    if (failures.isNotEmpty()) {
        logWebDav(
            "online completion invalid stored chapters detected title=${target.result.name} " +
                "count=${failures.size} indices=${failures.take(20).joinToString { (it.index + 1).toString() }}",
        )
    }
    return failures
}

internal fun WebDavDriveHook.remapOnlineCompletionFailures(
    failures: List<OnlineFailedChapter>,
    remoteChapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
): List<OnlineFailedChapter> {
    if (failures.isEmpty()) return emptyList()
    val remote = remoteChapters.map { chapter ->
        RemoteOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
            title = chapter.title,
            volumeTitle = chapter.volumeTitle,
        )
    }
    val storedFailures = failures.map { failure ->
        StoredOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(failure.url),
            title = failure.title,
            volumeTitle = failure.volumeTitle,
            href = failure.href,
        )
    }
    val matches = OnlineChapterUpdatePlanner.plan(storedFailures, remote)
    val byStored = matches.mapNotNull { slot -> slot.stored?.let { it to slot.remoteIndex } }.toMap()
    return failures.mapIndexedNotNull { failureIndex, failure ->
        val stored = storedFailures[failureIndex]
        val remoteIndex = byStored[stored] ?: return@mapIndexedNotNull null
        val chapter = remoteChapters[remoteIndex]
        failure.copy(
            index = remoteIndex,
            title = chapter.title,
            url = chapter.url,
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
            href = chapterHrefs.getOrNull(remoteIndex).orEmpty(),
        )
    }.distinctBy { it.index }
}

internal fun WebDavDriveHook.showOnlineCompletionDownloadModeDialog(target: OnlineDownloadTarget) {
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
    target: OnlineDownloadTarget,
    mode: OnlineBookDownloadMode = OnlineBookDownloadMode.FULL,
) {
    val context = currentContext()
    if (context == null) {
        showToast("无法启动在线补全下载：缺少 Context")
        return
    }
    val appContext = context.applicationContext ?: context
    target.source = OnlineSourceDownloadPolicyStore.attach(appContext, target.source)
    if (OnlineSourceDownloadPolicyStore.remainingToday(appContext, target.source) == 0) {
        showToast(OnlineSourceDownloadPolicyStore.limitReachedMessage(target.source))
        return
    }
    ensureOnlineCompletionCancelReceiver(appContext)
    val key = "${target.source.id}|${target.result.detailUrl}|${target.result.name}|${mode.name}"
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
        notificationId = notificationId,
        key = key,
        name = target.result.name,
        cacheDir = cacheDir,
        tracker = tracker,
        workId = workId,
    )
    if (onlineCompletionRunningDownloads.putIfAbsent(key, task) != null) {
        cancelOnlineCompletionTrackedWork(task)
        showToast("正在下载：${target.result.name}")
        return
    }
    onlineCompletionRunningDownloadsByNotificationId[notificationId] = task
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
    val thread = Thread({
        var partialImportThread: Thread? = null
        runCatching {
            throwIfOnlineCompletionDownloadCancelled(task)
            val localFile = File(task.cacheDir, "${safeOnlineFileName(target.result.name)}.epub")
            task.cacheDir.mkdirs()
            if (mode == OnlineBookDownloadMode.ON_DEMAND) {
                val importResult = downloadOnlineCompletionOnDemandBook(
                    target = target,
                    outputFile = localFile,
                    task = task,
                    onProgress = { progress, message ->
                        throwIfOnlineCompletionDownloadCancelled(task)
                        progressNotifier.running(progress, message)
                    },
                )
                task.importedBookDir = importResult.bookDir
                throwIfOnlineCompletionDownloadCancelled(task)
                progressNotifier.finish("已导入逐章加载书籍", target.result.detailUrl, success = true)
                logWebDav(
                    "online completion on-demand ready file=${localFile.absolutePath} " +
                        "size=${localFile.length()} importedDir=${importResult.bookDir}",
                )
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "已导入逐章加载：${target.result.name}", Toast.LENGTH_SHORT).show()
                }
                cleanupOnlineCompletionDownloadTask(task)
                return@runCatching
            }
            downloadOnlineCompletionBook(
                target = target,
                outputFile = localFile,
                task = task,
                onProgress = { progress, message ->
                    throwIfOnlineCompletionDownloadCancelled(task)
                    val displayedProgress = if (task.importedBookDir != null && progress < 50) 50 else progress
                    progressNotifier.running(displayedProgress, message)
                },
                onPartialReady = { partialFile, count ->
                    throwIfOnlineCompletionDownloadCancelled(task)
                    progressNotifier.running(48, "正在导入前 $count 章", force = true)
                    partialImportThread = Thread({
                        if (task.cancelRequested) return@Thread
                        val partialImported = runCatching {
                            throwIfOnlineCompletionDownloadCancelled(task)
                            val importResult = importOnlineCompletionBook(
                                file = partialFile,
                                target = target,
                                onWaiting = {
                                    throwIfOnlineCompletionDownloadCancelled(task)
                                    progressNotifier.running(48, "等待导入队列：前 $count 章", force = true)
                                },
                                onStarted = {
                                    throwIfOnlineCompletionDownloadCancelled(task)
                                    progressNotifier.running(48, "正在导入前 $count 章", force = true)
                                },
                            )
                            importResult.bookDir?.let { importedDir ->
                                task.importedBookDir = importedDir
                                flushOnlineCompletionDownloadedChapters(task, target)
                                syncOnlineCompletionImportedBookSize(target)
                                logWebDav(
                                    "online completion partial import visible book=${target.result.name} " +
                                        "dir=${importedDir.absolutePath}",
                                )
                            }
                            importResult.imported
                        }.onFailure { error ->
                            if (error is OnlineCompletionDownloadCancelledException || task.cancelRequested) {
                                return@Thread
                            }
                            logWebDav(
                                "online completion partial import failed but download continues: " +
                                    "${error.message ?: error.javaClass.name}",
                            )
                        }.getOrDefault(false)
                        val continueMessage = if (partialImported) {
                            "已导入前 $count 章，继续下载"
                        } else {
                            "前 $count 章已写入，继续下载"
                        }
                        progressNotifier.running(50, continueMessage, force = true)
                    }, "ReaMicroOnlineCompletionPartialImport").also {
                        it.start()
                    }
                },
            )
            throwIfOnlineCompletionDownloadCancelled(task)
            partialImportThread?.takeIf { it.isAlive }?.let { thread ->
                progressNotifier.running(90, "等待前 100 章导入完成", force = true)
                thread.join()
            }
            throwIfOnlineCompletionDownloadCancelled(task)
            if (task.importedBookDir != null) {
                progressNotifier.running(94, "写入剩余章节", force = true)
                flushOnlineCompletionDownloadedChapters(task, target)
                syncOnlineCompletionImportedBookSize(target)
                logWebDav(
                    "online completion final import skipped; chapters written to imported dir=${task.importedBookDir}",
                )
            } else {
                progressNotifier.running(94, "正在导入完整章节", force = true)
                importOnlineCompletionBook(
                    file = localFile,
                    target = target,
                    onWaiting = {
                        throwIfOnlineCompletionDownloadCancelled(task)
                        progressNotifier.running(94, "等待导入队列", force = true)
                    },
                    onStarted = {
                        throwIfOnlineCompletionDownloadCancelled(task)
                        progressNotifier.running(94, "正在导入完整章节", force = true)
                    },
                )
            }
            throwIfOnlineCompletionDownloadCancelled(task)
            progressNotifier.finish("已导入阅微", target.result.detailUrl, success = true)
            logWebDav(
                "online completion download complete file=${localFile.absolutePath} " +
                    "size=${localFile.length()} importedDir=${task.importedBookDir}",
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "已导入：${target.result.name}", Toast.LENGTH_SHORT).show()
            }
            cleanupOnlineCompletionDownloadTask(task)
        }.onFailure {
            if (it is OnlineCompletionDownloadCancelledException || task.cancelRequested) {
                cancelOnlineCompletionTrackedWork(task)
                cleanupOnlineCompletionDownloadTask(task)
                cancelOnlineCompletionNotification(appContext, task.notificationId, task.key)
                logWebDav("online completion download cancelled book=${target.result.name}")
            } else {
                XposedBridge.log("$LOG_PREFIX online completion download failed: ${it.stackTraceToString()}")
                cleanupOnlineCompletionDownloadTask(task)
                progressNotifier.finish(it.message ?: "下载失败", null, success = false)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "在线补全下载失败：${it.message ?: target.result.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }.also {
            onlineCompletionRunningDownloads.remove(key, task)
            onlineCompletionRunningDownloadsByNotificationId.remove(notificationId, task)
        }
    }, "ReaMicroOnlineCompletionDownload")
    task.thread = thread
    thread.start()
}

internal fun WebDavDriveHook.createOnlineCompletionTrackedTask(tracker: Any, bookName: String): String? =
    runCatching {
        val id = "online-${UUID.randomUUID()}"
        tracker.javaClass.methods.first {
            it.name == "createTask" && it.parameterTypes.size == 4
        }.apply { isAccessible = true }.invoke(
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

internal fun WebDavDriveHook.onlineCompletionFailedChapter(
    target: OnlineDownloadTarget,
    chapter: OnlineChapter,
    index: Int,
    attempts: Int,
    message: String,
    href: String = "",
): OnlineFailedChapter =
    OnlineFailedChapter(
        index = index,
        title = chapter.title.ifBlank { "第 ${index + 1} 章" },
        url = chapter.url,
        volumeTitle = chapter.volumeTitle,
        level = chapter.level,
        attempts = attempts,
        message = message,
        updatedAt = System.currentTimeMillis(),
        sourceId = target.source.id,
        detailUrl = target.result.detailUrl,
        href = href,
    )

internal fun WebDavDriveHook.onlineCompletionRetryDelayMs(round: Int): Long =
    (ONLINE_COMPLETION_RETRY_DELAY_MS * round.coerceAtLeast(1)).coerceAtMost(ONLINE_COMPLETION_RETRY_DELAY_MAX_MS)

internal fun WebDavDriveHook.sleepOnlineCompletionRetryDelay(task: OnlineCompletionDownloadTask?, delayMs: Long) {
    val deadline = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(0L)
    while (true) {
        task?.let { throwIfOnlineCompletionDownloadCancelled(it) }
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) break
        Thread.sleep(remaining.coerceAtMost(500L))
    }
    task?.let { throwIfOnlineCompletionDownloadCancelled(it) }
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
): OnlineCompletionImportResult {
    throwIfOnlineCompletionDownloadCancelled(task)
    val tocSnapshot = loadOnlineCompletionToc(target, onProgress)
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
        repeat(2) {
            if (downloaded[index] != null) return@repeat
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
                lastError = error
                errors[index] = error.message.orEmpty().ifBlank { error.javaClass.name }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE) {
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
                downloadOnlineBytes(target.source, coverUrl)
            }
        }.getOrNull()
    }
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
    )
    throwIfOnlineCompletionDownloadCancelled(task)
    onProgress(82, "正在导入逐章加载书籍")
    val importResult = importOnlineCompletionBook(
        file = outputFile,
        target = target,
        onWaiting = { onProgress(82, "等待导入队列") },
        onStarted = { onProgress(86, "正在导入逐章加载书籍") },
    )
    val bookDir = importResult.bookDir ?: error("导入后未找到图书目录")
    OnlineOnDemandMetadataStore.write(bookDir, metadata)
    syncOnlineCompletionImportedBookSize(target)
    onProgress(96, "逐章加载书籍已就绪")
    return importResult
}

internal fun WebDavDriveHook.downloadOnlineCompletionBook(
    target: OnlineDownloadTarget,
    outputFile: File,
    task: OnlineCompletionDownloadTask,
    onProgress: (Int, String) -> Unit,
    onPartialReady: (File, Int) -> Unit,
) {
    throwIfOnlineCompletionDownloadCancelled(task)
    val tocSnapshot = loadOnlineCompletionToc(target, onProgress)
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
    }
    val cover = target.result.coverUrl.takeIf { it.isNotBlank() }?.let { coverUrl ->
        runCatching {
            throwIfOnlineCompletionDownloadCancelled(task)
            OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
                throwIfOnlineCompletionDownloadCancelled(task)
                downloadOnlineBytes(target.source, coverUrl)
            }
        }.onSuccess { payload ->
            logWebDav(
                "online completion cover downloaded url=${coverUrl.take(160)} " +
                    "bytes=${payload.bytes.size} type=${payload.mimeType.ifBlank { "unknown" }}",
            )
        }.onFailure { error ->
            logWebDav(
                "online completion cover failed url=${coverUrl.take(160)} " +
                    "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }.getOrNull()
    }
    val shouldImportFirstBatch = chapters.size > ONLINE_COMPLETION_PARTIAL_IMPORT_THRESHOLD
    var firstBatchImported = false
    var consecutiveServiceFailures = 0
    val batchSession = onlineChapterBatchSession(target, chapters, 0 until allowedChapterCount)
    fun downloadedCount(): Int = downloaded.count { it != null }

    fun maybeImportFirstBatch(progress: Int, processedCount: Int) {
        throwIfOnlineCompletionDownloadCancelled(task)
        if (!shouldImportFirstBatch || firstBatchImported) return
        val firstBatch = downloaded.take(ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS)
        if (
            firstBatch.size < ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS ||
            processedCount < ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS ||
            firstBatch.all { it == null }
        ) return
        firstBatchImported = true
        val partialFile = File(
            outputFile.parentFile ?: outputFile.absoluteFile.parentFile ?: File("/data/local/tmp"),
            "${outputFile.nameWithoutExtension}_first_${ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS}.epub",
        )
        onProgress(progress, "生成前 ${ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS} 章 EPUB")
        throwIfOnlineCompletionDownloadCancelled(task)
        val partialChapters = chapters.mapIndexed { index, chapter ->
            downloaded[index] ?: onlineCompletionPendingChapter(chapter, index)
        }
        writeOnlineCompletionEpub(partialFile, target, partialChapters, cover)
        logWebDav(
            "online completion partial epub ready path=${partialFile.absolutePath} " +
                "downloaded=${firstBatch.size} toc=${partialChapters.size}",
        )
        onPartialReady(partialFile, ONLINE_COMPLETION_PARTIAL_IMPORT_CHAPTERS)
    }

    fun attemptChapter(index: Int, immediateRetry: Boolean): Boolean {
        throwIfOnlineCompletionDownloadCancelled(task)
        val chapter = chapters[index]
        dailyLimitMessage?.let { message ->
            failed.add(index)
            nonRetryable.add(index)
            failureMessages[index] = message
            return false
        }
        val maxAttemptsThisRound = if (immediateRetry) 2 else 1
        var roundAttempts = 0
        while (attempts[index] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT &&
            roundAttempts < maxAttemptsThisRound && index !in nonRetryable
        ) {
            throwIfOnlineCompletionDownloadCancelled(task)
            attempts[index]++
            roundAttempts++
            val attempt = attempts[index]
            runCatching {
                throwIfOnlineCompletionDownloadCancelled(task)
                downloadOnlineChapter(target, detail.url, detail.body, chapter, index, batchSession)
            }.onSuccess { chapterContent ->
                downloaded[index] = chapterContent
                task.downloadedChapters[index] = chapterContent
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
                failed.add(index)
                val message = error.message.orEmpty().ifBlank { error.javaClass.name }
                failureMessages[index] = message
                if (error is OnlineSourceDailyLimitException) {
                    dailyLimitMessage = message
                    nonRetryable.add(index)
                }
                if (isPermanentOnlineChapterFailure(error)) {
                    nonRetryable.add(index)
                }
                val retryKind = onlineHttpRetryKind(error)
                if (retryKind != OnlineHttpRetryKind.NONE && index !in nonRetryable) {
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
        if (failed.isNotEmpty()) {
            onProgress(progress, "下载章节 ${index + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
        }
        maybeImportFirstBatch(progress, index + 1)
    }
    var retryRound = 0
    while (failed.any { it !in nonRetryable && attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }) {
        retryRound++
        val retryCandidates = failed.filter { it !in nonRetryable && attempts[it] < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }
        val waitMs = onlineCompletionRetryDelayMs(retryRound)
        onProgress(84, "等待重试失败章节 ${retryCandidates.size} 章${onlineCompletionFailureSuffix(failed.size)}")
        logWebDav(
            "online completion delayed retry round=$retryRound wait=${waitMs}ms " +
                "candidates=${retryCandidates.size} failed=${failed.size} " +
                "downloaded=${downloadedCount()}/${chapters.size}",
        )
        sleepOnlineCompletionRetryDelay(task, waitMs)
        retryCandidates.forEachIndexed { retryIndex, chapterIndex ->
            throwIfOnlineCompletionDownloadCancelled(task)
            val progress = 84 + ((retryIndex + 1) * 4 / retryCandidates.size.coerceAtLeast(1))
            onProgress(progress, "重试章节 ${chapterIndex + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
            attemptChapter(chapterIndex, immediateRetry = false)
            if (failed.isNotEmpty()) {
                onProgress(progress, "重试章节 ${chapterIndex + 1}/${chapters.size}${onlineCompletionFailureSuffix(failed.size)}")
            }
            maybeImportFirstBatch(progress, chapters.size)
        }
    }
    val successCount = downloadedCount()
    if (failed.isNotEmpty()) {
        onProgress(88, "仍有 ${failed.size} 章失败，继续生成 EPUB")
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
    logOnlineCompletionFinalFailures(target, finalFailures, "download")
    task.importedBookDir?.let { bookDir ->
        writeOnlineCompletionFailedChapters(bookDir, target, finalFailures)
    }
    val finalChapters = chapters.mapIndexed { index, chapter ->
        downloaded[index] ?: OnlineDownloadedChapter(
            title = chapter.title.ifBlank { "第 ${index + 1} 章" },
            content = onlineCompletionFailurePlaceholder(attempts[index], failureMessages[index].orEmpty()),
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
            sourceUrl = chapter.url,
        )
    }
    onProgress(88, "生成 EPUB")
    throwIfOnlineCompletionDownloadCancelled(task)
    writeOnlineCompletionEpub(outputFile, target, finalChapters, cover, finalFailures)
    logWebDav(
        "online completion epub ready path=${outputFile.absolutePath} " +
            "chapters=${finalChapters.size} success=$successCount failed=${failed.size}",
    )
}

internal fun WebDavDriveHook.loadOnlineCompletionToc(
    target: OnlineDownloadTarget,
    onProgress: (Int, String) -> Unit,
): OnlineTocSnapshot {
    val detailUrl = target.result.detailUrl.ifBlank { error("搜索结果缺少详情页地址") }
    onProgress(3, "读取详情页")
    val detail = OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
        requestOnlineSearch(target.source, detailUrl)
    }
    logWebDav("online completion detail ok url=${detail.url} bytes=${detail.body.length}")
    val tocUrl = buildOnlineTocUrl(target.source, detail.url, detail.body)
    logWebDav("online completion toc url=${tocUrl.ifBlank { detail.url }}")
    val toc = if (tocUrl.isBlank() || tocUrl == detail.url) {
        detail
    } else {
        onProgress(4, "读取目录")
        OnlineConcurrentRateLimiter.withLimitBlocking(target.source) {
            requestOnlineSearch(target.source, tocUrl)
        }
    }
    val chapters = parseOnlineToc(target.source, target.result, toc.url, toc.body)
    if (chapters.isEmpty()) {
        val hint = if (target.result.chapterCount > 0) {
            "，搜索结果标记 ${target.result.chapterCount} 章，可能未登录或源端未缓存章节"
        } else {
            ""
        }
        error("在线源目录为空$hint，已停止导入：${target.source.name}")
    }
    enrichOnlineTargetFromDetail(target, detail.body, detail.url, chapters.size)
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
        // 段评属于阅读运行时数据，不写入 EPUB 正文，避免导出时残留动态段评标识。
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
        )
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

internal fun WebDavDriveHook.fanqieContentBody(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return body
    val data = root.optJSONObject("data") ?: return body
    return (data.optJSONObject("data") ?: data).optString("content")
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
): Boolean {
    val explicitVolume = isVolumeRule.trim()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source).trim().lowercase(Locale.ROOT) }
        ?.let { value ->
            when (value) {
                "true", "1", "yes", "volume", "vol", "part", "group", "section" -> true
                "false", "0", "no", "chapter" -> false
                else -> null
            }
        }
    val nodeType = listOf("$.type", "$.kind", "$.nodeType", "$.node_type", "type", "kind", "nodeType", "node_type")
        .asSequence()
        .map { onlineJsonString(node, it).cleanOnlineText() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()
    return OnlineChapterUpdatePlanner.isVolumeNode(explicitVolume, nodeType, title, hasChapterUrl)
}

internal fun WebDavDriveHook.onlineChapterVolumeTitle(node: Any?): String =
    listOf(
        "$.volume_name",
        "$.volumeName",
        "$.volume",
        "$.part_name",
        "$.partName",
        "$.section_name",
        "$.sectionName",
        "$.group_name",
        "$.groupName",
        "volume_name",
        "volumeName",
        "volume",
        "part_name",
        "partName",
        "section_name",
        "sectionName",
        "group_name",
        "groupName",
    ).asSequence()
        .map { onlineJsonString(node, it).cleanOnlineText() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()

internal fun WebDavDriveHook.fallbackOnlineChapterRawUrl(node: Any?): String =
    listOf(
        "$.itemId", "$.item_id", "$.chapterId", "$.chapter_id", "$.id", "$.url", "$.href",
        "itemId", "item_id", "chapterId", "chapter_id", "id",
    )
        .asSequence()
        .map { onlineJsonString(node, it) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

/** 书旗目录固定为 data.chapterList[0].volumeList，大数组场景直接读取。 */
internal fun WebDavDriveHook.shuqiChapterListNodes(root: Any, chapterListRule: String): List<Any?> {
    if (!chapterListRule.substringBefore("<js>").trim()
            .equals("$.data.chapterList[0].volumeList", ignoreCase = true)
    ) return emptyList()
    val data = (root as? JSONObject)?.optJSONObject("data") ?: return emptyList()
    val volumes = data.optJSONArray("chapterList") ?: return emptyList()
    val chapters = volumes.optJSONObject(0)?.optJSONArray("volumeList") ?: return emptyList()
    return (0 until chapters.length()).mapNotNull { index ->
        chapters.opt(index)?.takeIf { it != JSONObject.NULL }
    }
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

internal fun WebDavDriveHook.evaluateOnlineChapterUrlJs(urlRule: String, result: String): String? {
    if (!urlRule.contains("java.base64Encode(result)", ignoreCase = true)) return null
    val template = Regex("`([^`]*java\\.base64Encode\\(result\\)[^`]*)`")
        .find(urlRule)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    val encoded = Base64.encodeToString(result.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return template.replace("\${java.base64Encode(result)}", encoded)
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

internal fun WebDavDriveHook.extractOnlineHtmlChapterContent(html: String): String {
    val cleaned = html
        .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
    val container = listOf(
        Regex("""(?is)<article\b[^>]*>(.*?)</article>"""),
        Regex("""(?is)<div\b[^>]*(?:id|class)\s*=\s*["'][^"']*(?:content|chapter|reader|read)[^"']*["'][^>]*>(.*?)</div>"""),
        Regex("""(?is)<body\b[^>]*>(.*?)</body>"""),
    ).asSequence()
        .mapNotNull { it.find(cleaned)?.groupValues?.getOrNull(1) }
        .firstOrNull()
        ?: cleaned
    return container
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
        .let(::normalizeOnlineChapterText)
}

internal fun WebDavDriveHook.normalizeOnlineChapterText(raw: String): String {
    if (raw.isBlank()) return ""
    return OnlineChapterImageMarkup.preserve(raw)
        .let(::cleanOnlineChapterContentValue)
        .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .decodeOnlineHtmlEntities()
        .replace(Regex("[ \\t\\r\\f]+"), " ")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

internal fun WebDavDriveHook.downloadOnlineBytes(source: OnlineSourceEntry, requestUrl: String): OnlineBinaryPayload {
    return withOnlineCleartextAllowed(requestUrl) {
        fun execute(authRetried: Boolean): OnlineBinaryPayload {
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
            try {
                val code = connection.responseCode
                if (code in 200..299) {
                    return OnlineBinaryPayload(
                        bytes = connection.inputStream.use { it.readBytes() },
                        mimeType = connection.contentType?.substringBefore(';')?.trim().orEmpty(),
                        url = connection.url.toString(),
                    )
                }
                if ((code == 401 || code == 403) && !authRetried && source.hasLoginConfig) {
                    val login = OnlineSourceAuth.loginWithSavedCredentials(currentApplicationContext() ?: currentContext(), source)
                    logWebDav(
                        "online completion binary auth retry source=${source.name} code=$code " +
                            "success=${login.success} message=${login.message}",
                    )
                    if (login.success) return execute(authRetried = true)
                }
                val authHint = if (code == 401 || code == 403) "：未登录或会员权限不足" else ""
                error("HTTP $code$authHint")
            } finally {
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
): Boolean {
    val root = bookDir.canonicalFile
    writeOnlineCompletionDefaultStyle(root)
    var changed = false
    val decor = onlineCompletionBookDirDecor(root)
    val volumeFirstChapters = onlineVolumeSegments(
        remoteChapters.map { OnlineDownloadedChapter(title = it.title, content = "", volumeTitle = it.volumeTitle) },
    ).mapTo(hashSetOf()) { it.startIndex }
    newChapters.forEach { (remoteIndex, chapter) ->
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
    if (writeOnlineCompletionVolumePages(root, tocChapters, decor)) changed = true
    val tocFile = File(root, "OEBPS/toc.ncx")
    val nextToc = onlineTocNcx(target, tocChapters, chapterHrefs)
    if (!tocFile.isFile || tocFile.readText(Charsets.UTF_8) != nextToc) {
        tocFile.writeText(nextToc, Charsets.UTF_8)
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
        writeOnlineCompletionTextAtomically(opfFile, nextOpf)
        changed = true
    }
    synchronizeOnlineImageManifest(root)
    if (writeOnlineCompletionChapterIndex(root, target, remoteChapters, chapterHrefs)) changed = true
    return changed
}

internal fun WebDavDriveHook.onlineCompletionPendingChapter(chapter: OnlineChapter, index: Int): OnlineDownloadedChapter =
    OnlineDownloadedChapter(
        title = chapter.title.ifBlank { "第 ${index + 1} 章" },
        content = "本章正在后台下载中。\n\n退出本书后重新进入，可刷新已经下载完成的正文。",
        volumeTitle = chapter.volumeTitle,
        level = chapter.level,
        sourceUrl = chapter.url,
    )

internal fun WebDavDriveHook.onlineCompletionOnDemandPendingChapter(
    chapter: OnlineChapter,
    index: Int,
): OnlineDownloadedChapter = OnlineDownloadedChapter(
    title = chapter.title.ifBlank { "第 ${index + 1} 章" },
    content = "本章尚未下载。\n\n打开本章时将自动下载正文，请保持网络连接。",
    volumeTitle = chapter.volumeTitle,
    level = chapter.level,
    sourceUrl = chapter.url,
)

internal fun WebDavDriveHook.downloadOnlineCompletionOnDemandChapter(
    request: OnlineOnDemandChapterRequest,
) {
    val context = currentContext() ?: error("按需下载缺少 Context")
    val source = OnlineSourceStore.list(context)
        .firstOrNull { it.id == request.metadata.sourceId }
        ?.let { OnlineSourceDownloadPolicyStore.attach(context, it) }
        ?: error("在线书源已不存在：${request.metadata.sourceId}")
    val metadata = OnlineOnDemandMetadataStore.read(request.bookDir)
        ?: error("按需章节元数据不存在")
    val current = metadata.chapter(request.chapterIndex)
        ?: error("按需章节索引越界：${request.chapterIndex}")
    if (current.state == OnlineChapterState.READY) return
    val downloadingMetadata = OnlineOnDemandMetadataStore.update(request.bookDir) { latest ->
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
            requestOnlineSearch(source, request.metadata.detailUrl)
        }
        downloadOnlineChapter(
            target = target,
            detailUrl = detail.url,
            detailBody = detail.body,
            chapter = chapter,
            index = request.chapterIndex,
        )
    }
    result.onSuccess { content ->
        writeOnlineCompletionChapterToBookDir(
            bookDir = request.bookDir,
            index = request.chapterIndex,
            chapter = content,
            href = current.href,
            target = target,
        )
        OnlineOnDemandMetadataStore.update(request.bookDir) { latest ->
            latest.updateChapter(request.chapterIndex) {
                it.markReady(System.currentTimeMillis())
            }
        }
        logWebDav(
            "on-demand chapter ready book=${request.metadata.bookName} " +
                "index=${request.chapterIndex + 1} href=${current.href}",
        )
    }.onFailure { error ->
        OnlineOnDemandMetadataStore.update(request.bookDir) { latest ->
            latest.updateChapter(request.chapterIndex) {
                it.markFailed(error.message.orEmpty().ifBlank { error.javaClass.name })
            }
        }
        throw error
    }
}

internal fun WebDavDriveHook.writeOnlineCompletionChapterToImportedBookIfReady(
    task: OnlineCompletionDownloadTask,
    target: OnlineDownloadTarget,
    index: Int,
    chapter: OnlineDownloadedChapter,
) {
    val bookDir = task.importedBookDir ?: return
    task.bookDirWriteLock.lock()
    try {
        throwIfOnlineCompletionDownloadCancelled(task)
        writeOnlineCompletionChapterToBookDir(bookDir, index, chapter, target = target)
    } finally {
        task.bookDirWriteLock.unlock()
    }
}

internal fun WebDavDriveHook.flushOnlineCompletionDownloadedChapters(
    task: OnlineCompletionDownloadTask,
    target: OnlineDownloadTarget,
) {
    val bookDir = task.importedBookDir ?: return
    val chapters = task.downloadedChapters.entries
        .sortedBy { it.key }
        .map { it.key to it.value }
    if (chapters.isEmpty()) return
    task.bookDirWriteLock.lock()
    try {
        throwIfOnlineCompletionDownloadCancelled(task)
        chapters.forEach { (index, chapter) ->
            writeOnlineCompletionChapterToBookDir(bookDir, index, chapter, target = target)
        }
        logWebDav(
            "online completion flushed downloaded chapters to imported dir=" +
                "${bookDir.absolutePath} count=${chapters.size}",
        )
    } finally {
        task.bookDirWriteLock.unlock()
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
) {
    val root = bookDir.canonicalFile
    val textDir = File(root, "OEBPS/Text").canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    if (!textDir.path.startsWith(rootPrefix)) {
        error("EPUB Text directory escapes book dir")
    }
    textDir.mkdirs()
    writeOnlineCompletionDefaultStyle(root)
    val relativeHref = href.trim().removePrefix("OEBPS/")
        .takeIf { it.startsWith("Text/") && it.endsWith(".xhtml", ignoreCase = true) }
        ?: "Text/chapter_${(index + 1).toString().padStart(4, '0')}.xhtml"
    val file = File(root, "OEBPS/$relativeHref").canonicalFile
    if (!file.path.startsWith(rootPrefix)) error("EPUB chapter path escapes book dir")
    val imageHrefs = if (target != null) {
        localizeOnlineChapterImages(root, target, chapter)
    } else {
        existingOnlineChapterImageHrefs(root, chapter)
    }
    writeOnlineCompletionTextAtomically(
        file,
        chapterXhtml(
            title = chapter.title,
            content = chapter.content,
            imageHrefs = imageHrefs,
            decor = decor ?: onlineCompletionBookDirDecor(root),
            isVolumeFirstChapter = isVolumeFirstChapter,
        ),
    )
    if (index == 0 || (index + 1) % 20 == 0) {
        logWebDav("online completion chapter file updated ${index + 1} path=${file.absolutePath}")
    }
}

internal fun WebDavDriveHook.writeOnlineCompletionTextAtomically(target: File, content: String) {
    writeOnlineCompletionBytesAtomically(target, content.toByteArray(Charsets.UTF_8))
}

internal fun WebDavDriveHook.writeOnlineCompletionBytesAtomically(target: File, content: ByteArray) {
    val parent = target.parentFile ?: error("EPUB target directory missing: ${target.name}")
    parent.mkdirs()
    val temp = File(
        parent,
        ".${target.name}.tmp-${Thread.currentThread().id}-${System.nanoTime()}",
    )
    temp.writeBytes(content)
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: Exception) {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal fun WebDavDriveHook.onlineCompletionChapterIndexFile(bookDir: File): File =
    File(bookDir, "OEBPS/$ONLINE_COMPLETION_CHAPTER_INDEX")

internal fun WebDavDriveHook.readOnlineCompletionChapterIndex(bookDir: File): List<StoredOnlineChapter> =
    runCatching {
        val file = onlineCompletionChapterIndexFile(bookDir)
        if (!file.isFile) return emptyList()
        val items = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("chapters") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val href = item.optString("href").trim()
                if (href.isBlank()) continue
                add(
                    StoredOnlineChapter(
                        sourceChapterId = item.optString("sourceChapterId"),
                        title = item.optString("title"),
                        volumeTitle = item.optString("volumeTitle"),
                        href = href,
                    ),
                )
            }
        }
    }.onFailure {
        logWebDav("online completion chapter index read failed: ${it.message ?: it.javaClass.name}")
    }.getOrDefault(emptyList())

internal fun WebDavDriveHook.readLegacyOnlineCompletionChapters(bookDir: File): List<StoredOnlineChapter> =
    runCatching {
        val tocFile = File(bookDir, "OEBPS/toc.ncx")
        if (!tocFile.isFile) return emptyList()
        OnlineChapterUpdatePlanner.parseLegacyNcx(tocFile.readText(Charsets.UTF_8))
    }.onFailure {
        logWebDav("online completion legacy toc read failed: ${it.message ?: it.javaClass.name}")
    }.getOrDefault(emptyList())

internal fun WebDavDriveHook.onlineCompletionChapterIndexJson(
    target: OnlineDownloadTarget,
    chapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
): String {
    val items = JSONArray()
    chapters.forEachIndexed { index, chapter ->
        items.put(
            JSONObject()
                .put("sourceChapterId", OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url))
                .put("url", chapter.url)
                .put("title", chapter.title)
                .put("volumeTitle", chapter.volumeTitle)
                .put("href", chapterHrefs.getOrNull(index).orEmpty()),
        )
    }
    return JSONObject()
        .put("version", 1)
        .put("sourceId", target.source.id)
        .put("detailUrl", target.result.detailUrl)
        .put("chapters", items)
        .toString(2)
}

internal fun WebDavDriveHook.writeOnlineCompletionChapterIndex(
    bookDir: File,
    target: OnlineDownloadTarget,
    chapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
): Boolean {
    val file = onlineCompletionChapterIndexFile(bookDir)
    val content = onlineCompletionChapterIndexJson(target, chapters, chapterHrefs)
    if (file.isFile && file.readText(Charsets.UTF_8) == content) return false
    file.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
    return true
}

internal fun WebDavDriveHook.readOnlineCompletionFailedChapters(bookDir: File): List<OnlineFailedChapter> =
    runCatching {
        val file = onlineCompletionFailedLogFile(bookDir)
        if (!file.isFile) return emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val chapters = root.optJSONArray("chapters") ?: return emptyList()
        buildList {
            for (i in 0 until chapters.length()) {
                val item = chapters.optJSONObject(i) ?: continue
                val index = if (item.has("index")) {
                    item.optInt("index", -1)
                } else {
                    item.optInt("chapterNumber", 0) - 1
                }
                if (index < 0) continue
                add(
                    OnlineFailedChapter(
                        index = index,
                        title = item.optString("title").ifBlank { "第 ${index + 1} 章" },
                        url = item.optString("url"),
                        volumeTitle = item.optString("volumeTitle"),
                        level = item.optInt("level", 0),
                        attempts = item.optInt("attempts", 0),
                        message = item.optString("message"),
                        updatedAt = item.optLong("updatedAt", 0L),
                        sourceId = item.optString("sourceId"),
                        detailUrl = item.optString("detailUrl"),
                        href = item.optString("href"),
                    ),
                )
            }
        }.distinctBy { it.index }.sortedBy { it.index }
    }.onFailure {
        logWebDav("online completion failed chapter log read failed: ${it.message ?: it.javaClass.name}")
    }.getOrDefault(emptyList())

internal fun WebDavDriveHook.writeOnlineCompletionFailedChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
) {
    val file = onlineCompletionFailedLogFile(bookDir)
    if (failures.isEmpty()) {
        if (file.exists()) file.delete()
        return
    }
    file.parentFile?.mkdirs()
    file.writeText(onlineCompletionFailedChaptersJson(target, failures), Charsets.UTF_8)
    logWebDav(
        "online completion failed chapter log written book=${target.result.name} " +
            "count=${failures.size} path=${file.absolutePath}",
    )
}

internal fun WebDavDriveHook.mergeOnlineCompletionFailedChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
) {
    val merged = (readOnlineCompletionFailedChapters(bookDir) + failures)
        .associateBy { it.index }
        .values
        .sortedBy { it.index }
    writeOnlineCompletionFailedChapters(bookDir, target, merged)
}

internal fun WebDavDriveHook.onlineCompletionFailedChaptersJson(
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
): String {
    val chapters = JSONArray()
    failures.sortedBy { it.index }.forEach { failure ->
        chapters.put(
            JSONObject()
                .put("index", failure.index)
                .put("chapterNumber", failure.index + 1)
                .put("title", failure.title)
                .put("url", failure.url)
                .put("volumeTitle", failure.volumeTitle)
                .put("level", failure.level)
                .put("attempts", failure.attempts)
                .put("message", failure.message)
                .put("updatedAt", failure.updatedAt)
                .put("sourceId", failure.sourceId.ifBlank { target.source.id })
                .put("detailUrl", failure.detailUrl.ifBlank { target.result.detailUrl })
                .put("href", failure.href),
        )
    }
    return JSONObject()
        .put("version", 1)
        .put("bookName", target.result.name)
        .put("sourceId", target.source.id)
        .put("sourceName", target.source.name)
        .put("detailUrl", target.result.detailUrl)
        .put("updatedAt", System.currentTimeMillis())
        .put("chapters", chapters)
        .toString(2)
}

internal fun WebDavDriveHook.importOnlineCompletionBook(
    file: File,
    target: OnlineDownloadTarget,
    onWaiting: (() -> Unit)? = null,
    onStarted: (() -> Unit)? = null,
): OnlineCompletionImportResult {
    var locked = onlineCompletionImportLock.tryLock()
    if (!locked) {
        logWebDav("online completion import queued book=${target.result.name} file=${file.name}")
        onWaiting?.invoke()
        onlineCompletionImportLock.lock()
        locked = true
    }
    return try {
        onStarted?.invoke()
        importOnlineCompletionBookLocked(file, target)
    } finally {
        if (locked) onlineCompletionImportLock.unlock()
    }
}

internal fun WebDavDriveHook.importOnlineCompletionBookLocked(file: File, target: OnlineDownloadTarget): OnlineCompletionImportResult {
    val bookshelf = awaitBookshelfRepository()
        ?: error("阅微导入服务暂不可用，请先打开书架后重试")
    val sourceUrl = target.result.detailUrl.ifBlank { target.source.sourceUrl }
    val importUuid = onlineBookUuid(target)
    val importTitle = target.result.name
    OnlineCompletionImportSignal.remember(importUuid, importTitle, sourceUrl)
    return try {
        val unzipDirFile = unzipOnlineCompletionEpub(file)
        val unzipDir = okioPath(unzipDirFile)
        val booksDir = currentOnlineCompletionBooksDir(bookshelf)
        val imported = importOnlineCompletionEpubDirectory(unzipDir, booksDir)
        val bookDir = imported.first
        val opf = imported.second
        val platformFile = platformFile(file)
        rememberPendingWebDavImport(
            platformFile = platformFile,
            localFile = file,
            sourceUrl = sourceUrl,
            sourceSize = file.length(),
        )
        logWebDav(
            "online completion epub imported dir=$bookDir " +
                "title=${callStringPath(opf, "metadata", "title", "value")} " +
                "uuid=${callStringPath(opf, "metadata", "uuid", "value")}",
        )
        val importMethod = findImportBookMethod(bookshelf)
        val result = invokeSuspendBlocking(
            importMethod,
            bookshelf,
            *importBookArgs(
                importMethod,
                platformFile,
                bookDir,
                opf,
                sourceUrl,
                java.lang.Long.valueOf(file.length()),
            ),
        )
        val importedBook = syncOnlineCompletionImportedBookMetadata(
            bookshelf = bookshelf,
            target = target,
            sourceUrl = sourceUrl,
        )
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
            ?: findOnlineCompletionImportedBook(bookshelf, target, sourceUrl)
            ?: error("阅微导入后未找到书架记录：${target.result.name}")
        OnlineCompletionImportResult(
            imported = true,
            bookDir = runCatching { bookDirectory(visibleBook).canonicalFile }
                .getOrElse { File(bookDir.toString()).canonicalFile },
        )
    } finally {
        OnlineCompletionImportSignal.forget(importUuid, importTitle, sourceUrl)
    }
}

internal fun WebDavDriveHook.syncOnlineCompletionImportedBookMetadata(
    bookshelf: Any,
    target: OnlineDownloadTarget,
    sourceUrl: String,
): Any? =
    runCatching {
        val imported = findOnlineCompletionImportedBook(bookshelf, target, sourceUrl) ?: return null
        val bookDir = runCatching { bookDirectory(imported).canonicalFile }.getOrNull()
        val size = bookDir?.takeIf { it.isDirectory }?.let { bookDirectorySize(it) }
        val updated = copyBookWithBackupAndPublisher(
            book = imported,
            backupType = BACKUP_TYPE_ONLINE_COMPLETION,
            backupId = onlineImportedBookBackupId(target),
            backupCode = target.source.id,
            publisher = imported.callString("getPublisher"),
            cover = imported.callString("getCover").ifBlank { target.result.coverUrl },
            size = size,
            updated = System.currentTimeMillis(),
        )
        updateLocalBook(updated)
        logWebDav(
            "online completion metadata synced uuid=${updated.callString("getUuid")} " +
                "backupId=${bookBackupIdOf(updated)} source=${target.source.name} " +
                "size=${updated.callLong("getSize")} updated=${updated.callLong("getUpdated")} " +
                "cover=${updated.callString("getCover").take(120)}",
        )
        updated
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX online completion metadata sync failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun WebDavDriveHook.findOnlineCompletionImportedBook(
    bookshelf: Any,
    target: OnlineDownloadTarget,
    sourceUrl: String,
): Any? =
    findLocalBookByUrl(bookshelf, sourceUrl)
        ?: findLocalBookByUrl(bookshelf, target.result.detailUrl)

internal fun WebDavDriveHook.syncOnlineCompletionImportedBookSize(target: OnlineDownloadTarget) {
    runCatching {
        val bookshelf = currentBookshelfRepository() ?: return
        val sourceUrl = target.result.detailUrl.ifBlank { target.source.sourceUrl }
        val imported = findLocalBookByUrl(bookshelf, sourceUrl)
            ?: findLocalBookByUrl(bookshelf, target.result.detailUrl)
            ?: return
        val bookDir = bookDirectory(imported).canonicalFile
        val size = bookDirectorySize(bookDir)
        val updated = copyBookWithBackupAndPublisher(
            book = imported,
            backupType = BACKUP_TYPE_ONLINE_COMPLETION,
            backupId = bookBackupIdOf(imported).ifBlank { onlineImportedBookBackupId(target) },
            backupCode = imported.callString("getBackupCode").ifBlank { target.source.id },
            publisher = imported.callString("getPublisher"),
            cover = imported.callString("getCover").ifBlank { target.result.coverUrl },
            size = size,
            updated = System.currentTimeMillis(),
        )
        updateLocalBook(updated)
        logWebDav(
            "online completion imported book size synced uuid=${updated.callString("getUuid")} size=$size",
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX online completion size sync failed: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.importOnlineCompletionEpubDirectory(unzipDir: Any, booksDir: Any): Pair<Any, Any> {
    val managerClass = cls(EPUB_FILE_MANAGER_CLASS)
    val manager = runCatching {
        managerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    }.getOrNull() ?: error("阅微 EpubFileManager INSTANCE 未找到")
    // 2.2.0：import(Path, Path):Pair；2.3.0 起 import(Path, Path, Function1):Pair（新增进度回调）。
    // 按方法名 + 前两参 okio.Path + 返回 Pair 匹配，取参数最少者，兼容新旧签名。
    val importMethod = (managerClass.methods.asSequence() + managerClass.declaredMethods.asSequence())
        .filter { candidate ->
            candidate.name == EPUB_IMPORT_METHOD &&
                candidate.returnType.name == KOTLIN_PAIR_CLASS &&
                candidate.parameterTypes.size >= 2 &&
                candidate.parameterTypes[0].name == OKIO_PATH_CLASS &&
                candidate.parameterTypes[1].name == OKIO_PATH_CLASS
        }
        .minByOrNull { it.parameterTypes.size }
        ?.apply { isAccessible = true }
        ?: error("阅微 EpubFileManager.import 方法未找到")
    // 2.3.0 的进度回调参数可空（宿主内部对其做了空判断），多余参数一律补 null。
    val importArgs = arrayListOf<Any?>(unzipDir, booksDir)
    while (importArgs.size < importMethod.parameterTypes.size) importArgs.add(null)
    val pair = importMethod.invoke(manager, *importArgs.toTypedArray())
        ?: error("阅微 EpubFileManager.import 返回空")
    val first = pair.javaClass.methods.first { it.name == "getFirst" && it.parameterTypes.isEmpty() }
        .apply { isAccessible = true }
        .invoke(pair)
        ?: error("阅微 EpubFileManager.import 未返回 bookDir")
    val second = pair.javaClass.methods.first { it.name == "getSecond" && it.parameterTypes.isEmpty() }
        .apply { isAccessible = true }
        .invoke(pair)
        ?: error("阅微 EpubFileManager.import 未返回 OPF")
    return first to second
}

internal fun WebDavDriveHook.unzipOnlineCompletionEpub(file: File): File {
    val outputDir = File(
        file.parentFile ?: file.absoluteFile.parentFile ?: File("/data/local/tmp"),
        "${file.nameWithoutExtension}_unzipped",
    )
    if (outputDir.exists()) outputDir.deleteRecursively()
    outputDir.mkdirs()
    val root = outputDir.canonicalFile
    ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            try {
                val target = File(root, entry.name).canonicalFile
                val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
                if (target != root && !target.path.startsWith(rootPrefix)) {
                    error("EPUB entry escapes output dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out -> zip.copyTo(out) }
                }
            } finally {
                zip.closeEntry()
            }
        }
    }
    logWebDav("online completion epub unzipped dir=${outputDir.absolutePath}")
    return outputDir
}

internal fun WebDavDriveHook.okioPath(file: File): Any =
    cls(OKIO_PATH_CLASS).getDeclaredMethod("get", File::class.java)
        .apply { isAccessible = true }
        .invoke(null, file)

internal fun WebDavDriveHook.obtainOnlineCompletionOpf(bookDir: Any): Any {
    val opfClass = cls(OPF_CLASS)
    val companion = sequenceOf("INSTANCE", "Companion")
        .mapNotNull { fieldName ->
            runCatching {
                opfClass.companionField(fieldName)?.let { field ->
                    field.isAccessible = true
                    field.get(null)
                }
            }.getOrNull()
        }
        .firstOrNull()
        ?: (opfClass.declaredFields.asSequence() + opfClass.fields.asSequence())
            .filter { Modifier.isStatic(it.modifiers) && it.type.name.contains("Opf\$Companion") }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(null)
                }.getOrNull()
            }
            .firstOrNull()
        ?: error(
            "EPUB OPF Companion 未找到: fields=" +
                opfClass.declaredFields.joinToString { "${it.name}:${it.type.name}" },
        )
    val obtain = (companion.javaClass.methods.asSequence() + companion.javaClass.declaredMethods.asSequence())
        .firstOrNull { it.name == "obtain" && it.parameterTypes.size == 1 }
        ?: error(
            "EPUB OPF obtain 方法未找到: companion=${companion.javaClass.name}, methods=" +
                companion.javaClass.declaredMethods.joinToString { "${it.name}/${it.parameterTypes.size}" },
        )
    obtain.isAccessible = true
    return obtain.invoke(companion, bookDir) ?: error("EPUB OPF 解析失败")
}

internal fun WebDavDriveHook.cancelOnlineCompletionNotification(context: Context, id: Int, key: String) {
    cancelOnlineCompletionHostNotification(context, id)
    sendOnlineCompletionCancelBroadcastToModule(context, id, key)
}

internal fun WebDavDriveHook.cancelOnlineCompletionHostNotification(context: Context, id: Int) {
    if (id <= 0) return
    runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(id)
    }.onFailure {
        logWebDav("online completion host notification cancel failed id=$id: ${it.message}")
    }
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
                android.R.drawable.ic_menu_close_clear_cancel,
                "\u53d6\u6d88",
                onlineCompletionCancelPendingIntent(context, id, key),
            )
        }
        manager.notify(id, builder.build())
        cancelOnlineCompletionNotificationIfDone(manager, id, done)
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
    val broadcastSent = sendOnlineCompletionNotificationBroadcast(context, id, key, cancellable, title, text, progress, done)
    val shouldStartActivity = shouldStartOnlineCompletionNotificationActivity(id, done, broadcastSent)
    val activitySent = if (shouldStartActivity) {
        startOnlineCompletionNotificationActivity(context, id, key, cancellable, title, text, progress, done)
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
    if (done) return !broadcastSent
    val now = System.currentTimeMillis()
    val last = onlineCompletionModuleActivityPromptAt[id] ?: 0L
    if (last > 0L && broadcastSent) return false
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
): Boolean =
    runCatching {
        val intent = onlineCompletionNotificationIntent(id, key, cancellable, title, text, progress, done).apply {
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
): Boolean =
    runCatching {
        val intent = onlineCompletionNotificationIntent(id, key, cancellable, title, text, progress, done).apply {
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
): Intent =
    Intent(ONLINE_COMPLETION_NOTIFICATION_ACTION).apply {
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

internal fun WebDavDriveHook.importCacheFile(cacheDir: File?, root: String, fileName: String): File =
    File(
        File(cacheDir ?: File("/data/local/tmp"), "${root}/${System.currentTimeMillis()}_${UUID.randomUUID()}"),
        fileName.safeWebDavFileName(),
    )

internal fun WebDavDriveHook.enqueueNativeImport(workerManager: Any, platformFile: Any): Any? {
    // 阅微 2.3.0 起 WorkerManager.enqueueImport 由 1 参 (PlatformFile) 变为
    // 2 参 (PlatformFile, boolean) 或 3 参 (PlatformFile, String, boolean)。
    // 兼容多种签名，避免旧版 .first{ size==1 } 找不到匹配抛 NoSuchElementException 导致导入无反应。
    val candidates = (workerManager.javaClass.methods.asSequence() +
        workerManager.javaClass.declaredMethods.asSequence())
        .filter { it.name == WORKER_ENQUEUE_IMPORT_METHOD }
        .distinct()
        .toList()

    // 优先旧版 1 参
    candidates.firstOrNull { it.parameterTypes.size == 1 }?.let {
        it.isAccessible = true
        return it.invoke(workerManager, platformFile)
    }
    // 2 参 (PlatformFile, boolean)：boolean 传 false
    candidates.firstOrNull {
        it.parameterTypes.size == 2 &&
            (it.parameterTypes[1] == java.lang.Boolean.TYPE || it.parameterTypes[1] == java.lang.Boolean::class.java)
    }?.let {
        it.isAccessible = true
        return it.invoke(workerManager, platformFile, false)
    }
    // 3 参 (PlatformFile, String, boolean)：书名传 null，boolean 传 false
    candidates.firstOrNull {
        it.parameterTypes.size == 3 &&
            it.parameterTypes[1] == String::class.java &&
            (it.parameterTypes[2] == java.lang.Boolean.TYPE || it.parameterTypes[2] == java.lang.Boolean::class.java)
    }?.let {
        it.isAccessible = true
        return it.invoke(workerManager, platformFile, null, false)
    }
    // 兜底：取参数最少的一个 enqueueImport，非首参用默认值填充
    val fallback = candidates.minByOrNull { it.parameterTypes.size }
        ?: error("WorkerManager.$WORKER_ENQUEUE_IMPORT_METHOD not found")
    fallback.isAccessible = true
    val args = fallback.parameterTypes.mapIndexed { index, type ->
        when {
            index == 0 -> platformFile
            type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> false
            else -> null
        }
    }.toTypedArray()
    return fallback.invoke(workerManager, *args)
}

internal fun WebDavDriveHook.findImportBookMethod(bookshelf: Any): Method =
    (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
        .filter {
            it.name == BOOKSHELF_IMPORT_BOOK_METHOD &&
                it.parameterTypes.size >= 6 &&
                it.parameterTypes.last().name == KOTLIN_CONTINUATION_CLASS
        }
        .minByOrNull { it.parameterTypes.size }
        ?.apply { isAccessible = true }
        ?: error("阅微 importBook 方法未找到")

/**
 * 按 importBook 实际参数个数组织调用实参（不含末尾 Continuation，由 invokeSuspendBlocking 追加）。
 * 顺序固定为 PlatformFile, Path, Opf, String(url), Long(size)[, Function1 进度回调]。
 * 2.3.0 起在 size 与 Continuation 之间新增进度回调，用无副作用的 Function1 代理补齐。
 */

internal fun WebDavDriveHook.importBookArgs(method: Method, file: Any?, bookDir: Any?, opf: Any?, url: Any?, size: Any?): Array<Any?> {
    val realParamCount = (method.parameterTypes.size - 1).coerceAtLeast(0)
    val args = arrayListOf(file, bookDir, opf, url, size)
    while (args.size < realParamCount) {
        args.add(functionProxy("ImportProgress", FUNCTION1_CLASS) { targetUnit() })
    }
    return args.toTypedArray()
}
