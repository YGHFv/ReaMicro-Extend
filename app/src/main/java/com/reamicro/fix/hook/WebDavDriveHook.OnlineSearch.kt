package com.reamicro.fix.hook

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
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
import com.reamicro.fix.online.OnlineJsonPathCompat
import com.reamicro.fix.online.OnlineSourceScriptCompat
import com.reamicro.fix.online.OnlineSourceTrxsCompat
import com.reamicro.fix.webdav.applyOnlineChapterListRuleCompat
import com.reamicro.fix.webdav.cleanOnlineChapterTitleValue
import com.reamicro.fix.webdav.evaluateQqReaderCoverRule
import com.reamicro.fix.webdav.formatOnlineWordCountValue
import com.reamicro.fix.webdav.inferOnlineStatusFromLastChapterTitle
import com.reamicro.fix.webdav.isOnlineChapterCountSelector
import com.reamicro.fix.webdav.onlineSearchRelevanceScore
import com.reamicro.fix.webdav.onlineHttpErrorDetail
import com.reamicro.fix.webdav.OnlineSourceHttpException
import com.reamicro.fix.webdav.parseOnlineUrlRequestCompat
import com.reamicro.fix.webdav.resolveOnlineChapterListRuleCompat
import com.reamicro.fix.webdav.resolveOnlineUrlCompat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*

// WebDavDriveHook 的在线源搜索簇。
//
// 按书源规则构造搜索请求、解析 JSON/HTML 结果、补全字数章节数等元数据。
//
// 从 WebDavDriveHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun WebDavDriveHook.renderOnlineCompletionCloudBookSearchRow(book: Any, target: OnlineDownloadTarget, composer: Any) {
    val textColors = onlineCompletionSearchTextColors(composer)
    val factory = functionProxy("OnlineCompletionSearchRowAndroidView", FUNCTION1_CLASS) { args ->
        val context = args?.getOrNull(0) as? Context
            ?: activityProvider()
            ?: error("No context for online completion search row")
        createOnlineCompletionSearchRowView(context, book, target, textColors)
    }
    val update = functionProxy("OnlineCompletionSearchRowUpdate", FUNCTION1_CLASS) { args ->
        (args?.getOrNull(0) as? View)?.let { view ->
            val latestTarget = onlineCompletionSearchTargets[cloudPathOf(book)] ?: target
            applyOnlineCompletionSearchRow(view, latestTarget, textColors)
        }
        targetUnit()
    }
    runCatching {
        cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(
            null,
            factory,
            fillMaxWidthModifier(),
            update,
            composer,
            0,
            0,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render online completion search row: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.createOnlineCompletionSearchRowView(
    context: Context,
    book: Any,
    target: OnlineDownloadTarget,
    textColors: IntArray,
): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(92)
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(12))
        setOnClickListener { handleOnlineCompletionSearchTap(book) }
    }
    val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedDrawable(Color.rgb(232, 232, 232), context.dp(4).toFloat())
    }
    row.addView(cover, LinearLayout.LayoutParams(context.dp(48), context.dp(66)).apply {
        rightMargin = context.dp(14)
    })
    loadOnlineCompletionSearchCover(cover, target.source, target.result.coverUrl)

    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val titleView = TextView(context).apply {
        text = target.result.name.ifBlank { "未命名" }
        setTextColor(textColors[0])
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    texts.addView(titleView)
    val authorView = TextView(context).apply {
        text = target.result.author.ifBlank { "未知作者" }
        setTextColor(textColors[1])
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(0, context.dp(5), 0, 0)
    }
    texts.addView(authorView)
    val metaView = TextView(context).apply {
        text = onlineCompletionSearchMetaLine(target.result)
        setTextColor(textColors[2])
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(0, context.dp(5), 0, 0)
    }
    texts.addView(metaView)
    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val path = cloudPathOf(book)
    onlineCompletionSearchRowViews[path] = WeakReference(row)
    // 详情请求可能在 AndroidView 真正创建前已经完成。登记 View 后再读取一次最新结果，
    // 避免工厂闭包携带的旧结果覆盖已补全的章节数、字数等信息。
    onlineCompletionSearchTargets[path]
        ?.takeIf { it != target }
        ?.let { latestTarget -> applyOnlineCompletionSearchRow(row, latestTarget, colors = null) }
    return row
}

internal fun WebDavDriveHook.applyOnlineCompletionSearchRow(row: View, target: OnlineDownloadTarget, colors: IntArray?) {
    val cover = (row as? ViewGroup)?.getChildAt(0) as? ImageView
    if (cover != null) loadOnlineCompletionSearchCover(cover, target.source, target.result.coverUrl)
    val textContainer = (row as? ViewGroup)?.getChildAt(1) as? ViewGroup ?: return
    (textContainer.getChildAt(0) as? TextView)?.apply {
        text = target.result.name.ifBlank { "未命名" }
        colors?.getOrNull(0)?.let { setTextColor(it) }
    }
    (textContainer.getChildAt(1) as? TextView)?.apply {
        text = target.result.author.ifBlank { "未知作者" }
        colors?.getOrNull(1)?.let { setTextColor(it) }
    }
    (textContainer.getChildAt(2) as? TextView)?.apply {
        text = onlineCompletionSearchMetaLine(target.result)
        colors?.getOrNull(2)?.let { setTextColor(it) }
    }
}

internal fun WebDavDriveHook.updateOnlineCompletionSearchTarget(path: String, target: OnlineDownloadTarget) {
    onlineCompletionSearchTargets[path] = target
    val rowRef = onlineCompletionSearchRowViews[path] ?: return
    Handler(Looper.getMainLooper()).post {
        val row = rowRef.get()
        if (row == null) {
            onlineCompletionSearchRowViews.remove(path, rowRef)
            return@post
        }
        val latestTarget = onlineCompletionSearchTargets[path] ?: return@post
        applyOnlineCompletionSearchRow(row, latestTarget, colors = null)
    }
}

internal fun WebDavDriveHook.loadOnlineCompletionSearchCover(imageView: ImageView, source: OnlineSourceEntry, coverUrl: String) {
    val url = normalizeOnlineCoverUrl(source, sourceBaseUrl(source), coverUrl).trim()
    if (url.isBlank()) return
    if (imageView.tag == url) return
    imageView.tag = url
    Thread({
        runCatching {
            decodeOnlineDataImage(url)?.let { return@runCatching it }
            val connection = withOnlineCleartextAllowed(url) {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4_000
                    readTimeout = 6_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    parseOnlineHeaders(source.header).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                    }
                    OnlineSourceAuth.requestHeaders(currentApplicationContext() ?: currentContext(), source, url).forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                    }
                }
            }
            try {
                connection.inputStream.use { input -> BitmapFactory.decodeStream(input) }
            } finally {
                connection.disconnect()
            }
        }.onSuccess { bitmap ->
            if (bitmap != null) {
                Handler(Looper.getMainLooper()).post {
                    if (imageView.tag == url) imageView.setImageBitmap(bitmap)
                }
            }
        }.onFailure {
            logWebDav("online completion search cover failed url=${url.take(120)} error=${it.message.orEmpty()}")
        }
    }, "ReaMicroOnlineSearchCover").start()
}

internal fun WebDavDriveHook.sanitizeHostCloudSearchResults(param: XC_MethodHook.MethodHookParam, map: Map<*, *>) {
    runCatching {
        // 仅保留 host 原生可渲染的键（WebDAV / 本地库），补全键交给我们自己渲染。
        val hasOnlineEntry = map.containsKey(BACKUP_TYPE_ONLINE_COMPLETION)
        if (!hasOnlineEntry) return
        val sanitized = LinkedHashMap<Any?, Any?>()
        map.forEach { (key, value) ->
            if (key == BACKUP_TYPE_ONLINE_COMPLETION) return@forEach
            // 空列表也剔除，避免任何 host 版本对空 list 做 get(0)
            if (value is List<*> && value.isEmpty()) return@forEach
            sanitized[key] = value
        }
        param.args?.set(2, sanitized)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX sanitize host cloud results failed: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.rememberHomeSearchSnapshot(
    webDavResults: List<Any>,
    localResults: List<Any>,
) {
    lastHomeSearchWebDavResults = webDavResults
    lastHomeSearchLocalResults = localResults
}

internal fun WebDavDriveHook.searchOnlineCompletionSources(query: String): List<Any> {
    val context = currentContext() ?: return emptyList()
    val needle = query.trim()
    if (needle.isBlank()) return emptyList()
    val sources = enabledOnlineCompletionSources(context)
    logWebDav("online completion search query=$needle sources=${sources.size}")
    onlineCompletionSearchTargets.clear()
    onlineCompletionSearchRowViews.clear()
    val selectedSources = sources.take(HOME_SEARCH_RESULT_LIMIT)
    return searchOnlineCompletionSourcesConcurrent(selectedSources, needle)
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

    val latch = CountDownLatch(sources.size)
    val lock = Any()
    val groups = Array<OnlineSearchGroup?>(sources.size) { index ->
        OnlineSearchGroup(sources[index], needle, emptyList(), "搜索中")
    }

    fun snapshot(): List<OnlineSearchGroup> =
        synchronized(lock) {
            groups.mapIndexed { index, group ->
                group ?: OnlineSearchGroup(sources[index], needle, emptyList(), "搜索中")
            }
        }

    fun publish(index: Int, group: OnlineSearchGroup) {
        val next = synchronized(lock) {
            groups[index] = group
            groups.mapIndexed { groupIndex, item ->
                item ?: OnlineSearchGroup(sources[groupIndex], needle, emptyList(), "搜索中")
            }
        }
        onUpdate(next)
    }

    sources.forEachIndexed { index, source ->
        Thread({
            try {
                val finalGroup = searchOnlineCompletionSource(source, needle) { parsedGroup ->
                    publish(index, parsedGroup)
                }
                publish(index, finalGroup)
            } finally {
                latch.countDown()
            }
        }, "ReaMicroOnlineSourceSearch-${source.id.takeLast(6)}").start()
    }

    latch.await(ONLINE_COMPLETION_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    val timedOut = mutableListOf<Int>()
    synchronized(lock) {
        groups.forEachIndexed { index, group ->
            if (group?.error == "搜索中") {
                groups[index] = OnlineSearchGroup(sources[index], needle, emptyList(), "搜索超时")
                timedOut += index
            }
        }
    }
    if (timedOut.isNotEmpty()) onUpdate(snapshot())
    return snapshot()
}

internal fun WebDavDriveHook.onlineCompletionPendingSearchGroups(query: String): List<OnlineSearchGroup> {
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

internal fun WebDavDriveHook.searchOnlineCompletionSourcesConcurrent(
    sources: List<OnlineSourceEntry>,
    query: String,
): List<OnlineSearchGroup> {
    if (sources.isEmpty()) return emptyList()
    val latch = CountDownLatch(sources.size)
    val groups = arrayOfNulls<OnlineSearchGroup>(sources.size)
    sources.forEachIndexed { index, source ->
        Thread({
            try {
                groups[index] = searchOnlineCompletionSource(source, query)
            } finally {
                latch.countDown()
            }
        }, "ReaMicroOnlineSourceSearch-${source.id.takeLast(6)}").start()
    }
    latch.await(ONLINE_COMPLETION_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return groups.mapIndexed { index, group ->
        group ?: OnlineSearchGroup(sources[index], query, emptyList(), "搜索超时")
    }
}

internal fun WebDavDriveHook.searchOnlineCompletionSource(
    source: OnlineSourceEntry,
    query: String,
    onParsedResults: ((OnlineSearchGroup) -> Unit)? = null,
): OnlineSearchGroup {
    if (source.searchUrl.isBlank()) {
        return OnlineSearchGroup(source, query, emptyList(), "源缺少 searchUrl")
    }
    return runCatching {
        val request = buildOnlineSearchRequest(source, query)
        val response = OnlineConcurrentRateLimiter.withLimitBlocking(source) {
            requestOnlineSearch(source, request)
        }
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
        val results = enrichOnlineSearchResultsMetadata(source, parsedResults)
        logWebDav("online completion source ok name=${source.name} results=${results.size} first=${results.firstOrNull()?.name.orEmpty()}")
        OnlineSearchGroup(source, query, results, "")
    }.getOrElse {
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
    val enriched = arrayOfNulls<OnlineBookSearchResult>(selected.size)
    val latch = CountDownLatch(selected.size)
    selected.forEachIndexed { index, result ->
        Thread({
            try {
                enriched[index] = enrichOnlineSearchResultMetadata(source, result)
            } finally {
                latch.countDown()
            }
        }, "ReaMicroOnlineMeta-${source.id.takeLast(6)}-$index").start()
    }
    latch.await(ONLINE_COMPLETION_SEARCH_METADATA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return results.mapIndexed { index, result ->
        if (index < enriched.size) enriched[index] ?: result else result
    }
}

internal fun WebDavDriveHook.enrichOnlineSearchResultMetadata(
    source: OnlineSourceEntry,
    result: OnlineBookSearchResult,
): OnlineBookSearchResult {
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
    // 解析 legado options block：url,{"method":"POST","body":"searchkey={{key}}","charset":"gbk","headers":{...}}
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

// 定位 legado searchUrl 中 options block 的起始逗号位置（",{" 之前）。
internal fun WebDavDriveHook.indexOfOptionsBlock(raw: String): Int {
    // 避免误匹配 URL 自身的查询参数；legado options 块以 ",{" 或 ", {" 开头且是合法 JSON 对象。
    val candidates = listOf(",{", ", {")
    for (sep in candidates) {
        val idx = raw.indexOf(sep)
        if (idx > 0) {
            val candidate = raw.substring(idx).removePrefix(",").trim()
            if (candidate.startsWith("{") && candidate.endsWith("}")) return idx
        }
    }
    return -1
}

internal fun WebDavDriveHook.parseOptionsJson(raw: String?): JSONObject =
    if (raw.isNullOrBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

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
    val requestUrl = request.url
    if (requestUrl.startsWith("data:", ignoreCase = true)) {
        return OnlineHttpResponse(requestUrl, onlineDataUrlStringResponse(requestUrl))
    }
    val charset = runCatching { Charset.forName(request.charset) }.getOrDefault(Charsets.UTF_8)
    val isPost = request.method == "POST" && request.body != null
    return withOnlineCleartextAllowed(requestUrl) {
        fun execute(authRetried: Boolean): OnlineHttpResponse {
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = if (isPost) "POST" else "GET"
                connectTimeout = onlineConnectTimeoutMillis(source)
                readTimeout = onlineReadTimeoutMillis(source)
                setRequestProperty("User-Agent", "Mozilla/5.0 ReaMicro-Extend/online-source")
                setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml,*/*")
                if (isPost) {
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                parseOnlineHeaders(source.header).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                }
                request.headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                }
                OnlineSourceAuth.requestHeaders(currentApplicationContext() ?: currentContext(), source, requestUrl).forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
                }
                if (isPost) {
                    doOutput = true
                    val bodyBytes = request.body!!.toByteArray(charset)
                    setRequestProperty("Content-Length", bodyBytes.size.toString())
                    outputStream.use { it.write(bodyBytes) }
                }
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(charset)?.use { it.readText() }.orEmpty()
                if (code in 200..299) return OnlineHttpResponse(connection.url.toString(), body)
                if ((code == 401 || code == 403) && !authRetried && source.hasLoginConfig) {
                    val login = OnlineSourceAuth.loginWithSavedCredentials(currentApplicationContext() ?: currentContext(), source)
                    logWebDav(
                        "online completion auth retry source=${source.name} code=$code " +
                            "success=${login.success} message=${login.message}",
                    )
                    if (login.success) return execute(authRetried = true)
                }
                val reason = onlineHttpErrorDetail(body).ifBlank {
                    if (code == 401 || code == 403) "未登录或会员权限不足" else ""
                }
                throw OnlineSourceHttpException(
                    statusCode = code,
                    detail = reason,
                    retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.times(1_000L),
                )
            } finally {
                connection.disconnect()
            }
        }
        execute(authRetried = false)
    }
}

internal fun WebDavDriveHook.onlineDataUrlStringResponse(url: String): String {
    val dataPart = url.substringAfter("base64,", "")
        .substringBefore(",{")
        .substringBefore(";")
        .trim()
    if (dataPart.isBlank()) return ""
    val bytes = Base64.decode(dataPart, Base64.DEFAULT)
    return if (url.substringAfter(dataPart, "").contains(""""type"""")) {
        bytes.toLowerHexString()
    } else {
        String(bytes, Charsets.UTF_8)
    }
}

internal fun WebDavDriveHook.parseOnlineHeaders(raw: String): Map<String, String> {
    val text = raw.trim()
    if (text.isBlank()) return emptyMap()
    // Legado 的 @js:/<js> header 是脚本指令而非真实请求头；模块不执行 JS，若按“冒号分割”会拼出一个
    // 名为 "@js" 的非法头。API 主机会忽略它，但封面所在的字节跳动 CDN 会直接判 400，导致封面/图片全部
    // 加载失败。真正的密钥头由 credentialHeaders 单独注入，这里遇到脚本头直接跳过。
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

// 只接受合法的 HTTP header 名（RFC 7230 token），过滤掉 @js 之类会被 CDN/WAF 判 400 的非法头名。
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

internal fun WebDavDriveHook.parseOnlineJsonRoot(body: String): Any? =
    runCatching {
        val text = body.trim()
        if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
    }.getOrNull()

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

internal fun WebDavDriveHook.parseOnlineHtmlResults(
    source: OnlineSourceEntry,
    query: String,
    baseUrl: String,
    html: String,
): List<OnlineBookSearchResult> {
    val results = mutableListOf<OnlineBookSearchResult>()
    val anchorRegex = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
    anchorRegex.findAll(html).forEach { match ->
        if (results.size >= ONLINE_COMPLETION_RESULT_LIMIT) return@forEach
        val title = match.groupValues[2].cleanOnlineText()
        if (title.isBlank() || title.length > 120) return@forEach
        if (!title.contains(query, ignoreCase = true) && results.size >= 3) return@forEach
        val href = match.groupValues[1].trim()
        val start = (match.range.first - 240).coerceAtLeast(0)
        val end = (match.range.last + 360).coerceAtMost(html.length)
        val window = html.substring(start, end)
        val author = Regex("""(?is)(?:作者|author)\s*[:：]?\s*</?[^>]*>\s*([^<\s]{1,40})""")
            .find(window)?.groupValues?.getOrNull(1).orEmpty().cleanOnlineText()
        val cover = Regex("""(?is)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
            .find(window)?.groupValues?.getOrNull(1).orEmpty()
        results.add(
            OnlineBookSearchResult(
                sourceName = source.name,
                name = title,
                author = author,
                coverUrl = resolveOnlineUrl(baseUrl, cover),
                detailUrl = resolveOnlineUrl(baseUrl, href),
                intro = "",
            ),
        )
    }
    return results
}

internal fun WebDavDriveHook.firstJsonString(json: JSONObject, vararg names: String): String =
    names.asSequence()
        .map { json.optString(it, "").trim() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        .orEmpty()

internal fun WebDavDriveHook.onlineResultMetadata(
    source: OnlineSourceEntry,
    node: Any?,
    rule: JSONObject?,
    baseUrl: String,
    chapterCountFallback: Int = 0,
): OnlineResultMetadata {
    val kind = rule?.optString("kind", "").orEmpty()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source) }
        .orEmpty()
    val wordCountRule = rule?.optString("wordCount", "").orEmpty()
        .takeUnless(::isOnlineChapterCountSelector)
        .orEmpty()
    val wordCount = wordCountRule
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source) }
        .orEmpty()
        .ifBlank { onlineFirstJsonString(node, ONLINE_WORD_COUNT_FIELDS) }
    val updateTime = rule?.optString("updateTime", "").orEmpty()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source) }
        .orEmpty()
        .ifBlank { onlineFirstJsonString(node, ONLINE_UPDATE_TIME_FIELDS) }
    val chapterCount = onlineFirstJsonInt(node, ONLINE_CHAPTER_COUNT_FIELDS)
        .takeIf { it > 0 }
        ?: chapterCountFallback.coerceAtLeast(0)
    val statusHint = rule?.optString("lastChapter", "").orEmpty()
        .takeIf { it.isNotBlank() }
        ?.let { onlineRuleValue(node, it, baseUrl, source = source) }
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

internal fun WebDavDriveHook.onlineFirstJsonString(node: Any?, fields: List<String>): String =
    fields.asSequence()
        .flatMap { field -> sequenceOf(field, "$.$field", "$..$field") }
        .map { onlineJsonString(node, it).cleanOnlineText() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        .orEmpty()

internal fun WebDavDriveHook.onlineFirstJsonInt(node: Any?, fields: List<String>): Int =
    fields.asSequence()
        .flatMap { field -> sequenceOf(field, "$.$field", "$..$field") }
        .mapNotNull { rule ->
            onlineJsonString(node, rule)
                .replace(",", "")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.toDoubleOrNull()
                ?.toInt()
        }
        .firstOrNull { it > 0 }
        ?: 0

internal fun WebDavDriveHook.onlineCompletionSearchMetaLine(result: OnlineBookSearchResult): String {
    val tail = if (result.sourceName.isWanFengLiSourceName()) {
        result.platformName.ifBlank { result.updateTime }
    } else {
        result.updateTime
    }
    return listOfNotNull(
        result.status.ifBlank { null },
        result.wordCount.ifBlank { null },
        result.chapterCount.takeIf { it > 0 }?.let { "${it}章" },
        tail.ifBlank { null },
    ).joinToString(" / ").ifBlank { "详情加载中" }
}

internal fun WebDavDriveHook.onlineCompletionStatusText(kind: String, node: Any?, statusHint: String = ""): String {
    val text = kind.cleanOnlineText()
    statusTextFromHumanText(text)?.let { return it }
    statusTextFromHumanText(statusHint.cleanOnlineText())?.let { return it }
    onlineFirstJsonString(node, ONLINE_STATUS_TEXT_FIELDS)
        .takeIf { it.isNotBlank() }
        ?.let { statusTextFromHumanText(it) }
        ?.let { return it }
    onlineJsonString(node, "$..creation_status").trim().takeIf { it.isNotBlank() }?.let { value ->
        return when (value.toIntOrNull()) {
            1 -> "连载"
            4 -> "断更"
            else -> "完结"
        }
    }
    onlineJsonString(node, "$..tomato_book_status").trim().takeIf { it.isNotBlank() }?.let { value ->
        return when (value.toIntOrNull()) {
            3 -> "下架"
            else -> ""
        }
    }
    listOf("is_finish", "isFinished", "finished", "complete", "completed").forEach { field ->
        val value = onlineJsonString(node, "$..$field").trim().lowercase(Locale.ROOT)
        if (value.isNotBlank()) {
            return if (value in setOf("true", "1", "yes")) "完结" else "连载"
        }
    }
    inferOnlineStatusFromLastChapterTitle(statusHint)
        .ifBlank {
            inferOnlineStatusFromLastChapterTitle(
                onlineFirstJsonString(
                    node,
                    listOf("last_chapter_title", "lastChapterTitle", "latest_chapter_title", "latestChapterTitle"),
                ),
            )
        }
        .takeIf { it.isNotBlank() }
        ?.let { return it }
    val statusSource = text.ifBlank {
        sequenceOf(
            "status",
            "bookStatus",
            "book_status",
            "creation_status",
            "tomato_book_status",
            "is_finish",
            "complete",
            "finished",
        ).map { onlineJsonString(node, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
    val normalized = statusSource.lowercase(Locale.ROOT)
    return when {
        statusSource.contains("断更") -> "断更"
        statusSource.contains("下架") -> "下架"
        statusSource.contains("连载") || normalized in setOf("1", "serial", "ongoing", "false") -> "连载"
        statusSource.contains("完结") || statusSource.contains("已完") ||
            normalized in setOf("0", "2", "3", "4", "finish", "finished", "complete", "completed", "true") -> "完结"
        else -> ""
    }
}

internal fun WebDavDriveHook.statusTextFromHumanText(text: String): String? =
    when {
        text.contains("断更") -> "断更"
        text.contains("下架") -> "下架"
        text.contains("连载") -> "连载"
        text.contains("完结") || text.contains("已完") -> "完结"
        else -> null
    }

internal fun WebDavDriveHook.formatOnlineWordCount(raw: String): String {
    val text = raw.cleanOnlineText()
    return formatOnlineWordCountValue(text)
}

internal fun WebDavDriveHook.formatOnlineUpdateTime(raw: String): String {
    val text = raw.cleanOnlineText()
    if (text.isBlank()) return ""
    val numeric = text.toLongOrNull()
    if (numeric != null && numeric > 0L) {
        val millis = if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        return SimpleDateFormat("yyyy MM dd", Locale.getDefault()).format(java.util.Date(millis))
    }
    val normalized = text.replace('T', ' ').replace(Regex("""\.\d+Z?$"""), "").trim()
    val parsed = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
    ).asSequence().mapNotNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(normalized) }.getOrNull()
    }.firstOrNull()
    return if (parsed != null) {
        SimpleDateFormat("yyyy MM dd", Locale.getDefault()).format(parsed)
    } else {
        normalized.take(10).replace('-', ' ').replace('/', ' ')
    }
}

internal fun WebDavDriveHook.onlineRuleValue(
    node: Any?,
    rawRule: String,
    baseUrl: String,
    query: String? = null,
    source: OnlineSourceEntry? = null,
): String {
    var rule = rawRule.trim()
    if (rule.isBlank() || node == null || node == JSONObject.NULL) return ""
    if (rule.startsWith("<js>", ignoreCase = true)) return ""
    if (rule.startsWith("@js:", ignoreCase = true)) {
        if (rule.contains("replaceCover", ignoreCase = true)) {
            return replaceFanqieCover(onlineRuleValue(node, "thumb_url", baseUrl, source = source))
        }
        return ""
    }
    val inlineTagIndex = rule.indexOf("<js>", ignoreCase = true)
    if (inlineTagIndex > 0) {
        val selectorRule = rule.substring(0, inlineTagIndex).trim()
        val selectedValue = onlineRuleValue(node, selectorRule, baseUrl, query, source)
        OnlineSourceScriptCompat.resolveInlineResultUrl(rule, selectedValue)?.let { return it }
    }
    val inlineJsIndex = rule.indexOf("\n@js:", ignoreCase = true)
    if (inlineJsIndex >= 0) {
        val selectorRule = rule.substring(0, inlineJsIndex).trim()
        val selectedValue = onlineRuleValue(node, selectorRule, baseUrl, query, source)
        evaluateQqReaderCoverRule(rule, selectedValue)?.let { return it }
    }
    rule = rule.substringBefore("\n<js>", rule).substringBefore("\n@js:", rule).trim()
    if (rule.contains("{{") && rule.contains("}}")) {
        return applyOnlineTemplate(rule, node, baseUrl, query, source = source)
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
): String {
    val loginInfo = source?.let {
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
        val sourceExpression = OnlineSourceLoginConfig.expressionValue(expr, sourceUrl, loginInfo)
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
            // legado JS：java.put('var', key) —— 存变量并原地返回 key，等价于直接用搜索词
            Regex("""^java\.put\(\s*['"][^'"]*['"]\s*,\s*(key|keyword|searchKey)\s*\)$""", RegexOption.IGNORE_CASE)
                .matchEntire(expr) != null ->
                query?.let { if (encodeQuery) URLEncoder.encode(it, encodeCharset) else it }.orEmpty()
            else -> ""
        }
    }
}

internal fun WebDavDriveHook.onlineJsonString(node: Any?, rule: String): String =
    onlineJsonValues(node, rule).firstOrNull()?.let(::onlineJsonPrimitive).orEmpty()

internal fun WebDavDriveHook.onlineJsonInt(node: Any?, vararg rules: String): Int =
    rules.asSequence()
        .map { onlineJsonString(node, it).trim() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        ?.toDoubleOrNull()
        ?.toInt()
        ?: 0

internal fun WebDavDriveHook.onlineJsonRuleValues(node: Any?, rule: String): List<Any?> {
    if (node == null || rule.isBlank()) return emptyList()
    val selectorRule = rule.substringBefore("<js>", rule).substringBefore("@js:", rule).trim()
    if (selectorRule.isBlank()) return emptyList()
    onlineJsonCandidateRoots(node).forEach { candidate ->
        val values = onlineJsonValues(candidate, selectorRule).flatMap(::onlineJsonRuleItems)
        if (values.isNotEmpty()) return values
    }
    return emptyList()
}

internal fun WebDavDriveHook.onlineJsonRuleItems(value: Any?): List<Any?> =
    when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        else -> listOfNotNull(value).filter { it != JSONObject.NULL }
    }

internal fun WebDavDriveHook.onlineJsonCandidateRoots(node: Any?): List<Any?> {
    val roots = linkedSetOf<Any?>()
    fun add(value: Any?) {
        if (value != null && value != JSONObject.NULL) roots.add(value)
    }
    add(node)
    if (node is JSONObject) {
        listOf("data", "result", "book", "chapter", "rows", "ret_data").forEach { key ->
            add(node.opt(key))
        }
        (node.opt("data") as? JSONObject)?.let { data ->
            listOf("data", "result", "book", "chapter", "rows", "ret_data").forEach { key ->
                add(data.opt(key))
            }
        }
    }
    return roots.toList()
}

internal fun WebDavDriveHook.onlineJsonValues(node: Any?, rawRule: String): List<Any?> =
    OnlineJsonPathCompat.values(node, rawRule)

internal fun WebDavDriveHook.onlineJsonPrimitive(value: Any?): String =
    when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> value.toString()
    }.trim()

internal fun WebDavDriveHook.replaceFanqieCover(raw: String): String {
    val clean = raw.trim().substringBefore('~').substringBefore('?').trim()
    if (clean.isBlank()) return ""
    val path = runCatching {
        val normalized = if (clean.startsWith("//")) "https:$clean" else clean
        if (normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        ) {
            URI(normalized).rawPath.orEmpty().trimStart('/')
        } else {
            normalized.trimStart('/')
        }
    }.getOrDefault(clean.trimStart('/'))
        .removePrefix("origin/")
        .removePrefix("/origin/")
        .trimStart('/')
    val imagePath = when {
        path.isBlank() -> return ""
        path.startsWith("novel-pic/", ignoreCase = true) -> path
        path.startsWith("novel-images/", ignoreCase = true) -> path
        path.startsWith("novel-static/", ignoreCase = true) -> path
        path.contains('/') -> path
        else -> "novel-pic/$path"
    }
    return "https://p6-novel.byteimg.com/origin/$imagePath"
}

internal fun WebDavDriveHook.sourceBaseUrl(source: OnlineSourceEntry): String =
    Regex("""https?://[^\s#]+""").find(source.sourceUrl)?.value.orEmpty()

internal fun WebDavDriveHook.resolveOnlineUrl(baseUrl: String, value: String): String =
    resolveOnlineUrlCompat(baseUrl, value)

internal fun WebDavDriveHook.normalizeOnlineCoverUrl(source: OnlineSourceEntry, baseUrl: String, value: String): String {
    val raw = value.trim()
    if (raw.isBlank()) return ""
    // 番茄封面常是相对的 novel-images/novel-pic/novel-static 路径，直接按 baseUrl 拼会落到 API 域名下
    // （如 https://api.yuezhi.me/v1/books/novel-images/...）而 404；这类路径统一走字节跳动图片源。
    if (!raw.startsWith("http", ignoreCase = true) && FANQIE_IMAGE_PATH_REGEX.containsMatchIn(raw)) {
        replaceFanqieCover(raw).takeIf { it.isNotBlank() }?.let { return it }
    }
    val resolved = resolveOnlineUrl(baseUrl.ifBlank { sourceBaseUrl(source) }, value)
    if (!resolved.startsWith("http://", ignoreCase = true)) {
        // 已被错误拼到 API 域名下的番茄图片路径，纠正为字节跳动图片源。
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

internal fun WebDavDriveHook.updateHomeOnlineCompletionSearchResults(viewModel: Any?, onlineResults: List<Any>) {
    if (viewModel == null) return
    rememberHomeViewModelDependencies(viewModel)
    runCatching {
        val updateMethod = viewModel.javaClass.superclass?.methods?.firstOrNull {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        } ?: viewModel.javaClass.methods.first {
            it.name == "updateUiState" && it.parameterTypes.size == 1
        }
        updateMethod.apply { isAccessible = true }.invoke(
            viewModel,
            functionProxy("OnlineCompletionHomeSearchState", FUNCTION1_CLASS) { args ->
                val state = args?.getOrNull(0) ?: return@functionProxy args?.getOrNull(0)
                runCatching {
                    val current = homeCloudSearchResults(state)
                    val merged = linkedMapOf<Any?, Any?>()
                    current?.forEach { (key, value) -> merged[key] = value }
                    if (onlineResults.isEmpty()) {
                        merged.remove(BACKUP_TYPE_ONLINE_COMPLETION)
                    } else {
                        merged[BACKUP_TYPE_ONLINE_COMPLETION] = onlineResults
                    }
                    copyHomeUiStateWithCloudResults(state, merged)
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed to copy online completion search state: ${it.stackTraceToString()}")
                }.getOrDefault(state)
            },
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to update online completion search state: ${it.stackTraceToString()}")
    }
}

internal fun WebDavDriveHook.homeCloudSearchResults(state: Any): Map<*, *>? =
    state.javaClass.methods.firstOrNull {
        it.name == "getCloudSearchResults" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(state) as? Map<*, *>

internal fun WebDavDriveHook.homeCloudSearchResultsComponentIndex(state: Any, parameterCount: Int): Int? {
    val current = homeCloudSearchResults(state) ?: return null
    for (index in 0 until parameterCount) {
        val component = state.invokeNoArg("component${index + 1}")
        if (component === current) return index
    }
    return null
}

internal fun WebDavDriveHook.handleOnlineCompletionSearchTap(book: Any): Boolean {
    val path = cloudPathOf(book)
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
    val root = parseOnlineJsonRoot(detailBody) ?: return
    val bookInfoRule = runCatching { JSONObject(target.source.ruleBookInfo) }.getOrNull()
    val node = bookInfoRule?.optString("init", "").orEmpty()
        .trim()
        .takeIf { it.isNotBlank() }
        ?.let { onlineJsonRuleValues(root, it).firstOrNull() }
        ?: root
    val meta = onlineResultMetadata(
        source = target.source,
        node = node,
        rule = bookInfoRule,
        baseUrl = detailUrl,
        chapterCountFallback = chapterCount,
    )
    val current = target.result
    val enriched = current.copy(
        author = current.author.ifBlank {
            bookInfoRule?.optString("author", "").orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { onlineRuleValue(node, it, detailUrl, source = target.source).cleanOnlineText() }
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
                        onlineRuleValue(node, it, detailUrl, source = target.source),
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
                ?.let { onlineRuleValue(node, it, detailUrl, source = target.source).cleanOnlineText() }
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
            "chapters=${enriched.chapterCount} update=${enriched.updateTime}",
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
    val loginInfo = OnlineSourceAuth.loginInfo(currentApplicationContext() ?: currentContext(), source)
    var currentVolumeTitle = ""
    return nodes.mapIndexedNotNull { index, node ->
        val rawTitle = cleanOnlineChapterTitleValue(
            onlineRuleValue(node, titleRule, baseUrl, source = source).cleanOnlineText(),
        )
        val ruleRawUrl = onlineRuleValue(node, urlRule, baseUrl, source = source)
        val fallbackRawUrl = if (ruleRawUrl.isBlank()) fallbackOnlineChapterRawUrl(node) else ""
        val rawUrl = ruleRawUrl.ifBlank { fallbackRawUrl }
        if (isOnlineTocVolumeNode(source, node, isVolumeRule, rawTitle, rawUrl.isNotBlank(), baseUrl)) {
            currentVolumeTitle = rawTitle
            return@mapIndexedNotNull null
        }
        val title = rawTitle.ifBlank { "第 ${index + 1} 章" }
        if (index == 0) {
            logWebDav(
                "online completion toc first title=$title urlRuleLen=${urlRule.length} " +
                    "ruleRaw=${ruleRawUrl.take(80)} fallbackRaw=${fallbackRawUrl.take(80)}",
            )
        }
        val url = buildOnlineChapterUrl(source, baseUrl, urlRule, rawUrl, loginInfo)
        val volumeTitle = onlineChapterVolumeTitle(node).ifBlank { currentVolumeTitle }
        if (url.isBlank()) null else OnlineChapter(
            title = title,
            url = url,
            volumeTitle = volumeTitle,
            level = if (volumeTitle.isBlank()) 0 else 1,
        )
    }.distinctBy { it.url }
}

internal fun WebDavDriveHook.onlineCompletionSearchTextColors(composer: Any): IntArray =
    runCatching {
        val scheme = colorScheme(composer)
        val title = composeColorToArgb(scheme.longMethod("getOnBackground"))
        val author = runCatching { composeColorToArgb(scheme.longMethod("getOnSurfaceVariant")) }
            .getOrElse { composeColorToArgb(themeOnBackgroundVariant(composer)) }
        intArrayOf(title, author, withAlpha(author, 210))
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to resolve online completion search theme colors: ${it.stackTraceToString()}")
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

internal fun WebDavDriveHook.homeSearchTapMethod(): Method = synchronized(methodCache) {
    methodCache.getOrPut("$HOME_SEARCH_BAR_CLASS#homeSearchTap") {
        val candidates = cls(HOME_SEARCH_BAR_CLASS).methods.asSequence() +
            cls(HOME_SEARCH_BAR_CLASS).declaredMethods.asSequence()
        val matched = candidates.filter { candidate ->
            val types = candidate.parameterTypes
            types.size == 2 &&
                types[0].name == INTENT_RECEIVER_CLASS &&
                types[1].name == CLOUD_BOOK_CLASS
        }.toList()
        (
            matched.firstOrNull { it.name.startsWith("SearchResult") }
                ?: matched.firstOrNull { !it.name.startsWith("\$r8\$lambda") }
                ?: matched.firstOrNull()
            )?.apply { isAccessible = true }
            ?: error("$HOME_SEARCH_BAR_CLASS home search tap (IntentReceiver,CloudBook) not found")
    }
}
