package com.reamicro.fix.epub.editor

import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException

internal class HostSelectionSourceMapper(private val loader: ClassLoader) {
    private val parserType = loader.loadClass("com.fleeksoft.ksoup.parser.Parser")
    private val companion = parserType.getField("Companion").get(null)
    private val normalize = loader.loadClass("app.zhendong.reamicro.arch.fs.PathExtKt")
        .getDeclaredMethod("normalizeSelfClosingNonVoidTags", String::class.java).apply { isAccessible = true }
    private val cfiType = loader.loadClass("org.epub.html.EpubCFI")
    private val cfiCompanion = cfiType.getField("Companion").get(null)
    private val methods = HashMap<String, Method>()

    private var reading: HostReadingContext? = null

    fun bindReadingDocument(document: Any, controller: Any) {
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)!!
        val nodes = field(controller, "nodes") as Map<*, *>
        val selection = invoke(controller, "getSelection") ?: error("选区已关闭")
        val endpoints = listOf(invoke(selection, "getAnchor")!!, invoke(selection, "getFocus")!!)
        val ids = endpoints.map { invoke(it, "getNodeId") as String }
        val ordered = nodes.values.filterNotNull().sortedWith { a, b ->
            numberComparison(invoke(a, "getCfi")!!, invoke(b, "getCfi")!!)
        }
        val indices = ids.map { id -> ordered.indexOfFirst { invoke(it, "getId") == id } }
        require(indices.all { it >= 0 }) { "阅读页选区片段已失效" }
        val slices = ordered.subList(indices.min(), indices.max() + 1).map { node ->
            SelectionRenderedSlice(anchorPoint(invoke(node, "getCfi")!!),
                invoke(invoke(node, "getText")!!, "getText") as String)
        }
        val window = loader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
        reading = HostReadingContext(document, invoke(document, "getEpubcfi")!!,
            (invoke(field(document, "styleSheets"), "getRuleSets") as List<*>).toList(),
            field(document, "dir") as String, invoke(document, "isFullScreen") as Boolean,
            invoke(window, "getRootStyle")!!, slices)
    }

    fun bindSearchDocument(document: Any) {
        fun field(name: String) = document.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(document)!!
        val window = loader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
        reading = HostReadingContext(document, invoke(document, "getEpubcfi")!!,
            (invoke(field("styleSheets"), "getRuleSets") as List<*>).toList(), field("dir") as String,
            invoke(document, "isFullScreen") as Boolean, invoke(window, "getRootStyle")!!, emptyList())
    }

    fun checkReadingContext(document: Any? = null) {
        val context = reading ?: error("缺少真实阅读页渲染上下文，未写入")
        val window = loader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
        require((document == null || document === context.document) &&
            invoke(window, "getRootStyle") === context.rootStyle) { "阅读页或排版设置已变化，请重新选择（未写入）" }
    }

    fun checkReadingDocuments(documents: Collection<*>) {
        checkReadingContext()
        val original = reading!!.document
        require(documents.any { it === original }) { "章节渲染已更新，请重新选择（未写入）" }
    }

    fun verifyReadingSlices(map: SelectionSourceMap) {
        val context = reading ?: error("缺少真实阅读页选区快照")
        require(context.slices.isNotEmpty()) { "阅读页选区快照为空" }
        context.slices.forEach { map.verifyRenderedSlice(it) }
    }

    fun hostCfi(raw: String): Any = invoke(cfiCompanion, "create", raw) ?: error("宿主无法解析选区 CFI")
    fun point(cfi: Any): SelectionCfiPoint {
        val offset = invoke(cfi, "getOffset") ?: error("选区缺少字符位置")
        val steps = (invoke(cfi, "getSteps") as? List<*>).orEmpty().map { number(it!!, "getIndex") }
        return SelectionCfiPoint(
            number(invoke(cfi, "getSpine")!!, "getIndex"),
            number(invoke(cfi, "getItemref")!!, "getIndex"), steps,
            number(offset, "getOffset"), number(offset, "getIndex"), number(invoke(cfi, "getBody")!!, "getIndex"),
        )
    }

    fun anchorPoint(cfi: Any): SelectionCfiPoint {
        val offset = invoke(cfi, "getOffset")
        val steps = (invoke(cfi, "getSteps") as? List<*>).orEmpty().map { number(it!!, "getIndex") }
        return SelectionCfiPoint(
            number(invoke(cfi, "getSpine")!!, "getIndex"),
            number(invoke(cfi, "getItemref")!!, "getIndex"), steps,
            offset?.let { number(it, "getOffset") } ?: 0,
            offset?.let { number(it, "getIndex") } ?: 1, number(invoke(cfi, "getBody")!!, "getIndex"),
        )
    }

    fun rangeOverlapsSelection(range: Any, start: Any, end: Any): Boolean {
        val pageStart = invoke(range, "getStart") ?: return false
        val pageEnd = invoke(range, "getEndExclusive") ?: return false
        if (numberComparison(start, end) >= 0 || numberComparison(pageStart, pageEnd) >= 0) return false

        return numberComparison(start, pageEnd) < 0 && numberComparison(end, pageStart) > 0
    }
    private fun numberComparison(left: Any, right: Any): Int =
        (invoke(left, "compareTo", right) as Number).toInt()

    fun viewportSourceOffset(source: SelectionSourceMap, cfi: Any): Int? {
        val point = anchorPoint(cfi)
        val bias = invoke(cfi, "getSideBias")
        val afterNode = invoke(cfi, "getOffset") == null &&
            bias != null && invoke(bias, "getAfter") == true
        return if (afterNode) source.sourceOffsetAfterNode(point) else source.sourceOffset(point)
    }
    fun cfiForPoint(template: Any, point: SelectionCfiPoint): Any {
        val stepType = loader.loadClass("org.epub.html.EpubCFI\$StepReference")
        val idType = loader.loadClass("org.epub.html.EpubCFI\$IDAssertion")
        val offsetType = loader.loadClass("org.epub.html.EpubCFI\$CharacterOffset")
        val steps = point.steps.map { stepType.getConstructor(Int::class.javaPrimitiveType, idType).newInstance(it, null) }
        val offset = offsetType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(point.offsetIndex, point.offset)
        val builder = invoke(invoke(template, "toBuilder")!!, "newBuilder")!!
        require(number(invoke(template, "getSpine")!!, "getIndex") == point.spine &&
            number(invoke(template, "getItemref")!!, "getIndex") == point.itemRef) { "CFI 章节身份不一致" }

        invoke(builder, "body", stepType.getConstructor(Int::class.javaPrimitiveType, idType).newInstance(point.bodyIndex, null))
        invoke(builder, "steps", steps)
        invoke(builder, "offset", offset)

        if (invoke(template, "getOffset") != null) {
            val bias = invoke(template, "getSideBias")
            if (bias != null) {
                val translatedBias = bias.javaClass.getConstructor(
                    Boolean::class.javaPrimitiveType, String::class.java,
                ).newInstance(invoke(bias, "getBefore"), "")
                invoke(builder, "sideBias", translatedBias)
            }
        }
        val result = invoke(builder, "build")!!
        require(this.point(result) == point) { "宿主 CFI 往返校验失败" }
        return result
    }
    fun map(source: String, baseUri: String): SelectionSourceMap {
        require(source.length <= 4_000_000) { "章节超过当前选区映射的安全范围，请使用图书结构编辑器" }
        val normalized = normalize.invoke(null, source) as String
        val positions = NormalizedSelectionPositions(source, normalized) { normalize.invoke(null, it) as String }
        val parser = invoke(companion, "htmlParser")!!
        invoke(parser, "setTrackPosition", true)
        val document = invoke(parser, "parseInput", normalized, baseUri)!!
        reading?.let { return HostReadingProjection(this, loader, it).map(source, document, positions) }

        val body = invoke(document, "body")!!
        val nodeType = loader.loadClass("com.fleeksoft.ksoup.nodes.Node")
        val pathMethod = loader.loadClass("org.epub.utils.NodeExtKt").getMethod("getAllSteps", nodeType)
        val text = StringBuilder()
        val starts = SelectionInts()
        val ends = SelectionInts()
        val anchors = linkedMapOf<String, Int>()
        val textNodes = linkedMapOf<String, IntRange>()
        val nodeEnds = hashMapOf<String, Int>()
        val groups = hashMapOf<String, String>()
        val groupEnds = hashMapOf<String, Int>()
        val structuralBreaks = hashSetOf<Int>()
        val blockBreaks = hashSetOf<Int>()
        val entityCache = hashMapOf<String, String>()
        fun add(value: String, start: Int = -1, end: Int = -1) {
            for (c in value) { text.append(c); starts.add(start); ends.add(end) }
        }
        fun blockBreak() {
            if (text.isEmpty()) return
            if (text.last() != '\n') {
                structuralBreaks += text.length
                add("\n")
            }

            blockBreaks += text.lastIndex
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

            if (block) blockBreak()
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
                            if (text.length > before) textNodes[nodeKey] = before until text.length
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
                if (text.length > before) blockBreak()
                groupEnds[group] = text.length
            }
            nodeEnds[nodeKey] = text.length
        }
        visit(body, "0", 0)
        val runEnds = groups.mapValues { (_, group) -> groupEnds[group] ?: text.length }
        return SelectionSourceMap(source, text.toString(), starts.toArray(), ends.toArray(), anchors, runEnds, structuralBreaks, textNodes, nodeEnds, blockBreaks)
    }

    private fun number(target: Any, name: String) = (invoke(target, name) as Number).toInt()
    internal fun invoke(target: Any, name: String, vararg args: Any?): Any? {
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
    fun removeLast() { require(size > 0); size-- }
    fun addAll(values: IntArray) { for (v in values) add(v) }
    fun toArray(): IntArray = array.copyOf(size)
}

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
