package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread



import com.reamicro.fix.core.HostEpubRuntimeBindings
import com.reamicro.fix.core.HostReaderWindowBindings
import android.widget.Toast
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.currentReaderPage(): Any? =
    currentPageStrong ?: currentPageRef?.get()

internal fun ReaderHook.openReaderCompletionPlanPage() {
    val activity = activityProvider()
    val bookKey = ReaderHighlightBookContext.bookKey
    val bookTitle = ReaderHighlightBookContext.bookTitle
    val navGraphScope = currentReaderNavGraphScopeRef?.get()
    val opened = ReaMicroSettingsHook.openReaderCompletionPlanFromReader(
        bookKey = bookKey,
        bookTitle = bookTitle,
        navGraphScope = navGraphScope,
    )
    if (!opened) {
        HookRuntime.log("$LOG_PREFIX open completion plan page failed (navGraphScope unavailable)")
        activity?.runOnModuleUiThread {
            Toast.makeText(activity, "\u672a\u80fd\u6253\u5f00\u8865\u5168\u8ba1\u5212", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun ReaderHook.jumpToCfi(
    receiver: Any?,
    viewModel: Any?,
    cfi: String,
    mark: Any? = null,
    chapterIndex: Int,
    title: String,
    summary: String,
): Boolean {
    val markJumped = runCatching {
        val host = requireNotNull(HostEpubRuntimeBindings.current)
        val targetMark = mark ?: createReaderMark(
            id = -System.currentTimeMillis(),
            chapter = title,
            startCfi = cfi,
            endCfi = cfi,
            quote = summary,
            style = MARK_STYLE_LINE,
            color = MARK_COLOR_RED,
        ) ?: return@runCatching false
        val intent = host.constructor("MarkJump").newInstance(targetMark)
        dispatchReaderIntent(receiver, viewModel, intent, "MarkJump")
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX full-text search mark jump failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (markJumped) {
        HookRuntime.log("$LOG_PREFIX full-text search mark jump dispatched cfi=$cfi")
        return true
    }

    val host = requireNotNull(HostEpubRuntimeBindings.current)
    val bookmark = host.record("bookmark", mapOf(
        "chapter" to chapterIndex, "title" to title, "summary" to summary,
        "epubcfi" to cfi, "remark" to "", "type" to 0,
    ))
    val intent = host.constructor("BookmarkJump").newInstance(bookmark)
    return dispatchReaderIntent(receiver, viewModel, intent, "BookmarkJump").also {
        HookRuntime.log("$LOG_PREFIX full-text search bookmark jump dispatched=$it cfi=$cfi")
    }
}

internal fun ReaderHook.isValidEpubCfi(cfi: String): Boolean = createEpubCfi(cfi) != null

internal fun ReaderHook.catalogChapterItemMarkId(item: Any?): Long? =
    HostEpubRuntimeBindings.current?.value("catalogItem.mark", item)?.let(::transientHighlightMarkId)

internal fun ReaderHook.resolveCatalogIndexForCfi(cfi: String, catalog: List<Any>): Int? = runCatching {
    if (cfi.isBlank() || catalog.isEmpty()) return@runCatching null
    val host = requireNotNull(HostEpubRuntimeBindings.current)
    val cfiObject = createEpubCfi(cfi) ?: return@runCatching null
    (host.method("catalogIndex").invoke(null, cfiObject, catalog) as? Number)?.toInt()
}.onFailure { HookRuntime.log("$LOG_PREFIX resolve search highlight catalog index failed: ${it.stackTraceToString()}") }.getOrNull()

internal fun ReaderHook.createEpubCfi(cfi: String): Any? = runCatching {
    if (cfi.isBlank()) null else requireNotNull(HostEpubRuntimeBindings.current).method("cfi").invoke(null, cfi)
}.getOrNull()

internal fun ReaderHook.epubPageNumber(page: Any?): Int? {
    page ?: return null
    val vm = currentViewModelRef?.get() ?: return null
    val window = HostReaderWindowBindings.current ?: return null
    return (window.pages(vm).entries.firstOrNull { it.value === page }?.key as? Number)?.toInt()
}

internal fun ReaderHook.epubPageSpine(page: Any): Int? {
    val vm = currentViewModelRef?.get()
    val window = HostReaderWindowBindings.current
    if (vm != null && window != null) {
        val slot = epubPageNumber(page)
        if (slot != null) window.forward(vm)[slot]?.let { return window.pair(it).first }
    }
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return null
    val table = onDemandSpineTable(epub) ?: return null
    val anchor = HostEpubRuntimeBindings.current?.anchor(page).orEmpty()
    return com.reamicro.fix.online.download.OnlineOnDemandPrefetchPlanner.hostSpinePositionFromCfi(anchor, table.positions)
}

internal fun ReaderHook.epubPageSignature(page: Any?): String? {
    page ?: return null
    val host = HostEpubRuntimeBindings.current ?: return null
    if (!host.type("page").isInstance(page)) return null
    return listOfNotNull(epubPageNumber(page)?.let { "n=$it" },
        host.text("page.start", page).takeIf { it.isNotBlank() }?.let { "s=$it" },
        host.text("page.end", page).takeIf { it.isNotBlank() }?.let { "e=$it" },
        host.anchor(page).takeIf { it.isNotBlank() }?.let { "a=$it" })
        .joinToString("|").takeIf { it.isNotBlank() }
}

internal fun ReaderHook.logCatalogDumpIfNeeded(
    context: CatalogContext,
    catalog: List<CatalogChapterEntry>,
    root: File,
    pathHitCount: Int,
) {
    val key = "${bookKey(context)}:${catalog.size}:${root.absolutePath}"
    if (catalogDumpLoggedForKey == key) return
    catalogDumpLoggedForKey = key
    HookRuntime.log("$LOG_PREFIX catalog dump root=${root.absolutePath} count=${catalog.size} pathHits=$pathHitCount")
    catalog.take(12).forEach { entry ->
        val chapter = entry.chapter
        HookRuntime.log(
            "$LOG_PREFIX catalog dump #${entry.index} class=${chapter.javaClass.name} " +
                "title=${catalogChapterTitle(chapter)} href=${requireNotNull(HostEpubRuntimeBindings.current).text("chapter.href", chapter)} " +
                "id=${catalogChapterId(chapter)} parent=${HostEpubRuntimeBindings.current?.int("chapter.parentOrder", chapter)} " +
                "level=${catalogChapterLevel(chapter, context.catalog)} path=${entry.titlePath} " +
                "fields=${catalogChapterFields(chapter)}",
        )
    }
}

internal fun ReaderHook.catalogChapterFields(chapter: Any): String {
    val host = requireNotNull(HostEpubRuntimeBindings.current)
    return com.reamicro.fix.core.HostEpubRuntimeQueries.records.getValue("chapter").second.joinToString(";") { role ->
        "$role=${host.value("chapter.$role", chapter)?.toString()?.take(80)}"
    }
}

internal fun ReaderHook.indexedCatalogChapters(
    catalog: List<Any>,
    root: File? = null,
    epubTitlePaths: Map<String, List<String>> = root?.let(::epubCatalogTitlePaths).orEmpty(),
): List<CatalogChapterEntry> {
    val parentTitlePaths = catalogParentTitlePaths(catalog)
    val titleStack = ArrayList<String>()
    var currentVolumeTitle = ""
    return catalog.mapIndexed { index, chapter ->
        val title = catalogChapterTitle(chapter).normalizeChapterTitle()
        val id = catalogChapterId(chapter)
        val href = normalizeCatalogHref(requireNotNull(HostEpubRuntimeBindings.current).text("chapter.href", chapter))
        val epubTitlePath = epubTitlePaths.titlePathForHref(href)
        val level = catalogChapterLevel(chapter, catalog).coerceAtLeast(0)
        while (titleStack.size > level) titleStack.removeAt(titleStack.lastIndex)
        // Malformed or filtered catalogs may start below depth zero or skip ancestors.
        // Preserve that missing depth without indexing an empty stack or overwriting a parent.
        while (titleStack.size < level) titleStack.add("")
        titleStack.add(title)
        val sequentialTitlePath = if (isVolumeCatalogTitle(title)) {
            currentVolumeTitle = title
            title
        } else if (currentVolumeTitle.isNotBlank() && title.isNotBlank() && !title.contains(currentVolumeTitle)) {
            listOf(currentVolumeTitle, title).dedupeAdjacent().joinToString(" ")
        } else {
            ""
        }
        val structuralTitleParts = epubTitlePath.ifEmpty {
            parentTitlePaths[id].orEmpty().takeIf { it.size > 1 }
                ?: titleStack.filter { it.isNotBlank() }.dedupeAdjacent()
        }
        CatalogChapterEntry(
            index = index,
            chapter = chapter,
            titlePath = epubTitlePath.joinToString(" ").ifBlank {
                parentTitlePaths[id].orEmpty().joinToString(" ").ifBlank {
                    sequentialTitlePath.ifBlank {
                        titleStack.filter { it.isNotBlank() }.dedupeAdjacent().joinToString(" ")
                    }
                }
            },
            titleParts = structuralTitleParts,
        )
    }
}

internal fun ReaderHook.isVolumeCatalogTitle(value: String): Boolean {
    val compact = value.replace(Regex("\\s+"), "")
    return Regex("""^第[0-9一二三四五六七八九十百千万〇零两]+[卷部篇集].*""").matches(compact) ||
        compact in setOf("正文", "番外", "外传", "后日谈")
}

internal fun ReaderHook.isChapterCatalogTitle(value: String): Boolean {
    val compact = value.replace(Regex("\\s+"), "")
    return Regex("""^第[0-9一二三四五六七八九十百千万〇零两]+[章节回话幕].*""").matches(compact) ||
        Regex("""^(序章|楔子|终章|尾声|后记).*""").matches(compact)
}

internal fun ReaderHook.catalogParentTitlePaths(catalog: List<Any>): Map<Long, List<String>> {
    val host = requireNotNull(HostEpubRuntimeBindings.current)
    val byOrder = catalog.associateBy { host.int("chapter.playOrder", it) }
    val cache = java.util.IdentityHashMap<Any, List<String>>()
    fun pathFor(chapter: Any, visiting: Set<Int> = emptySet()): List<String> {
        cache[chapter]?.let { return it }
        val order = host.int("chapter.playOrder", chapter) ?: return emptyList()
        val parentOrder = host.int("chapter.parentOrder", chapter)
        val parent = parentOrder?.takeIf { it != order && it !in visiting }?.let { byOrder[it] }
        val title = catalogChapterTitle(chapter).normalizeChapterTitle()
        val path = ((parent?.let { pathFor(it, visiting + order) }).orEmpty() + title)
            .filter { it.isNotBlank() }.dedupeAdjacent()
        cache[chapter] = path
        return path
    }
    return catalog.associate { catalogChapterId(it) to pathFor(it) }
}
internal fun ReaderHook.epubCatalogTitlePaths(root: File): Map<String, List<String>> {
    val result = linkedMapOf<String, List<String>>()
    root.walkTopDown()
        .filter { it.isFile && it.extension.equals("ncx", ignoreCase = true) }
        .forEach { file -> result.putAll(parseNcxTitlePaths(root, file)) }
    root.walkTopDown()
        .filter { file ->
            file.isFile &&
                (file.extension.equals("xhtml", ignoreCase = true) || file.extension.equals("html", ignoreCase = true))
        }
        .forEach { file -> result.putAll(parseNavTitlePaths(root, file)) }
    return result
}

internal fun ReaderHook.normalizeCatalogHref(value: String): String {
    val normalized = normalizeHref(value)
    val file = normalizePath(normalized.substringBefore('#'))
    val anchor = normalized.substringAfter('#', "").takeIf { it.isNotBlank() }
    return file + anchor.orEmpty().let { if (it.isBlank()) "" else "#$it" }
}

internal fun ReaderHook.catalogChapterLevel(chapter: Any, catalog: List<Any>): Int {
    val host = requireNotNull(HostEpubRuntimeBindings.current)
    val byOrder = catalog.associateBy { host.int("chapter.playOrder", it) }
    val seen = HashSet<Int>()
    var cursor = chapter
    var depth = 0
    while (true) {
        val order = host.int("chapter.playOrder", cursor) ?: break
        if (!seen.add(order)) break
        val parentOrder = host.int("chapter.parentOrder", cursor) ?: break
        if (parentOrder in seen) break
        cursor = byOrder[parentOrder] ?: break
        depth++
    }
    return depth
}
internal fun ReaderHook.catalogChapterId(chapter: Any): Long =
    (HostEpubRuntimeBindings.current?.value("chapter.id", chapter) as? Number)?.toLong() ?: 0L

internal fun ReaderHook.currentEpubRoot(): File? =
    (currentEpubStrong ?: currentEpubRef?.get())
        ?.let(::epubDirectory)
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory }

internal fun ReaderHook.publishOnlineReaderPage(page: Any) {
    val spineIndex = epubPageSpine(page) ?: return
    OnlineReaderContextBridge.updatePage(currentEpubRoot(), spineIndex)
}

internal fun ReaderHook.epubDirectory(epub: Any?): String =
    HostEpubRuntimeBindings.current?.text("epub.directory", epub).orEmpty()

internal fun ReaderHook.coverUriFromEpubRoot(root: File): String {
    findOpfCoverFile(root)?.let { return it.absolutePath }
    val commonNames = listOf(
        "cover.jpg",
        "cover.jpeg",
        "cover.png",
        "cover.webp",
        "Images/cover.jpg",
        "Images/cover.jpeg",
        "Images/cover.png",
        "Images/cover.webp",
        "OEBPS/cover.jpg",
        "OEBPS/cover.jpeg",
        "OEBPS/cover.png",
        "OEBPS/cover.webp",
        "OEBPS/Images/cover.jpg",
        "OEBPS/Images/cover.jpeg",
        "OEBPS/Images/cover.png",
        "OEBPS/Images/cover.webp",
    )
    commonNames.forEach { path ->
        File(root, path).takeIf { it.isFile && it.isCoverImageFile() }?.let { return it.absolutePath }
    }
    return root.walkTopDown()
        .filter { it.isFile && it.isCoverImageFile() }
        .firstOrNull { it.nameWithoutExtension.contains("cover", ignoreCase = true) }
        ?.absolutePath
        .orEmpty()
}

internal fun ReaderHook.catalogTextFiles(root: File, catalog: List<Any>, itemRefs: List<Any>): List<File> {
    val fromSpine = itemRefs
        .mapIndexedNotNull { position, item ->
            val file = searchFileForHref(root, requireNotNull(HostEpubRuntimeBindings.current).text("item.href", item)) ?: return@mapIndexedNotNull null
            val index = HostEpubRuntimeBindings.current?.int("item.index", item) ?: position
            index to file
        }
        .sortedBy { it.first }
        .map { it.second }
        .distinctBy { it.absolutePath }
    if (fromSpine.isNotEmpty()) return fromSpine

    val fromCatalog = catalog
        .mapNotNull { searchFileForHref(root, requireNotNull(HostEpubRuntimeBindings.current).text("chapter.href", it)) }
        .distinctBy { it.absolutePath }
    if (fromCatalog.isNotEmpty()) return fromCatalog
    return root.walkTopDown()
        .filter { it.isFile && it.isTextContentFile() }
        .map { it.canonicalFileSafe() ?: it }
        .sortedBy { it.absolutePath }
        .toList()
}

internal fun ReaderHook.fileChapterTitleHint(raw: String): String {
    val headTitle = Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
        .find(raw)?.groupValues?.getOrNull(1)
        ?.normalizeChapterTitle()
        .orEmpty()
    if (headTitle.isNotBlank()) return headTitle
    return Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
        .find(raw)?.groupValues?.getOrNull(1)
        ?.normalizeChapterTitle()
        .orEmpty()
}

internal fun ReaderHook.chooseChapterTitle(catalogTitle: String, fileTitle: String): String {
    if (fileTitle.isBlank()) return catalogTitle
    if (catalogTitle.isBlank()) return fileTitle
    if (fileTitle == catalogTitle) return catalogTitle
    if (isBareChapterLabel(catalogTitle) && !isBareChapterLabel(fileTitle)) return fileTitle
    if (fileTitle.length > catalogTitle.length && fileTitle.contains(catalogTitle)) return fileTitle
    return catalogTitle
}

internal fun ReaderHook.isBareChapterLabel(value: String): Boolean {
    if (value.isBlank()) return false
    val compact = value.replace(Regex("\\s+"), "")
    return Regex("""^第[0-9一二三四五六七八九十百千万〇零两]+[章节卷回集部篇]$""").matches(compact) ||
        compact in setOf("番外", "序章", "楔子", "后记", "尾声", "前言")
}

internal fun ReaderHook.catalogChapterTitle(chapter: Any): String =
    HostEpubRuntimeBindings.current?.text("chapter.title", chapter).orEmpty()

internal fun ReaderHook.cfiBaseForFile(
    root: File,
    file: File,
    itemRefs: List<Any>,
    spineCfiIndex: Int,
    chapter: Any?,
): CfiBase? {
    cfiBaseFromChapter(chapter)?.let { return it }
    if (spineCfiIndex < 0) return null
    val relative = normalizePath(relativePath(root, file))
    val itemRef = itemRefs.firstOrNull { item ->
        val href = normalizePath(requireNotNull(HostEpubRuntimeBindings.current).text("item.href", item).substringBefore('#'))
        href.isNotBlank() && (relative == href || relative.endsWith("/$href") || href.endsWith("/$relative"))
    } ?: return null
    val itemRefIndex = HostEpubRuntimeBindings.current?.int("item.index", itemRef) ?: return null
    return CfiBase(spineCfiIndex, itemRefIndex)
}

internal fun ReaderHook.cfiBaseFromChapter(chapter: Any?): CfiBase? {
    val cfi = HostEpubRuntimeBindings.current?.chapterCfi(chapter)?.toString().orEmpty()
    val match = Regex("""epubcfi\(/(\d+)/(\d+)""").find(cfi) ?: return null
    val spineIndex = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    val itemRefIndex = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
    return CfiBase(spineIndex, itemRefIndex)
}

internal fun ReaderHook.chapterForFile(
    root: File,
    file: File,
    chaptersByHref: Map<String, List<IndexedChapter>>,
    chaptersByFile: Map<String, List<IndexedChapter>>,
): IndexedChapter? {
    val relative = normalizePath(relativePath(root, file))
    val canonicalPath = (file.canonicalFileSafe() ?: file).absolutePath
    return chaptersByFile[canonicalPath]?.firstOrNull()
        ?: chaptersByHref[relative]?.firstOrNull()
        ?: chaptersByHref.entries.firstOrNull { (href, _) -> sameSearchContentPath(relative, href) }?.value?.firstOrNull()
}

internal fun ReaderHook.chaptersForFile(
    root: File,
    file: File,
    chaptersByHref: Map<String, List<IndexedChapter>>,
    chaptersByFile: Map<String, List<IndexedChapter>>,
): List<IndexedChapter> {
    val relative = normalizePath(relativePath(root, file))
    val canonicalPath = (file.canonicalFileSafe() ?: file).absolutePath
    return buildList {
        chaptersByFile[canonicalPath]?.let(::addAll)
        chaptersByHref[relative]?.let(::addAll)
        chaptersByHref.forEach { (href, chapters) ->
            if (sameSearchContentPath(relative, href)) addAll(chapters)
        }
    }.distinctBy { it.index }
        .sortedBy { it.index }
}

internal fun ReaderHook.chapterAnchorsForFile(raw: String, chapters: List<IndexedChapter>): List<ChapterAnchor> =
    chapters.mapNotNull { chapter ->
        val href = normalizeCatalogHref(requireNotNull(HostEpubRuntimeBindings.current).text("chapter.href", chapter.entry.chapter))
        val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val textStart = htmlAnchorTextStart(raw, anchor) ?: return@mapNotNull null
        ChapterAnchor(
            textStart = textStart,
            index = chapter.index,
            chapter = chapter.entry.chapter,
            title = chapter.entry.titlePath,
            titleParts = chapter.entry.titleParts,
        )
    }.sortedBy { it.textStart }

internal fun ReaderHook.fallbackCurrentChapterTitle(): String =
    HostEpubRuntimeBindings.current?.text("page.chapter", currentReaderPage()).orEmpty()

internal fun ReaderHook.titleFromCurrentEpubRoot(): String =
    currentEpubRoot()
        ?.nameWithoutExtension
        ?.takeIf { it.isNotBlank() }
        .orEmpty()
