package com.reamicro.fix.epub.editor

import java.io.File
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal object EpubNavigation {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_ENTRIES = 50000
    data class Row(val title: String, val href: String, val path: String?, val depth: Int)
    private val spaces = Regex("\\s+")
    internal fun parse(text: String): org.w3c.dom.Document {
        require(text.length <= MAX_BYTES) { "目录文本超过 8M 字符安全上限" }

        require(!Regex("<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "目录含自定义实体，暂不解析或保存" }
        return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }.parse(InputSource(StringReader(text)))
    }

    private fun inventory(root: File, paths: List<String>) = paths.distinct().mapNotNull { path ->
        runCatching { EpubTextFiles.resolve(root, path).canonicalPath to path }.getOrNull()
    }.toMap()

    private fun resolve(base: File, href: String, inventory: Map<String, String>): String? = runCatching {
        if (href.isBlank() || href.startsWith("/")) return null
        val uri = URI(href.replace(" ", "%20"))
        if (uri.isAbsolute || uri.rawAuthority != null) return null
        val path = URLDecoder.decode(uri.rawPath.orEmpty().replace("+", "%2B"), "UTF-8")
        if (path.startsWith("/") || '\\' in path || path.any { it < ' ' }) return null

        inventory[(if (path.isEmpty()) base else File(base.parentFile, path)).toPath().toAbsolutePath().normalize().toString()]
    }.getOrNull()

    fun paths(root: File, paths: List<String>, checkCancelled: () -> Unit = {}): Set<String> {
        val inventory = inventory(root, paths)
        val result = paths.filter { it.endsWith(".ncx", true) }.toMutableSet()
        for (path in paths.filter { it.endsWith(".opf", true) }) {
            checkCancelled()
            val file = EpubTextFiles.resolve(root, path)
            val document = runCatching { parse(EpubTextFiles.load(file, MAX_BYTES, checkCancelled).text) }
                .getOrElse { checkCancelled(); null } ?: continue
            val items = document.getElementsByTagNameNS("*", "item")
            for (index in 0 until items.length) {
                checkCancelled()
                val item = items.item(index) as Element
                if ("nav" in item.getAttribute("properties").split(spaces)) {
                    resolve(file, item.getAttribute("href"), inventory)?.takeIf(::usesScriptaEpubEditor)?.let(result::add)
                }
            }
        }
        return result
    }

    fun read(root: File, path: String, paths: List<String>, checkCancelled: () -> Unit = {}): List<Row> {
        val file = EpubTextFiles.resolve(root, path)
        return rows(EpubTextFiles.load(file, MAX_BYTES, checkCancelled).text, file, inventory(root, paths), checkCancelled)
    }

    internal fun rows(text: String, file: File, inventory: Map<String, String>, checkCancelled: () -> Unit = {}): List<Row> {
        val document = parse(text)
        val ncx = file.extension.equals("ncx", true)
        if (ncx) require(document.documentElement.localName == "ncx") { "不是 NCX 目录" }
        val result = ArrayList<Row>()
        data class Frame(val element: Element, val depth: Int, val inToc: Boolean)
        val stack = java.util.ArrayDeque<Frame>()
        stack.add(Frame(document.documentElement, 0, false))
        while (stack.isNotEmpty()) {
            checkCancelled()
            val (element, depth, inheritedToc) = stack.removeLast()
            val name = element.localName
            val type = element.getAttributeNS("http://www.idpf.org/2007/ops", "type")
                .ifBlank { element.getAttribute("epub:type") }
            val inToc = inheritedToc || (name == "nav" &&
                ("toc" in type.split(spaces) || element.getAttribute("role") == "doc-toc"))
            if ((ncx && name == "navPoint") || (!ncx && inToc && name == "a")) {
                var title = if (ncx) "" else element.textContent
                var href = if (ncx) "" else element.getAttribute("href")
                if (ncx) {
                    var child = element.firstChild
                    while (child != null) {
                        if (child is Element) when (child.localName) {
                            "navLabel" -> title = child.textContent
                            "content" -> href = child.getAttribute("src")
                        }
                        child = child.nextSibling
                    }
                }
                require(result.size < MAX_ENTRIES) { "目录超过 $MAX_ENTRIES 项，请使用源码编辑" }
                result.add(Row(spaces.replace(title, " ").trim().take(512).ifBlank { "[未命名]" }, href,
                    resolve(file, href, inventory), (if (ncx) depth else depth - 1).coerceAtLeast(0)))
            }
            val nextDepth = depth + if ((ncx && name == "navPoint") || (!ncx && inToc && name == "ol")) 1 else 0
            var child = element.lastChild
            while (child != null) {
                if (child is Element) stack.add(Frame(child, nextDepth, inToc))
                child = child.previousSibling
            }
        }
        return result
    }

    fun validateSource(path: String, text: String) {
        if (path.endsWith(".ncx", true)) {
            val doc = parse(text)
            require(doc.documentElement.localName == "ncx" && doc.getElementsByTagNameNS("*", "navMap").length > 0) {
                "NCX 必须包含 ncx 和 navMap 节点，未保存"
            }
        }
    }
}
