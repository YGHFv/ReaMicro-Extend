package com.reamicro.fix.hook

import com.reamicro.fix.hook.reader.*

internal class HostSearchIndexBuilder(loader: ClassLoader) {
    private val parserClass = loader.loadClass("com.fleeksoft.ksoup.parser.Parser")
    private val companion = parserClass.getField("Companion").get(null)
    private val htmlParser = companion.javaClass.getMethod("htmlParser")
    private val parse = parserClass.getMethod("parseInput", String::class.java, String::class.java)
    private val normalize = loader.loadClass("app.zhendong.reamicro.arch.fs.PathExtKt")
        .getDeclaredMethod("normalizeSelfClosingNonVoidTags", String::class.java).apply { isAccessible = true }
    private val nodeClass = loader.loadClass("com.fleeksoft.ksoup.nodes.Node")
    private val textClass = loader.loadClass("com.fleeksoft.ksoup.nodes.TextNode")
    private val nodeName = nodeClass.getMethod("nodeName")
    private val childNodes = nodeClass.getMethod("childNodes")
    private val nodeText = textClass.getMethod("text")
    private val body = loader.loadClass("com.fleeksoft.ksoup.nodes.Document").getMethod("body")
    private val ext = loader.loadClass("org.epub.utils.NodeExtKt")
    private val getSteps = ext.getMethod("getAllSteps", nodeClass)
    private val getStep = ext.getMethod("getSteps", nodeClass)
    private val cfiClass = loader.loadClass("org.epub.html.EpubCFI")
    private val cfiCompanion = cfiClass.getField("Companion").get(null)
    private val create = cfiCompanion.javaClass.getMethod("create", String::class.java)
    private val toBuilder = cfiClass.getMethod("toBuilder")
    private val builderClass = loader.loadClass("org.epub.html.EpubCFI\$Builder")
    private val stepClass = loader.loadClass("org.epub.html.EpubCFI\$StepReference")
    private val offsetClass = loader.loadClass("org.epub.html.EpubCFI\$CharacterOffset")
    private val setBody = builderClass.getMethod("body", stepClass)
    private val setSteps = builderClass.getMethod("steps", List::class.java)
    private val setOffset = builderClass.getMethod("offset", offsetClass)
    private val build = builderClass.getMethod("build")
    private val zeroOffset = offsetClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(1, 0)

    fun index(raw: String, base: CfiBase): IndexedSearchText {
        require(raw.length <= 4_000_000) { "章节超过索引范围" }
        val doc = parse.invoke(htmlParser.invoke(companion), normalize.invoke(null, raw), "/search-source.xhtml")!!
        val root = body.invoke(doc)!!
        val template = create.invoke(cfiCompanion, "epubcfi(/${base.spineIndex}/${base.itemRefIndex}/4/0)")!!
        val builder = toBuilder.invoke(template)!!

        setBody.invoke(builder, getStep.invoke(null, root))
        setOffset.invoke(builder, zeroOffset)
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
                        setSteps.invoke(builder, getSteps.invoke(null, node))
                        val prefix = build.invoke(builder).toString().substringBeforeLast(':') + ":"
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
