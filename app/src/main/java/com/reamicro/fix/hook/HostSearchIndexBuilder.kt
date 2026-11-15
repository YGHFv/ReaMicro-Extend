package com.reamicro.fix.hook

import com.reamicro.fix.core.HostEpubSourceBindings
import com.reamicro.fix.core.HostHtmlParser
import com.reamicro.fix.hook.reader.*

internal class HostSearchIndexBuilder(loader: ClassLoader) {
    private val parser = HostHtmlParser(loader)
    private val host get() = requireNotNull(HostEpubSourceBindings.current)

    fun index(raw: String, base: CfiBase): IndexedSearchText {
        require(raw.length <= 4_000_000) { "章节超过索引范围" }
        val doc = parser.parse(raw, "/search-source.xhtml")
        val root = host.body(doc)
        val spine = host.step(base.spineIndex)
        val item = host.step(base.itemRefIndex)
        val bodyStep = host.method("node.step").invoke(null, root)
        val zeroOffset = host.offset(1, 0)
        val text = StringBuilder()
        val spans = ArrayList<TextSpan>()
        fun separator() { if (text.isNotEmpty() && text.last() != '\n') text.append('\n') }
        fun visit(node: Any, depth: Int) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("索引已取消")
            require(depth < 256)
            val name = host.nodeName(node).lowercase(java.util.Locale.ROOT)
            if (name in ignored) return
            val block = name in blocks
            if (block) separator()
            when {
                name == "#text" -> {
                    val value = host.text(node).trim(' ', '\n')
                    if (value.isNotEmpty()) {
                        val start = text.length; text.append(value)
                        // 使用同一宿主 CFI 数据类型，省去已内联的 Builder setter；不改节点步进与字符偏移。
                        val location = host.record("cfi", mapOf("spine" to spine, "item" to item, "body" to bodyStep,
                            "steps" to host.method("node.steps").invoke(null, node), "offset" to zeroOffset, "bias" to null))
                        val prefix = location.toString().substringBeforeLast(':') + ":"
                        spans += TextSpan(start, text.length, base, emptyList(), 1, 0, prefix)
                    }
                }
                name == "br" -> text.append('\n')
                name in objects -> text.append('\ufffc')
                else -> host.children(node).forEach { visit(it, depth + 1) }
            }
            if (block) separator()
        }
        visit(root, 0)
        return IndexedSearchText(text.toString().trimEnd('\n'), spans, searchSourceDigest(raw))
    }
    private companion object {
        val ignored = setOf("#comment", "#doctype", "#cdata", "script", "style", "head", "noscript", "template", "rt", "rp")
        val objects = setOf("img", "svg", "image", "video", "audio", "object")
        val blocks = setOf("body", "p", "div", "section", "article", "main", "aside", "header", "footer",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dd", "dt", "blockquote",
            "pre", "table", "tr", "td", "th", "figure", "figcaption", "nav", "hr")
    }
}
