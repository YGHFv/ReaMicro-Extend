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

/** The six fields shown by ReaMicro 1.3 EpubMetadataScreen, including EPUB3 refinements. */
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
        // Only serialize metadata: manifest, spine, guide and XML encoding declaration remain untouched.
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

    private fun parse(text: String): Document {
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
