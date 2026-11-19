package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread
import com.reamicro.fix.core.HostReaderWindowBindings

import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.online.download.OnlineOnDemandBridge
import com.reamicro.fix.online.download.OnlineOnDemandChapterRequest
import com.reamicro.fix.online.download.OnlineOnDemandPrefetchPlanner
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Method
import java.io.File
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.online.download.OnlineOnDemandMetadataStore

internal fun ReaderHook.logOnDemandPrefetchDebug(message: String) {
    HookRuntime.log("$LOG_PREFIX on-demand prefetch $message")
}

internal fun ReaderHook.prefetchOnlineOnDemandChapters(page: Any) {
    if (activeReaderActivity() == null) return
    val bookDir = currentEpubRoot() ?: return
    val currentIndex = onDemandPageLocation(bookDir, page)?.metadataIndex ?: return
    val spineKey = "${bookDir.absolutePath}|$currentIndex"
    if (spineKey == lastOnDemandPrefetchSpineKey) return
    lastOnDemandPrefetchSpineKey = spineKey

    OnlineOnDemandPrefetchPlanner.READING_OFFSETS
        .filter { it > 0 }
        .forEach { offset ->
            prefetchOnlineOnDemandChapter(
                bookDir = bookDir,
                anchorIndex = currentIndex,
                chapterIndex = currentIndex + offset,
                reason = "reading-progress-$offset",
            )
        }
}

internal fun ReaderHook.prefetchOnlineOnDemandNeighborhood(request: OnlineOnDemandChapterRequest) {
    OnlineOnDemandPrefetchPlanner.CATALOG_NEIGHBOR_OFFSETS.forEach { offset ->
        prefetchOnlineOnDemandChapter(
            bookDir = request.bookDir,
            anchorIndex = request.chapterIndex,
            chapterIndex = request.chapterIndex + offset,
            reason = "catalog-neighborhood-$offset",
        )
    }
}

internal fun ReaderHook.prefetchOnlineOnDemandChapter(
    bookDir: File,
    anchorIndex: Int,
    chapterIndex: Int,
    reason: String,
) {
    val generation = onDemandRefreshCoordinator.generation()
    if (activeReaderActivity() == null || !sameReaderBookRoot(currentEpubRoot(), bookDir)) return
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, chapterIndex, retryFailed = reason.contains("-retry-"))
        ?: return logOnDemandPrefetchDebug("skip:no-pending:$chapterIndex:$reason")
    val prefetchKey = "${request.bookDir.absolutePath}|${request.chapter.href}"
    var added = false
    onDemandRefreshCoordinator.inGeneration(generation) { added = onDemandPrefetchInFlight.add(prefetchKey) }
    if (!added) return
    val accepted = OnlineOnDemandBridge.request(request) { result ->
        val refreshKey = onDemandRefreshKey(result.request.bookDir, result.request.chapter.href)
        if (!onDemandRefreshCoordinator.inGeneration(generation) {
            onDemandPrefetchInFlight.remove(prefetchKey)
            if (result.isSuccess) {
                onDemandPrefetchRetryCount.remove(prefetchKey)
                onDemandRefreshPending += refreshKey
            }
        }) return@request
        if (result.isSuccess) {
            HookRuntime.log(
                "$LOG_PREFIX on-demand prefetch completed anchor=${anchorIndex + 1} " +
                    "target=${result.request.chapterIndex + 1} reason=$reason " +
                    "href=${result.request.chapter.href}",
            )
        } else {
            val error = result.error
            HookRuntime.log(
                "$LOG_PREFIX on-demand prefetch failed index=${result.request.chapterIndex + 1} " +
                    "reason=$reason: ${error?.stackTraceToString()}",
            )
            if (error?.message.orEmpty().contains("HTTP 429") && activeReaderActivity() != null) {
                scheduleOnDemandPrefetchRetry(
                    bookDir = bookDir,
                    anchorIndex = anchorIndex,
                    chapterIndex = chapterIndex,
                    reason = reason,
                    prefetchKey = prefetchKey,
                    generation = generation,
                )
            }
        }
    }
    if (accepted) {
        HookRuntime.log(
            "$LOG_PREFIX on-demand prefetch requested anchor=${anchorIndex + 1} " +
                "target=${request.chapterIndex + 1} reason=$reason href=${request.chapter.href}",
        )
    } else {
        onDemandRefreshCoordinator.inGeneration(generation) { onDemandPrefetchInFlight.remove(prefetchKey) }
    }
}

internal fun ReaderHook.scheduleOnDemandPrefetchRetry(
    bookDir: File,
    anchorIndex: Int,
    chapterIndex: Int,
    reason: String,
    prefetchKey: String,
    generation: Long,
) {
    if (activeReaderActivity() == null) return
    var retry = 0
    if (!onDemandRefreshCoordinator.inGeneration(generation) {
        retry = (onDemandPrefetchRetryCount[prefetchKey] ?: 0) + 1
        onDemandPrefetchRetryCount[prefetchKey] = retry
    }) return
    if (retry > ON_DEMAND_PREFETCH_429_RETRIES) {
        HookRuntime.log("$LOG_PREFIX on-demand prefetch abandoned after rate limit index=${chapterIndex + 1}")
        return
    }
    val delayMs = ON_DEMAND_PREFETCH_429_RETRY_MS * retry
    val presentation = onDemandRefreshCoordinator.presentation()
    onDemandHandler.postDelayed(
        {
            if (!onDemandRefreshCoordinator.isGeneration(generation) ||
                !onDemandRefreshCoordinator.isPresentation(presentation) || activeReaderActivity() == null) return@postDelayed
            prefetchOnlineOnDemandChapter(
                bookDir = bookDir,
                anchorIndex = anchorIndex,
                chapterIndex = chapterIndex,
                reason = "$reason-retry-$retry",
            )
        },
        delayMs,
    )
    HookRuntime.log(
        "$LOG_PREFIX on-demand prefetch retry scheduled index=${chapterIndex + 1} " +
            "attempt=$retry delay=${delayMs}ms",
    )
}

internal fun ReaderHook.interceptOnDemandChapterStep(
    param: HookCallback.Call,
    intent: Any,
    step: Int,
): Boolean {
    val bookDir = currentEpubRoot() ?: return false
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return false
    val viewModel = param.thisObject ?: return false
    val host = requireNotNull(com.reamicro.fix.core.HostEpubRuntimeBindings.current)
    val catalogIndex = host.int("state.catalogIndex", host.state(viewModel)) ?: 0
    val chapter = host.list("epub.chapters", epub).getOrNull(catalogIndex + step) ?: return false
    val href = host.text("chapter.href", chapter)
    val refreshKey = onDemandRefreshKey(bookDir, href)
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, href, retryFailed = true)
        ?: if (refreshKey in onDemandRefreshPending) {
            OnlineOnDemandBridge.readyRequest(bookDir, href)
        } else {
            null
        }
        ?: return false
    HookRuntime.log(
        "$LOG_PREFIX on-demand chapter-step pending step=$step catalog=$catalogIndex " +
            "target=${request.chapterIndex + 1} href=$href",
    )
    return interceptOnDemandRequest(
        param = param,
        intent = intent,
        request = request,
        targetCfi = host.chapterCfi(chapter),
        replayLabel = if (step > 0) "OnDemandJumpNextChapter" else "OnDemandJumpPreviousChapter",
    )
}

internal fun ReaderHook.interceptOnDemandJump(param: HookCallback.Call, intent: Any): Boolean {
    val bookDir = currentEpubRoot() ?: return false
    val lifecycle = requireNotNull(com.reamicro.fix.core.HostReaderLifecycleBindings.current)
    val host = requireNotNull(com.reamicro.fix.core.HostEpubRuntimeBindings.current)
    val chapter = lifecycle.field("JumpChapter.chapter").get(intent) ?: return false
    val href = host.text("chapter.href", chapter)
    val refreshKey = onDemandRefreshKey(bookDir, href)
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, href, retryFailed = true)
        ?: if (refreshKey in onDemandRefreshPending) {
            OnlineOnDemandBridge.readyRequest(bookDir, href)
        } else {
            null
        }
        ?: return false
    HookRuntime.log(
        "$LOG_PREFIX on-demand catalog jump pending " +
            "index=${lifecycle.field("JumpChapter.index").getInt(intent)} href=$href " +
            "spine=${request.chapterIndex} target=${request.chapterIndex + 1}",
    )
    return interceptOnDemandRequest(
        param = param,
        intent = intent,
        request = request,
        targetCfi = host.chapterCfi(chapter),
        replayLabel = "OnDemandJumpChapter",
    )
}

internal fun ReaderHook.interceptOnDemandRequest(
    param: HookCallback.Call,
    intent: Any,
    request: OnlineOnDemandChapterRequest,
    targetCfi: Any?,
    replayLabel: String,
): Boolean {
    val viewModel = param.thisObject ?: return false
    val session = captureRefreshSession(viewModel, request.bookDir) ?: return false

    onDemandRefreshCoordinator.cancelActiveIf { true }
    onDemandJumpLease?.let { onDemandRefreshCoordinator.release(it) }
    val lease = onDemandRefreshCoordinator.claim("jump:${onDemandRefreshKey(request.bookDir, request.chapter.href)}")
        ?: return false
    onDemandJumpLease = lease
    val accepted = OnlineOnDemandBridge.request(request) { result ->
        if (result.isSuccess) markOnDemandDownloadReady(session.generation, result.request)
        if (!isRefreshSessionCurrent(session)) {
            onDemandRefreshCoordinator.release(lease)
            return@request
        }
        val posted = onDemandHandler.post {
            if (!isRefreshSessionCurrent(session) || !onDemandRefreshCoordinator.isCurrent(lease)) {
                onDemandRefreshCoordinator.release(lease)
                return@post
            }
            if (result.isSuccess) {
                if (replayLabel == "OnDemandJumpChapter") prefetchOnlineOnDemandNeighborhood(result.request)
                replayOnDemandJumpWhenSpineCacheReady(session, lease, result.request, targetCfi, intent, replayLabel)
            } else {
                onDemandRefreshCoordinator.release(lease)
                HookRuntime.log("$LOG_PREFIX on-demand chapter failed: ${result.error?.stackTraceToString()}")
                activityProvider()?.let { Toast.makeText(it, "章节下载失败：${result.error?.message.orEmpty()}", Toast.LENGTH_LONG).show() }
            }
        }
        if (!posted) onDemandRefreshCoordinator.release(lease)
    }
    if (!accepted) { onDemandRefreshCoordinator.release(lease); return false }
    param.result = null
    activityProvider()?.let { activity -> activity.runOnModuleUiThread {
        if (isRefreshSessionCurrent(session)) Toast.makeText(activity, "正在下载：${request.chapter.title}", Toast.LENGTH_SHORT).show()
    } }
    return true
}

internal fun ReaderHook.replayOnDemandJumpWhenSpineCacheReady(
    session: ReaderRefreshSession,
    lease: ReaderRefreshCoordinator.Lease,
    request: OnlineOnDemandChapterRequest,
    targetCfi: Any?,
    intent: Any,
    replayLabel: String,
) {
    onDemandRefreshCoordinator.submit(lease) { active ->
        val current = { active() && isRefreshSessionCurrent(session) }
        try {
            requireRefreshCurrent(current)
            val rebuiltPage = targetCfi?.let { cfi ->
                val table = onDemandSpineTable(session.epub) ?: error("宿主章节列表尚未就绪")
                val spine = OnlineOnDemandPrefetchPlanner.hostSpinePositionFromCfi(cfi.toString(), table.positions)
                    ?: error("目录 CFI 不在宿主章节列表中，未重排")
                check(table.hrefs.getOrNull(spine) == normalizeOnDemandHref(request.chapter.href)) {
                    "目录章节与下载章节不一致，未重排"
                }
                rebuildOnDemandVirtualWindow(session, cfi, spine, current)
            }
            refreshOnMain(session, current) {
                try {
                    replayingOnDemandJump.set(true)
                    dispatchReaderIntent(lastCatalogContext?.intentReceiver, session.viewModel, intent, replayLabel)
                    onDemandRefreshPending.remove(onDemandRefreshKey(request.bookDir, request.chapter.href))
                    HookRuntime.log("$LOG_PREFIX on-demand virtual window rebuilt index=${request.chapterIndex + 1} page=${rebuiltPage ?: -1}")
                } finally { replayingOnDemandJump.remove() }
                targetUnit()
            }
        } catch (error: Throwable) {
            if (!current() || error is InterruptedException || error is java.util.concurrent.CancellationException) {
                HookRuntime.log("$LOG_PREFIX on-demand jump cancelled: session/target changed")
            } else {
                HookRuntime.logError("$LOG_PREFIX on-demand jump refresh failed", error)
                onDemandHandler.post {
                    if (isRefreshSessionCurrent(session) && onDemandJumpLease === lease) activityProvider()?.let {
                        Toast.makeText(it, "章节已下载，但分页未就绪，请重新点击目录", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

internal fun ReaderHook.onDemandRefreshKey(bookDir: File, href: String): String =
    "${bookDir.canonicalPath}|${href.trim().substringBefore('#').replace('\\', '/')}"

private fun ReaderHook.markOnDemandDownloadReady(generation: Long, request: OnlineOnDemandChapterRequest) {
    val key = onDemandRefreshKey(request.bookDir, request.chapter.href)
    onDemandRefreshCoordinator.inGeneration(generation) { onDemandRefreshPending += key }
}

internal fun ReaderHook.scheduleOnDemandVisiblePageRefresh(viewModel: Any, page: Any) {
    if (activeReaderActivity() == null) return
    if (Looper.myLooper() != Looper.getMainLooper()) {
        val generation = onDemandRefreshCoordinator.generation()
        val presentation = onDemandRefreshCoordinator.presentation()
        onDemandHandler.post {
            if (onDemandRefreshCoordinator.isGeneration(generation) &&
                onDemandRefreshCoordinator.isPresentation(presentation)) scheduleOnDemandVisiblePageRefresh(viewModel, page)
        }
        return
    }
    val root = currentEpubRoot() ?: return
    val session = captureRefreshSession(viewModel, root) ?: return
    val metadata = runCatching { OnlineOnDemandMetadataStore.read(root) }.getOrNull() ?: return
    val location = onDemandPageLocation(root, page, metadata) ?: return
    val chapter = metadata.chapter(location.metadataIndex) ?: return
    val refreshKey = onDemandRefreshKey(root, chapter.href)
    if (onDemandVisibleRefreshKey != refreshKey) {
        onDemandVisibleRefreshKey = refreshKey
        onDemandVisibleBusyRetries = 0
        onDemandRefreshCoordinator.cancelActiveIf { it.key.startsWith("visible:") && it.key != "visible:$refreshKey" }
    }
    val nativeSlot = searchPageBridge?.settledSnapshot(viewModel)?.index
    if (nativeSlot != null && onDemandVisibleSlot != nativeSlot) {
        onDemandVisibleSlot = nativeSlot
        onDemandVisibleBusyRetries = 0
        onDemandRefreshCoordinator.cancelActiveIf { it.key.startsWith("visible:") }
    }
    if (selectionEditSaving.get() || selectionPagerTarget != null || onDemandVisibleBusyRetries >= 3) return
    if (onDemandJumpLease?.let(onDemandRefreshCoordinator::isCurrent) == true) return
    val pending = OnlineOnDemandBridge.pendingRequest(root, chapter.href)
    val request = pending ?: if (refreshKey in onDemandRefreshPending) OnlineOnDemandBridge.readyRequest(root, chapter.href) else null
    request ?: return
    val lease = onDemandRefreshCoordinator.claim("visible:$refreshKey") ?: return
    if (pending == null) {
        refreshOnDemandVisiblePageWhenSpineCacheReady(session, lease, request, refreshKey)
        return
    }
    val accepted = OnlineOnDemandBridge.request(request) { result ->
        if (result.isSuccess) markOnDemandDownloadReady(session.generation, result.request)
        if (!isRefreshSessionCurrent(session)) {
            onDemandRefreshCoordinator.release(lease)
            return@request
        }
        val posted = onDemandHandler.post {
            if (!isRefreshSessionCurrent(session) || !onDemandRefreshCoordinator.isCurrent(lease)) {
                onDemandRefreshCoordinator.release(lease)
                return@post
            }
            if (result.isSuccess) {
                HookRuntime.log("$LOG_PREFIX on-demand visible chapter downloaded index=${request.chapterIndex + 1}")
                refreshOnDemandVisiblePageWhenSpineCacheReady(session, lease, result.request, refreshKey)
            } else {
                onDemandRefreshCoordinator.release(lease)
                HookRuntime.logError("$LOG_PREFIX on-demand visible chapter failed", result.error)
            }
        }
        if (!posted) onDemandRefreshCoordinator.release(lease)
    }
    if (!accepted) onDemandRefreshCoordinator.release(lease)
    else HookRuntime.log("$LOG_PREFIX on-demand visible chapter requested index=${location.metadataIndex + 1} spine=${location.hostSpineIndex}")
}

internal fun ReaderHook.refreshOnDemandVisiblePageWhenSpineCacheReady(
    session: ReaderRefreshSession,
    lease: ReaderRefreshCoordinator.Lease,
    request: OnlineOnDemandChapterRequest,
    refreshKey: String,
) {
    val visibleSlot = onDemandVisibleSlot
    fun isVisibleSession() = isRefreshSessionCurrent(session) && onDemandVisibleRefreshKey == refreshKey &&
        (visibleSlot == null || onDemandVisibleSlot == visibleSlot) &&
        !selectionEditSaving.get() && selectionPagerTarget == null &&
        onDemandJumpLease?.let(onDemandRefreshCoordinator::isCurrent) != true
    if (!isVisibleSession()) { onDemandRefreshCoordinator.release(lease); return }
    onDemandRefreshCoordinator.submit(lease) { active ->
        val current = { active() && isVisibleSession() }
        try {

            val anchorPage = refreshOnMain(session, current) {
                searchPageBridge?.settledPage(session.viewModel) ?: currentReaderPage()
            } ?: return@submit
            val location = onDemandPageLocation(session.root, anchorPage) ?: return@submit
            if (location.metadataIndex != request.chapterIndex) return@submit
            val cfi = com.reamicro.fix.core.HostEpubRuntimeBindings.current?.value("page.start", anchorPage) ?: return@submit
            val page = rebuildOnDemandVirtualWindow(session, cfi, location.hostSpineIndex, current, anchorPage)
            refreshOnMain(session, current) {

                check(isOnDemandSpineLoaded(session.viewModel, location.hostSpineIndex)) { "Rebuilt spine not loaded" }
                val delivered = emitOnDemandPagerCorrection(session.viewModel, page)
                onDemandRefreshPending.remove(refreshKey)
                onDemandVisibleBusyRetries = 0
                HookRuntime.log("$LOG_PREFIX on-demand visible page refreshed index=${request.chapterIndex + 1} page=$page correction=$delivered")
                targetUnit()
            }
        } catch (error: Throwable) {
            when {
                !current() || error is InterruptedException || error is java.util.concurrent.CancellationException ->
                    HookRuntime.log("$LOG_PREFIX on-demand visible refresh cancelled: session/viewport changed")
                error is ReaderRefreshLockBusy -> onDemandHandler.post {
                    if (isVisibleSession()) {
                        onDemandVisibleBusyRetries++
                        HookRuntime.log("$LOG_PREFIX on-demand visible refresh deferred: host loading retry=$onDemandVisibleBusyRetries")
                        if (onDemandVisibleBusyRetries < 3) onDemandHandler.postDelayed({
                            if (isVisibleSession()) currentReaderPage()?.let { scheduleOnDemandVisiblePageRefresh(session.viewModel, it) }
                        }, 500L)
                        else HookRuntime.logError("$LOG_PREFIX on-demand visible refresh waiting exhausted; turn away/back to retry")
                    }
                }
                else -> HookRuntime.logError("$LOG_PREFIX on-demand visible page refresh failed", error)
            }
        }
    }
}

internal fun ReaderHook.onDemandPageLocation(
    bookDir: File,
    page: Any,
    knownMetadata: com.reamicro.fix.online.download.OnlineOnDemandMetadata? = null,
): OnDemandPageLocation? {
    val metadata = knownMetadata ?: runCatching { OnlineOnDemandMetadataStore.read(bookDir) }.getOrNull()
        ?: return null
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return null
    val table = onDemandSpineTable(epub) ?: return null
    val cfi = requireNotNull(com.reamicro.fix.core.HostEpubRuntimeBindings.current).anchor(page)
    val hostSpineIndex = OnlineOnDemandPrefetchPlanner.hostSpinePositionFromCfi(cfi, table.positions)
        ?: return null

    val href = table.hrefs.getOrNull(hostSpineIndex)?.takeIf { it.isNotBlank() } ?: return null
    val metadataIndex = onDemandNormalizedHrefs(bookDir, metadata).indexOf(href).takeIf { it >= 0 }
        ?: return null
    val chapter = metadata.chapter(metadataIndex) ?: return null
    return OnDemandPageLocation(metadataIndex, hostSpineIndex, chapter.href)
}

internal fun ReaderHook.normalizeOnDemandHref(value: String): String =
    value.trim()
        .substringBefore('#')
        .replace('\\', '/')
        .removePrefix("OEBPS/")
        .removePrefix("./")

private fun ReaderHook.onDemandNormalizedHrefs(
    bookDir: File,
    metadata: com.reamicro.fix.online.download.OnlineOnDemandMetadata,
): List<String> {
    val target = com.reamicro.fix.online.download.OnlineOnDemandMetadataStore.file(bookDir)
    val key = "${bookDir.absolutePath}|${target.lastModified()}|${target.length()}|${metadata.chapters.size}"
    onDemandNormalizedHrefsCache?.takeIf { it.first == key }?.let { return it.second }
    return metadata.chapters.map { normalizeOnDemandHref(it.href) }
        .also { onDemandNormalizedHrefsCache = key to it }
}

internal fun ReaderHook.rebuildOnDemandVirtualWindow(
    session: ReaderRefreshSession, anchorCfi: Any, anchorSpineIndex: Int, current: () -> Boolean,
    visiblePage: Any? = null,
): Int {
    requireRefreshCurrent(current)
    val host = requireNotNull(HostReaderWindowBindings.current) { "Reader window contract unavailable" }
    val mutex = host.field("vm.mutex").get(session.viewModel)
        ?: error("宿主缺少章节加载锁，未修改分页缓存")
    val lease = com.reamicro.fix.core.HostMutex(mutex, Any())
    var locked = false
    var window: ReaderSelectionWindow? = null
    try {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (!locked) {
            requireRefreshCurrent(current)
            locked = lease.tryAcquire()
            if (!locked) {
                if (SystemClock.elapsedRealtime() >= deadline) throw ReaderRefreshLockBusy()
                Thread.sleep(40L)
            }
        }
        requireRefreshCurrent(current)
        val method = findOnDemandRecenterMethod(session.viewModel, session.epub, anchorCfi)
            ?: error("宿主缺少 recenterVirtualWindow")
        if (visiblePage != null && selectionWindowHookInstalled) {
            val pages = host.pages(session.viewModel)
            val slot = (pages?.entries?.firstOrNull { it.value === visiblePage }?.key as? Number)?.toInt()
            val mapping = host.forward(session.viewModel)
            val local = slot?.let { mapping[it] }?.let { host.pair(it).second }
            if (slot != null && local != null) {
                window = ReaderSelectionWindow(session.viewModel, slot, anchorSpineIndex, local, anchorCfi)
                selectionWindow = window
            }
        }

        val rebuilt = (invokeRefreshHostSuspend(session, current) {
            invokeOnDemandRecenter(method, session.viewModel, anchorSpineIndex, session.epub, anchorCfi, it)
        } as? Number)?.toInt() ?: error("宿主重排没有返回有效页码")
        val target = if (window?.applied == true) window!!.visibleSlot else rebuilt

        val pages = host.pages(session.viewModel)
        val targetPage = pages?.get(target) ?: error("重排目标页不存在")
        val table = onDemandSpineTable(session.epub) ?: error("宿主章节列表不可用")
        val targetCfi = host.start(targetPage).toString()
        val actualSpine = OnlineOnDemandPrefetchPlanner.hostSpinePositionFromCfi(targetCfi, table.positions)
        check(actualSpine == anchorSpineIndex) {
            "重排章节校验失败 expected=$anchorSpineIndex actual=$actualSpine；未发送翻页通知"
        }
        return target
    } finally {
        if (window != null && selectionWindow === window) selectionWindow = null
        if (locked) lease.release()
    }
}

internal fun ReaderHook.emitOnDemandPagerCorrection(viewModel: Any, virtualPage: Int): Boolean =
    runCatching {
        requireNotNull(HostReaderWindowBindings.current).emitCorrection(viewModel, virtualPage)
    }.onFailure { HookRuntime.logError("$LOG_PREFIX on-demand pager correction failed", it) }.getOrDefault(false)

internal fun ReaderHook.findOnDemandRecenterMethod(viewModel: Any, epub: Any, anchorCfi: Any): Method? {
    val host = HostReaderWindowBindings.current ?: return null
    return host.method("recenter").takeIf {
        host.type("vm").isInstance(viewModel) && host.type("epub").isInstance(epub) && host.type("cfi").isInstance(anchorCfi)
    }
}
internal fun invokeOnDemandRecenter(
    method: Method, target: Any, spine: Int, epub: Any, cfi: Any?, continuation: Any?,
): Any? = when (method.parameterTypes.size) {
    4 -> method.invoke(target, spine, epub, cfi, continuation)
    5 -> method.invoke(target, spine, epub, cfi, false, continuation)
    else -> error("宿主重排接口参数不匹配：${method.parameterTypes.size}")
}

internal fun ReaderHook.isOnDemandSpineLoaded(viewModel: Any, spineIndex: Int): Boolean =
    runCatching {
        val host = requireNotNull(HostReaderWindowBindings.current)
        val loadedSpines = host.field("vm.loaded").get(viewModel) as? Set<*>
        loadedSpines?.any { (it as? Number)?.toInt() == spineIndex } == true
    }.getOrDefault(false)
