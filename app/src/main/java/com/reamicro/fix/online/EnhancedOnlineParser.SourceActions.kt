package com.reamicro.fix.online

import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.reload.TaskCancellation
import io.legado.app.data.entities.Book
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import org.json.JSONObject

internal data class EnhancedSourceActionBook(val source: OnlineSourceEntry, val book: DiscoverBook)

/**
 * Selection semantics from BookSearchScope/SearchScope and AddToBookshelfDialog.
 * These helpers never change enabled-source settings or edit a source's rules.
 */
internal object EnhancedSourceActionSelection {
    data class Scope(val sources: List<String> = emptyList(), val groups: List<String> = emptyList())

    fun parseScope(raw: String?): Scope {
        val text = raw.orEmpty()
        if (text.isEmpty()) return Scope()
        val json = text.trim().takeIf { it.startsWith("{") && it.endsWith("}") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json != null) {
            when (json.optString("type")) {
                "source" -> {
                    val values = json.optJSONArray("sources")
                    return Scope(sources = (0 until (values?.length() ?: 0)).mapNotNull {
                        values?.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank)
                    })
                }
                "group" -> {
                    val values = json.optJSONArray("groups")
                    return Scope(groups = (0 until (values?.length() ?: 0)).mapNotNull {
                        values?.opt(it)?.takeUnless { value -> value == JSONObject.NULL || value is JSONObject || value is org.json.JSONArray }
                            ?.toString()?.takeIf(String::isNotBlank)
                    })
                }
            }
        }
        val items = text.split(',').filter(String::isNotBlank)
        val sources = items.mapNotNull {
            val split = it.indexOf("::")
            if (split <= 0 || split >= it.lastIndex) null else it.substring(split + 2)
        }
        return if (items.isNotEmpty() && sources.size == items.size) Scope(sources = sources)
            else Scope(groups = text.split(',').map(String::trim).filter(String::isNotBlank))
    }

    fun source(caller: OnlineSourceEntry, all: List<OnlineSourceEntry>, origin: String?): OnlineSourceEntry =
        origin?.let { key -> all.firstOrNull { it.sourceUrl == key } } ?: caller

    fun searchSources(
        all: List<OnlineSourceEntry>,
        enabled: List<OnlineSourceEntry>,
        rawScope: String?,
    ): List<OnlineSourceEntry> {
        val scope = parseScope(rawScope)
        val selected = when {
            scope.sources.isNotEmpty() -> scope.sources.mapNotNull { key -> all.firstOrNull { it.sourceUrl == key } }
            scope.groups.isNotEmpty() -> enabled.filter { source ->
                val group = metadata(source).optString("bookSourceGroup")
                group.split(',').any { it in scope.groups }
            }
            else -> enabled
        }
        // SearchScope.getBookSourceParts falls back to enabled sources when a
        // supplied scope no longer resolves to an installed source or group.
        return selected.ifEmpty { enabled }.distinctBy { it.id }
            .sortedBy { metadata(it).optInt("customOrder", 0) }
    }

    fun addBookCandidates(
        bookUrl: String,
        all: List<OnlineSourceEntry>,
        enabled: List<OnlineSourceEntry>,
    ): List<OnlineSourceEntry> {
        require(bookUrl.isNotBlank()) { "url不能为空" }
        val base = NetworkUtils.getBaseUrl(bookUrl) ?: error("书籍地址格式不对")
        val candidates = ArrayList<OnlineSourceEntry>()
        val match = AnalyzeUrl.paramPattern.find(bookUrl)
        if (match != null) {
            val origin = runCatching {
                GSON.fromJson(bookUrl.substring(match.range.last + 1), AnalyzeUrl.UrlOption::class.java).getOrigin()
            }.getOrNull()
            all.firstOrNull { it.sourceUrl == origin }?.let(candidates::add)
        }
        enabled.firstOrNull { it.sourceUrl == base }?.let(candidates::add)
        enabled.sortedBy { metadata(it).optInt("customOrder", 0) }.forEach { source ->
            val pattern = metadata(source).optString("bookUrlPattern").trim()
            if (pattern.isNotEmpty() && pattern != "NONE" &&
                runCatching { bookUrl.matches(pattern.toRegex()) }.getOrDefault(false)) {
                candidates += source
            }
        }
        return candidates.distinctBy { it.id }
    }

    private fun metadata(source: OnlineSourceEntry): JSONObject =
        runCatching { JSONObject(source.rawJson) }.getOrElse { JSONObject() }
}

/** Get book metadata only; host import still requires the user's existing download action. */
internal fun EnhancedOnlineParser.addBookAction(
    bookUrl: String,
    all: List<OnlineSourceEntry>,
    enabled: List<OnlineSourceEntry>,
): EnhancedSourceActionBook {
    val mode = OnlineParserMode.capture()
    for (source in EnhancedSourceActionSelection.addBookCandidates(bookUrl, all, enabled)) {
        TaskCancellation.checkCurrent()
        OnlineParserMode.requireCurrent(mode.generation)
        val attempt = runCatching {
            OnlineSourceTask(source).use { task ->
                task.run {
                    run(source, mode) { op ->
                        val book = Book(bookUrl = bookUrl, origin = source.sourceUrl, originName = source.name)
                        WebBook.getBookInfoAwait(op.upstream, book)
                        EnhancedSourceActionBook(source, DiscoverBook(
                            name = book.name, author = book.author, coverUrl = book.coverUrl.orEmpty(),
                            detailUrl = book.bookUrl, intro = book.intro.orEmpty(), kind = book.kind.orEmpty(),
                            lastChapter = book.latestChapterTitle.orEmpty(), wordCount = book.wordCount.orEmpty(),
                            parsingState = mode.copy(bookJson = GSON.toJson(book),
                                sourceTemporaryVariable = op.upstream.getTemporaryVariable()),
                        ))
                    }
                }
            }
        }.onFailure(TaskCancellation::rethrowIfCancelled)
        attempt.getOrNull()?.let { return it }
    }
    error("未找到匹配书源或匹配书源未能读取书籍详情")
}

internal fun EnhancedOnlineParser.searchAction(
    source: OnlineSourceEntry, keyword: String, page: Int,
): List<EnhancedSourceActionBook> = search(source, keyword, page).map { item ->
    EnhancedSourceActionBook(source, DiscoverBook(
        name = item.name, author = item.author, coverUrl = item.coverUrl, detailUrl = item.detailUrl,
        intro = item.intro, wordCount = item.wordCount, status = item.status, updateTime = item.updateTime,
        chapterCount = item.chapterCount.takeIf { it > 0 }?.toString().orEmpty(), parsingState = item.parsingState,
    ))
}
