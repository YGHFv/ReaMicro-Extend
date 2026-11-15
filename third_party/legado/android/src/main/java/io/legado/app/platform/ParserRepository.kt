package io.legado.app.platform

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule

/**
 * Replaces Legado's application database at the parser boundary. Account values
 * are supplied by the host's existing account store, keyed by bookSourceUrl.
 * An absent integration fails explicitly rather than storing a second account.
 */
object ParserRepository {
    interface Adapter {
        fun source(key: String): BookSource?
        fun book(bookUrl: String): Book?
        fun chapters(bookUrl: String): List<BookChapter>
        fun chapter(bookUrl: String, index: Int): BookChapter?
        fun saveChapter(chapter: BookChapter)
        fun saveBook(book: Book)
        fun deleteBook(bookUrl: String)
        fun titleReplaceRules(book: Book): List<ReplaceRule>
        fun saveReplaceRule(rule: ReplaceRule)
        fun saveContent(source: BookSource?, book: Book, chapter: BookChapter, content: String)
        fun loginInfo(sourceKey: String): String?
        fun putLoginInfo(sourceKey: String, value: String?)
        fun loginHeader(sourceKey: String): String?
        fun putLoginHeader(sourceKey: String, value: String?)
        fun sourceVariable(sourceKey: String): String?
        fun putSourceVariable(sourceKey: String, value: String?)
    }

    @Volatile private var installed: Adapter? = null
    val adapter: Adapter get() = installed ?: error("ParserRepository has not been installed")

    @JvmStatic fun install(value: Adapter) { installed = value }
    @JvmStatic fun uninstall(value: Adapter) { if (installed === value) installed = null }
    fun source(key: String) = adapter.source(key)
    fun book(bookUrl: String) = adapter.book(bookUrl)
    fun chapters(bookUrl: String) = adapter.chapters(bookUrl)
    fun chapter(bookUrl: String, index: Int) = adapter.chapter(bookUrl, index)
    fun saveChapter(chapter: BookChapter) = adapter.saveChapter(chapter)
    fun saveBook(book: Book) = adapter.saveBook(book)
    fun deleteBook(bookUrl: String) = adapter.deleteBook(bookUrl)
    fun titleReplaceRules(book: Book) = adapter.titleReplaceRules(book)
    fun saveReplaceRule(rule: ReplaceRule) = adapter.saveReplaceRule(rule)
    fun saveContent(source: BookSource?, book: Book, chapter: BookChapter, content: String) =
        adapter.saveContent(source, book, chapter, content)
}
