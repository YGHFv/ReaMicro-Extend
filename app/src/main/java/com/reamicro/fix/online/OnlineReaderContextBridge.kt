package com.reamicro.fix.online

import java.io.File

internal data class OnlineReaderContextSnapshot(
    val bookDir: File,
    val spineIndex: Int,
)

/**
 * ReaderHook 与正文运行时 Hook 之间的最小共享状态。
 *
 * 这里只发布 EPUB 根目录和当前 spine，不持有宿主 ViewModel、页面或 Activity，
 * 避免跨 Hook 共享宿主对象造成生命周期泄漏。
 */
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

    /**
     * 阅微 CFI 的 spine step 是固定容器索引（通常为 6），章节序号来自 itemref step。
     * 实际章节 CFI 依次为 /6/4、/6/6、/6/8，因此需要转换为 0、1、2。
     */
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
