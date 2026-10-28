package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.HostObject
import com.reamicro.fix.core.HostObjects
import com.reamicro.fix.core.HostHtmlParser
import com.reamicro.fix.core.HostHtmlNormalization
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException

internal class HostSelectionSourceMapper(private val loader: ClassLoader) {
    private val parser = HostHtmlParser(loader)
    private val cfiType = loader.hostClass("org.epub.html.EpubCFI")
    private val methods = HashMap<String, Method>()
    private var sourcePositions: HostSelectionSourcePositions? = null
    /** `org.epub.UIEpubWindow` 在混淆版被 R8 归约为纯静态类，统一走 HostObject 取排版状态。 */
    private val epubWindow: HostObject by lazy { HostObjects.get(loader, "org.epub.UIEpubWindow") }

    private var reading: HostReadingContext? = null

    fun bindReadingDocument(document: Any, controller: Any) {
        fun field(target: Any, name: String) = target.javaClass.hostField(name)
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
        reading = HostReadingContext(document, invoke(document, "getEpubcfi")!!,
            (invoke(field(document, "styleSheets"), "getRuleSets") as List<*>).toList(),
            field(document, "dir") as String, invoke(document, "isFullScreen") as Boolean,
            requireNotNull(epubWindow.call("getRootStyle")) { "排版根样式尚未初始化" }, slices)
    }

    fun bindSearchDocument(document: Any) {
        fun field(name: String) = document.javaClass.hostField(name).apply { isAccessible = true }.get(document)!!
        reading = HostReadingContext(document, invoke(document, "getEpubcfi")!!,
            (invoke(field("styleSheets"), "getRuleSets") as List<*>).toList(), field("dir") as String,
            invoke(document, "isFullScreen") as Boolean,
            requireNotNull(epubWindow.call("getRootStyle")) { "排版根样式尚未初始化" }, emptyList())
    }

    fun checkReadingContext(document: Any? = null) {
        val context = reading ?: error("缺少真实阅读页渲染上下文，未写入")
        require((document == null || document === context.document) &&
            epubWindow.callOrNull("getRootStyle") === context.rootStyle) {
            "阅读页或排版设置已变化，请重新选择（未写入）"
        }
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

    fun hostCfi(raw: String): Any {
        if (HostSymbols.isIdentity) return invoke(cfiType.hostField("Companion").get(null), "create", raw)
            ?: error("宿主无法解析选区 CFI")
        return requireNotNull(loader.hostClass("org.epub.html.EpubCFI\$Companion")
            .hostMethod("create", String::class.java).invoke(null, raw))
    }
    internal fun sourceRange(node: Any) = requireNotNull(sourcePositions).range(node)
    internal fun unescape(value: String) = requireNotNull(sourcePositions).unescape(value)
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
        val stepType = loader.hostClass("org.epub.html.EpubCFI\$StepReference")
        val idType = loader.hostClass("org.epub.html.EpubCFI\$IDAssertion")
        val offsetType = loader.hostClass("org.epub.html.EpubCFI\$CharacterOffset")
        val steps = point.steps.map { stepType.getConstructor(Int::class.javaPrimitiveType, idType).newInstance(it, null) }
        val offset = offsetType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(point.offsetIndex, point.offset)
        require(number(invoke(template, "getSpine")!!, "getIndex") == point.spine &&
            number(invoke(template, "getItemref")!!, "getIndex") == point.itemRef) { "CFI 章节身份不一致" }
        val biasType = loader.hostClass("org.epub.html.EpubCFI\$SideBias")
        val bias = if (invoke(template, "getOffset") != null) invoke(template, "getSideBias") else null
        val translatedBias = bias?.let {
            biasType.getConstructor(Boolean::class.javaPrimitiveType, String::class.java)
                .newInstance(invoke(it, "getBefore"), "")
        }
        val result = cfiType.getDeclaredConstructor(stepType, stepType, stepType, List::class.java, offsetType, biasType)
            .newInstance(invoke(template, "getSpine"), invoke(template, "getItemref"),
                stepType.getConstructor(Int::class.javaPrimitiveType, idType).newInstance(point.bodyIndex, null),
                steps, offset, translatedBias)
        require(this.point(result) == point) { "宿主 CFI 往返校验失败" }
        return result
    }
    fun map(source: String, baseUri: String): SelectionSourceMap {
        require(source.length <= 4_000_000) { "章节超过当前选区映射的安全范围，请使用图书结构编辑器" }
        val normalized = HostHtmlNormalization.normalize(source)
        val positions = NormalizedSelectionPositions(source, normalized, HostHtmlNormalization::normalize)
        val document = parser.parse(source, baseUri)
        val body = invoke(document, "body")!!
        sourcePositions = HostSelectionSourcePositions(loader, normalized, body, positions)
        reading?.let { return HostReadingProjection(this, loader, it).map(source, document, positions) }
        val nodeType = loader.hostClass("com.fleeksoft.ksoup.nodes.Node")
        val pathMethod = loader.hostClass("org.epub.utils.NodeExtKt").hostMethod("getAllSteps", nodeType)
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
                                entityCache.getOrPut(candidate) { unescape(candidate) }
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
        try {
            // 水平合并后的 Builder 与 Request 共用类名，必须按当前已知对象角色解析成员。
            if (target.javaClass == loader.hostClass("org.epub.html.EpubCFI\$Builder") &&
                name in setOf("newBuilder", "build")) {
                return target.javaClass.hostMethod(HostSymbols.resolveMethodName("org.epub.html.EpubCFI\$Builder", name))
                    .invoke(target)
            }
            if (name == "getHasInlineDecoration" && !HostSymbols.isIdentity) {
                if (invoke(target, "getRequiresInlineAtomicBox") == true) return false
                val style = requireNotNull(invoke(target, "getStyle"))
                // 与 B1 getter 的八项判断完全一致；B2 把该 getter 内联到了布局中。
                val spacing = listOf("getPaddingLeft", "getPaddingRight", "getMarginLeft", "getMarginRight")
                    .any { (invoke(requireNotNull(invoke(style, it)), "getNumber") as Number).toFloat() != 0f }
                return spacing || listOf("getBorderLeftStyle", "getBorderRightStyle", "getBackgroundColor", "getBackgroundImage")
                    .any { invoke(requireNotNull(invoke(style, it)), "isValid") == true }
            }
            return XposedHelpers.callMethod(target, name, *args)
        }
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
