package com.reamicro.fix.online

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.platform.ParserRepository
import io.legado.app.utils.GSON
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** A download owns its upstream objects. No process-wide book/TOC cache retains completed tasks. */
internal class EnhancedParserBook(
    val source: OnlineSourceEntry,
    var book: Book,
    var chapters: List<BookChapter> = emptyList(),
) {
    var dirty = false
    var replaceChapters = false
    val dirtyChapters = linkedSetOf<Int>()
    var sourceTemporaryVariable: String? = null
}

internal class EnhancedParserOperation(
    val source: OnlineSourceEntry,
    val upstream: BookSource,
    val generation: Long,
    var book: EnhancedParserBook? = null,
) {
    fun check() {
        OnlineParserMode.requireCurrent(generation)
        OnlineSourceStore.requireCurrent(source)
    }
}

/**
 * Legado's database-facing parser methods write only parser state here. The existing
 * EPUB/import transaction remains the sole owner of host book records and content.
 */
internal class EnhancedParserRepository(private val context: () -> Context) : ParserRepository.Adapter {
    val current = ThreadLocal<EnhancedParserOperation>()
    private val fileLock = Any()
    private val root: File get() = File(context().filesDir, "online-enhanced-parser")

    private var database: SQLiteDatabase? = null

    private fun database(): SQLiteDatabase = database ?: run {
        check(root.isDirectory || root.mkdirs()) { "无法创建解析状态目录" }
        SQLiteDatabase.openOrCreateDatabase(File(root, "books.db"), null).also {
            it.execSQL("CREATE TABLE IF NOT EXISTS books (id TEXT PRIMARY KEY, revision TEXT NOT NULL, book TEXT NOT NULL, variable TEXT)")
            it.execSQL("CREATE TABLE IF NOT EXISTS chapters (bookId TEXT NOT NULL, idx INTEGER NOT NULL, chapter TEXT NOT NULL, PRIMARY KEY(bookId, idx))")
            database = it
        }
    }

    fun close() = synchronized(fileLock) { database?.close(); database = null }

    private fun operation(): EnhancedParserOperation =
        checkNotNull(current.get()) { "书源解析会话已结束" }.also { it.check() }

    fun <T> withOperation(op: EnhancedParserOperation, action: () -> T): T {
        op.check()
        val previous = current.get()
        current.set(op)
        return try { action().also { op.check() } } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    private fun sourceEntry(key: String): OnlineSourceEntry =
        operation().source.takeIf { it.sourceUrl == key }
            ?: OnlineSourceStore.list(context()).firstOrNull { it.sourceUrl == key }
            ?: error("书源已不存在")

    override fun source(key: String): BookSource? =
        if (operation().source.sourceUrl == key) operation().upstream else
            OnlineSourceStore.list(context()).firstOrNull { it.sourceUrl == key }
                ?.let(EnhancedOnlineParser::decodeSource)

    override fun book(bookUrl: String): Book? {
        val op = operation()
        return op.book?.takeIf { it.book.bookUrl == bookUrl }?.book
            ?: read(op.source, bookUrl)?.book
    }

    override fun chapters(bookUrl: String): List<BookChapter> {
        val op = operation()
        return op.book?.takeIf { it.book.bookUrl == bookUrl }?.chapters
            ?: read(op.source, bookUrl)?.chapters.orEmpty()
    }

    override fun chapter(bookUrl: String, index: Int): BookChapter? =
        chapters(bookUrl).let { list ->
            list.getOrNull(index)?.takeIf { it.index == index } ?: list.firstOrNull { it.index == index }
        }

    override fun saveBook(book: Book) {
        val op = operation()
        val state = op.book?.takeIf { it.book.bookUrl == book.bookUrl }
            ?: EnhancedParserBook(op.source, book).also { op.book = it }
        state.book = book
        state.dirty = true
    }

    override fun saveChapter(chapter: BookChapter) {
        val op = operation()
        val state = op.book?.takeIf { it.book.bookUrl == chapter.bookUrl }
            ?: read(op.source, chapter.bookUrl)?.also { op.book = it }
            ?: error("章节所属解析会话不存在")
        val list = state.chapters.toMutableList()
        val index = list.indexOfFirst { it.index == chapter.index }
        if (index >= 0) list[index] = chapter else list += chapter
        state.chapters = list
        state.dirty = true
        state.dirtyChapters += chapter.index
    }

    override fun deleteBook(bookUrl: String) {
        val op = operation()
        synchronized(fileLock) {
            op.check()
            val id = bookKey(op.source, bookUrl)
            val db = database()
            db.beginTransaction()
            try {
                db.delete("books", "id=?", arrayOf(id))
                db.delete("chapters", "bookId=?", arrayOf(id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (op.book?.book?.bookUrl == bookUrl) op.book = null
        }
    }

    override fun titleReplaceRules(book: Book): List<ReplaceRule> = synchronized(fileLock) {
        operation()
        readRules().filter { rule ->
            rule.isEnabled && rule.scopeTitle &&
                (rule.scope.isNullOrEmpty() || rule.scope!!.contains(book.name) || rule.scope!!.contains(book.origin)) &&
                (rule.excludeScope == null ||
                    (!rule.excludeScope!!.contains(book.name) && !rule.excludeScope!!.contains(book.origin)))
        }.sortedBy { it.order }
    }

    override fun saveReplaceRule(rule: ReplaceRule) = synchronized(fileLock) {
        val op = operation()
        val rules = readRules().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index < 0) rules += rule else rules[index] = rule
        write(File(root, "replace-rules.json"), GSON.toJson(rules), op::check)
    }

    private fun readRules(): List<ReplaceRule> {
        val file = File(root, "replace-rules.json")
        if (!file.isFile) return emptyList()
        return GSON.fromJson(file.readText(), Array<ReplaceRule>::class.java).toList()
    }

    override fun saveContent(source: BookSource?, book: Book, chapter: BookChapter, content: String) {
        val op = operation()
        synchronized(fileLock) {
            write(File(root, "content/${digest(op.source.id + book.bookUrl)}/${chapter.index}.txt"),
                content, op::check)
        }
    }

    override fun loginInfo(sourceKey: String): String? =
        OnlineSourceAuth.parserLoginInfo(context(), sourceEntry(sourceKey))

    override fun putLoginInfo(sourceKey: String, value: String?) =
        OnlineSourceAuth.putParserLoginInfo(context(), sourceEntry(sourceKey), value)

    override fun loginHeader(sourceKey: String): String? =
        OnlineSourceAuth.parserLoginHeader(context(), sourceEntry(sourceKey))

    override fun putLoginHeader(sourceKey: String, value: String?) =
        OnlineSourceAuth.putParserLoginHeader(context(), sourceEntry(sourceKey), value)

    override fun sourceVariable(sourceKey: String): String? =
        OnlineSourceAuth.sourceVariable(context(), sourceEntry(sourceKey))

    override fun putSourceVariable(sourceKey: String, value: String?) =
        OnlineSourceAuth.putParserVariable(context(), sourceEntry(sourceKey), value)

    fun read(source: OnlineSourceEntry, bookUrl: String): EnhancedParserBook? = synchronized(fileLock) {
        val id = bookKey(source, bookUrl)
        val db = database()
        val state = db.query("books", arrayOf("revision", "book", "variable"), "id=?", arrayOf(id),
            null, null, null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != source.revision.substringBefore(':')) return null
            EnhancedParserBook(source, GSON.fromJson(cursor.getString(1), Book::class.java)).also {
                it.sourceTemporaryVariable = cursor.getString(2)
            }
        }
        val chapters = arrayListOf<BookChapter>()
        db.query("chapters", arrayOf("chapter"), "bookId=?", arrayOf(id), null, null, "idx").use { cursor ->
            while (cursor.moveToNext()) {
                com.reamicro.fix.reload.TaskCancellation.checkCurrent()
                chapters += GSON.fromJson(cursor.getString(0), BookChapter::class.java)
            }
        }
        state.chapters = chapters
        state
    }

    fun persist(state: EnhancedParserBook) = synchronized(fileLock) {
        val op = operation()
        val db = database()
        val id = bookKey(state.source, state.book.bookUrl)
        db.beginTransaction()
        try {
            op.check()
            val values = ContentValues().apply {
                put("id", id)
                put("revision", state.source.revision.substringBefore(':'))
                put("book", GSON.toJson(state.book))
                put("variable", state.sourceTemporaryVariable)
            }
            check(db.insertWithOnConflict("books", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            if (state.replaceChapters) db.delete("chapters", "bookId=?", arrayOf(id))
            state.chapters.forEach { chapter ->
                if (state.replaceChapters || chapter.index in state.dirtyChapters) {
                    op.check()
                    val entry = ContentValues().apply {
                        put("bookId", id); put("idx", chapter.index); put("chapter", GSON.toJson(chapter))
                    }
                    check(db.insertWithOnConflict("chapters", null, entry, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                }
            }
            op.check()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        state.dirty = false
        state.replaceChapters = false
        state.dirtyChapters.clear()
    }

    private fun bookKey(source: OnlineSourceEntry, url: String): String = digest(source.id + "\n" + url)

    private fun write(file: File, value: String, checkCurrent: () -> Unit) {
        checkCurrent()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建解析状态目录" }
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            checkCurrent()
            check(temp.renameTo(file)) { "无法提交解析状态" }
        } finally { temp.delete() }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
