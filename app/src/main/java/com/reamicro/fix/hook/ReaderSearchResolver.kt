package com.reamicro.fix.hook

import android.os.SystemClock
import com.reamicro.fix.epub.editor.*
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.reader.SearchHighlightMarkDraft
import com.reamicro.fix.xposed.XposedHelpers

internal data class ResolvedSearchTarget(
    val result: FullTextSearchResult, val start: Any, val end: Any,
    val ranges: List<SearchHighlightMarkDraft>, val flipStyle: Int?,
    val scrollElementId: Any? = null, val scrollGlyphOffsetPx: Int = 0, val scrollGlyphHeightPx: Int = 0,
)

internal class ReaderSearchResolver(private val reader: ReaderHook) {
    private data class Chapter(val modified: Long, val length: Long, val epub: Any, val digest: String,
        val sourceMapper: HostSelectionSourceMapper, val source: SelectionSourceMap,
        val renderMapper: HostSelectionSourceMapper?, val rendered: SelectionSourceMap?,
        val rootStyle: Any?, val elements: List<Any> = emptyList()) {
        val targets = LinkedHashMap<String, ResolvedSearchTarget>()
    }
    private val cache = LinkedHashMap<String, Chapter>()

    private val scroll = ReaderSearchScrollResolver(reader)
    fun clear() { cache.clear(); scroll.clear() }
    fun resolve(result: FullTextSearchResult, index: Int, vm: Any, epub: Any,
        loaded: ReaderSearchScrollBridge.Snapshot?, current: () -> Boolean): ResolvedSearchTarget {
        val window = reader.classLoader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
        for (attempt in 0..1) {
            val styleBefore = reader.callNoArg(window, "getRootStyle")
            val snapshot = loaded?.takeIf { attempt == 0 && it.rootStyle === styleBefore }
            try { return resolveOnce(result, index, vm, epub, snapshot, current) }
            catch (error: Exception) {
                if (attempt == 0 && current() && styleBefore !== reader.callNoArg(window, "getRootStyle")) {
                    clear()
                    Thread.sleep(32)
                } else throw error
            }
        }
        error("排版仍在更新，请稍后定位")
    }
    private fun resolveOnce(result: FullTextSearchResult, index: Int, vm: Any, epub: Any,
        loaded: ReaderSearchScrollBridge.Snapshot?, current: () -> Boolean): ResolvedSearchTarget {
        check(current()) { "搜索会话已变化" }
        val modified = result.file.lastModified()
        val length = result.file.length()
        val key = result.file.path

        val flip = checkNotNull(reader.currentSearchPagerMode) { "翻页布局尚未就绪，请稍后定位" }
        val window = reader.classLoader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
        val rootStyle = reader.callNoArg(window, "getRootStyle")
        val cacheKey = "$flip|$key"
        val targetKey = "$index|${result.startCfi}|${result.endCfi}"
        var chapter = cache.remove(cacheKey)?.takeIf { it.epub === epub && it.digest == result.sourceDigest && it.modified == modified && it.length == length && it.rootStyle === rootStyle &&
            runCatching { it.renderMapper?.checkReadingContext() }.isSuccess &&
            (flip != 3 || it.targets.containsKey(targetKey) || scroll.canResolve(it.elements, result, it.sourceMapper)) }
        if (chapter == null) {
            val raw = EpubTextFiles.load(result.file, checkCancelled = {
                if (!current()) throw java.util.concurrent.CancellationException()
            }).text
            val digest = searchSourceDigest(raw)
            require(result.sourceDigest.isNotBlank() && digest == result.sourceDigest) { "章节已修改，请重新搜索" }
            val sourceMapper = HostSelectionSourceMapper(reader.classLoader)
            val source = sourceMapper.map(raw, key)
            val point = sourceMapper.point(sourceMapper.hostCfi(requireNotNull(result.startCfi)))
            val items = (reader.callNoArg(epub, "getItemRefs") as? List<*>).orEmpty()
            val spine = items.indexOfFirst { it != null && (reader.callNoArg(it, "getIndex") as? Number)?.toInt() == point.itemRef }
            require(spine >= 0) { "搜索结果不属于当前书籍" }
            if (flip == 3 && loaded != null && loaded.rootStyle === reader.callNoArg(window, "getRootStyle") &&
                scroll.canResolve(loaded.elements, result, sourceMapper)) {
                chapter = Chapter(modified, length, epub, digest, sourceMapper, source, null, null,
                    loaded.rootStyle, loaded.elements)
            }
            if (chapter == null) {
                val mutex = XposedHelpers.getObjectField(vm, "virtualPageLoadMutex") ?: error("章节加载锁不可用")
                val owner = Any()
                var locked = false
                try {
                    val until = SystemClock.elapsedRealtime() + 8_000
                    while (current() && !locked && SystemClock.elapsedRealtime() < until) {
                        locked = XposedHelpers.callMethod(mutex, "tryLock", owner) == true
                        if (!locked) Thread.sleep(20)
                    }
                    check(locked && current()) { "章节正在加载，请稍后重试" }
                    if (flip == 3) {
                        val read = epub.javaClass.getMethod("readElements", Int::class.javaPrimitiveType,
                            reader.classLoader.loadClass("kotlin.coroutines.Continuation"))
                        val elements = (reader.invokeSelectionHostSuspend { read.invoke(epub, spine, it) } as? List<*>)
                            .orEmpty().filterNotNull()
                        chapter = Chapter(modified, length, epub, digest, sourceMapper, source, null, null, reader.callNoArg(window, "getRootStyle"), elements)
                    } else {
                    val pages = (XposedHelpers.getObjectField(vm, "virtualPages") as? Map<*, *>)?.values.orEmpty()
                    var document = pages.firstNotNullOfOrNull { page ->
                        val cfi = page?.let { reader.callNoArg(it, "getStart") }
                        if (cfi != null && sourceMapper.anchorPoint(cfi).itemRef == point.itemRef)
                            reader.callNoArg(page, "getDocument") else null
                    }
                    if (document == null) {
                        val loader = reader.classLoader
                        val dispatcher = loader.loadClass("kotlinx.coroutines.Dispatchers").getMethod("getIO").invoke(null)
                        val read = epub.javaClass.getMethod("read", Int::class.javaPrimitiveType,
                            loader.loadClass("kotlinx.coroutines.CoroutineDispatcher"), loader.loadClass("kotlin.coroutines.Continuation"))
                        val loaded = reader.invokeSelectionHostSuspend { read.invoke(epub, spine, dispatcher, it) } as? List<*>
                        document = loaded?.firstNotNullOfOrNull { it?.let { page -> reader.callNoArg(page, "getDocument") } }
                    }
                    check(reader.currentViewModelRef?.get() === vm &&
                        (reader.currentEpubStrong ?: reader.currentEpubRef?.get()) === epub) { "搜索会话已更新" }

                    val renderMapper = HostSelectionSourceMapper(reader.classLoader)
                    renderMapper.bindSearchDocument(document ?: error("目标章节没有可阅读页面"))
                    val rendered = renderMapper.map(raw, key)
                    chapter = Chapter(modified, length, epub, digest, sourceMapper, source, renderMapper, rendered, reader.callNoArg(window, "getRootStyle"))
                    }
                } finally {
                    if (locked) XposedHelpers.callMethod(mutex, "unlock", owner)
                }
            }
        }
        val c = requireNotNull(chapter)
        cache[cacheKey] = c
        while (cache.size > 2) cache.remove(cache.keys.first())
        check(current()) { "搜索请求已更新" }
        c.targets[targetKey]?.let { cached ->
            check(c.rootStyle === reader.callNoArg(window, "getRootStyle")) { "排版正在更新，暂无法确认目标位置" }
            return cached.copy(result = cached.result.copy(intentReceiver = result.intentReceiver))
        }
        fun remember(target: ResolvedSearchTarget): ResolvedSearchTarget {
            check(c.rootStyle === reader.callNoArg(window, "getRootStyle")) { "排版正在更新，暂无法确认目标位置" }
            c.targets[targetKey] = target
            if (c.targets.size > 64) c.targets.remove(c.targets.keys.first())
            return target
        }
        if (flip == 3) {
            val target = scroll.resolve(c.elements, result, index, c.sourceMapper, c.source)
            check(current() && result.file.lastModified() == modified && result.file.length() == length) { "章节或会话已变化" }
            return remember(target)
        }
        val rendered = requireNotNull(c.rendered)
        val renderMapper = requireNotNull(c.renderMapper)
        val sourceStart = c.sourceMapper.point(c.sourceMapper.hostCfi(requireNotNull(result.startCfi)))
        val sourceEnd = c.sourceMapper.point(c.sourceMapper.hostCfi(requireNotNull(result.endCfi)))
        c.source.plan(sourceStart, sourceEnd, result.matchText)
        val a = c.source.sourceOffset(sourceStart) ?: error("搜索起点不能映射到源码")
        val b = c.source.sourceEndOffset(sourceEnd) ?: error("搜索终点不能映射到源码")
        val begin = rendered.pointAtSourceOffset(a, sourceStart.spine, sourceStart.itemRef) ?: error("命中内容未被宿主显示")
        val finish = rendered.pointAtSourceEndOffset(b, sourceStart.spine, sourceStart.itemRef) ?: error("命中内容包含特殊排版")
        require(rendered.sourceOffset(begin) == a && rendered.sourceEndOffset(finish) == b) { "命中内容被隐藏或替换，不能猜测位置" }
        val plan = rendered.plan(begin, finish, result.matchText)
        val template = c.sourceMapper.hostCfi(requireNotNull(result.startCfi))
        fun host(point: SelectionCfiPoint) = renderMapper.cfiForPoint(template, point)
        val start = host(begin)
        val end = host(finish)
        val id = SEARCH_HIGHLIGHT_MARK_ID_BASE + index
        val ranges = rendered.searchRuns().mapNotNull { run ->
            val from = maxOf(run.from, plan.projectedFrom)
            val until = minOf(run.until, plan.projectedUntil)
            if (from >= until) return@mapNotNull null
            val point = SelectionCfiPoint(begin.spine, begin.itemRef, run.steps, from - run.from, bodyIndex = begin.bodyIndex)
            SearchHighlightMarkDraft(id, result.chapterTitle, host(point).toString(),
                host(point.copy(offset = until - run.from)).toString(), rendered.searchText().substring(from, until))
        }
        require(ranges.isNotEmpty()) { "没有可绘制的命中范围" }
        check(current() && result.file.lastModified() == modified && result.file.length() == length) { "章节或搜索会话已变化，请重新搜索" }
        return remember(ResolvedSearchTarget(result.copy(cfi = start.toString(), startCfi = start.toString(), endCfi = end.toString()), start, end, ranges, flip))
    }
}
