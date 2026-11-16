package com.reamicro.fix.core

/** 内容提交后标记同一图书的新版本，不修改图书身份、阅读位置或书签。 */
internal object OnlineEpubSyncPatch {
    fun apply(sql: CatalogSql, uid: Long, uuid: String, updated: Long): Boolean {
        require(uid >= 0 && uuid.isNotBlank() && updated > 0)
        val row = sql.query("SELECT id FROM books WHERE uid=? AND uuid=?",
            listOf(uid, uuid), setOf(0)).singleOrNull() ?: return false
        val bookId = row.single() as Long
        sql.execute("UPDATE books SET updated=MAX(updated,?) WHERE id=? AND uid=? AND uuid=?",
            listOf(updated, bookId, uid, uuid))
        sql.execute(
            "INSERT INTO book_sync_state (book_id,metadata_updated_at,epub_dirty,cover_dirty,bookmarks_dirty," +
                "epub_revision,cover_revision,bookmarks_revision,epub_sha256,cover_source_sha256,bookmarks_sha256) " +
                "VALUES (?,?,1,0,0,?,0,0,'','','') ON CONFLICT(book_id) DO UPDATE SET " +
                "metadata_updated_at=MAX(metadata_updated_at,excluded.metadata_updated_at),epub_dirty=1," +
                "epub_revision=MAX(epub_revision+1,excluded.epub_revision)",
            listOf(bookId, updated, updated),
        )
        return true
    }
}
