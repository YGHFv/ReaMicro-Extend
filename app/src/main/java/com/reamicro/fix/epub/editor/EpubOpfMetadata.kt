package com.reamicro.fix.epub.editor

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal object EpubOpfMetadata {
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private val tags = mapOf(
        "title" to "title", "subtitle" to "title", "author" to "creator",
        "language" to "language", "publisher" to "publisher", "date" to "date",
    )

    fun read(text: String): Map<String, String> {
        val document = parse(text)
        return tags.keys.associateWith { find(document, it)?.textContent.orEmpty() }
    }

    fun update(text: String, field: String, value: String): String {
        EpubMetadataFields.validate(field, value)
        val tag = requireNotNull(tags[field]) { "不支持的元数据字段" }
        val document = parse(text)
        val metadata = metadata(document)
        var element = find(document, field)
        if (element == null) {
            val prefix = metadata.lookupPrefix(DC) ?: "dc"
            if (metadata.lookupNamespaceURI(prefix) != DC) {
                metadata.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:$prefix", DC)
            }
            element = document.createElementNS(DC, "$prefix:$tag")
            metadata.appendChild(element)
            if (field == "subtitle") {
                var id = "reamicro-edition"
                val ids = document.getElementsByTagName("*")
                while ((0 until ids.length).any { (ids.item(it) as? Element)?.getAttribute("id") == id }) id += "-1"
                element.setAttribute("id", id)
                val refinement = document.createElementNS(metadata.namespaceURI,
                    metadata.prefix?.let { "$it:meta" } ?: "meta")
                refinement.setAttribute("refines", "#$id")
                refinement.setAttribute("property", "title-type")
                refinement.textContent = "edition"
                metadata.appendChild(refinement)
            }
        }
        element.textContent = value

        val output = StringWriter()
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        factory.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(metadata), StreamResult(output))
        val range = Regex("""(?s)<((?:[\w.-]+:)?metadata)\b[^>]*>.*?</\1\s*>""")
            .find(text)?.range ?: error("无法定位 metadata 节点")
        return text.replaceRange(range, output.toString())
    }

    fun coverHref(text: String): String {
        val doc = parse(text)
        val metas = doc.getElementsByTagNameNS("*", "meta")
        val coverId = (0 until metas.length).map { metas.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
        val nodes = doc.getElementsByTagNameNS("*", "item")
        val items = (0 until nodes.length).map { nodes.item(it) as Element }
        return (items.firstOrNull { !coverId.isNullOrBlank() && it.getAttribute("id") == coverId }
            ?: items.firstOrNull { "cover-image" in it.getAttribute("properties").split(Regex("\\s+")) })
            ?.getAttribute("href").orEmpty()
    }

    fun packagePath(container: String): String {
        val doc = parse(container)
        val nodes = doc.getElementsByTagNameNS("*", "rootfile")
        val roots = (0 until nodes.length).map { nodes.item(it) as Element }
        return (roots.firstOrNull { it.getAttribute("media-type") == "application/oebps-package+xml" }
            ?: roots.firstOrNull())?.getAttribute("full-path").orEmpty()
    }

    fun spineHrefs(text: String): List<String> {
        val doc = parse(text)
        val manifest = doc.getElementsByTagNameNS("*", "manifest").item(0) as? Element
            ?: return emptyList()
        val spine = doc.getElementsByTagNameNS("*", "spine").item(0) as? Element
            ?: return emptyList()
        val items = manifest.getElementsByTagNameNS("*", "item")
        val hrefById = linkedMapOf<String, String>()
        for (index in 0 until items.length) {
            val item = items.item(index) as Element
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotBlank() && href.isNotBlank() && id !in hrefById) hrefById[id] = href
        }
        val refs = spine.getElementsByTagNameNS("*", "itemref")
        return (0 until refs.length).mapNotNull { index ->

            hrefById[(refs.item(index) as Element).getAttribute("idref")]
        }
    }

    fun updateCover(text: String, href: String, mediaType: String): String {
        require(href.isNotBlank()) { "封面路径为空" }
        val doc = parse(text)
        val manifest = doc.getElementsByTagNameNS("*", "manifest").item(0) as? Element
            ?: error("缺少 manifest 节点")
        val metadata = metadata(doc)
        val nodes = manifest.getElementsByTagNameNS("*", "item")
        val items = (0 until nodes.length).map { nodes.item(it) as Element }
        fun decoded(value: String) = runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2b"), "UTF-8")
        }.getOrDefault(value)
        var target = items.firstOrNull { decoded(it.getAttribute("href")) == decoded(href) }
        if (target == null) {
            target = doc.createElementNS(manifest.namespaceURI, manifest.prefix?.let { "$it:item" } ?: "item")
            target.setAttribute("href", href)
            target.setAttribute("media-type", mediaType)
            manifest.appendChild(target)
        }
        var id = target.getAttribute("id")
        if (id.isBlank()) {
            val all = doc.getElementsByTagName("*")
            val ids = (0 until all.length).map { (all.item(it) as Element).getAttribute("id") }.toSet()
            id = "reamicro-cover"
            while (id in ids) id += "-1"
            target.setAttribute("id", id)
        }
        (items + target).distinct().forEach { item ->
            val props = item.getAttribute("properties").split(Regex("\\s+"))
                .filter { it.isNotBlank() && it != "cover-image" }.toMutableList()
            if (item === target) props.add("cover-image")
            if (props.isEmpty()) item.removeAttribute("properties")
            else item.setAttribute("properties", props.joinToString(" "))
        }
        val metas = metadata.getElementsByTagNameNS("*", "meta")
        val covers = (0 until metas.length).map { metas.item(it) as Element }
            .filter { it.getAttribute("name") == "cover" }
        if (covers.isEmpty()) {
            val meta = doc.createElementNS(metadata.namespaceURI, metadata.prefix?.let { "$it:meta" } ?: "meta")
            meta.setAttribute("name", "cover")
            meta.setAttribute("content", id)
            metadata.appendChild(meta)
        } else covers.forEach { it.setAttribute("content", id) }
        return replaceElement(replaceElement(text, "metadata", metadata), "manifest", manifest)
    }

    internal fun replaceElement(text: String, name: String, node: Element): String {
        val output = StringWriter()
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        factory.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(node), StreamResult(output))
        val range = Regex("""(?s)<((?:[\w.-]+:)?${Regex.escape(name)})\b[^>]*>.*?</\1\s*>""")
            .find(text)?.range ?: error("无法定位 $name 节点")
        return text.replaceRange(range, output.toString())
    }

    internal fun parse(text: String): Document {
        require(!Regex("""<!DOCTYPE""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            "为避免外部实体，暂不编辑带 DOCTYPE 的 OPF"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(text)))
    }

    private fun metadata(document: Document): Element =
        document.getElementsByTagNameNS("*", "metadata").item(0) as? Element ?: error("缺少 metadata 节点")

    private fun find(document: Document, field: String): Element? {
        val metadata = metadata(document)
        val list = metadata.getElementsByTagNameNS(DC, tags[field] ?: return null)
        val candidates = (0 until list.length).map { list.item(it) as Element }
        fun refined(element: Element, property: String, value: String): Boolean {
            val id = element.getAttribute("id")
            if (id.isBlank()) return false
            val metas = metadata.getElementsByTagNameNS("*", "meta")
            return (0 until metas.length).any {
                val meta = metas.item(it) as Element
                meta.getAttribute("refines") == "#$id" &&
                    meta.getAttribute("property") == property && meta.textContent.trim() == value
            }
        }
        return when (field) {
            "title" -> candidates.firstOrNull { refined(it, "title-type", "main") }
                ?: candidates.firstOrNull { !refined(it, "title-type", "edition") && !refined(it, "title-type", "subtitle") }
            "subtitle" -> candidates.firstOrNull {
                refined(it, "title-type", "edition") || refined(it, "title-type", "subtitle")
            } ?: metadata.getElementsByTagNameNS(DC, "subtitle").item(0) as? Element
            "author" -> candidates.firstOrNull {
                refined(it, "role", "aut") || it.getAttribute("opf:role") == "aut"
            } ?: candidates.firstOrNull()
            else -> candidates.firstOrNull()
        }
    }
}
