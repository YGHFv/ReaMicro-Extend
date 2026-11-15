package com.reamicro.fix.core

internal data class BookCoverContentPatch(
    val bookId: Long,
    val uid: Long,
    val uuid: String,
    val expectedCover: String,
    val cover: String,
    val updated: Long,
) {
    /** 调用者使用同一写事务；同路径替换图片也必须递增封面 revision。 */
    fun apply(sql: CatalogSql): Boolean {
        require(bookId > 0 && uid >= 0 && updated > 0) { "图书账号或更新时间无效" }
        require(uuid.isNotBlank() && uuid != "." && uuid != ".." &&
            uuid.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) { "图书目录无效" }
        require(expectedCover.none(Char::isISOControl)) { "原封面值无效" }
        require(cover.isNotBlank() && !cover.startsWith('/') &&
            cover.none { it == '\\' || it == ':' || it.isISOControl() } &&
            cover.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "封面路径无效" }

        val identity = listOf(bookId, uid, uuid)
        val current = sql.query("SELECT cover FROM books WHERE id=? AND uid=? AND uuid=?", identity, emptySet())
            .singleOrNull() ?: return false
        if (current.single() != expectedCover) return false

        val syncQuery = "SELECT book_id,metadata_updated_at,epub_dirty,cover_dirty,bookmarks_dirty," +
            "epub_revision,cover_revision,bookmarks_revision,epub_sha256,cover_source_sha256,bookmarks_sha256 " +
            "FROM book_sync_state WHERE book_id=?"
        val syncNumbers = (0..7).toSet()
        val previousRows = sql.query(syncQuery, listOf(bookId), syncNumbers)
        check(previousRows.size <= 1) { "封面同步记录不唯一" }
        val previous = previousRows.singleOrNull()
        val revision = previous?.let {
            val old = it[6] as Long
            check(old >= 0) { "封面同步版本无效" }
            maxOf(Math.addExact(old, 1L), updated)
        } ?: updated

        sql.execute(
            "UPDATE books SET cover=?, updated=? WHERE id=? AND uid=? AND uuid=? AND cover=?",
            listOf(cover, updated) + identity + expectedCover,
        )
        val changes = sql.query("SELECT changes()", emptyList(), setOf(0)).single().single() as Long
        if (changes == 0L) return false
        check(changes == 1L) { "封面写入未限定到当前图书" }
        check(sql.query("SELECT cover,updated FROM books WHERE id=? AND uid=? AND uuid=?", identity, setOf(1))
            .singleOrNull() == listOf(cover, updated)) { "封面写入尚未确认" }

        sql.execute(
            "INSERT INTO book_sync_state (book_id,metadata_updated_at,epub_dirty,cover_dirty,bookmarks_dirty," +
                "epub_revision,cover_revision,bookmarks_revision,epub_sha256,cover_source_sha256,bookmarks_sha256) " +
                "VALUES (?,?,0,1,0,0,?,0,'','','') ON CONFLICT(book_id) DO UPDATE SET " +
                "metadata_updated_at=excluded.metadata_updated_at, cover_dirty=1, " +
                "cover_revision=MAX(cover_revision+1,excluded.cover_revision)",
            listOf(bookId, updated, updated),
        )
        val expected: MutableList<Any> = previous?.toMutableList() ?: mutableListOf(
            bookId, updated, 0L, 1L, 0L, 0L, revision, 0L, "", "", "",
        )
        expected[1] = updated
        expected[3] = 1L
        expected[6] = revision
        check(sql.query(syncQuery, listOf(bookId), syncNumbers).singleOrNull() == expected) {
            "封面同步状态写入尚未确认"
        }
        return true
    }
}
