package com.reamicro.fix.online.epub

/** 只记录元素在原文中的范围；迁移时不重新序列化整章，保留正文和无关标记。 */
internal object OnlineMarkupRanges {
    class Element(
        val tag: String,
        val attributes: String,
        val start: Int,
        val contentStart: Int,
        val parent: Element?,
    ) {
        var contentEnd = contentStart
        var end = contentStart
        var complete = false
        fun attribute(name: String): String? =
            ATTRIBUTE.findAll(attributes).firstOrNull { it.groupValues[1].equals(name, true) }
                ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
        fun hasClass(name: String): Boolean =
            attribute("class")?.split(Regex("\\s+"))?.contains(name) == true
        fun inside(other: Element): Boolean = start > other.start && end <= other.end
    }

    fun elements(html: String): List<Element> {
        val result = arrayListOf<Element>()
        val stack = arrayListOf<Element>()
        TAG.findAll(html).forEach { token ->
            val value = token.value
            if (value.startsWith("<!--") || value.startsWith("<!") || value.startsWith("<?")) return@forEach
            val name = token.groupValues[2].lowercase()
            if (token.groupValues[1].isNotEmpty()) {
                val index = stack.indexOfLast { it.tag == name }
                if (index < 0) return@forEach
                val element = stack[index]
                element.contentEnd = token.range.first
                element.end = token.range.last + 1
                element.complete = true
                while (stack.size > index) stack.removeAt(stack.lastIndex)
            } else {
                val element = Element(name, token.groupValues[3], token.range.first,
                    token.range.last + 1, stack.lastOrNull())
                result += element
                if (value.endsWith("/>") || name in VOID_TAGS) {
                    element.complete = true
                } else stack += element
            }
        }
        return result.filter { it.complete }
    }

    fun replace(html: String, replacements: List<Pair<Element, String>>): String {
        val builder = StringBuilder(html)
        replacements.sortedByDescending { it.first.start }.forEach { (element, replacement) ->
            builder.replace(element.start, element.end, replacement)
        }
        return builder.toString()
    }

    private val VOID_TAGS = setOf("img", "br", "hr", "input", "link", "meta", "source", "wbr", "area", "base", "col", "embed", "param")
    private val TAG = Regex("""<!--[\s\S]*?-->|<([/]?)([A-Za-z][\w:-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>""")
    private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
}
