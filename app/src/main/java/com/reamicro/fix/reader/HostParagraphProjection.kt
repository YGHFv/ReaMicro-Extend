package com.reamicro.fix.reader

import com.reamicro.fix.core.HostEpubSourceBindings
import com.reamicro.fix.core.HostHighlightDomBindings

/** Shares native inline projection for highlighting, selection editing and scroll search. */
internal object HostParagraphProjection {
    fun project(
        contentDom: Any,
        protect: (Any) -> Boolean = { false },
        onNode: ((Any, Int, Int) -> Unit)? = null,
    ): HighlightContextProjection.Projection {
        val dom = requireNotNull(HostHighlightDomBindings.current)
        val source = requireNotNull(HostEpubSourceBindings.current)
        val replacement = source.inlineReplacement()
        return HighlightContextProjection.project(
            dom.children(contentDom), replacement, dom::tag, dom::nodeText,
            source::horizontal, dom::children, protect, onNode,
        ) { node, tag, children ->
            val note = source.noteProjection(node, tag, children)
            val atomic = dom.method("parsed.atomic").invoke(node) == true && note?.expanded != true
            val sides = if (tag == "ruby" || atomic || note?.placeholder == true) false to false
                else source.decorationSides(node)
            HighlightContextProjection.Inline(
                placeholder = atomic || note?.placeholder == true,
                skipChildren = note?.skipChildren.orEmpty(),
                prefix = if (sides.first) replacement else "",
                suffix = (if (sides.second) replacement else "") +
                    if (tag != "ruby" && source.endsInlineLine(node)) "\n" else "",
            )
        }
    }
}
