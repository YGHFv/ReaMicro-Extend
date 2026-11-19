package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.OnlineDownloadedChapter
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.online.OnlineSourceDailyLimitException
import com.reamicro.fix.online.OnlineSourceDownloadPolicyStore
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.download.*
import com.reamicro.fix.online.epub.OnlineUpdateStyleFingerprint
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.settings.OnlineEpubStyleStore
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.util.UUID

/**
 * A shelf update owns its new chapters until publication. Pause publishes completed chapters;
 * cancellation before publication never edits the original book.
 */
internal fun WebDavDriveHook.updateOnlineCompletionBookIncrementally(
    book: Any,
    target: OnlineDownloadTarget,
    skipCompleted: Boolean = false,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    control: OnlineDownloadControl? = null,
    onProgress: (Int, String) -> Unit,
): OnlineChapterUpdateResult {
    val active = control ?: OnlineDownloadControl(TaskCancellation.current()?.child() ?: TaskCancellation())
    val context = requireNotNull(currentContext())
    val original = bookDirectory(book).canonicalFile
    val expectedRoot = File(context.filesDir, "${storageIdentity.accountId}/books").canonicalFile
    check(book.callLong("getUid") == storageIdentity.accountId && original.parentFile == expectedRoot) {
        "图书目录与更新账号不一致"
    }
    val execution = storageExecutionGeneration.get()
    fun checkIdentity() {
        active.check()
        OnlineSourceStore.requireCurrent(target.source)
        check(!storageClosed && settingsProvider().moduleEnabled && execution == storageExecutionGeneration.get() &&
            AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) { "账号或模块状态已变化，已停止章节更新" }
    }
    val originalGuard = OnlineBookWriteGuard(original, isCurrent = {
        !storageClosed && settingsProvider().moduleEnabled &&
            AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider()) &&
            OnlineSourceStore.isCurrent(target.source)
    }, commitCurrent = { action -> AccountHostRuntime.withCurrentStorage(storageIdentity, action = action) })
    val snapshot = OnlineUpdateStaging(original) { checkIdentity(); originalGuard.checkCurrent() }
    check(File(original, "OEBPS/Text").isDirectory && File(original, "OEBPS/content.opf").isFile &&
        File(original, "OEBPS/toc.ncx").isFile) { "当前图书不是在线补全生成的标准 EPUB，暂不能增量更新" }

    var toc: OnlineTocSnapshot? = null
    var chapterHrefs: List<String> = emptyList()
    var newIndices = emptySet<Int>()
    var oldMetadata: OnlineOnDemandMetadata? = null
    var recoveredMetadata = false
    var recoveredFailureLog = false
    var initialFailures: List<OnlineFailedChapter> = emptyList()
    val downloaded = linkedMapOf<Int, OnlineDownloadedChapter>()
    val attempts = mutableMapOf<Int, Int>()
    val failures = linkedMapOf<Int, OnlineFailedChapter>()
    val attempted = linkedSetOf<Int>()
    var skipped = false
    var pendingIndices = emptySet<Int>()
    var paused = false
    var stageRoot: File? = null
    val cacheRoot = File(context.cacheDir, "$ONLINE_COMPLETION_CACHE_ROOT/update-${UUID.randomUUID()}")
    onlineCompletionUpdateCacheDirs[cacheRoot.name] = cacheRoot
    target.imageCache = OnlineChapterImageCache(File(cacheRoot, "images"))
    try {
        try {
            active.networkCancellation.run {
                checkIdentity()
                val loaded = loadOnlineCompletionToc(target) { progress, message ->
                    checkIdentity(); onProgress(progress.coerceAtMost(12), message)
                }
                toc = loaded
                val remote = loaded.chapters
                val plan = onlineChapterUpdatePlan(original, remote)
                chapterHrefs = plan.first
                newIndices = plan.second.toSet()
                val savedMetadata = OnlineOnDemandMetadataStore.read(original)?.recoverAfterProcessRestart()
                oldMetadata = savedMetadata?.recoverPublishedChapters { href ->
                    checkIdentity()
                    val chapterFile = onlineCompletionChapterFile(original, href)
                    chapterFile.isFile && OnlineChapterContentValidator.storedXhtmlFailureReason(
                        chapterFile.readText(Charsets.UTF_8),
                    ) == null
                }
                recoveredMetadata = oldMetadata != savedMetadata
                val pendingHrefs = oldMetadata?.chapters.orEmpty()
                    .filter { it.state == OnlineChapterState.PENDING || it.state == OnlineChapterState.DOWNLOADING }
                    .mapTo(hashSetOf()) { it.href }
                pendingIndices = chapterHrefs.indices.filterTo(hashSetOf()) { chapterHrefs[it] in pendingHrefs }
                val storedFailures = remapOnlineCompletionFailures(
                    readOnlineCompletionFailedChapters(original), remote, chapterHrefs,
                )
                val logged = storedFailures.filter { failure ->
                    checkIdentity()
                    val chapterFile = onlineCompletionChapterFile(original, chapterHrefs[failure.index])
                    !chapterFile.isFile || OnlineChapterContentValidator.storedXhtmlFailureReason(
                        chapterFile.readText(Charsets.UTF_8),
                    ) != null
                }
                recoveredFailureLog = logged.size != storedFailures.size
                val invalid = detectInvalidOnlineCompletionChapters(
                    original, target, remote, chapterHrefs,
                    logged.mapTo(hashSetOf()) { it.index } + pendingIndices,
                )
                initialFailures = (logged + invalid).distinctBy { it.index }
                failures.putAll(initialFailures.associateBy { it.index })
                val candidates = (newIndices + pendingIndices + failures.keys).sorted()
                // A completed novel may still have local chapters left by pause or a failed fetch.
                // Batch "skip completed" must not prevent those downloaded books from resuming.
                if (skipCompleted && target.result.status == "完结" && candidates.isEmpty()) {
                    skipped = true
                    return@run
                }
                val allowance = OnlineSourceDownloadPolicyStore.remainingToday(context, target.source)
                val allowed = allowance?.let { candidates.take(it.coerceAtLeast(0)) } ?: candidates
                val batch = onlineChapterBatchSession(target, remote, allowed)
                val retryable = linkedSetOf<Int>()
                var serviceFailures = 0
                var quotaReached = false
                fun attempt(index: Int, immediate: Boolean) {
                    checkIdentity()
                    if (quotaReached) return
                    val chapter = remote[index]
                    repeat(if (immediate) 2 else 1) {
                        if ((attempts[index] ?: 0) >= ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT) return
                        checkIdentity()
                        attempts[index] = (attempts[index] ?: 0) + 1
                        attempted += index
                        try {
                            val content = downloadOnlineChapter(
                                target, loaded.detail.url, loaded.detail.body, chapter, index, batch,
                            )
                            checkIdentity()
                            downloaded[index] = content
                            failures.remove(index)
                            retryable.remove(index)
                            serviceFailures = 0
                            return
                        } catch (error: Throwable) {
                            TaskCancellation.rethrowIfCancelled(error)
                            checkIdentity()
                            if (error is OnlineSourceDailyLimitException) {
                                quotaReached = true
                                return
                            }
                            val previousAttempts = initialFailures.firstOrNull { it.index == index }?.attempts ?: 0
                            failures[index] = onlineCompletionFailedChapter(
                                target, chapter, index, previousAttempts + (attempts[index] ?: 0),
                                error.message ?: error.javaClass.simpleName, chapterHrefs[index],
                            )
                            if (isPermanentOnlineChapterFailure(error)) {
                                retryable.remove(index)
                                return
                            }
                            retryable += index
                            if (onlineHttpRetryKind(error) != OnlineHttpRetryKind.NONE &&
                                (attempts[index] ?: 0) < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT
                            ) {
                                serviceFailures++
                                val delay = onlineHttpRetryDelayMs(error, serviceFailures)
                                onProgress(80, "源暂时异常，等待重试第 ${index + 1} 章")
                                sleepOnlineCompletionRetryDelay(null, delay)
                            }
                        }
                    }
                }
                allowed.forEachIndexed { offset, index ->
                    onProgress(14 + offset * 65 / allowed.size.coerceAtLeast(1),
                        "下载未完成章节 ${offset + 1}/${allowed.size}")
                    attempt(index, true)
                }
                retryOnlineCompletionChapters(
                    candidates = {
                        if (quotaReached) emptyList() else retryable.filter { (attempts[it] ?: 0) < ONLINE_COMPLETION_CHAPTER_RETRY_LIMIT }
                    },
                    beforeRound = { round, remaining ->
                        checkIdentity()
                        onProgress(82, "等待重试 ${remaining.size} 章")
                        sleepOnlineCompletionRetryDelay(null, onlineCompletionRetryDelayMs(round))
                    },
                    attempt = { index, _, _ -> attempt(index, false) },
                )
                checkIdentity()
            }
        } catch (error: Throwable) {
            if (!active.shouldPause || !active.preparePausedPublication()) throw error
            paused = true
        }
        if (active.shouldPause && !paused) {
            check(active.preparePausedPublication()) { "暂停任务已被取消" }
            paused = true
        }
        fun publish(): OnlineChapterUpdateResult = active.cancellation.run {
            checkIdentity()
            if (skipped) return@run OnlineChapterUpdateResult(0, 0, skipped = true, paused = paused)
            val loaded = toc
            if (loaded == null || chapterHrefs.size != loaded.chapters.size || paused && downloaded.isEmpty()) {
                return@run OnlineChapterUpdateResult(0, 0, paused = paused,
                    pending = (newIndices + pendingIndices).size)
            }
            val remote = loaded.chapters
            val styleFingerprint = OnlineUpdateStyleFingerprint.of(
                OnlineEpubStyleStore.read(currentApplicationContext() ?: context),
            )
            val indexFile = onlineCompletionChapterIndexFile(original)
            val unchanged = downloaded.isEmpty() && attempted.isEmpty() &&
                newIndices.isEmpty() && pendingIndices.isEmpty() && failures.isEmpty() &&
                indexFile.isFile &&
                OnlineOnDemandMetadataCodec.chapterIndexMatches(indexFile.readText(Charsets.UTF_8),
                    onlineCompletionChapterIndexJson(target, remote, chapterHrefs)) &&
                OnlineUpdateStyleFingerprint.matches(original, styleFingerprint)
            if (unchanged) {
                onProgress(96, "目录未变化，核对书架目录")
                OnlineOnDemandBridge.withIdleBook(original) {
                    OnlineOnDemandMetadataStore.resourceEdit(original, allowInterruptedDownloads = true) {
                        snapshot.requireUnchanged()
                        originalGuard.checkCurrent()
                        checkIdentity()
                        if (!active.beginPublishing()) {
                            throw java.util.concurrent.CancellationException("更新已取消或暂停")
                        }
                        if (recoveredMetadata) OnlineOnDemandMetadataStore.write(original, requireNotNull(oldMetadata),
                            commit = originalGuard::commit, checkCurrent = ::checkIdentity)
                        if (recoveredFailureLog) writeOnlineCompletionFailedChapters(original, target, failures = emptyList(),
                            commit = originalGuard::commit, checkCurrent = ::checkIdentity)
                        if (recoveredMetadata || recoveredFailureLog) markOnlineEpubChanged(
                            original.name, storageIdentity, ::checkIdentity)
                        refreshOnlineCompletionCatalogTables(book, original, storageIdentity, ::checkIdentity)
                        active.markCommitted()
                    }
                }
                return@run OnlineChapterUpdateResult(0, 0, paused = paused)
            }
            onProgress(88, if (paused) "保存已下载的 ${downloaded.size} 章" else "准备提交章节更新")
            val stage = File(cacheRoot, "book")
            stageRoot = stage
            snapshot.copyTo(stage)
            val styled = syncOnlineCompletionDefaultStyle(stage, checkCurrent = ::checkIdentity)
            val nextChapters = downloaded.toMutableMap()
            remote.indices.forEach { index ->
                checkIdentity()
                if (index !in nextChapters && !onlineCompletionChapterFile(stage, chapterHrefs[index]).isFile) {
                    nextChapters[index] = if (oldMetadata?.mode == OnlineBookDownloadMode.ON_DEMAND)
                        onlineCompletionOnDemandPendingChapter(remote[index], index)
                    else onlineCompletionPausedPendingChapter(remote[index], index)
                }
            }
            val append = {
                appendOnlineCompletionChapters(
                    stage, target, remote, chapterHrefs, nextChapters, checkCurrent = ::checkIdentity,
                    allowNetworkImages = !paused,
                )
            }
            if (paused) append() else active.networkCancellation.run(append)
            val previousByHref = oldMetadata?.chapters.orEmpty().associateBy { it.href }
            val metadata = OnlineOnDemandMetadata(
                target.source.id, target.result.detailUrl, target.result.name,
                oldMetadata?.mode ?: OnlineBookDownloadMode.FULL,
                remote.mapIndexed { index, chapter ->
                    val previous = previousByHref[chapterHrefs[index]]
                    val ready = index in downloaded
                    val failure = failures[index]
                    val state = when {
                        ready -> OnlineChapterState.READY
                        failure != null -> OnlineChapterState.FAILED
                        index in pendingIndices || index in newIndices -> OnlineChapterState.PENDING
                        else -> previous?.state ?: OnlineChapterState.READY
                    }
                    OnlineOnDemandChapter(
                        OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url), chapter.url,
                        chapter.title, chapter.volumeTitle, chapterHrefs[index], state,
                        if (ready) System.currentTimeMillis() else previous?.downloadedAtMs ?: 0L,
                        (previous?.attempts ?: 0) + (attempts[index] ?: 0), failure?.message.orEmpty(),
                    )
                },
            )
            OnlineOnDemandMetadataStore.write(stage, metadata, checkCurrent = ::checkIdentity)
            writeOnlineCompletionFailedChapters(stage, target, failures.values.toList(), checkCurrent = ::checkIdentity)
            check(styleFingerprint == OnlineUpdateStyleFingerprint.of(
                OnlineEpubStyleStore.read(currentApplicationContext() ?: context),
            )) { "下载期间 EPUB 样式已变化，请重新更新" }
            writeOnlineCompletionTextAtomically(
                File(stage, OnlineUpdateStyleFingerprint.PATH), styleFingerprint, checkCurrent = ::checkIdentity,
            )
            val latest = findLocalBookById(book.callLong("getId"), storageIdentity, checkCurrent = ::checkIdentity)
                ?.takeIf { it.callString("getUuid") == book.callString("getUuid") }
                ?: error("图书记录已变化，已停止更新")
            var publication: OnlineCompletionDirectoryPublication? = null
            OnlineOnDemandBridge.withIdleBook(original) {
                OnlineOnDemandMetadataStore.resourceEdit(original, allowInterruptedDownloads = true) {
                    // The book lock excludes module writers. Keep the O(file count) walk outside
                    // the global account lock; only the final guarded directory moves hold it.
                    snapshot.requireUnchanged()
                    originalGuard.commit {
                        checkIdentity()
                        if (!active.beginPublishing()) throw java.util.concurrent.CancellationException("更新已取消或暂停")
                        publication = publishOnlineCompletionDirectory(stage, original,
                            commit = { it() }, checkCurrent = ::checkIdentity)
                        active.markCommitted()
                    }
                }
            }
            // The directory is now committed. Late notification/DAO failures must not discard it.
            runCatching { publication?.complete() }
                .onFailure { HookRuntime.logError("在线更新已提交，旧目录清理失败", it) }
            markOnlineEpubChanged(latest.callString("getUuid"), storageIdentity, ::checkIdentity)
            runCatching {
                refreshOnlineCompletionCatalogTables(latest, original, storageIdentity, ::checkIdentity)
                updateOnlineContentMetadata(latest, target, bookDirectorySize(original), storageIdentity,
                    checkCurrent = ::checkIdentity)
            }.onFailure {
                HookRuntime.logError("在线更新内容已保存，书架目录同步待重试", it)
                runCatching { onProgress(99, "章节已保存，书架目录同步待下次更新") }
            }
            val added = downloaded.keys.count { it in newIndices || it in pendingIndices }
            val retried = downloaded.size - added
            logWebDav("online update published downloaded=${downloaded.size} added=$added retried=$retried paused=$paused")
            OnlineChapterUpdateResult(added, failures.size, retried, styled, paused = paused,
                pending = metadata.chapters.count { it.state == OnlineChapterState.PENDING })
        }
        try {
            return publish()
        } catch (error: Throwable) {
            // A pause may arrive during EPUB/image staging after the chapter loop has finished.
            if (paused || !active.shouldPause || !active.preparePausedPublication()) throw error
            paused = true
            stageRoot?.deleteRecursively()
            stageRoot = null
            return publish()
        }
    } finally {
        target.imageCache = null
        runCatching { check(!cacheRoot.exists() || cacheRoot.deleteRecursively()) { "无法删除更新缓存" } }.onFailure {
            HookRuntime.logError("在线更新临时缓存清理失败", it)
        }
        onlineCompletionUpdateCacheDirs.remove(cacheRoot.name, cacheRoot)
        if (control == null) { active.finish(); active.cancellation.cancel() }
    }
}
