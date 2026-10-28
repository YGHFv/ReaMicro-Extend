package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostHtmlParser
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import java.util.IdentityHashMap

/** 逐树、逐节点验证原生 DOM 后才赋予位置，绝不按同一段文本的出现次序猜测。 */
internal class HostSelectionSourcePositions(
    loader: ClassLoader, source: String, body: Any, private val positions: NormalizedSelectionPositions,
) {
    private val ranges = IdentityHashMap<Any, Pair<Int, Int>>()
    private val names = loader.hostClass("com.fleeksoft.ksoup.nodes.Node").hostMethod("nodeName")
    private val children = loader.hostClass("com.fleeksoft.ksoup.nodes.Node").hostMethod("childNodes")
    private val wholeText = loader.hostClass("com.fleeksoft.ksoup.nodes.TextNode").hostMethod("getWholeText")
    private val parser = HostHtmlParser(loader)
    private val bodyMethod = loader.hostClass("com.fleeksoft.ksoup.nodes.Document").hostMethod("body")
    private val decoded = HashMap<String, String>()

    init {
        val raw = SelectionHtmlSourceTree(source)
        fun name(node: Any) = (names.invoke(node) as String).lowercase()
        fun nativeChildren(node: Any): List<Any> = (children.invoke(node) as List<*>).filterNotNull()
            .filterNot { name(it) == "#comment" }
        fun match(rawNodes: List<SelectionHtmlSourceTree.Node>, nativeNodes: List<Any>, depth: Int) {
            require(depth < 256) { "章节嵌套过深" }
            var index = 0
            for (native in nativeNodes) {
                val nativeName = name(native)
                if (nativeName == "tbody" && rawNodes.getOrNull(index)?.name == "tr") {
                    val count = nativeChildren(native).size
                    require(index + count <= rawNodes.size) { "宿主修复了表格结构，无法证明源码位置" }
                    match(rawNodes.subList(index, index + count), nativeChildren(native), depth + 1)
                    index += count
                    continue
                }
                val original = rawNodes.getOrNull(index++) ?: error("宿主生成了节点：$nativeName")
                require(original.name == nativeName) {
                    "宿主调整了章节结构：${original.name}/$nativeName，无法证明源码位置"
                }
                if (nativeName == "#text" || nativeName == "#cdata") {
                    val expected = if (nativeName == "#cdata") raw.text(original) else unescape(raw.text(original))
                    require(wholeText.invoke(native) == expected) { "宿主文本与原文件不一致，无法证明源码位置" }
                } else if (nativeName !in SelectionHtmlSourceTree.rawTextTags) {
                    match(original.children.filterNot { it.name == "#comment" }, nativeChildren(native), depth + 1)
                }
                ranges[native] = original.start to original.end
            }
            require(index == rawNodes.size) { "宿主丢弃了章节节点，无法证明源码位置" }
        }
        match(raw.bodyChildren(), nativeChildren(body), 0)
    }

    fun range(node: Any): Pair<Int, Int> {
        val (start, end) = ranges[node] ?: error("排版生成的节点没有源码位置")
        return positions.original(start, end)
    }

    fun unescape(value: String): String = decoded.getOrPut(value) {
        if ('&' !in value && '<' !in value) value.replace("\r\n", "\n").replace('\r', '\n')
        else {
            val root = bodyMethod.invoke(parser.parse("<body>$value</body>", "/selection-entity.xhtml"))
            val nodes = (children.invoke(root) as List<*>).filterNotNull()
            require(nodes.all { names.invoke(it) == "#text" }) { "实体解码产生了非文本节点" }
            nodes.joinToString("") { wholeText.invoke(it) as String }
        }
    }
}
