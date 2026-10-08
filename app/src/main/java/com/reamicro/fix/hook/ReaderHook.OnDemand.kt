package com.reamicro.fix.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.online.download.OnlineOnDemandBridge
import com.reamicro.fix.online.download.OnlineOnDemandChapterRequest
import com.reamicro.fix.online.download.OnlineOnDemandPrefetchPlanner
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Method
import java.io.File
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.online.download.OnlineOnDemandMetadataStore

internal fun ReaderHook.logOnDemandPrefetchDebug(message: String) {
    XposedBridge.log("$LOG_PREFIX on-demand prefetch $message")
}

internal fun ReaderHook.prefetchOnlineOnDemandChapters(page: Any) {
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
    if (currentEpubRoot() != bookDir) return
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, chapterIndex)
        ?: return logOnDemandPrefetchDebug("skip:no-pending:$chapterIndex:$reason")
    val prefetchKey = "${request.bookDir.absolutePath}|${request.chapter.href}"
    if (!onDemandPrefetchInFlight.add(prefetchKey)) return
    val accepted = OnlineOnDemandBridge.request(request) { result ->
        if (!onDemandRefreshCoordinator.isGeneration(generation)) return@request
        onDemandPrefetchInFlight.remove(prefetchKey)
        if (result.isSuccess) {
            onDemandPrefetchRetryCount.remove(prefetchKey)
            onDemandRefreshPending += onDemandRefreshKey(result.request.bookDir, result.request.chapter.href)
            XposedBridge.log(
                "$LOG_PREFIX on-demand prefetch completed anchor=${anchorIndex + 1} " +
                    "target=${result.request.chapterIndex + 1} reason=$reason " +
                    "href=${result.request.chapter.href}",
            )
        } else {
            val error = result.error
            XposedBridge.log(
                "$LOG_PREFIX on-demand prefetch failed index=${result.request.chapterIndex + 1} " +
                    "reason=$reason: ${error?.stackTraceToString()}",
            )
            if (error?.message.orEmpty().contains("HTTP 429")) {
                scheduleOnDemandPrefetchRetry(
                    bookDir = bookDir,
                    anchorIndex = anchorIndex,
                    chapterIndex = chapterIndex,
                    reason = reason,
                    prefetchKey = prefetchKey,
                )
            }
        }
    }
    if (accepted) {
        XposedBridge.log(
            "$LOG_PREFIX on-demand prefetch requested anchor=${anchorIndex + 1} " +
                "target=${request.chapterIndex + 1} reason=$reason href=${request.chapter.href}",
        )
    } else {
        onDemandPrefetchInFlight.remove(prefetchKey)
    }
}

internal fun ReaderHook.scheduleOnDemandPrefetchRetry(
    bookDir: File,
    anchorIndex: Int,
    chapterIndex: Int,
    reason: String,
    prefetchKey: String,
) {
    val retry = (onDemandPrefetchRetryCount[prefetchKey] ?: 0) + 1
    if (retry > ON_DEMAND_PREFETCH_429_RETRIES) {
        onDemandPrefetchRetryCount.remove(prefetchKey)
        XposedBridge.log("$LOG_PREFIX on-demand prefetch abandoned after rate limit index=${chapterIndex + 1}")
        return
    }
    onDemandPrefetchRetryCount[prefetchKey] = retry
    val delayMs = ON_DEMAND_PREFETCH_429_RETRY_MS * retry
    val generation = onDemandRefreshCoordinator.generation()
    Handler(Looper.getMainLooper()).postDelayed(
        {
            if (!onDemandRefreshCoordinator.isGeneration(generation)) return@postDelayed
            prefetchOnlineOnDemandChapter(
                bookDir = bookDir,
                anchorIndex = anchorIndex,
                chapterIndex = chapterIndex,
                reason = "$reason-retry-$retry",
            )
        },
        delayMs,
    )
    XposedBridge.log(
        "$LOG_PREFIX on-demand prefetch retry scheduled index=${chapterIndex + 1} " +
            "attempt=$retry delay=${delayMs}ms",
    )
}

internal fun ReaderHook.interceptOnDemandChapterStep(
    param: XC_MethodHook.MethodHookParam,
    intent: Any,
    step: Int,
): Boolean {
    val bookDir = currentEpubRoot() ?: return false
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return false
    val viewModel = param.thisObject
    val uiState = callNoArg(viewModel, "getUiState")
        ?.let { callNoArg(it, "getValue") }
        ?: return false
    val catalogIndex = (callNoArg(uiState, "getCatalogIndex") as? Number)?.toInt() ?: 0
    val target = runCatching {
        XposedHelpers.callMethod(epub, "findChapterByStep", catalogIndex, step)
    }.onFailure { error ->
        XposedBridge.log("$LOG_PREFIX on-demand chapter-step resolve failed: ${error.stackTraceToString()}")
    }.getOrNull() ?: return false
    val chapter = callNoArg(target, "getFirst") ?: return false
    val href = callNoArg(chapter, "getHref")?.toString().orEmpty()
    val refreshKey = onDemandRefreshKey(bookDir, href)
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, href)
        ?: if (refreshKey in onDemandRefreshPending) {
            OnlineOnDemandBridge.readyRequest(bookDir, href)
        } else {
            null
        }
        ?: return false
    XposedBridge.log(
        "$LOG_PREFIX on-demand chapter-step pending step=$step catalog=$catalogIndex " +
            "target=${request.chapterIndex + 1} href=$href",
    )
    return interceptOnDemandRequest(
        param = param,
        intent = intent,
        request = request,
        targetCfi = callNoArg(chapter, "getCfi"),
        replayLabel = if (step > 0) "OnDemandJumpNextChapter" else "OnDemandJumpPreviousChapter",
    )
}

internal fun ReaderHook.interceptOnDemandJump(param: XC_MethodHook.MethodHookParam, intent: Any): Boolean {
    val bookDir = currentEpubRoot() ?: return false
    val chapter = callNoArg(intent, "getChapter") ?: return false
    val href = callNoArg(chapter, "getHref")?.toString().orEmpty()
    val refreshKey = onDemandRefreshKey(bookDir, href)
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, href)
        ?: if (refreshKey in onDemandRefreshPending) {
            OnlineOnDemandBridge.readyRequest(bookDir, href)
        } else {
            null
        }
        ?: return false
    XposedBridge.log(
        "$LOG_PREFIX on-demand catalog jump pending " +
            "index=${callNoArg(intent, "getIndex") ?: -1} href=$href " +
            "spine=${request.chapterIndex} target=${request.chapterIndex + 1}",
    )
    return interceptOnDemandRequest(
        param = param,
        intent = intent,
        request = request,
        targetCfi = callNoArg(chapter, "getCfi"),
        replayLabel = "OnDemandJumpChapter",
    )
}

internal fun ReaderHook.interceptOnDemandRequest(
    param: XC_MethodHook.MethodHookParam,
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
        Handler(Looper.getMainLooper()).post {
            if (!isRefreshSessionCurrent(session) || !onDemandRefreshCoordinator.isCurrent(lease)) return@post
            if (result.isSuccess) {
                if (replayLabel == "OnDemandJumpChapter") prefetchOnlineOnDemandNeighborhood(result.request)
                replayOnDemandJumpWhenSpineCacheReady(session, lease, result.request, targetCfi, intent, replayLabel)
            } else {
                onDemandRefreshCoordinator.release(lease)
                XposedBridge.log("$LOG_PREFIX on-demand chapter failed: ${result.error?.stackTraceToString()}")
                activityProvider()?.let { Toast.makeText(it, "章节下载失败：${result.error?.message.orEmpty()}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    if (!accepted) { onDemandRefreshCoordinator.release(lease); return false }
    param.result = null
    activityProvider()?.let { activity -> activity.runOnUiThread {
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
                onDemandRefreshPending.remove(onDemandRefreshKey(request.bookDir, request.chapter.href))
                try {
                    replayingOnDemandJump.set(true)
                    dispatchReaderIntent(lastCatalogContext?.intentReceiver, session.viewModel, intent, replayLabel)
                    XposedBridge.log("$LOG_PREFIX on-demand virtual window rebuilt index=${request.chapterIndex + 1} page=${rebuiltPage ?: -1}")
                } finally { replayingOnDemandJump.remove() }
                targetUnit()
            }
        } catch (error: Throwable) {
            if (!current() || error is InterruptedException || error is java.util.concurrent.CancellationException) {
                XposedBridge.log("$LOG_PREFIX on-demand jump cancelled: session/target changed")
            } else {
                XposedBridge.logError("$LOG_PREFIX on-demand jump refresh failed", error)
                Handler(Looper.getMainLooper()).post {
                    if (isRefreshSessionCurrent(session) && onDemandJumpLease === lease) activityProvider()?.let {
                        Toast.makeText(it, "章节已下载，但分页未就绪，请重新点击目录", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

internal fun ReaderHook.onDemandRefreshKey(bookDir: File, href: String): String =
    "${bookDir.absolutePath}|${href.trim().substringBefore('#').replace('\\', '/')}"

internal fun ReaderHook.scheduleOnDemandVisiblePageRefresh(viewModel: Any, page: Any) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        val generation = onDemandRefreshCoordinator.generation()
        Handler(Looper.getMainLooper()).post {
            if (onDemandRefreshCoordinator.isGeneration(generation)) scheduleOnDemandVisiblePageRefresh(viewModel, page)
        }
        return
    }
    val root = currentEpubRoot() ?: return
    val session = captureRefreshSession(viewModel, root) ?: return
    val location = onDemandPageLocation(root, page) ?: return
    val metadata = runCatching { OnlineOnDemandMetadataStore.read(root) }.getOrNull() ?: return
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
        Handler(Looper.getMainLooper()).post {
            if (!isRefreshSessionCurrent(session) || !onDemandRefreshCoordinator.isCurrent(lease)) return@post
            if (result.isSuccess) {
                onDemandRefreshPending += refreshKey
                XposedBridge.log("$LOG_PREFIX on-demand visible chapter downloaded index=${request.chapterIndex + 1}")
                refreshOnDemandVisiblePageWhenSpineCacheReady(session, lease, result.request, refreshKey)
            } else {
                onDemandRefreshCoordinator.release(lease)
                XposedBridge.logError("$LOG_PREFIX on-demand visible chapter failed", result.error)
            }
        }
    }
    if (!accepted) onDemandRefreshCoordinator.release(lease)
    else XposedBridge.log("$LOG_PREFIX on-demand visible chapter requested index=${location.metadataIndex + 1} spine=${location.hostSpineIndex}")
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
            val cfi = callNoArg(anchorPage, "getStart") ?: return@submit
            val page = rebuildOnDemandVirtualWindow(session, cfi, location.hostSpineIndex, current, anchorPage)
            refreshOnMain(session, current) {

                check(isOnDemandSpineLoaded(session.viewModel, location.hostSpineIndex)) { "Rebuilt spine not loaded" }
                val delivered = emitOnDemandPagerCorrection(session.viewModel, page)
                onDemandRefreshPending.remove(refreshKey)
                onDemandVisibleBusyRetries = 0
                XposedBridge.log("$LOG_PREFIX on-demand visible page refreshed index=${request.chapterIndex + 1} page=$page correction=$delivered")
                targetUnit()
            }
        } catch (error: Throwable) {
            when {
                !current() || error is InterruptedException || error is java.util.concurrent.CancellationException ->
                    XposedBridge.log("$LOG_PREFIX on-demand visible refresh cancelled: session/viewport changed")
                error is ReaderRefreshLockBusy -> Handler(Looper.getMainLooper()).post {
                    if (isVisibleSession()) {
                        onDemandVisibleBusyRetries++
                        XposedBridge.log("$LOG_PREFIX on-demand visible refresh deferred: host loading retry=$onDemandVisibleBusyRetries")
                        if (onDemandVisibleBusyRetries < 3) Handler(Looper.getMainLooper()).postDelayed({
                            if (isVisibleSession()) currentReaderPage()?.let { scheduleOnDemandVisiblePageRefresh(session.viewModel, it) }
                        }, 500L)
                        else XposedBridge.logError("$LOG_PREFIX on-demand visible refresh waiting exhausted; turn away/back to retry")
                    }
                }
                else -> XposedBridge.logError("$LOG_PREFIX on-demand visible page refresh failed", error)
            }
        }
    }
}

internal fun ReaderHook.onDemandPageLocation(
    bookDir: File,
    page: Any,
    knownMetadata: com.reamicro.fix.online.download.OnlineOnDemandMetadata? = null,
): OnDemandPageLocation? {
    val metadata = knownMetadata ?: runCatching {
        com.reamicro.fix.online.download.OnlineOnDemandMetadataStore.read(bookDir)
    }.getOrNull() ?: return null
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return null
    val table = onDemandSpineTable(epub) ?: return null
    val cfi = callString(page, "getAnchor")
        .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
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
    val mutex = XposedHelpers.getObjectField(session.viewModel, "virtualPageLoadMutex")
        ?: error("宿主缺少章节加载锁，未修改分页缓存")
    val owner = Any()
    var locked = false
    var window: ReaderSelectionWindow? = null
    try {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (!locked) {
            requireRefreshCurrent(current)
            locked = XposedHelpers.callMethod(mutex, "tryLock", owner) == true
            if (!locked) {
                if (SystemClock.elapsedRealtime() >= deadline) throw ReaderRefreshLockBusy()
                Thread.sleep(40L)
            }
        }
        requireRefreshCurrent(current)
        val method = findOnDemandRecenterMethod(session.viewModel, session.epub, anchorCfi)
            ?: error("宿主缺少 recenterVirtualWindow")
        if (visiblePage != null && selectionWindowHookInstalled) {
            val pages = XposedHelpers.getObjectField(session.viewModel, "virtualPages") as? Map<*, *>
            val slot = (pages?.entries?.firstOrNull { it.value === visiblePage }?.key as? Number)?.toInt()
            val mapping = XposedHelpers.getObjectField(session.viewModel, "virtualToReal") as? Map<*, *>
            val local = slot?.let { mapping?.get(it) }?.let { callNoArg(it, "getSecond") as? Number }?.toInt()
            if (slot != null && local != null) {
                window = ReaderSelectionWindow(session.viewModel, slot, anchorSpineIndex, local, anchorCfi)
                selectionWindow = window
            }
        }

        val rebuilt = (invokeRefreshHostSuspend(session, current) {
            method.invoke(session.viewModel, anchorSpineIndex, session.epub, anchorCfi, false, it)
        } as? Number)?.toInt() ?: error("宿主重排没有返回有效页码")
        val target = if (window?.applied == true) window!!.visibleSlot else rebuilt

        val pages = XposedHelpers.getObjectField(session.viewModel, "virtualPages") as? Map<*, *>
        val targetPage = pages?.get(target) ?: error("重排目标页不存在")
        val table = onDemandSpineTable(session.epub) ?: error("宿主章节列表不可用")
        val targetCfi = callNoArg(targetPage, "getStart")?.toString().orEmpty()
        val actualSpine = OnlineOnDemandPrefetchPlanner.hostSpinePositionFromCfi(targetCfi, table.positions)
        check(actualSpine == anchorSpineIndex) {
            "重排章节校验失败 expected=$anchorSpineIndex actual=$actualSpine；未发送翻页通知"
        }
        return target
    } finally {
        if (window != null && selectionWindow === window) selectionWindow = null
        if (locked) XposedHelpers.callMethod(mutex, "unlock", owner)
    }
}

internal fun ReaderHook.emitOnDemandPagerCorrection(viewModel: Any, virtualPage: Int): Boolean =
    runCatching {
        val correction = XposedHelpers.getObjectField(viewModel, "_pagerCorrection")
        XposedHelpers.callMethod(correction, "tryEmit", Integer.valueOf(virtualPage)) == true
    }.onFailure { XposedBridge.logError("$LOG_PREFIX on-demand pager correction failed", it) }.getOrDefault(false)

internal fun ReaderHook.findOnDemandRecenterMethod(viewModel: Any, epub: Any, anchorCfi: Any): Method? {
    val candidates = mutableListOf<Method>()
    var type: Class<*>? = viewModel.javaClass
    while (type != null) {
        candidates += runCatching { type.declaredMethods.asList() }.getOrDefault(emptyList())
        type = type.superclass
    }
    fun Method.matchesSignature(): Boolean {
        val parameters = parameterTypes
        return parameters.size == 5 &&
            parameters[0] == Int::class.javaPrimitiveType &&
            parameters[1].isAssignableFrom(epub.javaClass) &&
            parameters[2].isAssignableFrom(anchorCfi.javaClass) &&
            parameters[3] == Boolean::class.javaPrimitiveType &&
            parameters[4].name == KOTLIN_CONTINUATION_CLASS
    }
    return candidates.firstOrNull { it.name == "recenterVirtualWindow" && it.matchesSignature() }
        ?.apply { isAccessible = true }

}

internal fun ReaderHook.isOnDemandSpineLoaded(viewModel: Any, spineIndex: Int): Boolean =
    runCatching {
        val loadedSpines = XposedHelpers.getObjectField(viewModel, "loadedSpines") as? Set<*>
        loadedSpines?.any { (it as? Number)?.toInt() == spineIndex } == true
    }.getOrDefault(false)
