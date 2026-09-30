package com.reamicro.fix.epub.editor

import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException

/**
 * Uses the target APK's own Ksoup parser and NodeExt paths; never writes its serialized DOM.
 * readHtml in 2.3.2 normalizes self-closing non-void tags before HTML parsing. Track that
 * preprocessing separately, so original offsets/entities/attributes remain intact.
 */
internal class HostSelectionSourceMapper(private val loader: ClassLoader) {
    private val parserType = loader.loadClass("com.fleeksoft.ksoup.parser.Parser")
    private val companion = parserType.getField("Companion").get(null)
    private val normalize = loader.loadClass("app.zhendong.reamicro.arch.fs.PathExtKt")
        .getDeclaredMethod("normalizeSelfClosingNonVoidTags", String::class.java).apply { isAccessible = true }
    private val cfiType = loader.loadClass("org.epub.html.EpubCFI")
    private val cfiCompanion = cfiType.getField("Companion").get(null)
    private val methods = HashMap<String, Method>()

    fun hostCfi(raw: String): Any = invoke(cfiCompanion, "create", raw) ?: error("宿主无法解析选区 CFI")
    fun point(cfi: Any): SelectionCfiPoint {
        val offset = invoke(cfi, "getOffset") ?: error("选区缺少字符位置")
        val steps = (invoke(cfi, "getSteps") as? List<*>).orEmpty().map { number(it!!, "getIndex") }
        return SelectionCfiPoint(
            number(invoke(cfi, "getSpine")!!, "getIndex"),
            number(invoke(cfi, "getItemref")!!, "getIndex"), steps,
            number(offset, "getOffset"), number(offset, "getIndex"),
        )
    }

    fun map(source: String, baseUri: String): SelectionSourceMap {
        require(source.length <= 4_000_000) { "章节超过当前选区映射的安全范围，请使用图书结构编辑器" }
        val normalized = normalize.invoke(null, source) as String
        val positions = NormalizedSelectionPositions(source, normalized) { normalize.invoke(null, it) as String }
        val parser = invoke(companion, "htmlParser")!!
        invoke(parser, "setTrackPosition", true)
        val document = invoke(parser, "parseInput", normalized, baseUri)!!
        val body = invoke(document, "body")!!
        val nodeType = loader.loadClass("com.fleeksoft.ksoup.nodes.Node")
        val pathMethod = loader.loadClass("org.epub.utils.NodeExtKt").getMethod("getAllSteps", nodeType)
        val text = StringBuilder()
        val starts = SelectionInts()
        val ends = SelectionInts()
        val anchors = linkedMapOf<String, Int>()
        val groups = hashMapOf<String, String>()
        val groupEnds = hashMapOf<String, Int>()
        val structuralBreaks = hashSetOf<Int>()
        val entityCache = hashMapOf<String, String>()
        fun add(value: String, start: Int = -1, end: Int = -1) {
            for (c in value) { text.append(c); starts.add(start); ends.add(end) }
        }
        fun key(node: Any): String {
            val steps = pathMethod.invoke(null, node) as List<*>
            return steps.joinToString("/") { number(it!!, "getIndex").toString() }
        }
        fun sourceRange(node: Any): Pair<Int, Int> {
            val range = invoke(node, "sourceRange")!!
            require(invoke(range, "isTracked") == true) { "该选区由解析器生成，缺少原文件位置" }
            return positions.original(number(range, "startPos"), number(range, "endPos"))
        }
        fun visit(node: Any, inheritedGroup: String, depth: Int) {
            require(depth < 256) { "章节嵌套过深" }
            val name = (invoke(node, "nodeName") as String).lowercase()
            val nodeKey = key(node)
            if (name in ignored) return
            val block = name in blocks
            val group = if (block) nodeKey else inheritedGroup
            anchors[nodeKey] = text.length
            groups[nodeKey] = group
            val before = text.length
            when {
                name == "#text" -> {
                    val expected = (invoke(node, "text") as String).trim(' ', '\n')
                    if (expected.isNotEmpty()) {
                        val mapped = runCatching {
                            val (a, b) = sourceRange(node)
                            mapSelectionTextNode(source.substring(a, b), a, expected) { candidate ->
                                entityCache.getOrPut(candidate) { invoke(companion, "unescapeEntities", candidate, false) as String }
                            }
                        }.getOrNull()
                        if (mapped == null) add(expected, -2, -2)
                        else {
                            text.append(mapped.text)
                            starts.addAll(mapped.starts); ends.addAll(mapped.ends)
                        }
                    }
                }
                name == "#cdata" -> add((invoke(node, "text") as String).trim(' ', '\n'), -2, -2)
                name == "br" -> {
                    val (a, b) = sourceRange(node)
                    add("\n", a, b)
                }
                name in objects -> add("\ufffc")
                else -> {
                    val children = (invoke(node, "childNodes") as? List<*>).orEmpty()
                    for (child in children) if (child != null) visit(child, group, depth + 1)
                }
            }
            if (block) {
                if (text.length > before && text.last() != '\n') {
                    structuralBreaks += text.length
                    add("\n")
                }
                groupEnds[group] = text.length
            }
        }
        visit(body, "0", 0)
        val runEnds = groups.mapValues { (_, group) -> groupEnds[group] ?: text.length }
        return SelectionSourceMap(source, text.toString(), starts.toArray(), ends.toArray(), anchors, runEnds, structuralBreaks)
    }

    private fun number(target: Any, name: String) = (invoke(target, name) as Number).toInt()
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val key = target.javaClass.name + "#" + name + args.joinToString { it?.javaClass?.name.orEmpty() }
        val method = methods.getOrPut(key) {
            target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { i ->
                        val type = method.parameterTypes[i]
                        args[i] == null || type.isInstance(args[i]) ||
                            (type == Boolean::class.javaPrimitiveType && args[i] is Boolean)
                    }
            } ?: error("宿主缺少选区映射接口：${target.javaClass.name}.$name")
        }
        try { return method.invoke(target, *args) }
        catch (e: InvocationTargetException) { throw e.targetException ?: e }
    }
    private companion object {
        val ignored = setOf("#comment", "#doctype", "script", "style", "head", "noscript", "template", "rt", "rp")
        val objects = setOf("img", "svg", "image", "video", "audio", "object")
        val blocks = setOf("body", "p", "div", "section", "article", "main", "aside", "header", "footer",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dd", "dt", "blockquote",
            "pre", "table", "tr", "td", "th", "figure", "figcaption", "nav", "hr")
    }
}

internal class SelectionInts {
    private var array = IntArray(256)
    private var size = 0
    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }
    fun addAll(values: IntArray) { for (v in values) add(v) }
    fun toArray(): IntArray = array.copyOf(size)
}

/** Token-wise reproduction must equal the host preprocessing exactly, or mapping is refused. */
internal class NormalizedSelectionPositions(
    private val original: String,
    private val normalized: String,
    normalizeToken: (String) -> String,
) {
    private data class Segment(val from: Int, val to: Int, val originalFrom: Int, val originalTo: Int)
    private val segments: List<Segment>
    init {
        if (original == normalized) segments = listOf(Segment(0, original.length, 0, original.length))
        else {
            val result = arrayListOf<Segment>()
            val rebuilt = StringBuilder()
            var i = 0
            while (i < original.length) {
                val start = i
                if (original.startsWith("<!--", i)) {
                    val end = original.indexOf("-->", i + 4)
                    require(end >= 0) { "章节注释未闭合" }; i = end + 3
                } else if (original[i] == '<' && i + 1 < original.length &&
                    (original[i + 1].isLetter() || original[i + 1] in "/!?")) {
                    var quote: Char? = null
                    i++
                    while (i < original.length) {
                        val c = original[i++]
                        if (quote != null) { if (c == quote) quote = null }
                        else if (c == '\'' || c == '"') quote = c
                        else if (c == '>') break
                    }
                } else {
                    i++
                    while (i < original.length && original[i] != '<') i++
                }
                val token = original.substring(start, i)
                val converted = normalizeToken(token)
                val from = rebuilt.length
                rebuilt.append(converted)
                result += Segment(from, rebuilt.length, start, i)
            }
            require(rebuilt.toString() == normalized) { "章节预处理无法无损映射，未修改文件" }
            segments = result
        }
    }
    fun original(start: Int, end: Int): Pair<Int, Int> {
        require(start >= 0 && end >= start && end <= normalized.length) { "源位置越界" }
        val segment = segments.firstOrNull { start >= it.from && end <= it.to }
            ?: error("选区文本跨越解析器合并边界")
        require(segment.to - segment.from == segment.originalTo - segment.originalFrom) {
            "选区落在解析器生成的标签中"
        }
        val a = segment.originalFrom + start - segment.from
        val b = segment.originalFrom + end - segment.from
        require(original.substring(a, b) == normalized.substring(start, end))
        return a to b
    }
}
