package com.reamicro.fix.online.epub

import com.reamicro.fix.epub.editor.EpubLinkRewriter

/** Adds generated resources to the actual OPF manifest, preserving the rest of the XML. */
internal object OnlineEpubManifest {
    data class Item(val id: String, val href: String, val mediaType: String)

    fun merge(original: String, items: Collection<Item>): String {
        val manifest = EpubLinkRewriter.nodes(original, html = false)
            .filter { it.name == "manifest" && it.parent?.name == "package" }
            .singleOrNull() ?: return original
        val existing = manifest.children.filter { it.name == "item" }
            .mapNotNull { it.attr("href")?.value }.toSet()
        val additions = items.distinctBy { it.href }.filter { it.href !in existing }
        if (additions.isEmpty()) return original
        val opening = original.substring(manifest.start, manifest.openEnd)
        val name = opening.drop(1).takeWhile { it.isLetterOrDigit() || it in "_:.-" }
        val itemName = name.substringBeforeLast(':', "").let { if (it.isBlank()) "item" else "$it:item" }
        val rows = additions.joinToString("\n") {
            """    <$itemName id="${EpubLinkRewriter.xml(it.id)}" href="${EpubLinkRewriter.xml(it.href)}" media-type="${EpubLinkRewriter.xml(it.mediaType)}"/>"""
        }
        val selfClosing = opening.trimEnd().endsWith("/>")
        if (!selfClosing && manifest.end <= manifest.openEnd) return original
        return if (selfClosing) {
            original.replaceRange(manifest.start, manifest.openEnd,
                opening.substringBeforeLast('/') + ">\n$rows\n  </$name>")
        } else {
            original.substring(0, manifest.closeStart) + "$rows\n  " +
                original.substring(manifest.closeStart)
        }
    }
}
