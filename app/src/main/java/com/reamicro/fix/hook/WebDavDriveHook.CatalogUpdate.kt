package com.reamicro.fix.hook

import com.reamicro.fix.core.CatalogTableSync
import com.reamicro.fix.core.HostCatalogRuntime
import com.reamicro.fix.core.OnlineBookContentPatch
import com.reamicro.fix.core.OnlineEpubSyncPatch
import com.reamicro.fix.core.CatalogSql
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.online.epub.onlineImportedBookBackupId

internal fun WebDavDriveHook.syncOnlineCatalogAtomically(
    itemRefDao: Any,
    chapterDao: Any,
    bookId: Long,
    itemRefs: List<*>,
    chapters: List<*>,
    bookUuid: String,
    storageIdentity: AccountHostRuntime.Identity,
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    val runtime = HostCatalogRuntime(classLoader)
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
    check(runtime.database(itemRefDao) === runtime.database(chapterDao)) { "Catalog DAOs use different databases" }
    fun values(role: String, value: Any?, vararg names: String): List<Any> {
        val row = requireNotNull(value)
        return names.map { name ->
            val item = requireNotNull(host.field("$role.$name").get(row))
            if (item is Number) item.toLong() else item as String
        }
    }
    val refs = itemRefs.map {
        values("item", it, "bookId", "index", "spineIndex", "idRef", "href", "properties", "size")
    }
    val toc = chapters.map {
        values("chapter", it, "bookId", "playOrder", "title", "href", "anchor", "epubcfi", "parentOrder")
    }
    var count = 0
    runtime.transaction(itemRefDao) { sql ->
        val guarded = guardedOnlineCatalogSql(sql, storageIdentity, checkCurrent)
        val row = guarded.query("SELECT uuid FROM books WHERE id=? AND uid=?",
            listOf(bookId, storageIdentity.accountId), emptySet()).singleOrNull()
        check(row?.singleOrNull() == bookUuid) { "图书记录已变化，已停止目录更新" }
        count += CatalogTableSync.sync(guarded, CatalogTableSync.Table.ITEM_REF, bookId, refs)
        count += CatalogTableSync.sync(guarded, CatalogTableSync.Table.CHAPTER, bookId, toc)
        checkCurrent()
    }
    checkCurrent()
    logWebDav("online completion catalog transaction bookId=$bookId changedRows=$count")
}

/** 只更新内容元数据，不把网络请求前或目录重建前的整本快照写回阅读进度。 */
internal fun WebDavDriveHook.updateOnlineContentMetadata(
    book: Any,
    target: OnlineDownloadTarget,
    size: Long,
    storageIdentity: AccountHostRuntime.Identity = requireNotNull(AccountHostRuntime.storageSnapshot(activityProvider())),
    bookshelf: Any? = null,
    checkCurrent: () -> Unit = {},
): Any {
    checkCurrent()
    check(book.callLong("getUid") == storageIdentity.accountId) { "图书账号已变化，已停止元数据更新" }
    val repository = bookshelf ?: currentBookshelfRepository() ?: error("BookshelfRepository unavailable")
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
    val dao = requireNotNull(host.field("repository.book").get(repository))
    HostCatalogRuntime(classLoader).transaction(dao) { sql ->
        val applied = OnlineBookContentPatch(
            book.callLong("getId"), book.callLong("getUid"), book.callString("getUuid"), size,
            System.currentTimeMillis(), onlineImportedBookBackupId(target), target.source.id, target.result.coverUrl,
        ).apply(guardedOnlineCatalogSql(sql, storageIdentity, checkCurrent))
        check(applied) { "图书记录已变化，已停止元数据更新" }
    }
    checkCurrent()
    return findLocalBookById(book.callLong("getId"), storageIdentity, repository, checkCurrent)
        ?.takeIf { it.callString("getUuid") == book.callString("getUuid") }
        ?: error("元数据更新后未找到原书籍")
}

/** 已发布正文必须先标记，目录刷新失败也不能让同步复用旧 EPUB。 */
internal const val ONLINE_EPUB_SYNC_PENDING = "META-INF/reamicro-sync-dirty.pending"

internal fun WebDavDriveHook.markOnlineEpubChanged(
    uuid: String,
    storageIdentity: AccountHostRuntime.Identity,
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    val context = currentContext() ?: error("Context unavailable")
    check(AccountHostRuntime.isCurrentStorage(storageIdentity, activityProvider())) { "账号状态已变化" }
    require(uuid.isNotBlank() && uuid !in setOf(".", "..") && uuid.none { it == '/' || it == '\\' || it.isISOControl() })
    val pending = java.io.File(context.filesDir, "${storageIdentity.accountId}/books/$uuid/$ONLINE_EPUB_SYNC_PENDING")
    AccountHostRuntime.withCurrentStorage(storageIdentity, activityProvider()) {
        checkCurrent()
        // 数据库暂时不可用时保留重试记录，下次同步先补脏位再规划上传。
        pending.parentFile.mkdirs()
        pending.writeText("1", Charsets.UTF_8)
    }
    val repository = currentBookshelfRepository() ?: error("BookshelfRepository unavailable")
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
    val dao = requireNotNull(host.field("repository.book").get(repository))
    HostCatalogRuntime(classLoader).transaction(dao) { sql ->
        check(OnlineEpubSyncPatch.apply(guardedOnlineCatalogSql(sql, storageIdentity, checkCurrent),
            storageIdentity.accountId, uuid, System.currentTimeMillis())) { "图书记录已变化，未标记旧图书" }
    }
    AccountHostRuntime.withCurrentStorage(storageIdentity, activityProvider()) {
        checkCurrent()
        check(!pending.exists() || pending.delete()) { "同步标记已保存，重试记录清理失败" }
    }
}

private fun WebDavDriveHook.guardedOnlineCatalogSql(
    sql: CatalogSql,
    identity: AccountHostRuntime.Identity,
    checkCurrent: () -> Unit,
): CatalogSql = object : CatalogSql {
    private fun verify() {
        checkCurrent()
        check(!storageClosed && settingsProvider().moduleEnabled && AccountHostRuntime.isCurrentStorage(identity)) {
            "账号或模块状态已变化，已停止图书写入"
        }
    }
    override fun execute(statement: String, args: List<Any>) {
        verify()
        AccountHostRuntime.withCurrentStorage(identity) {
            verify()
            sql.execute(statement, args)
        }
    }
    override fun query(statement: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> {
        verify()
        return AccountHostRuntime.withCurrentStorage(identity) {
            verify()
            sql.query(statement, args, numeric)
        }
    }
}
