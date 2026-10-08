package com.reamicro.fix.epub.editor

internal data class SelectionRenderedSlice(val start: SelectionCfiPoint, val text: String)

internal class HostReadingContext(
    val document: Any, val builder: Any, val rules: List<*>, val directory: String,
    val fullScreen: Boolean, val rootStyle: Any, val slices: List<SelectionRenderedSlice>,
)

internal class HostReadingProjection(
    private val host: HostSelectionSourceMapper, private val loader: ClassLoader,
    private val context: HostReadingContext,
) {
    private fun call(target: Any, name: String, vararg args: Any?) = host.invoke(target, name, *args)
    private fun children(target: Any) = (call(target, "getChildren") as? List<*>).orEmpty().filterNotNull()
    private fun key(cfi: Any) = host.anchorPoint(cfi).key

    fun map(source: String, document: Any, positions: NormalizedSelectionPositions): SelectionSourceMap {
        host.checkReadingContext()
        val sheets = loader.loadClass("org.epub.css.StyleSheets").getConstructor(List::class.java)
            .newInstance(context.rules)
        val body = call(document, "body")!!
        val builder = call(context.builder, "newBuilder")!!
        val nodeType = loader.loadClass("com.fleeksoft.ksoup.nodes.Node")
        val ext = loader.loadClass("org.epub.utils.NodeExtKt")
        call(builder, "body", ext.getMethod("getSteps", nodeType).invoke(null, body))
        val factory = loader.loadClass("org.epub.html.node.BodyDom").getField("INSTANCE").get(null)
        val rendered = call(factory, "parseBody", body, builder, sheets, context.directory, context.fullScreen)!!
        val parser = loader.loadClass("com.fleeksoft.ksoup.parser.Parser").getField("Companion").get(null)
        val entityCache = hashMapOf<String, String>()
        fun range(node: Any): Pair<Int, Int> {
            val r = call(node, "sourceRange")!!
            require(call(r, "isTracked") == true) { "排版生成的节点没有源码位置" }
            return positions.original((call(r, "startPos") as Number).toInt(), (call(r, "endPos") as Number).toInt())
        }
        val text = StringBuilder()
        val starts = SelectionInts()
        val ends = SelectionInts()
        val anchors = linkedMapOf<String, Int>()
        val limits = linkedMapOf<String, Int>()
        val runs = linkedMapOf<String, IntRange>()
        val nodeEnds = linkedMapOf<String, Int>()
        val breaks = hashSetOf<Int>()
        val origins = hashMapOf<Int, Pair<Int, Int>>()
        var priorContainer: Int? = null
        fun add(value: String, start: Int = -2, end: Int = -2) {
            text.append(value)
            repeat(value.length) { starts.add(start); ends.add(end) }
        }
        fun leaf(dom: Any, depth: Int) {
            require(depth < 256) { "章节嵌套过深" }
            val node = call(dom, "getNode")!!
            val tag = call(dom, "getTag") as String
            when (tag) {
                "#comment", "#doctype", "rt" -> return
                "#text" -> {
                    val value = call(dom, "getText") as String
                    if (value.isEmpty()) return
                    val style = call(dom, "getStyle")!!
                    val mode = call(call(style, "getWritingMode")!!, "getActualValue")!!
                    if (!mode.javaClass.name.endsWith("\$HorizontalTB")) {

                        add(value.filterNot { it.isWhitespace() }.map { it.toString() }.joinToString("\n"))
                        return
                    }
                    val mapped = runCatching {
                        val (a, b) = range(node)
                        mapSelectionTextNode(source.substring(a, b), a, value) { candidate ->
                            entityCache.getOrPut(candidate) { call(parser, "unescapeEntities", candidate, false) as String }
                        }
                    }.getOrNull()
                    if (mapped == null) add(value)
                    else { text.append(mapped.text); starts.addAll(mapped.starts); ends.addAll(mapped.ends) }
                }
                "br" -> {
                    val location = runCatching { range(node) }.getOrNull()
                    add("\n", location?.first ?: -2, location?.second ?: -2)
                }
                "img", "image", "svg", "path", "ruby", "video", "audio", "object" -> add("\ufffc")
                else -> {
                    val atomic = call(dom, "getRequiresInlineAtomicBox") == true
                    val decorated = call(dom, "getHasInlineDecoration") == true
                    val note = call(node, "hasAttr", "data-wr-footernote") == true ||
                        call(node, "hasAttr", "title") == true
                    if (atomic || decorated || note) add("\ufffc")
                    else for (child in children(dom)) leaf(child, depth + 1)
                }
            }
        }
        fun visit(dom: Any, depth: Int) {
            require(depth < 256) { "章节嵌套过深" }
            val type = dom.javaClass.simpleName
            val location = call(dom, "getLocation")!!
            val nodeKey = key(location)
            if (type == "ContentDom") {
                val parent = call(dom, "getParent")
                val container = parent?.let { call(it, "getElement") }?.let { runCatching { range(it).first }.getOrNull() }

                val separator = if (text.isNotEmpty()) text.length.also { add("\n", -1, -1); breaks += it } else null
                val from = text.length
                for (child in children(dom)) leaf(child, depth + 1)

                while (text.length > from && text.last() == '\n') {
                    text.setLength(text.length - 1); starts.removeLast(); ends.removeLast()
                }
                if (text.length == from) {
                    if (separator != null) {
                        text.setLength(separator); starts.removeLast(); ends.removeLast(); breaks -= separator
                    }
                    return
                }
                require(nodeKey !in runs) { "宿主生成了重复阅读流锚点，不能安全回写" }
                anchors[nodeKey] = from
                limits[nodeKey] = text.length
                runs[nodeKey] = from until text.length
                nodeEnds[nodeKey] = text.length
                if (separator != null) origins[separator] = (priorContainer ?: -1) to (container ?: -1)
                priorContainer = container
            } else {

                anchors.putIfAbsent(nodeKey, text.length)
                val nested = if (type == "TableDom") listOfNotNull(
                    call(dom, "getHead"), call(dom, "getBody"), call(dom, "getFoot"),
                ) else children(dom)
                for (child in nested) visit(child, depth + 1)
                limits.putIfAbsent(nodeKey, text.length)

                nodeEnds[nodeKey] = text.length
            }
        }
        visit(rendered, 0)
        host.checkReadingContext()
        return SelectionSourceMap(source, text.toString(), starts.toArray(), ends.toArray(),
            anchors, limits, breaks, runs, nodeEnds, breaks, origins, strictReadingRuns = true)
    }
}
