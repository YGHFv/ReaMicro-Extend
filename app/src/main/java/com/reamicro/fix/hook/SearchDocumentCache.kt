package com.reamicro.fix.hook

import com.reamicro.fix.hook.reader.SearchDocument
import java.io.File
import java.lang.ref.WeakReference

internal class SearchDocumentCache(private val maxWeight: Long = 32L * 1024 * 1024) {
    private data class Entry(val owner: WeakReference<Any>, val document: SearchDocument, val weight: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var weight = 0L
    @Synchronized fun get(file: File, owner: Any?): SearchDocument? {
        val item = entries[file.path] ?: return null
        val d = item.document
        if (owner == null || item.owner.get() !== owner || file.lastModified() != d.sourceLastModified || file.length() != d.sourceLength) {
            entries.remove(file.path); weight -= item.weight
            return null
        }
        return d
    }
    @Synchronized fun put(document: SearchDocument, owner: Any?) {
        if (owner == null) return
        val size = 1024L + document.text.length * 2L + document.indexedText.spans.sumOf {
            112L + (it.sourceCfiPrefix?.length ?: 0) * 2L
        }
        entries.remove(document.file.path)?.let { weight -= it.weight }
        if (size > maxWeight) return
        entries[document.file.path] = Entry(WeakReference(owner), document, size); weight += size
        while (weight > maxWeight || entries.size > 2048) {
            val first = entries.entries.iterator(); val old = first.next(); weight -= old.value.weight; first.remove()
        }
    }
    @Synchronized fun clear() { entries.clear(); weight = 0 }
    @Synchronized fun isNotEmpty(): Boolean = entries.isNotEmpty()
}
