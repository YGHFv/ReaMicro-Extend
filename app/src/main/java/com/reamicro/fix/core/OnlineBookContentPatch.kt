package com.reamicro.fix.core

internal data class OnlineBookContentPatch(
    val bookId: Long,
    val uid: Long,
    val uuid: String,
    val size: Long,
    val updated: Long,
    val backupId: String,
    val backupCode: String,
    val fallbackCover: String,
) {
    /** 在同一写事务内读取最新封面并保留原生同步标记，不接触 CFI、章节、进度或阅读时长。 */
    fun apply(sql: CatalogSql) {
        require(bookId > 0 && uuid.isNotBlank() && size >= 0)
        val identity = listOf(bookId, uid, uuid)
        val current = sql.query("SELECT cover FROM books WHERE id=? AND uid=? AND uuid=?", identity, emptySet())
            .singleOrNull() ?: return
        val oldCover = current.single() as String
        val cover = oldCover.ifBlank { fallbackCover }
        val coverChanged = if (oldCover != cover) 1L else 0L
        sql.execute(
            "UPDATE books SET size=?, updated=?, backup_type=10, " +
                "backup_id=CASE WHEN backup_id='' THEN ? ELSE backup_id END, " +
                "backup_code=CASE WHEN backup_code='' THEN ? ELSE backup_code END, cover=? " +
                "WHERE id=? AND uid=? AND uuid=?",
            listOf(size, updated, backupId, backupCode, cover) + identity,
        )
        // 对齐 BookshelfRepository.updateBook 的 metadata/cover 行为；不得覆盖已有 EPUB 和书签脏位。
        sql.execute(
            "INSERT INTO book_sync_state (book_id,metadata_updated_at,epub_dirty,cover_dirty,bookmarks_dirty," +
                "epub_revision,cover_revision,bookmarks_revision,epub_sha256,cover_source_sha256,bookmarks_sha256) " +
                "VALUES (?,?,0,?,0,0,?,0,'','','') ON CONFLICT(book_id) DO UPDATE SET " +
                "metadata_updated_at=excluded.metadata_updated_at, " +
                "cover_dirty=CASE WHEN excluded.cover_dirty THEN 1 ELSE cover_dirty END, " +
                "cover_revision=CASE WHEN excluded.cover_dirty THEN MAX(cover_revision+1,excluded.cover_revision) ELSE cover_revision END",
            listOf(bookId, updated, coverChanged, if (coverChanged == 1L) updated else 0L),
        )
    }
}
