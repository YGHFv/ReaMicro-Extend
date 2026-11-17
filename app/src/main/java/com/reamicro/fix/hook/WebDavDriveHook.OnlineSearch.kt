package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.online.OnlineConcurrentRateLimiter
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceLoginConfig
import com.reamicro.fix.online.OnlineSourceScriptCompat
import com.reamicro.fix.online.OnlineSourceTrxsCompat
import com.reamicro.fix.online.search.applyOnlineChapterListRuleCompat
import com.reamicro.fix.online.search.cleanOnlineChapterTitleValue
import com.reamicro.fix.online.search.evaluateQqReaderCoverRule
import com.reamicro.fix.online.search.isOnlineChapterCountSelector
import com.reamicro.fix.online.search.onlineSearchRelevanceScore
import com.reamicro.fix.online.search.onlineHttpErrorDetail
import com.reamicro.fix.online.download.OnlineSourceHttpException
import com.reamicro.fix.online.search.parseOnlineUrlRequestCompat
import com.reamicro.fix.online.search.resolveOnlineChapterListRuleCompat
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.onlineSearchTagLine
import com.reamicro.fix.online.search.OnlineSearchTrace
import com.reamicro.fix.online.search.onlineSearchRequestBudgetMs
import com.reamicro.fix.online.search.sourceBaseUrl
import com.reamicro.fix.online.search.parseOnlineJsonRoot
import com.reamicro.fix.online.search.onlineJsonRuleValues
import com.reamicro.fix.online.search.onlineFirstJsonString
import com.reamicro.fix.online.search.indexOfOptionsBlock
import com.reamicro.fix.online.search.parseOptionsJson
import com.reamicro.fix.online.search.resolveOnlineUrl
import com.reamicro.fix.online.search.onlineDataUrlStringResponse
import com.reamicro.fix.online.search.parseOnlineHtmlResults
import com.reamicro.fix.online.search.firstJsonString
import com.reamicro.fix.online.search.onlineFirstJsonInt
import com.reamicro.fix.online.search.onlineCompletionStatusText
import com.reamicro.fix.online.search.formatOnlineWordCount
import com.reamicro.fix.online.search.formatOnlineUpdateTime
import com.reamicro.fix.online.search.replaceFanqieCover
import com.reamicro.fix.online.search.onlineJsonString
import com.reamicro.fix.online.search.homeCloudSearchResults
import com.reamicro.fix.online.download.shuqiChapterListNodes
import com.reamicro.fix.online.download.fallbackOnlineChapterRawUrl
import com.reamicro.fix.online.download.onlineChapterVolumeTitle
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.reload.TaskCancellation

private data class OnlineSearchRowBinding(val path: String, val query: Long)
private data class OnlineSearchCoverBinding(val key: String, val query: Long, val generation: Long)
// 与在线图片请求的响应上限一致；显示时仍按 512 像素采样，不缓存原始大图。
private const val ONLINE_SEARCH_COVER_MAX_BYTES = 16 * 1024 * 1024
private const val ONLINE_SEARCH_COVER_MAX_EDGE = 512

internal fun WebDavDriveHook.renderOnlineCompletionCloudBookSearchRow(book: Any, target: OnlineDownloadTarget, composer: Any) {
    EmbeddedHostUi.captureTypography(classLoader, composer)
    val query = webDavHomeSearchSeq.get()
    val textColors = onlineCompletionSearchTextColors(composer)
    val factory = functionProxy("OnlineCompletionSearchRowAndroidView", FUNCTION1_CLASS) { args ->
        val context = args?.getOrNull(0) as? Context
            ?: activityProvider()
            ?: error("No context for online completion search row")
        createOnlineCompletionSearchRowView(context, book, target, textColors)
    }
    val update = functionProxy("OnlineCompletionSearchRowUpdate", FUNCTION1_CLASS) { args ->
        (args?.getOrNull(0) as? View)?.let { view ->
            bindOnlineCompletionSearchRow(view, book)
            val latestTarget = onlineCompletionSearchTargets[cloudPathOf(book)] ?: target
            applyOnlineCompletionSearchRow(view, latestTarget, textColors)
        }
        targetUnit()
    }
    runCatching {
        val baseModifier = fillMaxWidthModifier()
        val rowModifier = clickableModifier(baseModifier, "OnlineCompletionSearchRowClick") {
            handleOnlineCompletionSearchTap(book, query)
        }
        androidViews.render(factory, rowModifier, composer, update)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to render online completion search row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.createOnlineCompletionSearchRowView(
    context: Context,
    book: Any,
    target: OnlineDownloadTarget,
    textColors: IntArray,
): View {
    fun hostDp(value: Float) = (EmbeddedHostUi.dp(context, value) + 0.5f).toInt()
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(hostDp(16f), hostDp(10f), hostDp(16f), hostDp(10f))
    }
    val cover = HostSearchCoverView(context)
    row.addView(cover, LinearLayout.LayoutParams(hostDp(72f), hostDp(97.6f)).apply {
        rightMargin = hostDp(16f)
    })
    loadOnlineCompletionSearchCover(cover.image, target.source, target.result.coverUrl)

    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val titleView = TextView(context).apply {
        text = target.result.name.ifBlank { "未命名" }
        setTextColor(textColors[0])
        EmbeddedHostUi.text(this, "TitleSmall")
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = Gravity.START
    }
    texts.addView(titleView)
    val authorView = TextView(context).apply {
        text = target.result.author.ifBlank { "佚名" }
        setTextColor(textColors[1])
        EmbeddedHostUi.text(this, "BodyMedium")
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(0, hostDp(4f), 0, 0)
    }
    texts.addView(authorView)
    val tagsView = TextView(context).apply {
        text = onlineSearchTagLine(target.result)
        setTextColor(textColors[2])
        EmbeddedHostUi.text(this, "BodySmall")
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(0, hostDp(4f), 0, 0)
    }
    texts.addView(tagsView)
    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    applyOnlineCompletionSearchTypeface(texts)
    bindOnlineCompletionSearchRow(row, book)
    onlineCompletionSearchTargets[cloudPathOf(book)]
        ?.takeIf { it != target }
        ?.let { latestTarget -> applyOnlineCompletionSearchRow(row, latestTarget, colors = null) }
    row.contentDescription = onlineCompletionSearchDescription(target.result)
    return row
}

private fun WebDavDriveHook.bindOnlineCompletionSearchRow(row: View, book: Any) {
    val path = cloudPathOf(book)
    val previous = row.tag as? OnlineSearchRowBinding
    if (previous != null && previous.path != path) {
        onlineCompletionSearchRowViews[previous.path]?.takeIf { it.get() === row }?.let {
            onlineCompletionSearchRowViews.remove(previous.path, it)
        }
    }
    val query = webDavHomeSearchSeq.get()
    row.tag = OnlineSearchRowBinding(path, query)
    row.setOnClickListener { handleOnlineCompletionSearchTap(book, query) }
    onlineCompletionSearchRowViews.entries.removeAll { it.value.get() == null }
    onlineCompletionSearchRowViews[path] = WeakReference(row)
}

internal fun WebDavDriveHook.applyOnlineCompletionSearchRow(row: View, target: OnlineDownloadTarget, colors: IntArray?) {
    val cover = (row as? ViewGroup)?.getChildAt(0) as? HostSearchCoverView
    cover?.applyTheme()
    if (cover != null) loadOnlineCompletionSearchCover(cover.image, target.source, target.result.coverUrl)
    val textContainer = (row as? ViewGroup)?.getChildAt(1) as? ViewGroup ?: return
    (textContainer.getChildAt(0) as? TextView)?.apply {
        text = target.result.name.ifBlank { "未命名" }
        colors?.getOrNull(0)?.let { setTextColor(it) }
    }
    (textContainer.getChildAt(1) as? TextView)?.apply {
        text = target.result.author.ifBlank { "佚名" }
        colors?.getOrNull(1)?.let { setTextColor(it) }
    }
    (textContainer.getChildAt(2) as? TextView)?.apply {
        text = onlineSearchTagLine(target.result)
        colors?.getOrNull(2)?.let { setTextColor(it) }
    }
    row.contentDescription = onlineCompletionSearchDescription(target.result)
    applyOnlineCompletionSearchTypeface(textContainer)
}

private fun onlineCompletionSearchDescription(result: OnlineBookSearchResult): String =
    "${result.name}，${result.author.ifBlank { "佚名" }}，${onlineSearchTagLine(result)}"

internal fun WebDavDriveHook.applyOnlineCompletionSearchTypeface(textContainer: ViewGroup) {
    val roles = listOf("TitleSmall", "BodyMedium", "BodySmall")
    for (index in 0 until textContainer.childCount) {
        val textView = textContainer.getChildAt(index) as? TextView ?: continue
        EmbeddedHostUi.text(textView, roles.getOrElse(index) { "BodySmall" })
    }
}

internal fun WebDavDriveHook.updateOnlineCompletionSearchTarget(path: String, target: OnlineDownloadTarget, query: Long) {
    val generation = onlineSearchTasks.generation
    val signal = TaskCancellation.current()
    val write = Runnable {
        if (storagePaused || storageClosed || !homeSearch.isCurrent(query) ||
            !onlineSearchTasks.isCurrent(generation) || signal?.isCancelled == true
        ) return@Runnable
        onlineCompletionSearchTargets[path] = target
        val rowRef = onlineCompletionSearchRowViews[path]
        val row = rowRef?.get()
        if (row == null && rowRef != null) {
            onlineCompletionSearchRowViews.remove(path, rowRef)
        } else if (row?.tag == OnlineSearchRowBinding(path, query)) {
            applyOnlineCompletionSearchRow(row, target, colors = null)
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) write.run() else storageHandler.post(write)
}

internal fun WebDavDriveHook.loadOnlineCompletionSearchCover(imageView: ImageView, source: OnlineSourceEntry, coverUrl: String) {
    val url = normalizeOnlineCoverUrl(source, sourceBaseUrl(source), coverUrl).trim()
    val frame = imageView.parent as? HostSearchCoverView
    if (url.isBlank()) {
        imageView.tag = null
        frame?.state("暂无封面") ?: imageView.setImageDrawable(null)
        return
    }
    val generation = onlineSearchTasks.generation
    val binding = OnlineSearchCoverBinding("${source.id}|${source.revision}|$url", webDavHomeSearchSeq.get(), generation)
    if (storagePaused || storageClosed || !homeSearch.isCurrent(binding.query) || !onlineSearchTasks.isCurrent(generation)) return
    if (imageView.tag == binding) return
    imageView.tag = binding
    onlineSearchTasks.cachedCover(binding.key)?.let {
        frame?.loaded(it) ?: imageView.setImageBitmap(it)
        return
    }
    frame?.state("加载中") ?: imageView.setImageDrawable(null)
    val target = WeakReference(imageView)
    val accepted = onlineSearchTasks.cover(generation) {
        runCatching {
            TaskCancellation.checkCurrent()
            if (target.get() == null) return@runCatching null
            if (url.startsWith("data:image/", ignoreCase = true)) {
                require(url.length <= ONLINE_SEARCH_COVER_MAX_BYTES * 4) { "封面数据过大" }
                val separator = url.indexOf(',')
                require(separator >= 0) { "封面数据格式错误" }
                val payload = url.substring(separator + 1)
                val bytes = if (url.substring(0, separator).contains(";base64", ignoreCase = true)) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                }
                require(bytes.size <= ONLINE_SEARCH_COVER_MAX_BYTES) { "封面数据过大" }
                return@runCatching decodeOnlineSearchCover(bytes)
            }
            withOnlineCleartextAllowed(url) {
                val connection = URL(url).openConnection() as HttpURLConnection
                val cancellation = TaskCancellation.current()?.register(connection::disconnect)
                try {
                    TaskCancellation.checkCurrent()
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 6_000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                    connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    parseOnlineHeaders(source.header).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
                    }
                    OnlineSourceAuth.requestHeaders(currentApplicationContext() ?: currentContext(), source, url).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
                    }
                    TaskCancellation.checkCurrent()
                    require(connection.contentLengthLong <= ONLINE_SEARCH_COVER_MAX_BYTES) { "封面数据过大" }
                    connection.inputStream.use { input -> decodeOnlineSearchCover(readOnlineSearchCover(input)) }
                } finally {
                    cancellation?.close()
                    connection.disconnect()
                }
            }
        }.onSuccess { bitmap ->
            TaskCancellation.checkCurrent()
            if (onlineSearchTasks.isCurrent(generation)) {
                if (bitmap != null) onlineSearchTasks.cacheCover(binding.key, bitmap, generation)
                storageHandler.post {
                    val view = target.get()
                    if (!storagePaused && !storageClosed && homeSearch.isCurrent(binding.query) &&
                        onlineSearchTasks.isCurrent(generation) && view?.tag == binding
                    ) {
                        val currentFrame = view.parent as? HostSearchCoverView
                        if (bitmap == null) currentFrame?.state("封面不可用")
                        else currentFrame?.loaded(bitmap) ?: view.setImageBitmap(bitmap)
                    }
                }
            }
        }.onFailure {
            TaskCancellation.rethrowIfCancelled(it)
            storageHandler.post {
                val view = target.get()
                if (!storagePaused && !storageClosed && homeSearch.isCurrent(binding.query) &&
                    onlineSearchTasks.isCurrent(generation) && view?.tag == binding
                ) (view.parent as? HostSearchCoverView)?.state("封面加载失败")
            }
            logWebDav("online completion search cover failed url=${url.take(120)} error=${it.message.orEmpty()}")
        }
    }
    if (!accepted && imageView.tag == binding) {
        imageView.tag = null
        frame?.state("等待加载")
    }
}

private fun readOnlineSearchCover(input: InputStream): ByteArray {
    val bytes = ByteArrayOutputStream(32 * 1024)
    val buffer = ByteArray(8 * 1024)
    while (true) {
        TaskCancellation.checkCurrent()
        val count = input.read(buffer)
        if (count < 0) break
        require(bytes.size() + count <= ONLINE_SEARCH_COVER_MAX_BYTES) { "封面数据过大" }
        bytes.write(buffer, 0, count)
    }
    return bytes.toByteArray()
}

private fun decodeOnlineSearchCover(bytes: ByteArray): Bitmap? {
    TaskCancellation.checkCurrent()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val largest = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while (largest / sample > ONLINE_SEARCH_COVER_MAX_EDGE) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    TaskCancellation.checkCurrent()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun WebDavDriveHook.searchOnlineCompletionSourcesProgressively(
    query: String,
    onUpdate: (List<OnlineSearchGroup>) -> Unit,
): List<OnlineSearchGroup> {
    val context = currentContext() ?: return emptyList()
    val needle = query.trim()
    if (needle.isBlank()) return emptyList()
    val sources = enabledOnlineCompletionSources(context).take(HOME_SEARCH_RESULT_LIMIT)
    if (sources.isEmpty()) return emptyList()
    logWebDav("online completion progressive search query=$needle sources=${sources.size}")

    TaskCancellation.checkCurrent()
    val signal = TaskCancellation.current()?.child() ?: TaskCancellation()
    val generation = onlineSearchTasks.generation
    val latch = CountDownLatch(sources.size)
    val budgets = sources.map {
        onlineSearchRequestBudgetMs(it.respondTime,
            com.reamicro.fix.online.OnlineSourceDownloadPolicyStore.effectiveConcurrentRate(it))
    }
    val queueBudget = budgets.max() * ((sources.size - 1) / 3) + 30_000L
    val traces = budgets.map { OnlineSearchTrace(OnlineSearchTrace.nextId(), it, queueBudget) }
    val sourceSignals = sources.map { signal.child() }
    val groups = WebDavSearchResults(
        sources.map { OnlineSearchGroup(it, needle, emptyList(), "搜索中") },
        isCurrent = { !signal.isCancelled && onlineSearchTasks.isCurrent(generation) },
        onUpdate = onUpdate,
    )
    try {
        sources.forEachIndexed { index, source ->
            signal.check()
            val trace = traces[index]
            val accepted = onlineSearchTasks.source(sourceSignals[index], generation, latch::countDown) {
                if (!trace.start()) return@source
                trace.run {
                    try {
                        val finalGroup = searchOnlineCompletionSource(source, needle) { parsedGroup ->
                            trace.parsed { groups.publish(index, parsedGroup) }
                        }
                        if (trace.finish()) {
                            groups.publish(index, finalGroup)
                            HookRuntime.logAlways("ReaMicro online search source finished sourceId=${source.id} " +
                                "results=${finalGroup.results.size} failed=${finalGroup.error.isNotBlank()} ${trace.diagnostic()}")
                        }
                    } finally {
                        if (trace.finish()) {
                            HookRuntime.logAlways("ReaMicro online search source cancelled sourceId=${source.id} ${trace.diagnostic()}")
                        }
                    }
                }
            }
            if (!accepted) {
                trace.finish()
                groups.publish(index, OnlineSearchGroup(source, needle, emptyList(), "搜索队列繁忙"))
                HookRuntime.logAlways("ReaMicro online search source rejected sourceId=${source.id} ${trace.diagnostic()}")
            }
        }
        while (latch.count > 0) {
            signal.check()
            traces.forEachIndexed { index, trace ->
                if (trace.takeSlowSnapshot()) {
                    HookRuntime.logAlways("ReaMicro online search source slow sourceId=${sources[index].id} ${trace.diagnostic(includeStack = true)}")
                }
                if (trace.remainingMs() <= 0L && trace.finish()) {
                    val error = if (trace.hasStarted()) "搜索超时" else "搜索队列等待超时"
                    if (!trace.hasParsed()) {
                        groups.publish(index, OnlineSearchGroup(sources[index], needle, emptyList(), error))
                    }
                    HookRuntime.logAlways("ReaMicro online search source deadline sourceId=${sources[index].id} ${trace.diagnostic(includeStack = true)}")
                    sourceSignals[index].cancel()
                }
            }
            val wait = traces.minOf { it.remainingMs() }.coerceIn(1L, 250L)
            latch.await(wait, TimeUnit.MILLISECONDS)
        }
        signal.check()
        return groups.finish(
            transform = { if (it.error == "搜索中") it.copy(error = "搜索超时") else it },
            publish = true,
        )
    } finally {
        groups.finish()
        signal.cancel()
    }
}

internal fun WebDavDriveHook.onlineCompletionPendingSearchGroups(query: String): List<OnlineSearchGroup> {
    onlineSearchTasks.cancelAll()
    val context = currentContext() ?: return emptyList()
    val needle = query.trim()
    if (needle.isBlank()) {
        onlineCompletionSearchTargets.clear()
        onlineCompletionSearchRowViews.clear()
        return emptyList()
    }
    onlineCompletionSearchTargets.clear()
    onlineCompletionSearchRowViews.clear()
    return enabledOnlineCompletionSources(context)
        .take(HOME_SEARCH_RESULT_LIMIT)
        .map { source -> OnlineSearchGroup(source, needle, emptyList(), "搜索中") }
}

internal fun WebDavDriveHook.searchOnlineCompletionSource(
    source: OnlineSourceEntry,
    query: String,
    onParsedResults: ((OnlineSearchGroup) -> Unit)? = null,
): OnlineSearchGroup {
    TaskCancellation.checkCurrent()
    if (source.searchUrl.isBlank()) {
        return OnlineSearchGroup(source, query, emptyList(), "源缺少 searchUrl")
    }
    return runCatching {
        val trace = OnlineSearchTrace.current()
        trace?.stage("rule-url")
        val request = buildOnlineSearchRequest(source, query)
        trace?.stage("rate-limit")
        val response = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            trace?.stage("http-start")
            requestOnlineSearch(source, request)
        }
        TaskCancellation.checkCurrent()
        trace?.stage("parse-results")
        val parsedResults = parseOnlineSearchResults(source, query, response)
            .distinctBy { "${it.name}|${it.author}|${it.detailUrl}" }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<OnlineBookSearchResult>> { indexed ->
                    onlineSearchRelevanceScore(query, indexed.value.name, indexed.value.author)
                }.thenBy { it.index },
            )
            .map { it.value }
            .take(ONLINE_COMPLETION_RESULT_LIMIT)
        if (parsedResults.isNotEmpty()) {
            onParsedResults?.invoke(OnlineSearchGroup(source, query, parsedResults, ""))
        }
        trace?.stage("metadata-enrichment")
        val results = enrichOnlineSearchResultsMetadata(source, parsedResults)
        com.reamicro.fix.online.OnlineSourceStore.requireCurrent(source)
        logWebDav("online completion source ok name=${source.name} results=${results.size} first=${results.firstOrNull()?.name.orEmpty()}")
        OnlineSearchGroup(source, query, results, "")
    }.getOrElse {
        TaskCancellation.rethrowIfCancelled(it)
        HookRuntime.logAlways("ReaMicro online search source error sourceId=${source.id} " +
            "type=${it.javaClass.simpleName} ${OnlineSearchTrace.current()?.diagnostic().orEmpty()}")
        logWebDav("online completion source failed name=${source.name} error=${it.message}")
        OnlineSearchGroup(source, query, emptyList(), it.message.orEmpty().ifBlank { "搜索失败" })
    }
}

internal fun WebDavDriveHook.enrichOnlineSearchResultsMetadata(
    source: OnlineSourceEntry,
    results: List<OnlineBookSearchResult>,
): List<OnlineBookSearchResult> {
    if (results.isEmpty()) return results
    val selected = results.take(ONLINE_COMPLETION_SEARCH_METADATA_ENRICH_LIMIT)
    TaskCancellation.checkCurrent()
    val signal = TaskCancellation.current()?.child() ?: TaskCancellation()
    val generation = onlineSearchTasks.generation
    val enriched = WebDavSearchResults(
        results,
        isCurrent = { !signal.isCancelled && onlineSearchTasks.isCurrent(generation) },
    )
    val latch = CountDownLatch(selected.size)
    try {
        selected.forEachIndexed { index, result ->
            signal.check()
            onlineSearchTasks.metadata(signal, generation, latch::countDown) {
                enriched.publish(index, enrichOnlineSearchResultMetadata(source, result))
            }
        }
        latch.await(ONLINE_COMPLETION_SEARCH_METADATA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        signal.check()
        return enriched.finish()
    } finally {
        enriched.finish()
        signal.cancel()
    }
}

internal fun WebDavDriveHook.enrichOnlineSearchResultMetadata(
    source: OnlineSourceEntry,
    result: OnlineBookSearchResult,
): OnlineBookSearchResult {
    TaskCancellation.checkCurrent()
    if (
        result.detailUrl.isBlank() ||
        (
            result.wordCount.isNotBlank() &&
                result.updateTime.isNotBlank() &&
                result.chapterCount > 0 &&
                result.status.isNotBlank()
            )
    ) return result
    return runCatching {
        val detail = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            requestOnlineSearch(source, result.detailUrl)
        }
        val root = parseOnlineJsonRoot(detail.body) ?: return result
        val rule = runCatching { JSONObject(source.ruleBookInfo) }.getOrNull()
        val node = rule?.optString("init", "").orEmpty()
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { onlineJsonRuleValues(root, it).firstOrNull() }
            ?: root
        val meta = onlineResultMetadata(source, node, rule, detail.url)
        val enriched = result.copy(
            author = result.author.ifBlank {
                rule?.optString("author", "").orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let { onlineRuleValue(node, it, detail.url, source = source).cleanOnlineText() }
                    .orEmpty()
                    .ifBlank { onlineFirstJsonString(node, listOf("author", "bookAuthor", "authorName", "writer")) }
            },
            coverUrl = result.coverUrl.ifBlank {
                rule?.optString("coverUrl", "").orEmpty()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        normalizeOnlineCoverUrl(
                            source,
                            detail.url,
                            onlineRuleValue(node, it, detail.url, source = source),
                        )
                    }
                    .orEmpty()
                    .ifBlank {
                        normalizeOnlineCoverUrl(
                            source,
                            detail.url,
                            onlineFirstJsonString(node, listOf("cover", "coverUrl", "thumb_url", "thumb_uri", "bookCover")),
                        )
                    }
            },
            chapterCount = result.chapterCount.takeIf { it > 0 } ?: meta.chapterCount,
            status = result.status.ifBlank { meta.status },
            wordCount = result.wordCount.ifBlank { meta.wordCount },
            updateTime = result.updateTime.ifBlank { meta.updateTime },
        )
        logWebDav(
            "online search metadata enriched source=${source.name} book=${enriched.name} " +
                "status=${enriched.status} words=${enriched.wordCount} " +
                "chapters=${enriched.chapterCount} update=${enriched.updateTime}",
        )
        enriched
    }.onFailure {
        TaskCancellation.rethrowIfCancelled(it)
        logWebDav(
            "online search metadata enrich failed source=${source.name} book=${result.name} " +
                "url=${result.detailUrl.take(120)} error=${it.javaClass.simpleName}: ${it.message.orEmpty()}",
        )
    }.getOrDefault(result)
}

internal fun WebDavDriveHook.buildOnlineSearchRequest(source: OnlineSourceEntry, query: String): OnlineSearchRequest {
    val text = source.searchUrl.trim()
    val raw = if (text.startsWith("@js:", ignoreCase = true) || text.startsWith("<js>", ignoreCase = true)) {
        text
    } else {
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }
    if (raw.startsWith("@js:", ignoreCase = true) || raw.startsWith("<js>", ignoreCase = true)) {
        val url = buildOnlineSearchUrlFromJs(source, raw, query)
        if (url.isBlank()) error("暂不支持脚本型 searchUrl")
        return OnlineSearchRequest(url)
    }

    val splitIdx = indexOfOptionsBlock(raw)
    val urlPart = if (splitIdx < 0) raw else raw.substring(0, splitIdx).trim()
    val optionsJson = if (splitIdx < 0) null else raw.substring(splitIdx).trim().removePrefix(",").trim()
    val options = parseOptionsJson(optionsJson)
    val charset = options.optString("charset", "UTF-8").ifBlank { "UTF-8" }
    val method = options.optString("method", "GET").ifBlank { "GET" }.uppercase()
    val bodyTemplate = options.optString("body", "").orEmpty()
    val resolvedUrl = applyOnlineTemplate(
        urlPart,
        null,
        sourceBaseUrl(source),
        query,
        encodeQuery = true,
        encodeCharset = charset,
        source = source,
    )
    val url = resolveOnlineUrl(sourceBaseUrl(source), resolvedUrl)
    val body = if (bodyTemplate.isNotBlank()) {
        applyOnlineTemplate(
            bodyTemplate,
            null,
            sourceBaseUrl(source),
            query,
            encodeQuery = true,
            encodeCharset = charset,
            source = source,
        )
    } else null
    val headers = runCatching { options.optJSONObject("headers") }.getOrNull()?.let { h ->
        h.keys().asSequence().associateWith { h.optString(it, "") }
    } ?: emptyMap()
    return OnlineSearchRequest(url, method, body, charset, headers)
}

internal fun WebDavDriveHook.buildOnlineSearchUrl(source: OnlineSourceEntry, query: String): String {
    val text = source.searchUrl.trim()
    val raw = if (text.startsWith("@js:", ignoreCase = true) || text.startsWith("<js>", ignoreCase = true)) {
        text
    } else {
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }
    if (raw.startsWith("@js:", ignoreCase = true) || raw.startsWith("<js>", ignoreCase = true)) {
        return buildOnlineSearchUrlFromJs(source, raw, query)
    }
    val urlPart = raw.substringBefore(",{").substringBefore(", {").trim()
    val resolved = applyOnlineTemplate(
        urlPart,
        null,
        sourceBaseUrl(source),
        query,
        encodeQuery = true,
        source = source,
    )
    return resolveOnlineUrl(sourceBaseUrl(source), resolved)
}

internal fun WebDavDriveHook.buildOnlineSearchUrlFromJs(source: OnlineSourceEntry, raw: String, query: String): String {
    val context = currentApplicationContext() ?: currentContext()
    OnlineSourceTrxsCompat.searchUrl(source, query, page = 1)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    OnlineSourceScriptCompat.resolveSearchUrl(
        sourceUrl = sourceBaseUrl(source),
        rawScript = raw,
        rawQuery = query,
        page = 1,
        loginInfo = OnlineSourceAuth.loginInfo(context, source),
    )?.takeIf { it.isNotBlank() }?.let { return it }
    val templates = Regex("""return\s+`([^`]+)`""").findAll(raw).map { it.groupValues[1] }.toList()
    val selected = when {
        templates.isEmpty() -> return ""
        query.all { it.isDigit() } -> templates.first()
        else -> templates.last()
    }
    return resolveOnlineUrl(
        sourceBaseUrl(source),
        applyOnlineTemplate(
            selected,
            null,
            sourceBaseUrl(source),
            query,
            encodeQuery = true,
            source = source,
        ),
    )
}

internal fun WebDavDriveHook.requestOnlineSearch(source: OnlineSourceEntry, requestUrl: String): OnlineHttpResponse {
    val request = parseOnlineUrlRequestCompat(requestUrl)
    return requestOnlineSearch(source, OnlineSearchRequest(url = request.url, headers = request.headers))
}

internal fun WebDavDriveHook.requestOnlineSearch(source: OnlineSourceEntry, request: OnlineSearchRequest): OnlineHttpResponse {
    return com.reamicro.fix.online.OnlineSourceTask(source).use { operation ->
        operation.run { requestOnlineSearchCurrent(source, request) }
    }
}

private fun WebDavDriveHook.requestOnlineSearchCurrent(source: OnlineSourceEntry, request: OnlineSearchRequest): OnlineHttpResponse {
    TaskCancellation.checkCurrent()
    com.reamicro.fix.online.OnlineSourceStore.requireCurrent(source)
    val requestUrl = request.url
    if (requestUrl.startsWith("data:", ignoreCase = true)) {
        return OnlineHttpResponse(requestUrl, onlineDataUrlStringResponse(requestUrl))
    }
    val charset = runCatching { Charset.forName(request.charset) }.getOrDefault(Charsets.UTF_8)
    val isPost = request.method == "POST" && request.body != null
    return withOnlineCleartextAllowed(requestUrl) {
        var authRetried = false
        while (true) {
            TaskCancellation.checkCurrent()
            val connection = URL(requestUrl).openConnection() as HttpURLConnection
            val cancellation = TaskCancellation.current()?.register(connection::disconnect)
            val failure = try {
                TaskCancellation.checkCurrent()
                connection.requestMethod = if (isPost) "POST" else "GET"
                connection.connectTimeout = onlineConnectTimeoutMillis(source)
                connection.readTimeout = onlineReadTimeoutMillis(source)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                connection.setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml,*/*")
                if (isPost) connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                parseOnlineHeaders(source.header).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
                }
                request.headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
                }
                OnlineSourceAuth.requestHeaders(currentApplicationContext() ?: currentContext(), source, requestUrl).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
                }
                TaskCancellation.checkCurrent()
                if (isPost) {
                    connection.doOutput = true
                    val bodyBytes = request.body!!.toByteArray(charset)
                    connection.setRequestProperty("Content-Length", bodyBytes.size.toString())
                    connection.outputStream.use {
                        TaskCancellation.checkCurrent()
                        it.write(bodyBytes)
                    }
                }
                TaskCancellation.checkCurrent()
                OnlineSearchTrace.current()?.stage("http-headers")
                val code = connection.responseCode
                OnlineSearchTrace.current()?.stage("http-body")
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(charset)?.use { reader ->
                    com.reamicro.fix.online.download.readOnlineResponseText(reader, checkCurrent = {
                        TaskCancellation.checkCurrent()
                        com.reamicro.fix.online.OnlineSourceStore.requireCurrent(source)
                    })
                }.orEmpty()
                TaskCancellation.checkCurrent()
                com.reamicro.fix.online.OnlineSourceStore.requireCurrent(source)
                if (code in 200..299) return OnlineHttpResponse(connection.url.toString(), body)
                val reason = onlineHttpErrorDetail(body).ifBlank {
                    if (code == 401 || code == 403) "未登录或会员权限不足" else ""
                }
                OnlineSourceHttpException(
                    statusCode = code,
                    detail = reason,
                    retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.times(1_000L),
                )
            } catch (error: Throwable) {
                TaskCancellation.rethrowIfCancelled(error)
                throw error
            } finally {
                cancellation?.close()
                connection.disconnect()
            }
            TaskCancellation.checkCurrent()
            if ((failure.statusCode == 401 || failure.statusCode == 403) && !authRetried && source.hasLoginConfig) {
                OnlineSearchTrace.current()?.stage("auth-retry")
                val login = OnlineSourceAuth.loginWithSavedCredentials(currentApplicationContext() ?: currentContext(), source)
                TaskCancellation.checkCurrent()
                logWebDav(
                    "online completion auth retry source=${source.name} code=${failure.statusCode} " +
                        "success=${login.success} message=${login.message}",
                )
                authRetried = true
                if (login.success) continue
            }
            throw failure
        }
        @Suppress("UNREACHABLE_CODE")
        error("Online request finished without a response")
    }
}

internal fun WebDavDriveHook.parseOnlineHeaders(raw: String): Map<String, String> {
    val text = raw.trim()
    if (text.isBlank()) return emptyMap()

    if (text.startsWith("@js:", ignoreCase = true) || text.startsWith("<js>", ignoreCase = true)) {
        return emptyMap()
    }
    return runCatching {
        val json = JSONObject(text)
        json.keys().asSequence()
            .filter { isValidHttpHeaderName(it) }
            .associateWith { key -> json.optString(key, "") }
    }.getOrElse {
        text.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val name = line.take(index).trim()
                if (!isValidHttpHeaderName(name)) return@mapNotNull null
                name to line.substring(index + 1).trim()
            }
            .toMap()
    }
}

internal fun WebDavDriveHook.isValidHttpHeaderName(name: String): Boolean =
    name.isNotBlank() && HTTP_HEADER_NAME_REGEX.matches(name)

internal fun WebDavDriveHook.parseOnlineSearchResults(
    source: OnlineSourceEntry,
    query: String,
    response: OnlineHttpResponse,
): List<OnlineBookSearchResult> {
    val body = response.body.trim()
    if (body.isBlank()) return emptyList()
    return if (body.startsWith("{") || body.startsWith("[")) {
        parseOnlineJsonResults(source, response.url, body)
    } else {
        parseOnlineHtmlResults(source, query, response.url, body)
    }
}

internal fun WebDavDriveHook.parseOnlineJsonResults(
    source: OnlineSourceEntry,
    baseUrl: String,
    body: String,
): List<OnlineBookSearchResult> {
    val root = parseOnlineJsonRoot(body) ?: return emptyList()
    parseOnlineJsonResultsByRule(source, baseUrl, root).takeIf { it.isNotEmpty() }?.let { return it }
    val results = mutableListOf<OnlineBookSearchResult>()
    fun visit(value: Any?) {
        if (results.size >= ONLINE_COMPLETION_RESULT_LIMIT) return
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            is JSONObject -> {
                jsonObjectToOnlineResult(source, baseUrl, value)?.let(results::add)
                value.keys().asSequence().forEach { key ->
                    val child = value.opt(key)
                    if (child is JSONArray || child is JSONObject) visit(child)
                }
            }
        }
    }
    visit(root)
    return results
}

internal fun WebDavDriveHook.parseOnlineJsonResultsByRule(
    source: OnlineSourceEntry,
    baseUrl: String,
    root: Any,
): List<OnlineBookSearchResult> {
    val rule = runCatching { JSONObject(source.ruleSearch) }.getOrNull() ?: return emptyList()
    val nodes = onlineJsonRuleValues(root, rule.optString("bookList", ""))
    if (nodes.isEmpty()) return emptyList()
    return nodes.mapNotNull { node ->
        val name = onlineRuleValue(node, rule.optString("name", ""), baseUrl, source = source).cleanOnlineText()
        if (name.isBlank() || name.length > 120) return@mapNotNull null
        val author = onlineRuleValue(node, rule.optString("author", ""), baseUrl, source = source).cleanOnlineText()
        val detail = OnlineSourceTrxsCompat.detailUrl(source, node)
            ?: onlineRuleValue(node, rule.optString("bookUrl", ""), baseUrl, source = source)
        val cover = onlineRuleValue(node, rule.optString("coverUrl", ""), baseUrl, source = source)
        val intro = onlineRuleValue(node, rule.optString("intro", ""), baseUrl, source = source).cleanOnlineText()
        val meta = onlineResultMetadata(source, node, rule, baseUrl)
        OnlineBookSearchResult(
            sourceName = source.name,
            name = name,
            author = author,
            coverUrl = normalizeOnlineCoverUrl(source, baseUrl, cover),
            detailUrl = resolveOnlineUrl(baseUrl, detail),
            intro = intro,
            chapterCount = meta.chapterCount,
            status = meta.status,
            wordCount = meta.wordCount,
            updateTime = meta.updateTime,
            platformName = meta.platformName,
        )
    }.distinctBy { "${it.name}|${it.author}|${it.detailUrl}" }
        .take(ONLINE_COMPLETION_RESULT_LIMIT)
}

internal fun WebDavDriveHook.jsonObjectToOnlineResult(
    source: OnlineSourceEntry,
    baseUrl: String,
    json: JSONObject,
): OnlineBookSearchResult? {
    val name = firstJsonString(json, "bookName", "name", "title", "bookTitle", "novelName").cleanOnlineText()
    if (name.isBlank() || name.length > 120) return null
    val author = firstJsonString(json, "author", "bookAuthor", "writer", "authorName").cleanOnlineText()
    val detail = firstJsonString(json, "bookUrl", "detailUrl", "url", "href", "link")
    val cover = firstJsonString(json, "coverUrl", "cover", "img", "image", "bookCover")
    val intro = firstJsonString(json, "intro", "desc", "description", "bookDesc").cleanOnlineText()
    val meta = onlineResultMetadata(source, json, null, baseUrl)
    return OnlineBookSearchResult(
        sourceName = source.name,
        name = name,
        author = author,
        coverUrl = normalizeOnlineCoverUrl(source, baseUrl, cover),
        detailUrl = resolveOnlineUrl(baseUrl, detail),
        intro = intro,
        chapterCount = meta.chapterCount,
        status = meta.status,
        wordCount = meta.wordCount,
        updateTime = meta.updateTime,
        platformName = meta.platformName,
    )
}

internal fun WebDavDriveHook.onlineResultMetadata(
    source: OnlineSourceEntry,
    node: Any?,
    rule: JSONObject?,
    baseUrl: String,
    chapterCountFallback: Int = 0,
    loginInfo: Map<String, String>? = null,
): OnlineResultMetadata {
    val kind = rule?.optString("kind", "").orEmpty()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source, loginInfo = loginInfo) }
        .orEmpty()
    val wordCountRule = rule?.optString("wordCount", "").orEmpty()
        .takeUnless(::isOnlineChapterCountSelector)
        .orEmpty()
    val wordCount = wordCountRule
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source, loginInfo = loginInfo) }
        .orEmpty()
        .ifBlank { onlineFirstJsonString(node, ONLINE_WORD_COUNT_FIELDS) }
    val updateTime = rule?.optString("updateTime", "").orEmpty()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source, loginInfo = loginInfo) }
        .orEmpty()
        .ifBlank { onlineFirstJsonString(node, ONLINE_UPDATE_TIME_FIELDS) }
    val chapterCount = onlineFirstJsonInt(node, ONLINE_CHAPTER_COUNT_FIELDS)
        .takeIf { it > 0 }
        ?: chapterCountFallback.coerceAtLeast(0)
    val statusHint = rule?.optString("lastChapter", "").orEmpty()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source, loginInfo = loginInfo) }
        .orEmpty()
    val status = onlineCompletionStatusText(kind, node, statusHint)
    val platformName = if (source.isWanFengLiSource()) {
        onlineCompletionPlatformName(node, kind)
    } else {
        ""
    }
    logOnlineMetadataGaps(source, node, status, wordCount, updateTime, chapterCount)
    return OnlineResultMetadata(
        status = status,
        wordCount = formatOnlineWordCount(wordCount),
        updateTime = formatOnlineUpdateTime(updateTime),
        chapterCount = chapterCount,
        platformName = platformName,
    )
}

internal fun WebDavDriveHook.onlineRuleValue(
    node: Any?,
    rawRule: String,
    baseUrl: String,
    query: String? = null,
    source: OnlineSourceEntry? = null,
    loginInfo: Map<String, String>? = null,
): String {
    var rule = rawRule.trim()
    if (rule.isBlank() || node == null || node == JSONObject.NULL) return ""
    if (rule.startsWith("<js>", ignoreCase = true)) return ""
    if (rule.startsWith("@js:", ignoreCase = true)) {
        if (rule.contains("replaceCover", ignoreCase = true)) {
            return replaceFanqieCover(
                onlineRuleValue(node, "thumb_url", baseUrl, source = source, loginInfo = loginInfo),
            )
        }
        return ""
    }
    val inlineTagIndex = rule.indexOf("<js>", ignoreCase = true)
    if (inlineTagIndex > 0) {
        val selectorRule = rule.substring(0, inlineTagIndex).trim()
        val selectedValue = onlineRuleValue(
            node,
            selectorRule,
            baseUrl,
            query,
            source,
            loginInfo,
        )
        OnlineSourceScriptCompat.resolveInlineResultUrl(rule, selectedValue)?.let { return it }
    }
    val inlineJsIndex = rule.indexOf("\n@js:", ignoreCase = true)
    if (inlineJsIndex >= 0) {
        val selectorRule = rule.substring(0, inlineJsIndex).trim()
        val selectedValue = onlineRuleValue(
            node,
            selectorRule,
            baseUrl,
            query,
            source,
            loginInfo,
        )
        evaluateQqReaderCoverRule(rule, selectedValue)?.let { return it }
    }
    rule = rule.substringBefore("\n<js>", rule).substringBefore("\n@js:", rule).trim()
    if (rule.contains("{{") && rule.contains("}}")) {
        return applyOnlineTemplate(
            rule,
            node,
            baseUrl,
            query,
            source = source,
            loginInfo = loginInfo,
        )
    }
    val parts = rule.split("##", limit = 3)
    val selector = parts.firstOrNull().orEmpty().trim()
    val value = selector.split("||")
        .asSequence()
        .map { candidate -> onlineJsonString(node, candidate.trim()) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    if (parts.size >= 2 && parts[1].isNotBlank()) {
        return runCatching {
            value.replace(Regex(parts[1]), parts.getOrNull(2).orEmpty())
        }.getOrDefault(value)
    }
    return value
}

internal fun WebDavDriveHook.applyOnlineTemplate(
    raw: String,
    node: Any?,
    baseUrl: String,
    query: String?,
    page: Int = 1,
    encodeQuery: Boolean = false,
    encodeCharset: String = "UTF-8",
    source: OnlineSourceEntry? = null,
    loginInfo: Map<String, String>? = null,
): String {
    val effectiveLoginInfo = loginInfo ?: source?.let {
        OnlineSourceAuth.loginInfo(currentApplicationContext() ?: currentContext(), it)
    }.orEmpty()
    val sourceUrl = source?.sourceUrl.orEmpty().ifBlank { baseUrl }
    var text = raw
        .replace("{{(page-1)*10}}", ((page - 1) * 10).toString())
        .replace("{{(page - 1) * 10}}", ((page - 1) * 10).toString())
        .replace("{{page}}", page.toString())
    if (query != null) {
        val key = if (encodeQuery) URLEncoder.encode(query, encodeCharset) else query
        listOf("{{key}}", "{{keyword}}", "{{searchKey}}", "{key}", "{keyword}", "%s").forEach { token ->
            text = text.replace(token, key, ignoreCase = true)
        }
    }
    return Regex("""\{\{([^{}]+)\}\}""").replace(text) { match ->
        val expr = match.groupValues[1].trim()
        val sourceExpression = OnlineSourceLoginConfig.expressionValue(expr, sourceUrl, effectiveLoginInfo)
        when {
            sourceExpression != null -> sourceExpression
            Regex("""^baseUrl\s*\+\s*['"]([^'"]*)['"]$""").matchEntire(expr) != null ->
                baseUrl.trimEnd('/') + Regex("""^baseUrl\s*\+\s*['"]([^'"]*)['"]$""")
                    .matchEntire(expr)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
            expr.startsWith("$") || expr.startsWith(".") -> onlineJsonString(node, expr)
            expr.equals("page", ignoreCase = true) -> page.toString()
            expr.equals("key", ignoreCase = true) || expr.equals("keyword", ignoreCase = true) ->
                query?.let { if (encodeQuery) URLEncoder.encode(it, encodeCharset) else it }.orEmpty()

            Regex("""^java\.put\(\s*['"][^'"]*['"]\s*,\s*(key|keyword|searchKey)\s*\)$""", RegexOption.IGNORE_CASE)
                .matchEntire(expr) != null ->
                query?.let { if (encodeQuery) URLEncoder.encode(it, encodeCharset) else it }.orEmpty()
            else -> ""
        }
    }
}

internal fun WebDavDriveHook.normalizeOnlineCoverUrl(source: OnlineSourceEntry, baseUrl: String, value: String): String {
    val raw = com.reamicro.fix.online.search.directOnlineCoverUrl(value.trim())
    if (raw.isBlank()) return ""

    if (!raw.startsWith("http", ignoreCase = true) && FANQIE_IMAGE_PATH_REGEX.containsMatchIn(raw)) {
        replaceFanqieCover(raw).takeIf { it.isNotBlank() }?.let { return it }
    }
    val resolved = resolveOnlineUrl(baseUrl.ifBlank { sourceBaseUrl(source) }, raw)

    runCatching {
        val uri = URI(resolved)
        val host = uri.host.orEmpty()
        if (host.endsWith("byteimg.com", ignoreCase = true) ||
            host.endsWith("fqnovel.com", ignoreCase = true) ||
            host.endsWith("fanqienovel.com", ignoreCase = true)
        ) {
            uri.rawPath.orEmpty()
                .substringAfterLast("/novel-pic/", "")
                .takeIf { it.isNotBlank() }
                ?.let { replaceFanqieCover("novel-pic/$it").takeIf { u -> u.isNotBlank() }?.let { u -> return u } }
        }
    }
    if (!resolved.startsWith("http://", ignoreCase = true)) {

        val misrouted = runCatching { URI(resolved) }.getOrNull()
        val apiHost = runCatching { URI(sourceBaseUrl(source).ifBlank { baseUrl }) }.getOrNull()?.host
        if (misrouted != null && !apiHost.isNullOrBlank() &&
            misrouted.host.equals(apiHost, ignoreCase = true) &&
            FANQIE_IMAGE_PATH_REGEX.containsMatchIn(misrouted.rawPath.orEmpty())
        ) {
            replaceFanqieCover(misrouted.rawPath.orEmpty()).takeIf { it.isNotBlank() }?.let { return it }
        }
        return resolved
    }
    val sourceUrl = sourceBaseUrl(source).ifBlank { baseUrl }
    val sourceUri = runCatching { URI(sourceUrl) }.getOrNull()
    if (!sourceUri?.scheme.equals("https", ignoreCase = true)) return resolved
    val coverUri = runCatching { URI(resolved) }.getOrNull() ?: return resolved
    if (!coverUri.host.equals(sourceUri?.host.orEmpty(), ignoreCase = true)) return resolved
    return runCatching {
        URI(
            "https",
            coverUri.userInfo,
            coverUri.host,
            coverUri.port,
            coverUri.rawPath,
            coverUri.rawQuery,
            coverUri.rawFragment,
        ).toString()
    }.getOrDefault(resolved)
}

internal fun WebDavDriveHook.updateHomeOnlineCompletionSearchResults(
    viewModel: Any?,
    onlineResults: List<Any>,
    isCurrent: () -> Boolean = { !storageClosed && settingsProvider().moduleEnabled },
) {
    if (viewModel == null || !isCurrent()) return
    rememberHomeViewModelDependencies(viewModel)
    runCatching {
        com.reamicro.fix.core.HostHomeSearchBindings.requireCurrent().update(viewModel) { state ->
                if (!isCurrent()) return@update state
                runCatching {
                    val current = homeCloudSearchResults(state)
                    val merged = com.reamicro.fix.core.mergeHomeSearchResults(
                        current, mapOf(BACKUP_TYPE_ONLINE_COMPLETION to onlineResults),
                    )
                    if (!isCurrent() || merged == current) state else copyHomeUiStateWithCloudResults(state, merged)
                }.onFailure {
                    HookRuntime.log("$LOG_PREFIX failed to copy online completion search state: ${it.stackTraceToString()}")
                }.getOrDefault(state)
        }
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX failed to update online completion search state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.handleOnlineCompletionSearchTap(book: Any, query: Long = webDavHomeSearchSeq.get()): Boolean {
    if (!homeSearch.isCurrent(query)) return false
    val path = cloudPathOf(book)
    HookRuntime.logAlways("ReaMicro online search download click sequence=$query")
    logWebDav("online completion tap path=$path")
    return when {
        path.startsWith(ONLINE_COMPLETION_SOURCE_PREFIX) -> {
            onlineCompletionSearchTargets[path]?.let { target ->
                showOnlineCompletionDownloadModeDialog(target)
                return true
            }
            Toast.makeText(
                currentContext(),
                "该在线补全源暂无可下载结果",
                Toast.LENGTH_SHORT,
            ).show()
            true
        }
        path.startsWith(ONLINE_COMPLETION_BOOK_PREFIX) -> {
            val target = onlineCompletionSearchTargets[path]
            if (target == null) {
                logWebDav("online completion target expired path=$path known=${onlineCompletionSearchTargets.size}")
                Toast.makeText(currentContext(), "在线补全结果已过期，请重新搜索", Toast.LENGTH_SHORT).show()
            } else {
                showOnlineCompletionDownloadModeDialog(target)
            }
            true
        }
        else -> false
    }
}

internal fun WebDavDriveHook.enrichOnlineTargetFromDetail(
    target: OnlineDownloadTarget,
    detailBody: String,
    detailUrl: String,
    chapterCount: Int,
) {
    val startedAt = System.nanoTime()
    val root = parseOnlineJsonRoot(detailBody) ?: return
    val bookInfoRule = runCatching { JSONObject(target.source.ruleBookInfo) }.getOrNull()
    val node = bookInfoRule?.optString("init", "").orEmpty()
        .trim()
        .takeIf { it.isNotBlank() }
        ?.let { onlineJsonRuleValues(root, it).firstOrNull() }
        ?: root
    val loginInfo = OnlineSourceAuth.loginInfo(currentApplicationContext() ?: currentContext(), target.source)
    val meta = onlineResultMetadata(
        source = target.source,
        node = node,
        rule = bookInfoRule,
        baseUrl = detailUrl,
        chapterCountFallback = chapterCount,
        loginInfo = loginInfo,
    )
    val current = target.result
    val enriched = current.copy(
        author = current.author.ifBlank {
            bookInfoRule?.optString("author", "").orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let {
                    onlineRuleValue(
                        node,
                        it,
                        detailUrl,
                        source = target.source,
                        loginInfo = loginInfo,
                    ).cleanOnlineText()
                }
                .orEmpty()
                .ifBlank { onlineFirstJsonString(node, listOf("author", "bookAuthor", "authorName", "writer")) }
        },
        coverUrl = current.coverUrl.ifBlank {
            bookInfoRule?.optString("coverUrl", "").orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let {
                    normalizeOnlineCoverUrl(
                        target.source,
                        detailUrl,
                        onlineRuleValue(
                            node,
                            it,
                            detailUrl,
                            source = target.source,
                            loginInfo = loginInfo,
                        ),
                    )
                }
                .orEmpty()
                .ifBlank {
                    normalizeOnlineCoverUrl(
                        target.source,
                        detailUrl,
                        onlineFirstJsonString(node, listOf("cover", "coverUrl", "thumb_url", "thumb_uri", "bookCover")),
                    )
                }
        },
        intro = current.intro.ifBlank {
            bookInfoRule?.optString("intro", "").orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let {
                    onlineRuleValue(
                        node,
                        it,
                        detailUrl,
                        source = target.source,
                        loginInfo = loginInfo,
                    ).cleanOnlineText()
                }
                .orEmpty()
                .ifBlank { onlineFirstJsonString(node, listOf("intro", "abstract", "description", "bookDesc")) }
        },
        chapterCount = current.chapterCount.takeIf { it > 0 } ?: meta.chapterCount,
        status = current.status.ifBlank { meta.status },
        wordCount = current.wordCount.ifBlank { meta.wordCount },
        updateTime = current.updateTime.ifBlank { meta.updateTime },
        platformName = current.platformName.ifBlank { meta.platformName },
    )
    target.result = enriched
    logWebDav(
        "online metadata enriched source=${target.source.name} book=${enriched.name} " +
            "status=${enriched.status} words=${enriched.wordCount} " +
            "chapters=${enriched.chapterCount} update=${enriched.updateTime} " +
            "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}",
    )
}

internal fun WebDavDriveHook.parseOnlineToc(
    source: OnlineSourceEntry,
    result: OnlineBookSearchResult,
    baseUrl: String,
    html: String,
): List<OnlineChapter> {
    OnlineSourceTrxsCompat.catalogChapters(source, baseUrl, html)?.let { chapters ->
        return chapters.filterNot { it.isVolume }.map { chapter ->
            OnlineChapter(
                title = chapter.title,
                url = chapter.url,
                volumeTitle = chapter.volumeTitle,
                level = if (chapter.volumeTitle.isBlank()) 0 else 1,
            )
        }
    }
    val body = html.trim()
    if (body.startsWith("{") || body.startsWith("[")) {
        val root = parseOnlineJsonRoot(body)
        val chapters = root?.let { parseOnlineJsonToc(source, result, baseUrl, it) }.orEmpty()
        if (chapters.isNotEmpty()) return chapters
    }
    val anchors = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
        .findAll(html)
        .mapNotNull { match ->
            val title = cleanOnlineChapterTitleValue(match.groupValues[2].cleanOnlineText())
            if (title.isBlank() || title.length > 80) return@mapNotNull null
            val href = resolveOnlineUrl(baseUrl, match.groupValues[1])
            if (href.isBlank()) return@mapNotNull null
            val looksChapter = ONLINE_CHAPTER_TITLE_REGEX.containsMatchIn(title) ||
                href.contains("chapter", ignoreCase = true) ||
                href.contains("read", ignoreCase = true)
            if (!looksChapter) return@mapNotNull null
            OnlineChapter(title, href)
        }
        .distinctBy { it.url }
        .toList()
    if (anchors.isNotEmpty()) return anchors
    return Regex("""(?is)<option\b[^>]*\bvalue\s*=\s*["']([^"']+)["'][^>]*>(.*?)</option>""")
        .findAll(html)
        .mapNotNull { match ->
            val title = cleanOnlineChapterTitleValue(match.groupValues[2].cleanOnlineText())
            val href = resolveOnlineUrl(baseUrl, match.groupValues[1])
            if (title.isBlank() || href.isBlank()) null else OnlineChapter(title, href)
        }
        .distinctBy { it.url }
        .toList()
}

internal fun WebDavDriveHook.parseOnlineJsonToc(
    source: OnlineSourceEntry,
    result: OnlineBookSearchResult,
    baseUrl: String,
    root: Any,
): List<OnlineChapter> {
    val startedAt = System.nanoTime()
    val rule = runCatching { JSONObject(source.ruleToc) }.getOrNull() ?: return emptyList()
    val chapterListRule = rule.optString("chapterList", "")
    val compat = resolveOnlineChapterListRuleCompat(chapterListRule)
    val rawNodes = onlineJsonRuleValues(root, compat?.jsonPath ?: chapterListRule)
    val selectedNodes = rawNodes.ifEmpty { shuqiChapterListNodes(root, chapterListRule) }
    val nodes = compat?.let { applyOnlineChapterListRuleCompat(selectedNodes, it) } ?: selectedNodes
    logWebDav(
        "online completion toc nodes=${nodes.size} rule=${chapterListRule.take(120)} " +
            "compat=${compat?.jsonPath.orEmpty()} root=${root.javaClass.simpleName} " +
            "raw=${rawNodes.size} selector=${chapterListRule.substringBefore("<js>").trim()}",
    )
    if (nodes.isEmpty()) return emptyList()
    val titleRule = rule.optString("chapterName", "")
    val urlRule = rule.optString("chapterUrl", "")
    val isVolumeRule = rule.optString("isVolume", "")
    val loginStartedAt = System.nanoTime()
    val loginInfo = OnlineSourceAuth.loginInfo(currentApplicationContext() ?: currentContext(), source)
    logWebDav(
        "online completion toc login info fields=${loginInfo.size} " +
            "elapsedMs=${(System.nanoTime() - loginStartedAt) / 1_000_000}",
    )
    var currentVolumeTitle = ""
    var titleNs = 0L
    var urlNs = 0L
    var volumeNs = 0L
    var buildNs = 0L
    var volumeTitleNs = 0L
    val chapters = nodes.mapIndexedNotNull { index, node ->
        val titleStartedAt = System.nanoTime()
        val rawTitle = cleanOnlineChapterTitleValue(
            onlineRuleValue(
                node,
                titleRule,
                baseUrl,
                source = source,
                loginInfo = loginInfo,
            ).cleanOnlineText(),
        )
        titleNs += System.nanoTime() - titleStartedAt
        val urlStartedAt = System.nanoTime()
        val ruleRawUrl = onlineRuleValue(
            node,
            urlRule,
            baseUrl,
            source = source,
            loginInfo = loginInfo,
        )
        urlNs += System.nanoTime() - urlStartedAt
        val fallbackRawUrl = if (ruleRawUrl.isBlank()) fallbackOnlineChapterRawUrl(node) else ""
        val rawUrl = ruleRawUrl.ifBlank { fallbackRawUrl }
        val volumeStartedAt = System.nanoTime()
        if (
            isOnlineTocVolumeNode(
                source,
                node,
                isVolumeRule,
                rawTitle,
                rawUrl.isNotBlank(),
                baseUrl,
                loginInfo,
            )
        ) {
            currentVolumeTitle = rawTitle
            return@mapIndexedNotNull null
        }
        volumeNs += System.nanoTime() - volumeStartedAt
        val title = rawTitle.ifBlank { "第 ${index + 1} 章" }
        if (index == 0) {
            logWebDav(
                "online completion toc first title=$title urlRuleLen=${urlRule.length} " +
                    "ruleRaw=${ruleRawUrl.take(80)} fallbackRaw=${fallbackRawUrl.take(80)}",
            )
        }
        val buildStartedAt = System.nanoTime()
        val url = buildOnlineChapterUrl(source, baseUrl, urlRule, rawUrl, loginInfo)
        buildNs += System.nanoTime() - buildStartedAt
        val volumeTitleStartedAt = System.nanoTime()
        val volumeTitle = onlineChapterVolumeTitle(node).ifBlank { currentVolumeTitle }
        volumeTitleNs += System.nanoTime() - volumeTitleStartedAt
        if (url.isBlank()) null else OnlineChapter(
            title = title,
            url = url,
            volumeTitle = volumeTitle,
            level = if (volumeTitle.isBlank()) 0 else 1,
        )
    }.distinctBy { it.url }
    logWebDav(
        "online completion toc mapped nodes=${nodes.size} chapters=${chapters.size} " +
            "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} " +
            "phases titleMs=${titleNs / 1_000_000} urlMs=${urlNs / 1_000_000} " +
            "volumeMs=${volumeNs / 1_000_000} buildMs=${buildNs / 1_000_000} " +
            "volumeTitleMs=${volumeTitleNs / 1_000_000}",
    )
    return chapters
}

internal fun WebDavDriveHook.onlineCompletionSearchTextColors(composer: Any): IntArray =
    runCatching {
        val scheme = colorScheme(composer)
        val title = composeColorToArgb(scheme.longMethod("getOnBackground"))
        val author = runCatching {
            val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
            composeColorToArgb(ui.call("onBackgroundVariant", scheme) as Long)
        }
            .getOrElse { title }
        intArrayOf(title, author, withAlpha(author, 210))
    }.getOrElse {
        HookRuntime.log("$LOG_PREFIX failed to resolve online completion search theme colors: ${it.stackTraceToString()}")
        onlineCompletionSearchTextColors(currentContext()?.isNightMode() ?: activityProvider()?.isNightMode() ?: false)
    }

internal fun WebDavDriveHook.onlineCompletionSearchTextColors(dark: Boolean): IntArray =
    if (dark) {
        intArrayOf(
            Color.rgb(232, 234, 238),
            Color.rgb(188, 194, 204),
            Color.rgb(146, 153, 164),
        )
    } else {
        intArrayOf(
            Color.rgb(34, 34, 34),
            Color.rgb(92, 96, 104),
            Color.rgb(132, 132, 132),
        )
    }
