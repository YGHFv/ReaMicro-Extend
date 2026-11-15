package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.reload.TaskCancellation
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

internal data class WebDavResource(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: String,
)

internal data class WebDavListedResource(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val updatedAt: Long,
    val rawHref: String,
)

/** Only successful DAV properties describe a resource; failed propstat values are placeholders. */
internal object WebDavMultistatus {
    const val COLLECTION_QUERY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
    const val DIRECTORY_QUERY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
    private val httpStatus = Regex("""HTTP/\S+\s+(\d{3})(?:\s.*)?""")

    fun parse(xml: String): List<WebDavResource> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) {
            "WebDAV 目录响应不能包含文档类型或实体声明"
        }
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("WebDAV external entity is not supported") }
        }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        require(root.namespaceURI == "DAV:" && root.localName == "multistatus") { "WebDAV 目录响应格式无效" }
        return root.children("response").mapNotNull { response ->
            TaskCancellation.checkCurrent()
            val status = response.childText("status")
            if (status.isNotBlank() && !successful(status)) return@mapNotNull null
            val href = response.childText("href")
            if (href.isBlank()) return@mapNotNull null
            val properties = response.children("propstat")
                .filter { successful(it.childText("status")) }
                .flatMap { it.children("prop") }
            if (properties.isEmpty()) return@mapNotNull null
            fun property(name: String): String =
                properties.firstNotNullOfOrNull { it.children(name).firstOrNull()?.textContent }?.trim().orEmpty()
            WebDavResource(
                href = href,
                displayName = property("displayname"),
                isDirectory = properties.any { prop ->
                    prop.children("resourcetype").any { it.children("collection").isNotEmpty() }
                },
                size = property("getcontentlength").toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                lastModified = property("getlastmodified"),
            )
        }
    }

    private fun successful(value: String): Boolean =
        httpStatus.matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull() in 200..299

    private fun Element.childText(name: String): String =
        children(name).firstOrNull()?.textContent?.trim().orEmpty()

    private fun Element.children(name: String): List<Element> = buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val child = nodes.item(index) as? Element ?: continue
            if (child.namespaceURI == "DAV:" && child.localName == name) add(child)
        }
    }
}
