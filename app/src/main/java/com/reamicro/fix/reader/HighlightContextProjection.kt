package com.reamicro.fix.reader

import java.util.Collections
import java.util.IdentityHashMap

/** Projects only text; it never measures a future paragraph or mutates its native DOM. */
internal object HighlightContextProjection {
    data class Inline<T : Any>(
        val placeholder: Boolean = false,
        val skipChildren: Set<T> = emptySet(),
        val prefix: String = "",
        val suffix: String = "",
    )

    data class Projection(val text: String, val protectedRanges: List<IntRange>)

    fun <T : Any> text(
        roots: List<T>,
        replacement: String,
        tag: (T) -> String,
        value: (T) -> String,
        horizontal: (T) -> Boolean,
        children: (T) -> List<T>,
        inline: (T, String, List<T>) -> Inline<T>,
    ): String = project(roots, replacement, tag, value, horizontal, children, { false }, inline = inline).text

    fun <T : Any> project(
        roots: List<T>,
        replacement: String,
        tag: (T) -> String,
        value: (T) -> String,
        horizontal: (T) -> Boolean,
        children: (T) -> List<T>,
        protect: (T) -> Boolean,
        onNode: ((T, Int, Int) -> Unit)? = null,
        inline: (T, String, List<T>) -> Inline<T>,
    ): Projection {
        val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        val result = StringBuilder()
        val protectedRanges = ArrayList<IntRange>()
        fun append(node: T, depth: Int) {
            check(depth <= 256 && seen.size < 65536 && seen.add(node)) { "Malformed highlight paragraph tree" }
            val name = tag(node)
            val start = result.length
            when (name) {
                "#comment", "rt" -> return
                "#text" -> {
                    val text = value(node)
                    result.append(if (horizontal(node)) text else text.filterNot(Char::isWhitespace).map(Char::toString).joinToString("\n"))
                }
                "br" -> result.append('\n')
                "img", "image", "path" -> result.append(replacement)
                else -> {
                    val descendants = children(node)
                    val policy = inline(node, name, descendants)
                    if (policy.placeholder) result.append(replacement)
                    else {
                        result.append(policy.prefix)
                        descendants.filterNot { it in policy.skipChildren || name == "ruby" && tag(it) in setOf("rp", "rt") }
                            .forEach { append(it, depth + 1) }
                        result.append(policy.suffix)
                    }
                }
            }
            onNode?.invoke(node, start, result.length)
            // Highlight ranges use an exclusive end, matching native AnnotatedString offsets.
            if (result.length > start && protect(node)) protectedRanges += start..result.length
        }
        roots.forEach { append(it, 0) }
        val text = result.toString().trimEnd('\n')
        return Projection(text, protectedRanges.mapNotNull { range ->
            val end = minOf(range.last, text.length)
            if (range.first < end) range.first..end else null
        })
    }
}
