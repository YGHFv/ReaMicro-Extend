package com.reamicro.fix.hook

import android.content.Context
import android.widget.Toast
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.settings.ReaderHighlightBookContext
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import com.reamicro.fix.hook.reader.*

// 阅读页目录与翻页簇。
//
// 目录数据读取、cfi 定位、翻页样式、ScrollPager 崩溃兜底、正文渲染宽度兜底。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.currentReaderPage(): Any? =
    currentPageStrong ?: currentPageRef?.get()

internal fun ReaderHook.markScrollCrashPending() {
    val context = activityProvider()?.applicationContext ?: return
    context.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(SCROLL_CRASH_PENDING_KEY, true)
        .apply()
    scrollCrashMarkerOwnedByThisProcess = true
}

internal fun ReaderHook.clearScrollCrashPending(reason: String) {
    val context = activityProvider()?.applicationContext ?: return
    val prefs = context.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
    scrollCrashMarkerOwnedByThisProcess = false
    if (!prefs.getBoolean(SCROLL_CRASH_PENDING_KEY, false)) return
    prefs.edit().remove(SCROLL_CRASH_PENDING_KEY).apply()
    XposedBridge.log("$LOG_PREFIX scroll crash pending cleared: $reason")
}

internal fun ReaderHook.isScrollCrashPending(): Boolean =
    activityProvider()?.applicationContext
        ?.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
        ?.getBoolean(SCROLL_CRASH_PENDING_KEY, false)
        ?: false

internal fun ReaderHook.isPreviousScrollCrashPending(): Boolean =
    isScrollCrashPending() && !scrollCrashMarkerOwnedByThisProcess

// 从阅微原生高亮页点击"补全计划"：通过宿主 NavHost 导航到完整聚合页（复用宿主页面框架与返回）。
// navGraphScope 优先用阅读页底栏捕获到的实例，拿不到时由 ReaMicroSettingsHook 用全局单例兜底。
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
        XposedBridge.log("$LOG_PREFIX open completion plan page failed (navGraphScope unavailable)")
        activity?.runOnUiThread {
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
        val markClass = classLoader.loadClass(MARK_CLASS)
        val targetMark = mark ?: createReaderMark(
            id = -System.currentTimeMillis(),
            chapter = title,
            startCfi = cfi,
            endCfi = cfi,
            quote = summary,
            style = MARK_STYLE_LINE,
            color = MARK_COLOR_RED,
        ) ?: return@runCatching false
        val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$MarkJump")
        val intent = intentClass.getDeclaredConstructor(markClass).newInstance(targetMark)
        dispatchReaderIntent(receiver, viewModel, intent, "MarkJump")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX full-text search mark jump failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (markJumped) {
        XposedBridge.log("$LOG_PREFIX full-text search mark jump dispatched cfi=$cfi")
        return true
    }

    val bookmarkClass = classLoader.loadClass(BOOKMARK_CLASS)
    val bookmark = bookmarkClass
        .getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        .newInstance(
            chapterIndex,
            title,
            summary,
            cfi,
            "",
            0,
        )
    val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$BookmarkJump")
    val intent = intentClass.getDeclaredConstructor(bookmarkClass).newInstance(bookmark)
    return dispatchReaderIntent(receiver, viewModel, intent, "BookmarkJump").also {
        XposedBridge.log("$LOG_PREFIX full-text search bookmark jump dispatched=$it cfi=$cfi")
    }
}

internal fun ReaderHook.isValidEpubCfi(cfi: String): Boolean {
    val looksValid = Regex("""^epubcfi\(/\d+/\d+/4(?:/\d+)*(?::\d+)?\)$""").matches(cfi)
    return runCatching {
        val epubCfiClass = classLoader.loadClass("org.epub.html.EpubCFI")
        val companion = runCatching { epubCfiClass.getField("INSTANCE").get(null) }.getOrNull()
            ?: runCatching { epubCfiClass.getField("Companion").get(null) }.getOrNull()
            ?: return@runCatching looksValid
        val create = companion.javaClass.methods.firstOrNull {
            it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: return@runCatching looksValid
        create.invoke(companion, cfi) != null
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX full-text search cfi validation failed: ${it.stackTraceToString()}")
    }.getOrDefault(looksValid)
}

internal fun ReaderHook.catalogChapterItemMarkId(item: Any?): Long? =
    callNoArg(item, "getMark")?.let(::transientHighlightMarkId)

internal fun ReaderHook.resolveCatalogIndexForCfi(cfi: String, catalog: List<Any>): Int? =
    runCatching {
        if (cfi.isBlank() || catalog.isEmpty()) return@runCatching null
        val cfiObject = createEpubCfi(cfi) ?: return@runCatching null
        val viewModel = currentViewModelRef?.get() ?: return@runCatching null
        (XposedHelpers.callMethod(viewModel, "resolveCatalogIndexByCfi", cfiObject, catalog) as? Number)?.toInt()
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX resolve search highlight catalog index failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.createEpubCfi(cfi: String): Any? =
    runCatching {
        if (cfi.isBlank()) return@runCatching null
        val cfiClass = classLoader.loadClass("org.epub.html.EpubCFI")
        val cfiObject = companionObject(cfiClass) ?: return@runCatching null
        val create = cfiObject.javaClass.methods.firstOrNull {
            it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: return@runCatching null
        create.invoke(cfiObject, cfi)
    }.getOrNull()

internal fun ReaderHook.epubPageNumber(page: Any?): Int? =
    (callNoArg(page, "getNumber") as? Number)?.toInt()
        ?: (callNoArg(page, "getIndex") as? Number)?.toInt()

internal fun ReaderHook.epubPageSignature(page: Any?): String? {
    val range = callNoArg(page, "getRange")?.toString().orEmpty()
    val anchor = callString(page, "getAnchor")
        .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
    val number = epubPageNumber(page)
    val signature = listOfNotNull(
        number?.let { "n=$it" },
        range.takeIf { it.isNotBlank() }?.let { "r=$it" },
        anchor.takeIf { it.isNotBlank() }?.let { "a=$it" },
    ).joinToString("|")
    return signature.takeIf { it.isNotBlank() }
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
    XposedBridge.log("$LOG_PREFIX catalog dump root=${root.absolutePath} count=${catalog.size} pathHits=$pathHitCount")
    catalog.take(12).forEach { entry ->
        val chapter = entry.chapter
        XposedBridge.log(
            "$LOG_PREFIX catalog dump #${entry.index} class=${chapter.javaClass.name} " +
                "title=${catalogChapterTitle(chapter)} href=${callString(chapter, "getHref")} " +
                "id=${catalogChapterId(chapter)} parent=${catalogChapterParentId(chapter)} " +
                "level=${catalogChapterLevel(chapter, entry.index)} path=${entry.titlePath} " +
                "fields=${catalogChapterFields(chapter)}",
        )
    }
}

internal fun ReaderHook.catalogChapterFields(chapter: Any): String =
    chapter.javaClass.declaredFields.take(12).joinToString(";") { field ->
        runCatching {
            field.isAccessible = true
            "${field.name}=${field.get(chapter)?.toString()?.take(80)}"
        }.getOrDefault("${field.name}=?")
    }

internal fun ReaderHook.indexedCatalogChapters(
    catalog: List<Any>,
    root: File? = null,
    epubTitlePaths: Map<String, String> = root?.let(::epubCatalogTitlePaths).orEmpty(),
): List<CatalogChapterEntry> {
    val parentTitlePaths = catalogParentTitlePaths(catalog)
    val titleStack = ArrayList<String>()
    var currentVolumeTitle = ""
    return catalog.mapIndexed { index, chapter ->
        val title = catalogChapterTitle(chapter).normalizeChapterTitle()
        val id = catalogChapterId(chapter)
        val href = normalizeCatalogHref(callString(chapter, "getHref"))
        val epubTitlePath = epubTitlePaths.titlePathForHref(href)
        val level = catalogChapterLevel(chapter, index).coerceAtLeast(0)
        while (titleStack.size > level) titleStack.removeAt(titleStack.lastIndex)
        if (titleStack.size == level) {
            titleStack.add(title)
        } else {
            titleStack[titleStack.lastIndex] = title
        }
        val sequentialTitlePath = if (isVolumeCatalogTitle(title)) {
            currentVolumeTitle = title
            title
        } else if (currentVolumeTitle.isNotBlank() && title.isNotBlank() && !title.contains(currentVolumeTitle)) {
            listOf(currentVolumeTitle, title).dedupeAdjacent().joinToString(" ")
        } else {
            ""
        }
        CatalogChapterEntry(
            index = index,
            chapter = chapter,
            titlePath = epubTitlePath.ifBlank {
                parentTitlePaths[id].orEmpty().ifBlank {
                    sequentialTitlePath.ifBlank {
                        titleStack.filter { it.isNotBlank() }.dedupeAdjacent().joinToString(" ")
                    }
                }
            },
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

internal fun ReaderHook.catalogParentTitlePaths(catalog: List<Any>): Map<Long, String> {
    val byId = catalog.mapNotNull { chapter ->
        val id = catalogChapterId(chapter).takeIf { it != 0L } ?: return@mapNotNull null
        id to chapter
    }.toMap()
    val result = linkedMapOf<Long, String>()
    fun pathFor(chapter: Any, visiting: Set<Long> = emptySet()): String {
        val id = catalogChapterId(chapter)
        result[id]?.let { return it }
        val title = catalogChapterTitle(chapter).normalizeChapterTitle()
        val parentId = catalogChapterParentId(chapter).takeIf { it != 0L && it != id }
        val parent = parentId?.takeIf { it !in visiting }?.let { byId[it] }
        val path = if (parent != null) {
            listOf(pathFor(parent, visiting + id), title)
                .filter { it.isNotBlank() }
                .dedupeAdjacent()
                .joinToString(" ")
        } else {
            title
        }
        if (id != 0L) result[id] = path
        return path
    }
    catalog.forEach { pathFor(it) }
    return result
}

internal fun ReaderHook.epubCatalogTitlePaths(root: File): Map<String, String> {
    val result = linkedMapOf<String, String>()
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

internal fun ReaderHook.catalogChapterLevel(chapter: Any, index: Int): Int {
    listOf("getLevel", "getDepth", "getIndent", "getTier", "getLayer").forEach { method ->
        (callNoArg(chapter, method) as? Number)?.toInt()?.let { return it }
    }
    listOf("level", "depth", "indent", "tier", "layer").forEach { fieldName ->
        runCatching {
            chapter.javaClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                field.isAccessible = true
                (field.get(chapter) as? Number)?.toInt()
            }
        }.getOrNull()?.let { return it }
    }
    val value = chapter.toString()
    Regex("""(?:level|depth|indent|tier|layer)=(-?\d+)""")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return it }
    return if (index == 0) 0 else 0
}

internal fun ReaderHook.catalogChapterId(chapter: Any): Long =
    catalogChapterLongValue(chapter, listOf("getId", "getID", "getChapterId", "getChapterID"), listOf("id", "chapterId"))

internal fun ReaderHook.catalogChapterParentId(chapter: Any): Long =
    catalogChapterLongValue(
        chapter,
        listOf("getParentId", "getParentID", "getPid", "getPId", "getParentChapterId", "getParentChapterID"),
        listOf("parentId", "parentID", "pid", "pId", "parentChapterId"),
    )

internal fun ReaderHook.catalogChapterLongValue(chapter: Any, methods: List<String>, fields: List<String>): Long {
    methods.forEach { method ->
        (callNoArg(chapter, method) as? Number)?.toLong()?.let { return it }
    }
    fields.forEach { fieldName ->
        runCatching {
            chapter.javaClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                field.isAccessible = true
                val value = field.get(chapter)
                (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()
            }
        }.getOrNull()?.let { return it }
    }
    return 0L
}

internal fun ReaderHook.currentEpubRoot(): File? =
    (currentEpubStrong ?: currentEpubRef?.get())
        ?.let(::epubDirectory)
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isDirectory }

internal fun ReaderHook.publishOnlineReaderPage(page: Any) {
    val spineIndex = (callNoArg(page, "getSpineIndex") as? Number)?.toInt() ?: return
    OnlineReaderContextBridge.updatePage(currentEpubRoot(), spineIndex)
}

internal fun ReaderHook.epubDirectory(epub: Any?): String =
    callNoArg(epub, "getDirectory")?.toString().orEmpty()

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
            val file = searchFileForHref(root, callString(item, "getHref")) ?: return@mapIndexedNotNull null
            val index = (callNoArg(item, "getIndex") as? Number)?.toInt() ?: position
            index to file
        }
        .sortedBy { it.first }
        .map { it.second }
        .distinctBy { it.absolutePath }
    if (fromSpine.isNotEmpty()) return fromSpine

    val fromCatalog = catalog
        .mapNotNull { searchFileForHref(root, callString(it, "getHref")) }
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

internal fun ReaderHook.catalogChapterTitle(chapter: Any): String {
    listOf("getTitle", "getName", "getLabel", "getText", "getChapterName").forEach { method ->
        callString(chapter, method).takeIf { it.isNotBlank() }?.let { return it }
    }
    listOf("title", "name", "label", "text", "chapterName").forEach { fieldName ->
        runCatching {
            chapter.javaClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                field.isAccessible = true
                field.get(chapter)?.toString()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.let { return it }
    }
    val value = chapter.toString()
    Regex("""(?:title|name|label|text|chapterName)=([^,\)]+)""")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return ""
}

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
        val href = normalizePath(callString(item, "getHref").substringBefore('#'))
        href.isNotBlank() && (relative == href || relative.endsWith("/$href") || href.endsWith("/$relative"))
    } ?: return null
    val itemRefIndex = (callNoArg(itemRef, "getIndex") as? Number)?.toInt() ?: return null
    return CfiBase(spineCfiIndex, itemRefIndex)
}

internal fun ReaderHook.cfiBaseFromChapter(chapter: Any?): CfiBase? {
    val cfi = callNoArg(chapter, "getCfi")?.toString().orEmpty()
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
        val href = normalizeCatalogHref(callString(chapter.entry.chapter, "getHref"))
        val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val textStart = htmlAnchorTextStart(raw, anchor) ?: return@mapNotNull null
        ChapterAnchor(
            textStart = textStart,
            index = chapter.index,
            chapter = chapter.entry.chapter,
            title = chapter.entry.titlePath,
        )
    }.sortedBy { it.textStart }

internal fun ReaderHook.fallbackCurrentChapterTitle(): String {
    val page = currentPageRef?.get()
    return callString(callNoArg(page, "getChapter"), "getTitle")
        .ifBlank { callString(page, "getTitle") }
}

internal fun ReaderHook.titleFromCurrentEpubRoot(): String =
    currentEpubRoot()
        ?.nameWithoutExtension
        ?.takeIf { it.isNotBlank() }
        .orEmpty()
