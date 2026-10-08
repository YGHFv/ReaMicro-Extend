package com.reamicro.fix.epub.editor

internal class SelectionPatchComposer(private val source: String) {
    private data class Piece(val from: Int, val until: Int, val inserted: String? = null) {
        val length: Int get() = inserted?.length ?: (until - from)
        fun slice(a: Int, b: Int) = if (inserted == null) Piece(from + a, from + b)
            else Piece(0, 0, inserted.substring(a, b))
    }
    var text: String = source
        private set
    private var pieces = listOf(Piece(0, source.length))
    private val stages = arrayListOf<SelectionSourceEdit>()
    private var originalPieces = pieces
    private var offsets = emptyList<SelectionOffsetMapping>()
    fun mapOriginal(offset: Int, beforeInsertion: Boolean = false): Int =
        stages.fold(offset) { at, edit -> edit.translateBoundary(at, beforeInsertion) }
    fun originalRangeKept(from: Int, until: Int): Boolean {
        var low = 0; var high = originalPieces.size
        while (low < high) { val mid = (low + high) ushr 1
            if (originalPieces[mid].from <= from) low = mid + 1 else high = mid
        }
        return low > 0 && originalPieces[low - 1].until >= until
    }
    fun setOffsets(value: List<SelectionOffsetMapping>) { offsets = value }
    fun apply(raw: List<SelectionSourcePatch>) {
        val patches = raw.filter { it.start != it.end || it.replacement.isNotEmpty() }
            .sortedWith(compareBy<SelectionSourcePatch> { it.start }.thenBy { it.end })
        if (patches.isEmpty()) return
        val next = arrayListOf<Piece>()
        var index = 0
        var local = 0
        var cursor = 0
        fun consume(until: Int, keep: Boolean) {
            require(until in cursor..text.length) { "编辑补丁重叠或越界，未写入" }
            while (cursor < until) {
                val piece = pieces[index]
                val count = minOf(until - cursor, piece.length - local)
                if (keep && count > 0) next += piece.slice(local, local + count)
                cursor += count; local += count
                if (local == piece.length) { index++; local = 0 }
            }
        }
        for (patch in patches) {
            consume(patch.start, true); consume(patch.end, false)
            if (patch.replacement.isNotEmpty()) next += Piece(0, 0, patch.replacement)
        }
        consume(text.length, true)
        val updated = buildString { for (piece in next) {
            if (piece.inserted != null) append(piece.inserted) else append(source, piece.from, piece.until)
        } }
        val edit = SelectionSourceEdit(updated, patches)
        offsets = offsets.map { it.copy(newOffset = edit.translateOffset(it.newOffset)) }
        stages += edit; pieces = next; originalPieces = next.filter { it.inserted == null }; text = updated
    }
    fun result(): SelectionSourceEdit {
        val patches = arrayListOf<SelectionSourcePatch>()
        var cursor = 0
        val inserted = StringBuilder()
        fun flush(until: Int) {
            if (until > cursor || inserted.isNotEmpty()) {
                val value = inserted.toString()
                if (source.substring(cursor, until) != value) patches += SelectionSourcePatch(cursor, until, value)
            }
            inserted.setLength(0)
        }
        for (piece in pieces) {
            if (piece.inserted != null) inserted.append(piece.inserted) else {
                flush(piece.from); cursor = piece.until
            }
        }
        flush(source.length)
        return SelectionSourceEdit(text, patches, offsets)
    }
}
