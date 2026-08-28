package com.reamicro.fix.online.download

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class StoredOnlineChapter(
    val sourceChapterId: String,
    val title: String,
    val volumeTitle: String,
    val href: String,
)

internal data class RemoteOnlineChapter(
    val sourceChapterId: String,
    val title: String,
    val volumeTitle: String,
)

internal data class OnlineChapterUpdateSlot(
    val remoteIndex: Int,
    val stored: StoredOnlineChapter?,
) {
    val isNew: Boolean get() = stored == null
}

internal object OnlineChapterUpdatePlanner {
    fun isVolumeNode(
        explicitVolume: Boolean?,
        nodeType: String,
        title: String,
        hasChapterUrl: Boolean,
    ): Boolean {
        explicitVolume?.let { return it }
        if (nodeType.trim().lowercase(Locale.ROOT) in VOLUME_NODE_TYPES) return true
        if (!hasChapterUrl) return title.isNotBlank()
        return STRUCTURED_VOLUME_TITLE.matches(title.trim())
    }

    fun parseLegacyNcx(xml: String): List<StoredOnlineChapter> {
        if (xml.isBlank()) return emptyList()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val points = document.getElementsByTagNameNS("*", "navPoint")
        return buildList {
            for (index in 0 until points.length) {
                val point = points.item(index) as? Element ?: continue
                if (point.directChildElements("navPoint").isNotEmpty()) continue
                val title = point.getElementsByTagNameNS("*", "text")
                    .item(0)?.textContent.orEmpty().trim()
                val href = point.getElementsByTagNameNS("*", "content")
                    .item(0)?.let { it as? Element }?.getAttribute("src").orEmpty().trim()
                if (href.isBlank()) continue
                val volumeTitle = (point.parentNode as? Element)
                    ?.takeIf { it.localName == "navPoint" || it.nodeName.substringAfter(':') == "navPoint" }
                    ?.getElementsByTagNameNS("*", "navLabel")
                    ?.item(0)
                    ?.let { it as? Element }
                    ?.getElementsByTagNameNS("*", "text")
                    ?.item(0)
                    ?.textContent.orEmpty().trim()
                add(StoredOnlineChapter("", title, volumeTitle, href))
            }
        }
    }

    /**
     * 章节的稳定标识。
     *
     * 只保留能唯一定位章节的那一段（查询参数里的 ID，或路径末尾的 ID 段），
     * **不带域名与接口路径**——它们对识别章节没有贡献，只会让元数据文件里
     * 每章都重复一遍完整 URL。抓正文用的完整地址另存在 `url` 字段。
     *
     * 取不到可识别 ID 时（路径里没有数字或不透明段）退回归一化后的完整 URL，
     * 宁可长一点也不能让两个不同章节撞成同一个标识。
     */
    fun stableSourceChapterId(url: String): String {
        val clean = url.trim()
        if (clean.isBlank()) return ""
        val uri = runCatching { URI(clean) }.getOrNull()
            ?: return clean.substringBefore('#')
        val queryValues = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                val rawName = if (separator >= 0) part.substring(0, separator) else part
                val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
                val name = decodeUrlPart(rawName).lowercase(Locale.ROOT)
                val value = decodeUrlPart(rawValue).trim()
                if (name.isBlank() || value.isBlank()) null else name to value
            }
            .orEmpty()
        SOURCE_CHAPTER_ID_QUERY_NAMES.forEach { name ->
            queryValues.firstOrNull { it.first == name }?.second?.let { value ->
                return "$name:$value"
            }
        }
        // 没有查询参数就从路径里取 ID 段。REST 风格的书源（.../chapters/789864061）
        // 全部走这一条，是最常见的形态。
        pathChapterId(uri)?.let { return it }
        return runCatching {
            URI(
                uri.scheme?.lowercase(Locale.ROOT),
                uri.userInfo,
                uri.host?.lowercase(Locale.ROOT),
                uri.port,
                uri.path,
                uri.query,
                null,
            ).normalize().toString()
        }.getOrDefault(clean.substringBefore('#'))
    }

    /**
     * 从路径里取出章节 ID 段。
     *
     * 优先取末尾的纯数字段（`.../chapters/789864061`）；没有数字就取末尾足够长的
     * 不透明段（哈希、UUID 之类）。分页式路径（`.../read/123/456`）会带上前一段，
     * 避免不同书的同号章节撞在一起。
     */
    private fun pathChapterId(uri: URI): String? {
        val segments = uri.path
            ?.split('/')
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && it != "index.html" }
            ?: return null
        if (segments.isEmpty()) return null
        val last = segments.last().substringBeforeLast('.')
        if (last.isBlank()) return null
        val isNumeric = last.all(Char::isDigit)
        val isOpaque = last.length >= OPAQUE_ID_MIN_LENGTH && last.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (!isNumeric && !isOpaque) return null
        // 末段是纯数字时，若前一段也是纯数字，说明是「书 ID / 章节 ID」这类分页路径，
        // 两段一起才唯一。只取末段会让不同书的同号章节撞成一个标识。
        val previous = segments.dropLast(1).lastOrNull()?.substringBeforeLast('.')
        if (isNumeric && previous != null && previous.all(Char::isDigit) && previous.isNotBlank()) {
            return "$previous/$last"
        }
        return last
    }

    /**
     * 匹配用的归一形式。
     *
     * 已下载图书的元数据里存的是**旧格式的完整 URL**，新算出来的是短 ID，
     * 直接比对会全部对不上，更新时每一章都被判成新章节重下。
     * 所以两边都先过这个函数：完整 URL 会被压成同样的短 ID，短 ID 原样返回。
     */
    fun sourceChapterMatchKey(value: String): String {
        val clean = value.trim()
        if (clean.isBlank()) return ""
        // 已经是短 ID（不含协议分隔符）就不用再处理。
        if (!clean.contains("://")) return clean
        return stableSourceChapterId(clean)
    }

    fun plan(
        storedChapters: List<StoredOnlineChapter>,
        remoteChapters: List<RemoteOnlineChapter>,
    ): List<OnlineChapterUpdateSlot> {
        val unused = storedChapters.indices.toMutableSet()
        // 两边都归一：已存章节可能是旧格式的完整 URL，远端是新的短 ID。
        val bySourceId = queueBy(storedChapters) {
            sourceChapterMatchKey(it.sourceChapterId).takeIf(String::isNotBlank)
        }
        val byLegacyIdentity = queueBy(storedChapters) {
            if (it.sourceChapterId.isBlank()) legacyIdentity(it.volumeTitle, it.title) else null
        }
        return remoteChapters.mapIndexed { remoteIndex, remote ->
            val storedIndex = takeUnused(bySourceId[sourceChapterMatchKey(remote.sourceChapterId)], unused)
                ?: takeUnused(byLegacyIdentity[legacyIdentity(remote.volumeTitle, remote.title)], unused)
            OnlineChapterUpdateSlot(remoteIndex, storedIndex?.let(storedChapters::get))
        }
    }

    private fun queueBy(
        chapters: List<StoredOnlineChapter>,
        key: (StoredOnlineChapter) -> String?,
    ): Map<String, ArrayDeque<Int>> {
        val result = linkedMapOf<String, ArrayDeque<Int>>()
        chapters.forEachIndexed { index, chapter ->
            val value = key(chapter)?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            result.getOrPut(value, ::ArrayDeque).addLast(index)
        }
        return result
    }

    private fun takeUnused(queue: ArrayDeque<Int>?, unused: MutableSet<Int>): Int? {
        while (queue?.isNotEmpty() == true) {
            val index = queue.removeFirst()
            if (unused.remove(index)) return index
        }
        return null
    }

    private fun legacyIdentity(volumeTitle: String, title: String): String =
        "${normalizeIdentityPart(volumeTitle)}\u001f${normalizeIdentityPart(title)}"

    private fun normalizeIdentityPart(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(IDENTITY_WHITESPACE, "")

    private fun decodeUrlPart(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun Element.directChildElements(localName: String): List<Element> =
        buildList {
            val nodes = childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val element = node as Element
                if (element.localName == localName || element.nodeName.substringAfter(':') == localName) add(element)
            }
        }

    /** 不透明 ID（哈希、UUID 等）至少这么长才当作标识，避免把 "read"、"txt" 之类的词当成 ID。 */
    private const val OPAQUE_ID_MIN_LENGTH = 8

    private val SOURCE_CHAPTER_ID_QUERY_NAMES = listOf(
        "itemid",
        "item_id",
        "chapterid",
        "chapter_id",
        "cid",
        "id",
    )
    private val VOLUME_NODE_TYPES = setOf("volume", "vol", "part", "group", "section")
    private val STRUCTURED_VOLUME_TITLE = Regex(
        "(?:第\\s*[0-9０-９一二三四五六七八九十百千万〇零两]+\\s*[卷部篇册季].*|.+[卷部篇册季]|(?:volume|vol|part)\\s*[0-9０-９]+.*)",
        RegexOption.IGNORE_CASE,
    )
    private val IDENTITY_WHITESPACE = Regex("""\s+""")
}
