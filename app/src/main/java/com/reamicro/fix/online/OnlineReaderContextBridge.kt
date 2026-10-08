package com.reamicro.fix.online

import java.io.File

internal data class OnlineReaderContextSnapshot(
    val bookDir: File,
    val spineIndex: Int,
)

internal object OnlineReaderContextBridge {
    private val lock = Any()

    @Volatile
    private var current: OnlineReaderContextSnapshot? = null

    fun snapshot(): OnlineReaderContextSnapshot? = current

    fun updateBook(bookDir: File?) {
        val normalized = bookDir?.normalizedDirectory()
        synchronized(lock) {
            if (normalized == null) {
                current = null
                return
            }
            val previous = current
            current = OnlineReaderContextSnapshot(
                bookDir = normalized,
                spineIndex = if (previous?.bookDir?.samePathAs(normalized) == true) previous.spineIndex else -1,
            )
        }
    }

    fun updatePage(bookDir: File?, spineIndex: Int) {
        if (spineIndex < 0) return
        val normalized = bookDir?.normalizedDirectory() ?: return
        synchronized(lock) {
            current = OnlineReaderContextSnapshot(normalized, spineIndex)
        }
    }

    fun clear() {
        synchronized(lock) {
            current = null
        }
    }

    fun chapterIndexFromItemRef(itemRefIndex: Int): Int? {
        if (itemRefIndex < 4 || itemRefIndex % 2 != 0) return null
        return (itemRefIndex - 4) / 2
    }

    private fun File.normalizedDirectory(): File? =
        takeIf(File::isDirectory)
            ?.let { directory ->
                runCatching { directory.canonicalFile }
                    .getOrElse { directory.absoluteFile }
            }

    private fun File.samePathAs(other: File): Boolean =
        absolutePath.equals(other.absolutePath, ignoreCase = true)
}
