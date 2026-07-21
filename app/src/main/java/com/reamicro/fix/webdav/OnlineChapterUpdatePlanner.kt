package com.reamicro.fix.webdav

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

    fun plan(
        storedChapters: List<StoredOnlineChapter>,
        remoteChapters: List<RemoteOnlineChapter>,
    ): List<OnlineChapterUpdateSlot> {
        val unused = storedChapters.indices.toMutableSet()
        val bySourceId = queueBy(storedChapters) { it.sourceChapterId.takeIf(String::isNotBlank) }
        val byLegacyIdentity = queueBy(storedChapters) {
            if (it.sourceChapterId.isBlank()) legacyIdentity(it.volumeTitle, it.title) else null
        }
        return remoteChapters.mapIndexed { remoteIndex, remote ->
            val storedIndex = takeUnused(bySourceId[remote.sourceChapterId], unused)
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
