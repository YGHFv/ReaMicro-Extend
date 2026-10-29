package com.reamicro.fix.hook

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.core.HostHtmlParser
import com.reamicro.fix.hook.reader.*

internal class HostSearchIndexBuilder(loader: ClassLoader) {
    private val parser = HostHtmlParser(loader)
    private val nodeClass = loader.hostClass("com.fleeksoft.ksoup.nodes.Node")
    private val textClass = loader.hostClass("com.fleeksoft.ksoup.nodes.TextNode")
    private val nodeName = nodeClass.hostMethod("nodeName")
    private val childNodes = nodeClass.hostMethod("childNodes")
    private val nodeText = textClass.hostMethod("text")
    private val body = loader.hostClass("com.fleeksoft.ksoup.nodes.Document").hostMethod("body")
    private val getSteps = loader.hostStaticMethod("org.epub.utils.NodeExtKt", "getAllSteps", nodeClass)
    private val getStep = loader.hostStaticMethod("org.epub.utils.NodeExtKt", "getSteps", nodeClass)
    private val cfiClass = loader.hostClass("org.epub.html.EpubCFI")
    private val stepClass = loader.hostClass("org.epub.html.EpubCFI\$StepReference")
    private val idClass = loader.hostClass("org.epub.html.EpubCFI\$IDAssertion")
    private val offsetClass = loader.hostClass("org.epub.html.EpubCFI\$CharacterOffset")
    private val biasClass = loader.hostClass("org.epub.html.EpubCFI\$SideBias")
    private val step = stepClass.getConstructor(Int::class.javaPrimitiveType, idClass)
    private val cfi = cfiClass.getDeclaredConstructor(stepClass, stepClass, stepClass,
        List::class.java, offsetClass, biasClass).apply { isAccessible = true }
    private val zeroOffset = offsetClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(1, 0)

    fun index(raw: String, base: CfiBase): IndexedSearchText {
        require(raw.length <= 4_000_000) { "章节超过索引范围" }
        val doc = parser.parse(raw, "/search-source.xhtml")
        val root = body.invoke(doc)!!
        val spine = step.newInstance(base.spineIndex, null)
        val item = step.newInstance(base.itemRefIndex, null)
        val bodyStep = getStep.invoke(null, root)
        val text = StringBuilder()
        val spans = ArrayList<TextSpan>()
        fun separator() { if (text.isNotEmpty() && text.last() != '\n') text.append('\n') }
        fun visit(node: Any, depth: Int) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("索引已取消")
            require(depth < 256)
            val name = (nodeName.invoke(node) as String).lowercase()
            if (name in ignored) return
            val block = name in blocks
            if (block) separator()
            when {
                name == "#text" -> {
                    val value = (nodeText.invoke(node) as String).trim(' ', '\n')
                    if (value.isNotEmpty()) {
                        val start = text.length; text.append(value)
                        // 使用同一宿主 CFI 数据类型，省去已内联的 Builder setter；不改节点步进与字符偏移。
                        val location = cfi.newInstance(spine, item, bodyStep, getSteps.invoke(null, node), zeroOffset, null)
                        val prefix = location.toString().substringBeforeLast(':') + ":"
                        spans += TextSpan(start, text.length, base, emptyList(), 1, 0, prefix)
                    }
                }
                name == "br" -> text.append('\n')
                name in objects -> text.append('\ufffc')
                else -> (childNodes.invoke(node) as List<*>).filterNotNull().forEach { visit(it, depth + 1) }
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
