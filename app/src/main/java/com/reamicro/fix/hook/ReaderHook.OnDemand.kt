package com.reamicro.fix.hook

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.online.download.OnlineOnDemandBridge
import com.reamicro.fix.online.download.OnlineOnDemandChapterRequest
import com.reamicro.fix.online.download.OnlineOnDemandPrefetchPlanner
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.io.File
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.online.download.OnlineOnDemandMetadataStore

// 在线补全的按需下载簇。
//
// 章节预取与重试、按需跳转拦截、spine 缓存就绪后的重放与可见页刷新。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.logOnDemandPrefetchDebug(message: String) {
    XposedBridge.log("$LOG_PREFIX on-demand prefetch $message")
}

internal fun ReaderHook.prefetchOnlineOnDemandChapters(page: Any) {
    val bookDir = currentEpubRoot() ?: return
    val currentIndex = onDemandPageLocation(bookDir, page)?.metadataIndex ?: return
    val spineKey = "${bookDir.absolutePath}|$currentIndex"
    if (spineKey == lastOnDemandPrefetchSpineKey) return
    lastOnDemandPrefetchSpineKey = spineKey
    // 逐章模式只提前拉取后续两章；回读章节不抢占在线源额度，避免连续翻页触发并发 429。
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
    val request = OnlineOnDemandBridge.pendingRequest(bookDir, chapterIndex)
        ?: return logOnDemandPrefetchDebug("skip:no-pending:$chapterIndex:$reason")
    val prefetchKey = "${request.bookDir.absolutePath}|${request.chapter.href}"
    if (!onDemandPrefetchInFlight.add(prefetchKey)) return
    val accepted = OnlineOnDemandBridge.request(request) { result ->
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
    Handler(Looper.getMainLooper()).postDelayed(
        {
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
    val activity = activityProvider()
    val viewModel = param.thisObject
    val accepted = OnlineOnDemandBridge.request(request) { result ->
        if (result.isSuccess) {
            if (replayLabel == "OnDemandJumpChapter") {
                prefetchOnlineOnDemandNeighborhood(result.request)
            }
            activityProvider()?.runOnUiThread {
                replayOnDemandJumpWhenSpineCacheReady(
                    viewModel = viewModel,
                    request = result.request,
                    targetCfi = targetCfi,
                    intent = intent,
                    replayLabel = replayLabel,
                )
            }
        } else {
            val message = result.error?.message.orEmpty().ifBlank { "下载失败" }
            XposedBridge.log("$LOG_PREFIX on-demand chapter failed: ${result.error?.stackTraceToString()}")
            activityProvider()?.runOnUiThread {
                Toast.makeText(activityProvider(), "章节下载失败：$message", Toast.LENGTH_LONG).show()
            }
        }
    }
    if (!accepted) return false
    param.result = null
    activity?.runOnUiThread {
        Toast.makeText(activity, "正在下载：${request.chapter.title}", Toast.LENGTH_SHORT).show()
    }
    return true
}

internal fun ReaderHook.replayOnDemandJumpWhenSpineCacheReady(
    viewModel: Any,
    request: OnlineOnDemandChapterRequest,
    targetCfi: Any?,
    intent: Any,
    replayLabel: String,
) {
    Thread({
        val rebuiltPage = targetCfi?.let { cfi ->
            val targetSpineIndex = (callNoArg(cfi, "getSpineIndex") as? Number)?.toInt()
                ?: OnlineOnDemandPrefetchPlanner.hostSpineIndexFromCfi(cfi.toString())
                ?: request.chapterIndex
            rebuildOnDemandVirtualWindow(
                viewModel = viewModel,
                request = request,
                anchorCfi = cfi,
                anchorSpineIndex = targetSpineIndex,
            )
        }
        if (targetCfi != null && rebuiltPage == null) {
            activityProvider()?.runOnUiThread {
                Toast.makeText(activityProvider(), "章节已下载，但分页刷新失败，请重试", Toast.LENGTH_SHORT).show()
            }
            return@Thread
        }
        onDemandRefreshPending.remove(onDemandRefreshKey(request.bookDir, request.chapter.href))
        XposedBridge.log(
            "$LOG_PREFIX on-demand virtual window rebuilt " +
                "index=${request.chapterIndex + 1} page=${rebuiltPage ?: -1} href=${request.chapter.href}",
        )
        activityProvider()?.runOnUiThread {
            runCatching {
                replayingOnDemandJump.set(true)
                dispatchReaderIntent(
                    receiver = lastCatalogContext?.intentReceiver,
                    viewModel = viewModel,
                    intent = intent,
                    label = replayLabel,
                )
            }.onFailure { error ->
                XposedBridge.log("$LOG_PREFIX on-demand jump replay failed: ${error.stackTraceToString()}")
                Toast.makeText(activityProvider(), "章节已下载，请重新点击目录", Toast.LENGTH_SHORT).show()
            }.also {
                replayingOnDemandJump.remove()
            }
        }
    }, "ReaMicroOnDemandRefresh").start()
}

internal fun ReaderHook.onDemandRefreshKey(bookDir: File, href: String): String =
    "${bookDir.absolutePath}|${href.trim().substringBefore('#').replace('\\', '/')}"

internal fun ReaderHook.scheduleOnDemandVisiblePageRefresh(viewModel: Any, page: Any) {
    val bookDir = currentEpubRoot() ?: return
    val metadata = runCatching {
        com.reamicro.fix.online.download.OnlineOnDemandMetadataStore.read(bookDir)
    }.getOrNull() ?: return
    val location = onDemandPageLocation(bookDir, page, metadata) ?: return
    val chapter = metadata.chapter(location.metadataIndex) ?: return
    val refreshKey = onDemandRefreshKey(bookDir, chapter.href)
    val targetCfi = callNoArg(page, "getStart")
    if (targetCfi == null || !onDemandRefreshInFlight.add(refreshKey)) return

    val pendingRequest = OnlineOnDemandBridge.pendingRequest(bookDir, chapter.href)
    if (pendingRequest != null) {
        val accepted = OnlineOnDemandBridge.request(pendingRequest) { result ->
            if (result.isSuccess) {
                onDemandRefreshPending += refreshKey
                XposedBridge.log(
                    "$LOG_PREFIX on-demand visible chapter downloaded " +
                        "index=${result.request.chapterIndex + 1} href=${result.request.chapter.href}",
                )
                refreshOnDemandVisiblePageWhenSpineCacheReady(
                    viewModel = viewModel,
                    request = result.request,
                    fallbackPage = page,
                    refreshKey = refreshKey,
                )
            } else {
                onDemandRefreshInFlight.remove(refreshKey)
                XposedBridge.log(
                    "$LOG_PREFIX on-demand visible chapter failed: ${result.error?.stackTraceToString()}",
                )
            }
        }
        if (!accepted) onDemandRefreshInFlight.remove(refreshKey)
        if (accepted) {
            XposedBridge.log(
                "$LOG_PREFIX on-demand visible chapter requested " +
                    "index=${location.metadataIndex + 1} spine=${location.hostSpineIndex} href=${chapter.href}",
            )
        }
        return
    }

    val readyRequest = OnlineOnDemandBridge.readyRequest(bookDir, chapter.href)
    if (readyRequest == null || refreshKey !in onDemandRefreshPending) {
        onDemandRefreshInFlight.remove(refreshKey)
        return
    }
    refreshOnDemandVisiblePageWhenSpineCacheReady(
        viewModel = viewModel,
        request = readyRequest,
        fallbackPage = page,
        refreshKey = refreshKey,
    )
}

internal fun ReaderHook.refreshOnDemandVisiblePageWhenSpineCacheReady(
    viewModel: Any,
    request: OnlineOnDemandChapterRequest,
    fallbackPage: Any,
    refreshKey: String,
) {
    Thread({
        // getVirtualPage 也会为相邻页预布局；优先用 Statistics 记录的真实可见页做锚点，
        // 避免下载完成后把阅读位置强行纠正到尚未翻入的占位章节。
        val anchorPage = currentReaderPage() ?: fallbackPage
        val anchorCfi = callNoArg(anchorPage, "getStart")
        val anchorLocation = onDemandPageLocation(request.bookDir, anchorPage)
        val anchorChapterNumber = anchorLocation?.metadataIndex?.plus(1) ?: -1
        if (anchorLocation?.metadataIndex != request.chapterIndex) {
            XposedBridge.log(
                "$LOG_PREFIX on-demand visible page refresh stale " +
                    "index=${request.chapterIndex + 1} anchor=$anchorChapterNumber href=${request.chapter.href}",
            )
            onDemandRefreshInFlight.remove(refreshKey)
            return@Thread
        }
        val anchorSpineIndex = anchorLocation.hostSpineIndex
        val page = if (anchorCfi != null) rebuildOnDemandVirtualWindow(
            viewModel = viewModel,
            request = request,
            anchorCfi = anchorCfi,
            anchorSpineIndex = anchorSpineIndex,
        ) else null
        if (page != null) {
            emitOnDemandPagerCorrection(viewModel, page)
            if (isOnDemandSpineLoaded(viewModel, anchorSpineIndex)) {
                onDemandRefreshPending.remove(refreshKey)
                XposedBridge.log(
                    "$LOG_PREFIX on-demand visible page refreshed " +
                        "index=${request.chapterIndex + 1} anchor=$anchorChapterNumber " +
                        "page=$page href=${request.chapter.href}",
                )
            } else {
                XposedBridge.log(
                    "$LOG_PREFIX on-demand visible page refresh deferred " +
                        "index=${request.chapterIndex + 1} anchor=$anchorChapterNumber " +
                        "href=${request.chapter.href}",
                )
            }
        } else {
            XposedBridge.log(
                "$LOG_PREFIX on-demand visible page refresh failed " +
                    "index=${request.chapterIndex + 1} href=${request.chapter.href}",
            )
        }
        onDemandRefreshInFlight.remove(refreshKey)
    }, "ReaMicroOnDemandVisibleRefresh").start()
}

internal fun ReaderHook.onDemandPageLocation(
    bookDir: File,
    page: Any,
    knownMetadata: com.reamicro.fix.online.download.OnlineOnDemandMetadata? = null,
): OnDemandPageLocation? {
    val metadata = knownMetadata ?: runCatching {
        com.reamicro.fix.online.download.OnlineOnDemandMetadataStore.read(bookDir)
    }.getOrNull() ?: return null
    val normalizedHrefs = metadata.chapters.map { normalizeOnDemandHref(it.href) }
    val directIndex = readAloudPageHrefCandidates(page)
        .asSequence()
        .map(::normalizeOnDemandHref)
        .map(normalizedHrefs::indexOf)
        .firstOrNull { it >= 0 }
    val cfi = callString(page, "getAnchor")
        .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
    val metadataIndex = directIndex
        ?: OnlineOnDemandPrefetchPlanner.chapterIndexFromCfi(cfi, metadata.chapters.size)
        ?: return null
    val hostSpineIndex = (callNoArg(page, "getSpineIndex") as? Number)?.toInt()
        ?: OnlineOnDemandPrefetchPlanner.hostSpineIndexFromCfi(cfi)
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

internal fun ReaderHook.rebuildOnDemandVirtualWindow(
    viewModel: Any,
    request: OnlineOnDemandChapterRequest,
    anchorCfi: Any,
    anchorSpineIndex: Int,
): Int? {
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return null
    val mutex = runCatching {
        XposedHelpers.getObjectField(viewModel, "virtualPageLoadMutex")
    }.getOrNull()
    val owner = request
    var locked = mutex == null
    for (attempt in 0..ON_DEMAND_CACHE_LOCK_RETRIES) {
        if (!locked) {
            locked = runCatching {
                XposedHelpers.callMethod(mutex, "tryLock", owner) as? Boolean ?: false
            }.getOrDefault(false)
        }
        if (locked) break
        if (attempt < ON_DEMAND_CACHE_LOCK_RETRIES) Thread.sleep(ON_DEMAND_CACHE_LOCK_RETRY_MS)
    }
    if (!locked) {
        XposedBridge.log(
            "$LOG_PREFIX on-demand virtual window lock timeout " +
                "target=${request.chapterIndex + 1} href=${request.chapter.href}",
        )
        return null
    }
    return try {
        val recenterMethod = findOnDemandRecenterMethod(viewModel, epub, anchorCfi)
            ?: error("宿主缺少 recenterVirtualWindow")
        (invokeSuspendBlocking(
            recenterMethod,
            viewModel,
            anchorSpineIndex,
            epub,
            anchorCfi,
            false,
        ) as? Number)?.toInt()
    } catch (error: Throwable) {
        XposedBridge.log(
            "$LOG_PREFIX on-demand virtual window rebuild failed: ${error.stackTraceToString()}",
        )
        null
    } finally {
        if (mutex != null) {
            runCatching { XposedHelpers.callMethod(mutex, "unlock", owner) }
                .onFailure { error ->
                    XposedBridge.log("$LOG_PREFIX on-demand virtual window unlock failed: ${error.stackTraceToString()}")
                }
        }
    }
}

internal fun ReaderHook.emitOnDemandPagerCorrection(viewModel: Any, virtualPage: Int) {
    runCatching {
        val correction = XposedHelpers.getObjectField(viewModel, "_pagerCorrection")
        XposedHelpers.callMethod(correction, "tryEmit", Integer.valueOf(virtualPage))
    }.onFailure { error ->
        XposedBridge.log("$LOG_PREFIX on-demand pager correction failed: ${error.stackTraceToString()}")
    }
}

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
        ?: candidates.firstOrNull(Method::matchesSignature)?.apply { isAccessible = true }
}

internal fun ReaderHook.isOnDemandSpineLoaded(viewModel: Any, spineIndex: Int): Boolean =
    runCatching {
        val loadedSpines = XposedHelpers.getObjectField(viewModel, "loadedSpines") as? Set<*>
        loadedSpines?.any { (it as? Number)?.toInt() == spineIndex } == true
    }.getOrDefault(false)

internal fun ReaderHook.invalidateOnDemandPrefetchedSpineWhenCacheReady(
    viewModel: Any,
    request: OnlineOnDemandChapterRequest,
    spineIndex: Int,
    attempt: Int = 0,
) {
    val mutex = runCatching {
        XposedHelpers.getObjectField(viewModel, "virtualPageLoadMutex")
    }.getOrNull()
    val owner = request
    val locked = mutex == null || runCatching {
        XposedHelpers.callMethod(mutex, "tryLock", owner) as? Boolean ?: false
    }.getOrDefault(false)
    if (!locked) {
        if (attempt < ON_DEMAND_CACHE_LOCK_RETRIES) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    invalidateOnDemandPrefetchedSpineWhenCacheReady(
                        viewModel = viewModel,
                        request = request,
                        spineIndex = spineIndex,
                        attempt = attempt + 1,
                    )
                },
                ON_DEMAND_CACHE_LOCK_RETRY_MS,
            )
        } else {
            XposedBridge.log(
                "$LOG_PREFIX on-demand prefetched spine invalidation lock timeout " +
                    "index=$spineIndex href=${request.chapter.href}",
            )
        }
        return
    }
    try {
        invalidateOnDemandSpine(viewModel, request, spineIndex)
    } finally {
        if (mutex != null) {
            runCatching { XposedHelpers.callMethod(mutex, "unlock", owner) }
                .onFailure { error ->
                    XposedBridge.log(
                        "$LOG_PREFIX on-demand prefetched cache unlock failed: " +
                            error.stackTraceToString(),
                    )
                }
        }
    }
}

internal fun ReaderHook.invalidateOnDemandSpine(
    viewModel: Any,
    request: OnlineOnDemandChapterRequest,
    spineIndex: Int,
) {
    runCatching {
        val spineCache = XposedHelpers.getObjectField(viewModel, "spinePagesCache") as? MutableMap<Any?, Any?>
        val loadedSpines = XposedHelpers.getObjectField(viewModel, "loadedSpines") as? MutableSet<Any?>
        val virtualToReal = XposedHelpers.getObjectField(viewModel, "virtualToReal") as? MutableMap<Any?, Any?>
        val realToVirtual = XposedHelpers.getObjectField(viewModel, "realToVirtual") as? MutableMap<Any?, Any?>
        val virtualPages = XposedHelpers.getObjectField(viewModel, "virtualPages") as? MutableMap<Any?, Any?>
        val affectedVirtualPages = virtualToReal.orEmpty()
            .filterValues { real ->
                val first = callNoArg(real, "getFirst") as? Number
                first?.toInt() == spineIndex
            }
            .keys
            .toList()
        affectedVirtualPages.forEach { virtualPage ->
            val real = virtualToReal?.remove(virtualPage)
            if (real != null) realToVirtual?.remove(real)
            virtualPages?.remove(virtualPage)
        }
        realToVirtual?.keys
            ?.filter { real -> (callNoArg(real, "getFirst") as? Number)?.toInt() == spineIndex }
            ?.toList()
            ?.forEach(realToVirtual::remove)
        spineCache?.remove(spineIndex)
        loadedSpines?.remove(spineIndex)
        XposedBridge.log(
            "$LOG_PREFIX on-demand spine invalidated index=$spineIndex " +
                "virtualPages=${affectedVirtualPages.size} href=${request.chapter.href}",
        )
    }.onFailure { error ->
        XposedBridge.log("$LOG_PREFIX on-demand spine invalidation failed: ${error.stackTraceToString()}")
    }
}
