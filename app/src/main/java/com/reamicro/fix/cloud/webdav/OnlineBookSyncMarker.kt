package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.online.download.OnlineOnDemandMetadata
import com.reamicro.fix.online.download.OnlineOnDemandMetadataCodec
import com.reamicro.fix.online.download.OnlineOnDemandMetadataStore
import com.reamicro.fix.online.download.OnlineChapterState
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.ByteArrayOutputStream

/** 保留原 EPUB 身份、目录和章节路径，正文仅使用待从书源获取的占位页。 */
internal object OnlineBookSyncMarker {
    const val PATH = "OEBPS/reamicro-sync-online-marker.json"
    private const val INDEX = "OEBPS/${OnlineOnDemandMetadataStore.FILE_NAME}"
    fun metadata(root: File): OnlineOnDemandMetadata? {
        val index = File(root, INDEX)
        if (!index.isFile) return null
        return OnlineOnDemandMetadataCodec.decode(index.readText(Charsets.UTF_8)).takeIf {
            it.sourceId.isNotBlank() && it.detailUrl.isNotBlank() &&
                (it.chapters.isNotEmpty() || File(root, PATH).isFile)
        }
    }
    fun uuid(root: File): String? {
        val marker = File(root, PATH)
        if (!marker.isFile) return null
        val json = JSONObject(marker.readText(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        return json.getString("uuid").also {
            require(it.isNotBlank() && it !in setOf(".", "..") && it.none { c -> c == '/' || c == '\\' || c.isISOControl() })
        }
    }
    fun needsSourceCatalog(root: File): Boolean {
        val file = File(root, PATH)
        if (!file.isFile) return false
        val marker = JSONObject(file.readText(Charsets.UTF_8))
        val index = metadata(root) ?: return false
        return marker.optBoolean("progressOnly") && index.chapters.isEmpty() &&
            marker.optString("sourceId") == index.sourceId && marker.optString("detailUrl") == index.detailUrl
    }
    fun entries(root: File, uuid: String, progressOnly: Boolean = false): Map<String, ByteArray>? {
        val metadata = metadata(root) ?: return null
        require(uuid.isNotBlank() && '/' !in uuid && '\\' !in uuid)
        val marker = JSONObject().put("version", 1).put("uuid", uuid)
            .put("progressOnly", progressOnly)
            .put("sourceId", metadata.sourceId).put("detailUrl", metadata.detailUrl).put("bookName", metadata.bookName)
        if (progressOnly) return progressEntries(root, metadata, marker, uuid)
        val pending = metadata.copy(chapters = metadata.chapters.map {
            it.copy(state = OnlineChapterState.PENDING, downloadedAtMs = 0, attempts = 0, lastError = "")
        })
        val contents = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
            PATH to marker.toString().toByteArray(Charsets.UTF_8),
            INDEX to OnlineOnDemandMetadataCodec.encode(pending).toByteArray(Charsets.UTF_8),
        )
        for (path in listOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/toc.ncx")) {
            val file = File(root, path)
            require(file.isFile && file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            contents[path] = file.readBytes()
        }
        metadata.chapters.forEach { chapter ->
            val path = "OEBPS/${chapter.href.removePrefix("OEBPS/")}"
            require(ModuleConfigPolicy.safePath(path) && path.startsWith("OEBPS/Text/")) { "在线章节路径无效" }
            val title = escape(chapter.title)
            contents[path] = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1><p>章节等待从书源更新。</p></body></html>""".toByteArray(Charsets.UTF_8)
        }
        if (File(root, "OEBPS/Text/cover.xhtml").isFile) {
            contents["OEBPS/Text/cover.xhtml"] = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escape(metadata.bookName)}</title></head><body><h1>${escape(metadata.bookName)}</h1></body></html>""".toByteArray(Charsets.UTF_8)
        }
        // 分卷页、导航页和 CSS 不含章节正文；其原路径保留以维持原生目录索引。
        val chapters = contents.keys.toSet()
        File(root, "OEBPS").walkTopDown().filter(File::isFile).forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (path !in chapters && (file.extension.lowercase() == "css" ||
                file.name.startsWith("volume_") || file.name.startsWith("volume-") ||
                file.name in setOf("nav.xhtml", "toc.xhtml"))) contents[path] = file.readBytes()
        }
        require(contents.values.sumOf { it.size.toLong() } <= 16 * 1024 * 1024) { "在线图书标记目录过大" }
        return contents
    }
    fun write(root: File, uuid: String, output: File, progressOnly: Boolean = false): Boolean {
        val entries = entries(root, uuid, progressOnly) ?: return false
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                zip.write(bytes); zip.closeEntry()
            }
        }
        return true
    }
    /** 最小有效 EPUB 沿用原标识和书源元数据，不携带发送端章节列表。 */
    private fun progressEntries(root: File, metadata: OnlineOnDemandMetadata, marker: JSONObject, uuid: String): Map<String, ByteArray> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val opf = File(root, "OEBPS/content.opf")
        require(opf.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        val doc = opf.inputStream().use { factory.newDocumentBuilder().parse(it) }
        val pkg = doc.documentElement
        val namespace = pkg.namespaceURI ?: "http://www.idpf.org/2007/opf"
        // 元数据和原 unique-identifier 保留；章节、封面及导航清单均由接收端书源重建。
        val nodes = pkg.childNodes
        for (index in nodes.length - 1 downTo 0) {
            val node = nodes.item(index)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE && node.localName != "metadata") pkg.removeChild(node)
        }
        fun element(name: String) = doc.createElementNS(namespace, name)
        val manifest = element("manifest")
        manifest.appendChild(element("item").apply {
            setAttribute("id", "online-sync"); setAttribute("href", "Text/online-sync.xhtml")
            setAttribute("media-type", "application/xhtml+xml")
        })
        manifest.appendChild(element("item").apply {
            setAttribute("id", "ncx"); setAttribute("href", "toc.ncx")
            setAttribute("media-type", "application/x-dtbncx+xml")
        })
        pkg.appendChild(manifest)
        pkg.appendChild(element("spine").apply {
            setAttribute("toc", "ncx")
            appendChild(element("itemref").apply { setAttribute("idref", "online-sync") })
        })
        val bytes = ByteArrayOutputStream().also { out ->
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(out))
        }.toByteArray()
        val title = escape(metadata.bookName)
        return linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to bytes,
            "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="${escape(uuid)}"/></head><docTitle><text>$title</text></docTitle><navMap><navPoint id="online-sync" playOrder="1"><navLabel><text>等待书源更新</text></navLabel><content src="Text/online-sync.xhtml"/></navPoint></navMap></ncx>""".toByteArray(Charsets.UTF_8),
            "OEBPS/Text/online-sync.xhtml" to """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1><p>请配置对应书源后更新章节。</p></body></html>""".toByteArray(Charsets.UTF_8),
            PATH to marker.toString().toByteArray(Charsets.UTF_8),
            INDEX to OnlineOnDemandMetadataCodec.encode(metadata.copy(chapters = emptyList())).toByteArray(Charsets.UTF_8),
        )
    }
    /** 远端目录必须按原顺序包含本机章节，不能只凭章数认定远端较新。 */
    private fun containsLocalCatalog(received: OnlineOnDemandMetadata, existing: OnlineOnDemandMetadata): Boolean {
        var index = 0
        for (chapter in received.chapters) {
            val old = existing.chapters.getOrNull(index) ?: return true
            if (old.sourceChapterId == chapter.sourceChapterId && old.url == chapter.url &&
                old.href == chapter.href) index++
        }
        return index == existing.chapters.size
    }

    /** 接收端保留有效正文；旧目录或目录分歧交由书源核对，不截断本机图书。 */
    fun mergeLocalContent(incoming: File, local: File, expectedUuid: String) {
        require(uuid(incoming) == expectedUuid && local.name == expectedUuid) { "在线图书 UUID 不一致" }
        val received = metadata(incoming) ?: error("在线图书标记缺少章节索引")
        val existing = metadata(local) ?: return
        require(existing.sourceId == received.sourceId && existing.detailUrl == received.detailUrl) { "在线图书书源身份不一致" }
        if (!containsLocalCatalog(received, existing)) {
            // 复制完整本机 EPUB 结构，保持目录、路径和 CFI；仅留下接收标记触发书源核对。
            local.walkTopDown().filter(File::isFile).forEach { source ->
                val path = source.relativeTo(local).invariantSeparatorsPath
                if (path != PATH && (path == "mimetype" || path.startsWith("META-INF/") || path.startsWith("OEBPS/"))) {
                    require(ModuleConfigPolicy.safePath(path) &&
                        source.canonicalFile.toPath().startsWith(local.canonicalFile.toPath()))
                    val target = File(incoming, path).canonicalFile
                    require(target.toPath().startsWith(incoming.canonicalFile.toPath()))
                    target.apply { parentFile.mkdirs(); source.copyTo(this, overwrite = true) }
                }
            }
            return
        }
        val previous = existing.chapters.associateBy { it.href }
        val merged = received.copy(chapters = received.chapters.map { chapter ->
            val old = previous[chapter.href]
            val relative = "OEBPS/${chapter.href.removePrefix("OEBPS/")}"
            require(ModuleConfigPolicy.safePath(relative) && relative.startsWith("OEBPS/Text/"))
            val source = File(local, relative).canonicalFile
            if (old?.state == OnlineChapterState.READY && old.sourceChapterId == chapter.sourceChapterId &&
                old.url == chapter.url && source.isFile && source.toPath().startsWith(local.canonicalFile.toPath())) {
                File(incoming, relative).apply { parentFile.mkdirs(); source.copyTo(this, overwrite = true) }
                chapter.copy(state = OnlineChapterState.READY, downloadedAtMs = old.downloadedAtMs)
            } else chapter
        })
        File(incoming, INDEX).writeText(OnlineOnDemandMetadataCodec.encode(merged), Charsets.UTF_8)
        // 已有正文的图片等资源仍留在本机，不能因只接收标记而丢失。
        File(local, "OEBPS").walkTopDown().filter(File::isFile).forEach { source ->
            val path = source.relativeTo(local).invariantSeparatorsPath
            if (source.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "svg", "ttf", "otf", "woff", "woff2")) {
                require(source.canonicalFile.toPath().startsWith(local.canonicalFile.toPath()))
                File(incoming, path).apply { if (!exists()) { parentFile.mkdirs(); source.copyTo(this) } }
            }
        }
    }
    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
