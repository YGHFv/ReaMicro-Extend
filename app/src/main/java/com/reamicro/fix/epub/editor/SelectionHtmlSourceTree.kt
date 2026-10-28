package com.reamicro.fix.epub.editor

import java.util.Locale

/** 只记录原文件位置，不替代宿主 HTML 解析；修复过的非平衡结构必须拒绝对应。 */
internal class SelectionHtmlSourceTree(private val source: String) {
    class Node(val name: String, val start: Int, initialEnd: Int, val children: MutableList<Node> = arrayListOf()) {
        var end: Int = initialEnd
    }
    private val root = Node("#document", 0, source.length)

    init {
        val stack = arrayListOf(root)
        var cursor = 0
        fun add(name: String, start: Int, end: Int) { stack.last().children += Node(name, start, end) }
        while (cursor < source.length) {
            require(stack.size <= 256) { "章节嵌套过深" }
            val start = cursor
            when {
                source.startsWith("<!--", cursor) -> {
                    val end = source.indexOf("-->", cursor + 4)
                    require(end >= 0) { "章节注释未闭合" }
                    cursor = end + 3
                    add("#comment", start, cursor)
                }
                source.startsWith("<![CDATA[", cursor) -> {
                    val end = source.indexOf("]]>", cursor + 9)
                    require(end >= 0) { "章节 CDATA 未闭合" }
                    cursor = end + 3
                    add("#cdata", start + 9, end)
                }
                source.startsWith("<?", cursor) || source.startsWith("<!", cursor) -> {
                    cursor = tagEnd(cursor)
                }
                source.startsWith("</", cursor) -> {
                    cursor = tagEnd(cursor)
                    val name = source.substring(start + 2, cursor - 1).trim().lowercase(Locale.ROOT)
                    require(stack.size > 1 && stack.last().name == name) { "章节标签不平衡，无法证明源码位置：$name" }
                    stack.last().end = cursor
                    stack.removeAt(stack.lastIndex)
                }
                source[cursor] == '<' && source.getOrNull(cursor + 1)?.isLetter() == true -> {
                    cursor = tagEnd(cursor)
                    val name = source.substring(start + 1, cursor - 1).takeWhile { !it.isWhitespace() && it != '/' }
                        .lowercase(Locale.ROOT)
                    val node = Node(name, start, cursor)
                    stack.last().children += node
                    if (name in rawTextTags) {
                        val closing = source.indexOf("</$name", cursor, ignoreCase = true)
                        require(closing >= 0) { "章节标签未闭合：$name" }
                        cursor = tagEnd(closing)
                    } else if (name !in voids && !source.substring(start, cursor).trimEnd().endsWith("/>")) {
                        stack += node
                    }
                }
                else -> {
                    cursor++
                    while (cursor < source.length && !isMarkup(cursor)) cursor++
                    add("#text", start, cursor)
                }
            }
        }
        require(stack.size == 1) { "章节标签未闭合，无法证明源码位置" }
    }

    fun bodyChildren(): List<Node> {
        val html = root.children.firstOrNull { it.name == "html" }
        val children = html?.children ?: root.children
        return (children.firstOrNull { it.name == "body" }?.children ?: children)
            .filterNot { it.name == "head" || it.name == "#comment" }
    }

    fun text(node: Node): String = source.substring(node.start, node.end)

    private fun isMarkup(index: Int): Boolean = source[index] == '<' &&
        (source.getOrNull(index + 1)?.let { it.isLetter() || it in "/!?" } == true)

    private fun tagEnd(start: Int): Int {
        var quote: Char? = null
        var cursor = start + 1
        while (cursor < source.length) {
            val character = source[cursor++]
            if (quote != null) {
                if (character == quote) quote = null
            } else if (character == '"' || character == '\'') quote = character
            else if (character == '>') return cursor
        }
        error("章节标签未闭合")
    }

    companion object {
        val rawTextTags = setOf("script", "style")
        private val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr")
    }
}
