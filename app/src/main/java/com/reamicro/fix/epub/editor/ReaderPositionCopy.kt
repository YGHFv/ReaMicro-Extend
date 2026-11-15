package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostReaderPositionBindings

internal object ReaderPositionCopy {
    fun copy(book: Any, cfi: String, chapter: String, progress: Float, updated: Long): Any {
        require(cfi.isNotBlank() && progress.isFinite())
        return requireNotNull(HostReaderPositionBindings.current).copy("book", book, mapOf(
            "epubcfi" to cfi, "chapter" to chapter,
            "progress" to progress.coerceIn(0f, 1f), "updated" to updated,
        ))
    }
}
