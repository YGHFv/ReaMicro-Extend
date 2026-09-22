package com.reamicro.fix.discover

import com.reamicro.fix.hook.WebDavDriveHook
import com.reamicro.fix.hook.applyOnlineTemplate
import com.reamicro.fix.hook.requestOnlineSearch
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

/**
 * 「发现」页的数据来源：解析书源的 `exploreUrl`，按分类拉取书单。
 *
 * 复用模块既有的在线书源链路（`WebDavDriveHook.requestOnlineSearch` 发请求、
 * `onlineJsonRuleValues` 走 JSON 路径、`applyOnlineTemplate` 填模板），只是把入口
 * 从「关键词搜索 + ruleSearch」换成「分类地址 + ruleExplore」。
 */
internal object DiscoverRepository {

    /** 单次分类书单最多解析的条目数，避免大书单把 LazyColumn 撑爆。 */
    private const val MAX_BOOKS = 60

    /**
     * 从源的 `exploreUrl` 解析分类列表。
     *
     * 支持两种实际存在的写法：
     *
     * 1. **JSON 数组**（阅微/部分第三方书源，如书旗）：`[{"title":"都市","url":"..."}, ...]`。
     *    也接受 `{"title":..,"url":..}` 的单条对象，以及 `title` 缺失时退回 `name`。
     * 2. **Legado 逐行格式**：每行 `分类名::地址`，以 `-` 开头的行是上一分类的追加地址。
     *
     * 先试 JSON（以 `[` / `{` 开头时），失败再按逐行解析。地址里可含 `{{page}}` 之类
     * 的模板变量，此处不展开，取书单时再填。
     *
     * 解析不出任何分类时返回空列表，调用方据此跳过该源。
     */
    fun parseKinds(source: OnlineSourceEntry): List<DiscoverKind> =
        parseExploreKinds(source.exploreUrl)

    /**
     * 拉取某个分类的书单。
     *
     * `page` 填进分类地址里的 `{{page}}` 模板（Legado 惯例）；发现页的「加载更多」靠它翻页。
     * 分类地址可能挂多条（`urls`），逐条尝试，第一条有结果的就算命中；全部为空时返回
     * [DiscoverLoadState.Loaded] 包一个空列表，让 UI 显示「暂无内容」而不是报错。
     */
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

    /** 把分类地址展开成可请求的 URL：套模板（含页码）→ 拼 baseUrl。 */
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

    /**
     * 按 `ruleExplore` 解析书单。
     *
     * 规则键沿用 Legado 的命名：`bookList` 取列表节点，`name`/`author`/`intro`/`kind`/
     * `lastChapter`/`updateTime`/`bookUrl`/`coverUrl`/`wordCount` 取字段。`ruleExplore`
     * 为空时退化为「遍历 JSON 找像书名的对象」，保证没有配规则的源也能出内容。
     */
    private fun parseBooks(
        source: OnlineSourceEntry,
        baseUrl: String,
        body: String,
        rule: JSONObject?,
    ): List<DiscoverBook> {
        val text = body.trim()
        if (text.isBlank()) return emptyList()
        if (!text.startsWith("{") && !text.startsWith("[")) {
            // HTML 书单需要 DOM 选择器，模块不执行 JS，这里只做最朴素的 <a> 提取。
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

    /** 没配 `bookList` 时，递归收集所有「像一本书」的 JSONObject。 */
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
        // 状态字段各源写法差异极大（中文「完结」、数字 0/1、布尔 true/false，字段名更是五花八门），
        // 直接透传会在书单里显示成一个孤零零的「1」。这里交给在线补全那套综合解析器：它依次看
        // 提示文本、node 上的 creation_status / tomato_book_status / is_finish 等字段，最后还会从
        // 「最新章节」标题推断（「第 259 章（完）」这种），只产出「完结 / 连载 / 断更 / 下架」或空串。
        val statusHint = value("status", "bookStatus", "serializeStatus", "serializeStatusName")
        return DiscoverBook(
            name = name,
            author = value("author", "writer", "authorName", "bookAuthor").cleanOnlineText(),
            coverUrl = resolveOnlineUrlCompat(baseUrl, value("coverUrl", "cover", "img", "image")),
            detailUrl = resolveOnlineUrlCompat(baseUrl, detail),
            intro = value("intro", "description", "desc", "summary").cleanOnlineText(),
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

    /**
     * 求一个规则字段的值。
     *
     * `ruleExplore` 里的路径规则与 `ruleSearch` 同构（`$.data.list[*]` 之类），
     * 所以直接借 `onlineJsonRuleValues` 走一遍现有解析器：取到列表就用首个节点，
     * 取不到就退回按字面键名读。
     */
    private fun resolveRuleValue(
        node: Any?,
        rawRule: String,
        baseUrl: String,
        source: OnlineSourceEntry,
    ): String {
        val rule = rawRule.trim()
        if (rule.isBlank()) return ""
        // 规则本身可能带模板（如 `{{baseUrl}}/book/{{$.id}}`），先填模板再取值。
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

    /**
     * 详情页/封面这类字段需要拼成绝对地址，其余字段按原样返回。
     *
     * 键名在 `ruleExplore` 里是 Legado 的固定词表，这里按词表判断是否需要拼 baseUrl。
     */
    private fun resolveKnownUrlKey(
        rule: String,
        value: String,
        baseUrl: String,
        source: OnlineSourceEntry,
    ): String {
        if (value.isBlank()) return ""
        val needsResolve = URL_RULE_MARKERS.any { rule.contains(it) }
        if (!needsResolve) return value
        // 某些源用 `-` 前缀表示相对源站点根，这里统一交给 resolveOnlineUrlCompat 处理。
        val absolute = resolveOnlineUrlCompat(baseUrl, value)
        return absolute.ifBlank { value }
    }

    /** 无规则时的兜底：从 HTML 里挑出像书籍链接的 `<a>`。 */
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

    /** 规则路径里出现这些片段时，字段值需要拼成绝对 URL。 */
    private val URL_RULE_MARKERS = listOf("bookUrl", "coverUrl", "url", "cover", "img", "href", "link")

    private val ANCHOR_REGEX = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
}

// ── exploreUrl 解析（顶层函数，便于单测直接调用） ─────────────────────────────

/** 分类名过长时截断，防止标签行换行。 */
private const val MAX_KIND_TITLE = 20

/**
 * 解析 Legado「JS 发现页」的静态形态。
 *
 * 部分聚合源（晚风里等）的 `exploreUrl` 是一段 `@js:` 脚本：先定义一个 `var groups=[...]` 的
 * **纯 JSON 数组**（`[分组名, [条目名, 查询参数], 列数]`），再用 `search(params)` 把查询对象
 * 拼成相对地址。Legado 靠执行脚本生成可点分类，模块不执行任意 JS——但这一形态的关键字面量
 * 都是静态的，可以直接提取：
 *
 * 1. `var groups=[...];` → `JSONArray` 原样解析；
 * 2. `search()` 的地址前缀与固定追加参数 → 各用一条正则抓字面量
 *    （`return '<前缀>'+parts.join('<连接符>')` 与 `parts.push('<字面量>')`）；
 * 3. 每个条目的查询对象按 JSON 键序拼 `k=enc(v)`（与 JS `for...in` 的插入顺序一致），空值跳过
 *    ——与脚本里 `params[key]!==''` 的判断语义一致。
 *
 * 不追求完整 JS 语义：脚本输出的选择行（`type:'select'`）依赖 `source.getVariable()` 的用户
 * 状态，模块没有对应交互，天然跳过；空地址的标题行也被 `params` 为空挡掉。
 */
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

    // 条目与查询参数都从原始文本里按出现顺序抓：`org.json.JSONObject` 内部是 HashMap，
    // 键序会乱，而 JS 的 `for...in`（也就是真实请求的参数顺序）跟的是插入序。
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

/** 与 JS `encodeURIComponent` 对齐：`URLEncoder` 会把空格编成 `+`，这里换回 `%20`。 */
private fun discoverUrlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

/**
 * 解析 `exploreUrl` 里的分类列表，兼容 JSON 数组、Legado 逐行与 `@js:` 脚本三种写法。
 *
 * 先试 JSON；再对脚本形态做静态提取；都不行再按逐行解析。这样既能吃下
 * 书旗那类 `[{title,url},...]` 的写法，也不影响 Legado 原生的 `分类名::地址` 文本。
 */
internal fun parseExploreKinds(rawExploreUrl: String): List<DiscoverKind> {
    val raw = rawExploreUrl.trim()
    if (raw.isBlank()) return emptyList()
    parseExploreKindsJson(raw)?.takeIf { it.isNotEmpty() }?.let { return it }
    if (raw.startsWith("@js:", ignoreCase = true) || raw.startsWith("<js>", ignoreCase = true)) {
        parseExploreKindsScript(raw).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return parseExploreKindsLines(raw)
}

/**
 * 解析 JSON 数组写法。文本不是合法 JSON 时返回 null（区别于「是 JSON 但没有条目」）。
 *
 * 支持：
 * - `[{...}, {...}]` 数组；
 * - 单个 `{...}` 对象（包成单元素数组）；
 * - 标题取 `title`，缺失时退回 `name`（部分源用 `name`）。
 */
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

/**
 * 解析 Legado 逐行 `分类名::地址` 写法。
 *
 * `#` / `//` 开头的行视为注释；`-` 开头的行是上一分类的追加地址。
 */
internal fun parseExploreKindsLines(raw: String): List<DiscoverKind> {
    val kinds = mutableListOf<DiscoverKind>()
    raw.lineSequence().forEach { line ->
        val text = line.trim()
            // 逐行写法有时被整体包在方括号里（粘贴自 JS 数组），剥掉首尾括号再解析。
            .removePrefix("[").removeSuffix("]").trim()
        if (text.isBlank() || text.startsWith("#") || text.startsWith("//")) return@forEach
        // `-` 前缀 = 追加到上一个分类。
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
