package com.reamicro.fix.hook

import java.io.File
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.searchFullTextStreaming(
    keyword: String,
    context: CatalogContext,
    request: ReaderSearchRequest,
    onUpdate: (List<FullTextSearchResult>, Boolean) -> Unit,
) {

    fun current() = request.accepts(searchStateGeneration, searchRunSeq, activeSearchPageToken, searchPageDialogRef?.get() != null)
    if (!current()) return
    val key = bookKey(context)
    if (keyword.isBlank()) { onUpdate(emptyList(), true); return }
    val results = ArrayList<FullTextSearchResult>()
    val throttle = SearchUpdateThrottle(SEARCH_EMIT_INTERVAL_MS)
    fun emit(done: Boolean) {
        if (current() && throttle.accept(results.size, android.os.SystemClock.elapsedRealtime(), done)) {

            onUpdate(ArrayList(results), done)
        }
    }
    val cachedIndex = searchIndexState?.takeIf { it.bookKey == key && it.documents.all { document ->
        document.file.lastModified() == document.sourceLastModified && document.file.length() == document.sourceLength
    } }
    if (cachedIndex != null) {
        cachedIndex.completedQuery?.takeIf { it.keyword == keyword }?.let {
            if (current()) onUpdate(it.resultsForReceiver(context.intentReceiver), true)
            return
        }
        for (document in cachedIndex.documents) {
            if (!current()) return
            if (results.size >= MAX_SEARCH_RESULTS) break
            val previousSize = results.size
            appendSearchMatches(document, keyword, context, results, ::current)
            if (results.size != previousSize) emit(done = false)
        }
        if (current()) searchIndexState = cachedIndex.copy(completedQuery = SearchState(key, keyword, results.toList()))
        emit(done = true)
        return
    }

    val documents = ArrayList<SearchDocument>()
    var complete = true
    forEachSearchDocument(context, shouldContinue = ::current) { document ->
        if (!current()) { complete = false; return@forEachSearchDocument false }
        if (results.size < MAX_SEARCH_RESULTS) {
            val previousSize = results.size
            appendSearchMatches(document, keyword, context, results, ::current)
            if (results.size != previousSize) emit(done = false)
        }
        documents.add(document)
        if (results.size >= MAX_SEARCH_RESULTS) { complete = false; false } else true
    }
    if (complete && current() && bookKey(context) == key) {
        searchIndexState = SearchIndexState(key, documents, SearchState(key, keyword, results.toList()))
    }
    emit(done = true)
}

internal fun ReaderHook.appendSearchMatches(
    document: SearchDocument,
    keyword: String,
    context: CatalogContext,
    results: ArrayList<FullTextSearchResult>,
    current: () -> Boolean,
) {
    if (keyword.isEmpty() || !current()) return
    var from = document.text.indexOf(keyword, ignoreCase = true)
    if (from < 0) return
    var countInFile = 0
    val radius = (if (document.text.any { it.code > 127 }) SEARCH_CJK_SNIPPET_RADIUS else SEARCH_SNIPPET_RADIUS) + SEARCH_SNIPPET_EXTRA_RADIUS
    while (results.size < MAX_SEARCH_RESULTS && countInFile < MAX_MATCHES_PER_FILE) {
        if (!current()) return
        val index = document.text.indexOf(keyword, from, ignoreCase = true)
        if (index < 0) break
        val snippet = searchSnippet(document.text, index, index + keyword.length, radius)
        val chapterAnchor = document.chapterAnchors.lastOrNull { it.textStart <= index }
        val resultChapter = chapterAnchor?.chapter ?: document.chapter
        val resultChapterIndex = chapterAnchor?.index ?: document.chapterIndex
        val resultChapterTitle = chapterAnchor?.title ?: document.chapterTitle
        val startCfi = document.indexedText.cfiAt(index)
        val cfi = startCfi
        val endCfi = document.indexedText.cfiAtBoundary(index + keyword.length)
        if (startCfi == null || cfi == null || endCfi == null) {
            from = index + keyword.length
            continue
        }
        results.add(
            FullTextSearchResult(
                chapterIndex = resultChapterIndex,
                chapter = resultChapter,
                chapterTitle = resultChapterTitle,
                intentReceiver = context.intentReceiver,
                startCfi = startCfi,
                cfi = cfi,
                endCfi = endCfi,
                file = document.file,
                snippet = snippet.text,
                snippetMatchStart = snippet.matchStart,
                snippetMatchEnd = snippet.matchEnd,
                matchText = document.text.substring(index, (index + keyword.length).coerceAtMost(document.text.length)),
                sourceDigest = document.indexedText.sourceDigest,
            ),
        )
        countInFile++
        from = index + keyword.length.coerceAtLeast(1)
    }
}

internal fun ReaderHook.forEachSearchDocument(
    context: CatalogContext,
    explicitFiles: List<File>? = null,
    shouldContinue: () -> Boolean = { true },
    onDocument: (SearchDocument) -> Boolean,
) {
    if (!shouldContinue()) return
    val epub = currentEpubRef?.get()
    val root = currentEpubRoot() ?: return
    val epubTitlePaths = epubCatalogTitlePaths(root)
    val indexedCatalog = indexedCatalogChapters(context.catalog, root, epubTitlePaths)
    val chaptersByHref = indexedCatalog
        .mapIndexedNotNull { _, chapter ->
            val href = normalizeCatalogHref(callString(chapter.chapter, "getHref"))
            if (href.isBlank()) null else href to IndexedChapter(chapter.index, chapter)
        }
        .groupBy({ it.first }, { it.second })
    val chaptersByFile = indexedCatalog
        .mapIndexedNotNull { _, chapter ->
            val file = searchFileForHref(root, callString(chapter.chapter, "getHref")) ?: return@mapIndexedNotNull null
            file.absolutePath to IndexedChapter(chapter.index, chapter)
        }
        .groupBy({ it.first }, { it.second })
    val itemRefs = (epub?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.filterNotNull().orEmpty()
    val spineCfiIndex = (epub?.let { callNoArg(it, "getSpineCfiIndex") } as? Int) ?: -1
    val catalogFiles = catalogTextFiles(root, context.catalog, itemRefs)
    val files = explicitFiles
        ?.map { it.canonicalFileSafe() ?: it }
        ?.filter { it.isFile && it.isTextContentFile() }
        ?.distinctBy { it.absolutePath }
        ?.takeIf { it.isNotEmpty() }
        ?: catalogFiles
    for (file in files) {
        if (!shouldContinue()) return
        val cached = searchDocumentCache.get(file, epub)
        if (cached != null) {
            if (!onDocument(cached)) return
            continue
        }
        val raw = com.reamicro.fix.epub.editor.EpubTextFiles.load(file, checkCancelled = {
            if (!shouldContinue()) throw java.util.concurrent.CancellationException()
        }).text
        val chapter = chapterForFile(root, file, chaptersByHref, chaptersByFile)
        val chapterAnchors = chapterAnchorsForFile(raw, chaptersForFile(root, file, chaptersByHref, chaptersByFile))
        val fallbackTitlePath = epubTitlePaths.titlePathForFile(root, file)
        val cfiBase = cfiBaseForFile(root, file, itemRefs, spineCfiIndex, chapter?.entry?.chapter)
        val indexedText = indexedSearchText(raw, cfiBase)
        val text = indexedText.text.ifBlank { htmlToSearchText(raw) }
        if (text.isBlank()) continue
        val document =
            SearchDocument(
                file = file,
                chapterIndex = chapter?.index ?: -1,
                chapter = chapter?.entry?.chapter,
                chapterTitle = searchChapterTitle(raw, chapter?.entry, file, fallbackTitlePath),
                readAloudChapterTitle = chooseReadAloudDirectTitle(
                    readAloudHtmlChapterTitleHint(raw),
                    directReadAloudChapterTitle(chapter?.entry),
                ),
                text = text,
                indexedText = indexedText,
                chapterAnchors = chapterAnchors,
            )
        if (!shouldContinue()) return
        searchDocumentCache.put(document, epub)
        if (!onDocument(document)) return
    }
}

internal fun searchSnippet(text: String, start: Int, end: Int, compactRadius: Int): SearchSnippet {
    val from = (start - compactRadius).coerceAtLeast(0)
    val to = (end + compactRadius).coerceAtMost(text.length)
    val prefix = if (from > 0) "\u2026" else ""
    val suffix = if (to < text.length) "\u2026" else ""
    val body = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
    val leadingTrim = text.substring(from, start).length -
        text.substring(from, start).replace(Regex("^\\s+"), "").length
    val matchStart = prefix.length + (start - from - leadingTrim).coerceAtLeast(0)
    val matchEnd = (matchStart + (end - start)).coerceAtMost(prefix.length + body.length)
    return SearchSnippet(prefix + body + suffix, matchStart, matchEnd)
}

internal fun SearchState.resultsForReceiver(receiver: Any?): List<FullTextSearchResult> =
    if (results.all { it.intentReceiver === receiver }) results
    else results.map { it.copy(intentReceiver = receiver) }
