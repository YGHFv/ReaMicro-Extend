package com.reamicro.fix.discover

import com.reamicro.fix.hook.WebDavDriveHook
import com.reamicro.fix.hook.applyOnlineTemplate
import com.reamicro.fix.hook.normalizeOnlineCoverUrl
import com.reamicro.fix.hook.requestOnlineSearch
import com.reamicro.fix.hook.webdav.cleanOnlineMultilineText
import com.reamicro.fix.hook.webdav.cleanOnlineText
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.search.onlineJsonPrimitive
import com.reamicro.fix.online.search.onlineJsonRuleValues
import com.reamicro.fix.online.search.onlineCompletionStatusText
import com.reamicro.fix.online.search.resolveOnlineUrlCompat
import com.reamicro.fix.online.search.sourceBaseUrl
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal object DiscoverRepository {

    private const val MAX_BOOKS = 60

    fun parseKinds(source: OnlineSourceEntry): List<DiscoverKind> =
        parseExploreKinds(source.exploreUrl)

    fun parseFilter(source: OnlineSourceEntry): DiscoverFilter? =
        parseExploreFilter(source.exploreUrl)

    fun loadBooks(source: OnlineSourceEntry, kind: DiscoverKind, page: Int = 1): DiscoverLoadState {
        val hook = WebDavDriveHook.activeInstance
            ?: return DiscoverLoadState.Failed("在线书源模块未就绪")
        val base = sourceBaseUrl(source)
        if (base.isBlank()) return DiscoverLoadState.Failed("书源缺少 baseUrl")
        val rule = runCatching { JSONObject(source.ruleExplore) }.getOrNull()
        var lastError = ""
        kind.urls.forEach { rawUrl ->
            val resolved = resolveKindUrl(source, rawUrl, page)
            if (resolved.isBlank()) return@forEach
            val response = runCatching {
                hook.requestOnlineSearch(source, resolved)
            }.onFailure {
                lastError = it.message.orEmpty().ifBlank { it.javaClass.simpleName }
            }.getOrNull() ?: return@forEach

            val books = runCatching {
                parseBooks(source, response.url.ifBlank { resolved }, response.body, rule)
            }.onFailure {
                lastError = it.message.orEmpty().ifBlank { it.javaClass.simpleName }
            }.getOrDefault(emptyList())
            if (books.isNotEmpty()) return DiscoverLoadState.Loaded(books)
        }
        return if (lastError.isBlank()) {
            DiscoverLoadState.Loaded(emptyList())
        } else {
            DiscoverLoadState.Failed(lastError)
        }
    }

    private fun resolveKindUrl(source: OnlineSourceEntry, rawUrl: String, page: Int): String {
        val hook = WebDavDriveHook.activeInstance ?: return ""
        val text = rawUrl.trim()
        if (text.isBlank()) return ""
        if (text.startsWith("@js:", ignoreCase = true) || text.startsWith("<js>", ignoreCase = true)) {
            return ""
        }
        val base = sourceBaseUrl(source)
        val filled = if (text.contains("{{")) {
            runCatching {
                hook.applyOnlineTemplate(
                    raw = text,
                    node = null,
                    baseUrl = base,
                    query = null,
                    page = page,
                    source = source,
                )
            }.getOrDefault(text)
        } else {
            text
        }
        return resolveOnlineUrlCompat(base, filled)
    }

    private fun parseBooks(
        source: OnlineSourceEntry,
        baseUrl: String,
        body: String,
        rule: JSONObject?,
    ): List<DiscoverBook> {
        val text = body.trim()
        if (text.isBlank()) return emptyList()
        if (!text.startsWith("{") && !text.startsWith("[")) {

            return parseHtmlBooks(baseUrl, text)
        }
        val root = runCatching {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        }.getOrNull() ?: return emptyList()

        val nodes = if (rule != null) {
            onlineJsonRuleValues(root, rule.optString("bookList", ""))
        } else {
            emptyList()
        }
        val effectiveNodes = nodes.ifEmpty { collectCandidateNodes(root) }
        return effectiveNodes
            .mapNotNull { node -> toBook(source, baseUrl, node, rule) }
            .filter { it.name.isNotBlank() && it.name.length <= 120 }
            .distinctBy { it.key }
            .take(MAX_BOOKS)
    }

    private fun collectCandidateNodes(root: Any): List<Any> {
        val nodes = mutableListOf<Any>()
        fun visit(value: Any?) {
            if (nodes.size >= MAX_BOOKS * 2) return
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    if (looksLikeBook(value)) nodes += value
                    value.keys().asSequence().forEach { key ->
                        val child = value.opt(key)
                        if (child is JSONArray || child is JSONObject) visit(child)
                    }
                }
            }
        }
        visit(root)
        return nodes
    }

    private fun looksLikeBook(json: JSONObject): Boolean =
        BOOK_NAME_KEYS.any { key ->
            val value = json.optString(key, "").trim()
            value.isNotBlank() && value.length <= 120
        }

    private fun toBook(
        source: OnlineSourceEntry,
        baseUrl: String,
        node: Any?,
        rule: JSONObject?,
    ): DiscoverBook? {
        if (node == null || node == JSONObject.NULL) return null
        fun value(vararg keys: String): String {
            if (rule != null) {
                keys.forEach { key ->
                    val rawRule = rule.optString(key, "")
                    if (rawRule.isNotBlank()) {
                        val resolved = resolveRuleValue(node, rawRule, baseUrl, source)
                        if (resolved.isNotBlank()) return resolved
                    }
                }
            }
            return firstJsonString(node, *keys)
        }
        val name = value("name", "bookName", "title", "novelName").cleanOnlineText()
        if (name.isBlank()) return null
        val detail = value("bookUrl", "url", "detailUrl", "href", "link")

        val statusHint = value("status", "bookStatus", "serializeStatus", "serializeStatusName")
        val coverChosen = resolveOnlineUrlCompat(baseUrl, value("coverUrl", "cover", "img", "image"))

        val coverUrl = if (coverChosen.contains("novel-pic-r", ignoreCase = true)) {
            val thumb = runCatching {
                WebDavDriveHook.activeInstance?.normalizeOnlineCoverUrl(
                    source,
                    baseUrl,
                    firstJsonString(node, "thumb_url", "thumbUrl"),
                )
            }.getOrNull().orEmpty()
            thumb.ifBlank { coverChosen }
        } else {
            coverChosen
        }
        return DiscoverBook(
            name = name,
            author = value("author", "writer", "authorName", "bookAuthor").cleanOnlineText(),
            coverUrl = coverUrl,
            detailUrl = resolveOnlineUrlCompat(baseUrl, detail),

            intro = value("intro", "description", "desc", "summary").cleanOnlineMultilineText(),
            kind = value("kind", "category", "categoryName", "class").cleanOnlineText(),
            lastChapter = value("lastChapter", "latestChapter", "lastChapterTitle").cleanOnlineText(),
            updateTime = value("updateTime", "lastUpdateTime", "update").cleanOnlineText(),
            wordCount = value("wordCount", "words", "length").cleanOnlineText(),
            status = runCatching {
                onlineCompletionStatusText("", node, statusHint.ifBlank { value("lastChapter", "latestChapter") })
            }.getOrDefault(""),
            chapterCount = value("chapterCount", "chapters", "chapterNum", "totalChapter", "totalChapterNum")
                .cleanOnlineText(),
        )
    }

    private fun resolveRuleValue(
        node: Any?,
        rawRule: String,
        baseUrl: String,
        source: OnlineSourceEntry,
    ): String {
        val rule = rawRule.trim()
        if (rule.isBlank()) return ""

        if (rule.contains("{{")) {
            val hook = WebDavDriveHook.activeInstance ?: return ""
            return runCatching {
                hook.applyOnlineTemplate(
                    raw = rule,
                    node = node,
                    baseUrl = baseUrl,
                    query = null,
                    source = source,
                )
            }.getOrDefault("").trim()
        }
        val values = runCatching { onlineJsonRuleValues(node, rule) }.getOrDefault(emptyList())
        val first = values.firstOrNull() ?: return ""
        return resolveKnownUrlKey(rule, onlineJsonPrimitive(first), baseUrl, source)
    }

    private fun resolveKnownUrlKey(
        rule: String,
        value: String,
        baseUrl: String,
        source: OnlineSourceEntry,
    ): String {
        if (value.isBlank()) return ""
        val needsResolve = URL_RULE_MARKERS.any { rule.contains(it) }
        if (!needsResolve) return value

        val absolute = resolveOnlineUrlCompat(baseUrl, value)
        return absolute.ifBlank { value }
    }

    private fun parseHtmlBooks(baseUrl: String, html: String): List<DiscoverBook> {
        val books = mutableListOf<DiscoverBook>()
        ANCHOR_REGEX.findAll(html).forEach { match ->
            if (books.size >= MAX_BOOKS) return@forEach
            val href = match.groupValues[1].trim()
            val text = match.groupValues[2]
                .replace(Regex("(?is)<[^>]+>"), "")
                .cleanOnlineText()
            if (text.isBlank() || text.length > 60) return@forEach
            if (text.length < 2) return@forEach
            books += DiscoverBook(
                name = text,
                author = "",
                coverUrl = "",
                detailUrl = resolveOnlineUrlCompat(baseUrl, href),
                intro = "",
            )
        }
        return books.distinctBy { it.key }
    }

    private fun firstJsonString(node: Any?, vararg keys: String): String {
        if (node !is JSONObject) return ""
        keys.forEach { key ->
            val value = node.opt(key)
            val text = onlineJsonPrimitive(value)
            if (text.isNotBlank() && text != "null") return text
        }
        return ""
    }

    private val BOOK_NAME_KEYS = listOf("bookName", "name", "title", "novelName")

    private val URL_RULE_MARKERS = listOf("bookUrl", "coverUrl", "url", "cover", "img", "href", "link")

    private val ANCHOR_REGEX = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
}

private const val MAX_KIND_TITLE = 20

internal fun parseExploreKindsScript(raw: String): List<DiscoverKind> {
    val script = raw.trim().removePrefix("@js:").trim()
    val groupsText = Regex("""var\s+groups\s*=\s*(\[[\s\S]*?\])\s*;""")
        .find(script)
        ?.groupValues
        ?.getOrNull(1)
        ?: return emptyList()

    val prefix = Regex("""return\s*'([^']*)'\s*\+\s*parts\.join\('([^']*)'\)""")
        .find(script)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    if (prefix.isBlank()) return emptyList()
    val extras = Regex("""parts\.push\('([^']*)'\)""")
        .findAll(script)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .filter { it.isNotBlank() }
        .toList()

    val entryRegex = Regex("\"([^\"]+)\"\\s*,\\s*\\{([^{}]*)\\}")
    val pairRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
    val kinds = mutableListOf<DiscoverKind>()
    for (entry in entryRegex.findAll(groupsText)) {
        val title = entry.groupValues[1].trim()
        if (title.isBlank()) continue
        val parts = pairRegex.findAll(entry.groupValues[2]).mapNotNull { pair ->
            val key = pair.groupValues[1]
            val value = pair.groupValues[2]
            if (key.isBlank() || value.isBlank()) null else "$key=${discoverUrlEncode(value)}"
        }.toMutableList()
        parts += extras
        if (parts.isEmpty()) continue
        val url = prefix + parts.joinToString("&")
        kinds += DiscoverKind(title = title.take(MAX_KIND_TITLE), url = url, urls = listOf(url))
    }
    return kinds.distinctBy { it.key }
}

private fun discoverUrlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

internal fun parseExploreFilter(rawExploreUrl: String): DiscoverFilter? {
    val raw = rawExploreUrl.trim()
    if (!raw.startsWith("@js:", ignoreCase = true) && !raw.startsWith("<js>", ignoreCase = true)) {
        return null
    }
    val script = raw.removePrefix("@js:").removePrefix("<js>").trim()
    val groupsText = Regex("""var\s+groups\s*=\s*(\[[\s\S]*?\])\s*;""")
        .find(script)?.groupValues?.getOrNull(1) ?: return null
    val prefix = Regex("""return\s*'([^']*)'\s*\+\s*parts\.join\('([^']*)'\)""")
        .find(script)?.groupValues?.getOrNull(1).orEmpty()
    if (prefix.isBlank()) return null
    val extras = Regex("""parts\.push\('([^']*)'\)""")
        .findAll(script)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .filter { it.isNotBlank() }
        .toList()

    val rawGroups = Regex("""\["([^"]+)"\s*,\s*\[((?:\[[^\[\]]*\]|[^\[\]])*)\]\s*,\s*\d+\]""")
        .findAll(groupsText)
        .mapNotNull { match ->
            val name = match.groupValues[1].trim()
            val options = parseFilterGroupEntries(match.groupValues[2])
            if (name.isBlank() || options.isEmpty()) null else name to options
        }
        .toList()
    if (rawGroups.isEmpty()) return null

    val sorts = Regex("""var\s+sorts\s*=\s*\[([\s\S]*?)\]\s*;""")
        .find(script)?.groupValues?.getOrNull(1)
        ?.let { body ->
            Regex("""\{\s*label\s*:\s*['"]([^'"]+)['"]\s*,\s*value\s*:\s*['"]([^'"]*)['"]\s*\}""")
                .findAll(body)
                .map { DiscoverFilterOption(it.groupValues[1].trim(), listOf("sort" to it.groupValues[2])) }
                .filter { it.title.isNotBlank() }
                .toList()
        }
        .orEmpty()

    val filterGroups = mutableListOf<DiscoverFilterGroup>()
    rawGroups.forEachIndexed { index, (name, entries) ->
        if (index == 0 && sorts.isNotEmpty()) {

            filterGroups += DiscoverFilterGroup(name, sorts)
            return@forEachIndexed
        }
        val dimensionKey = filterDimensionKey(entries)
        val options = mutableListOf(DiscoverFilterOption(FILTER_ALL_TITLE, emptyList()))
        entries.forEach { entry ->
            val params = entry.second
            val value = params.firstOrNull { it.first == dimensionKey }?.second
            options += if (dimensionKey.isNotBlank() && value != null) {
                DiscoverFilterOption(entry.first, listOf(dimensionKey to value))
            } else {

                DiscoverFilterOption(entry.first, params)
            }
        }
        filterGroups += DiscoverFilterGroup(name, options)
    }
    if (filterGroups.size < 2) return null
    return DiscoverFilter(prefix = prefix, extras = extras, groups = filterGroups)
}

private const val FILTER_ALL_TITLE = "全部"

private fun parseFilterGroupEntries(body: String): List<Pair<String, List<Pair<String, String>>>> {
    val entryRegex = Regex("""\["([^"]+)"\s*,\s*\{([^{}]*)\}\]""")
    val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
    return entryRegex.findAll(body).mapNotNull { entry ->
        val title = entry.groupValues[1].trim()
        if (title.isBlank()) return@mapNotNull null
        val params = pairRegex.findAll(entry.groupValues[2]).mapNotNull { pair ->
            val key = pair.groupValues[1]
            val value = pair.groupValues[2]
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toList()
        title to params
    }.toList()
}

private fun filterDimensionKey(entries: List<Pair<String, List<Pair<String, String>>>>): String {
    val counts = LinkedHashMap<String, Int>()
    entries.forEach { (_, params) ->
        params.map { it.first }.distinct().filter { it != "sort" }.forEach { key ->
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts.maxByOrNull { it.value }?.takeIf { it.value * 2 >= entries.size }?.key.orEmpty()
}

internal fun parseExploreKinds(rawExploreUrl: String): List<DiscoverKind> {
    val raw = rawExploreUrl.trim()
    if (raw.isBlank()) return emptyList()
    parseExploreKindsJson(raw)?.takeIf { it.isNotEmpty() }?.let { return it }
    if (raw.startsWith("@js:", ignoreCase = true) || raw.startsWith("<js>", ignoreCase = true)) {
        parseExploreKindsScript(raw).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return parseExploreKindsLines(raw)
}

internal fun parseExploreKindsJson(raw: String): List<DiscoverKind>? {
    val first = raw.firstOrNull() ?: return null
    if (first != '[' && first != '{') return null
    val json = runCatching {
        if (first == '[') JSONArray(raw) else JSONArray().put(JSONObject(raw))
    }.getOrNull() ?: return null
    val kinds = mutableListOf<DiscoverKind>()
    for (index in 0 until json.length()) {
        val node = json.optJSONObject(index) ?: continue
        val title = node.optString("title").ifBlank { node.optString("name") }.trim()
        val url = node.optString("url").trim()
        if (title.isBlank() || url.isBlank()) continue
        kinds += DiscoverKind(
            title = title.take(MAX_KIND_TITLE),
            url = url,
            urls = listOf(url),
        )
    }
    return kinds.distinctBy { it.key }
}

internal fun parseExploreKindsLines(raw: String): List<DiscoverKind> {
    val kinds = mutableListOf<DiscoverKind>()
    raw.lineSequence().forEach { line ->
        val text = line.trim()

            .removePrefix("[").removeSuffix("]").trim()
        if (text.isBlank() || text.startsWith("#") || text.startsWith("//")) return@forEach

        if (text.startsWith("-")) {
            val extra = text.removePrefix("-").trim()
            if (extra.isBlank()) return@forEach
            val last = kinds.lastOrNull() ?: return@forEach
            kinds[kinds.lastIndex] = last.copy(urls = last.urls + extra)
            return@forEach
        }
        val splitIndex = text.indexOf("::")
        val title = if (splitIndex < 0) text else text.substring(0, splitIndex).trim()
        val url = if (splitIndex < 0) "" else text.substring(splitIndex + 2).trim()
        if (title.isBlank() || url.isBlank()) return@forEach
        kinds += DiscoverKind(
            title = title.take(MAX_KIND_TITLE),
            url = url,
            urls = listOf(url),
        )
    }
    return kinds.distinctBy { it.key }
}
