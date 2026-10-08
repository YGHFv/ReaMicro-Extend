package com.reamicro.fix.epub.editor

internal class SelectionMarkupIndex(val source: String) {
    data class Attribute(val name: String, val value: String, val raw: String)
    data class Element(
        val id: Int, val name: String, val rawName: String, val start: Int, val openEnd: Int,
        val parent: Int, val attrs: List<Attribute>, val preserved: Boolean,
        var closeStart: Int = -1, var end: Int = -1, val children: MutableList<Int> = arrayListOf(),
    ) {
        val identity: String get() = attrs.filter { it.name == "id" || it.name == "name" }
            .joinToString(" ") { it.raw }
        val anchor: String get() = identity.takeIf { it.isNotEmpty() }?.let { "<span $it></span>" }.orEmpty()
    }
    val elements = arrayListOf<Element>()
    var valid = true
        private set
    private val removable = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "span", "b", "i", "em", "strong", "u", "s", "small", "sup", "sub", "font", "a", "br")
    private val blocks = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "td", "th", "pre", "blockquote", "section", "article", "header", "footer", "body")
    private val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr")
    private val attrPattern = Regex("""([^\s=/'">]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
    init { parse() }
    private fun parse() {
        val stack = arrayListOf<Int>()
        var cursor = 0
        while (cursor < source.length) {
            val at = source.indexOf('<', cursor)
            if (at < 0) break
            if (source.startsWith("<!--", at)) {
                val end = source.indexOf("-->", at + 4)
                if (end < 0) { valid = false; return }
                cursor = end + 3; continue
            }
            var end = at + 1
            var quote: Char? = null
            while (end < source.length) {
                val c = source[end++]
                if (quote != null) { if (quote == c) quote = null }
                else if (c == '\'' || c == '"') quote = c
                else if (c == '>') break
            }
            if (end > source.length || source[end - 1] != '>' || quote != null) { valid = false; return }
            cursor = end
            val token = source.substring(at + 1, end - 1).trim()
            if (token.startsWith("!") || token.startsWith("?")) continue
            val closing = token.startsWith('/')
            val body = if (closing) token.drop(1).trimStart() else token
            val rawName = body.takeWhile { it.isLetterOrDigit() || it in ":-_" }
            val name = rawName.lowercase()
            if (name.isEmpty()) { valid = false; return }
            if (closing) {
                if (stack.isEmpty() || elements[stack.last()].name != name) { valid = false; return }
                elements[stack.removeAt(stack.lastIndex)].apply { closeStart = at; this.end = end }
                continue
            }
            val tail = body.drop(rawName.length).removeSuffix("/")
            val attrs = attrPattern.findAll(tail).map {
                Attribute(it.groupValues[1].lowercase(), it.groups[2]?.value ?: it.groups[3]?.value ?: it.groups[4]?.value.orEmpty(), it.value)
            }.toList()
            val parent = stack.lastOrNull() ?: -1
            val preserve = name in setOf("pre", "script", "style", "textarea", "title") ||
                (parent >= 0 && elements[parent].preserved) || attrs.any {
                    it.name == "xml:space" || (it.name == "style" && it.value.contains("white-space", true))
                } || attrs.map { it.name }.distinct().size != attrs.size
            val element = Element(elements.size, name, rawName, at, end, parent, attrs, preserve)
            elements += element
            if (parent >= 0) elements[parent].children += element.id
            if (name in voids || token.endsWith('/')) { element.closeStart = end; element.end = end; continue }
            if (name in setOf("script", "style", "textarea", "title")) {
                val close = Regex("</\\s*$name\\s*>", RegexOption.IGNORE_CASE).find(source, end)
                if (close == null) { valid = false; return }
                element.closeStart = close.range.first; element.end = close.range.last + 1
                cursor = element.end; continue
            }
            if (stack.size >= 256) { valid = false; return }
            stack += element.id
        }
        if (stack.isNotEmpty()) valid = false
    }
    fun blockAt(position: Int): Element? = elements.lastOrNull {
        it.name in blocks && position >= it.openEnd && position < it.closeStart
    }
    fun atStart(position: Int): Element? = elements.binarySearchBy(position) { it.start }
        .takeIf { it >= 0 }?.let { elements[it] }

    fun emptyReplacement(element: Element): String? {
        if (!valid || element.preserved || element.name !in removable ||
            element.attrs.any { it.name in setOf("href", "src", "epub:type", "role") || it.name.startsWith("on") }) return null
        if (element.name == "br") return element.anchor
        var at = element.openEnd
        val anchors = StringBuilder(element.anchor)
        for (childId in element.children) {
            val child = elements[childId]
            if (!selectionSemanticBlank(source.substring(at, child.start))) return null
            anchors.append(emptyReplacement(child) ?: return null)
            at = child.end
        }
        if (!selectionSemanticBlank(source.substring(at, element.closeStart))) return null
        return anchors.toString()
    }
    fun hasText(element: Element): Boolean {
        if (element.name in setOf("script", "style", "textarea", "title")) return false
        var at = element.openEnd
        for (id in element.children) {
            val child = elements[id]
            if (!selectionSemanticBlank(source.substring(at, child.start))) return true
            if (hasText(child)) return true
            at = child.end
        }
        return !selectionSemanticBlank(source.substring(at, element.closeStart))
    }
    fun merge(left: Element, right: Element): SelectionSourcePatch {
        val kinds = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6")
        require(valid && left.name in kinds && left.rawName == right.rawName && left.parent == right.parent &&
            !left.preserved && !right.preserved) { "标题、正文或不同层级的段落不能直接合并；请保留它们之间的换行（未写入）" }
        fun style(element: Element): Map<String, String> {
            require(element.attrs.all { it.name in setOf("id", "name", "class", "style", "lang", "xml:lang", "dir") }) {
                "段落带有语义属性，不能自动合并（未写入）"
            }
            return element.attrs.filter { it.name !in setOf("id", "name") }.associate { it.name to it.value }
        }
        require(style(left) == style(right)) { "两个段落样式不同，请保留分隔换行（未写入）" }
        require(elements.none { it.parent == left.id && it.name in blocks } &&
            elements.none { it.parent == right.id && it.name in blocks }) { "不能直接合并包含子段落的容器（未写入）" }
        val kept = StringBuilder()
        var at = left.end
        while (at < right.start) {
            if (source[at].isWhitespace()) { at++; continue }
            if (source.startsWith("<!--", at)) {
                val end = source.indexOf("-->", at + 4) + 3
                require(end >= at + 3 && end <= right.start)
                kept.append(source, at, end); at = end; continue
            }
            val anchor = atStart(at)
            require(anchor != null && anchor.end <= right.start && anchor.name in setOf("span", "a") &&
                anchor.attrs.all { it.name in setOf("id", "name") } && emptyReplacement(anchor) != null) {
                "段落之间还有未选中的结构或资源，未合并"
            }
            kept.append(source, at, anchor.end); at = anchor.end
        }
        kept.append(right.anchor)
        return SelectionSourcePatch(left.closeStart, right.openEnd, kept.toString())
    }
}

internal fun selectionSemanticBlank(raw: String): Boolean {
    if (raw.isBlank()) return true
    return raw.replace(Regex("&(?:nbsp|ensp|emsp|thinsp|#(?:160|32|9|10|13)|#x(?:a0|20|9|a|d));", RegexOption.IGNORE_CASE), " " ).isBlank()
}
