package com.reamicro.fix.hook

import com.reamicro.fix.core.CatalogTableSync
import com.reamicro.fix.core.Host234CatalogRuntime
import com.reamicro.fix.core.OnlineBookContentPatch
import com.reamicro.fix.core.hostField
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.online.epub.onlineImportedBookBackupId

internal fun WebDavDriveHook.syncOnlineCatalogAtomically(
    itemRefDao: Any,
    chapterDao: Any,
    bookId: Long,
    itemRefs: List<*>,
    chapters: List<*>,
) {
    fun database(dao: Any): Any? = dao.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(dao)
    check(database(itemRefDao) === database(chapterDao)) { "Catalog DAOs use different databases" }
    fun values(value: Any?, vararg names: String): List<Any> {
        val row = requireNotNull(value)
        return names.map { name ->
            val item = requireNotNull(row.javaClass.hostField(name).get(row))
            if (item is Number) item.toLong() else item as String
        }
    }
    val refs = itemRefs.map {
        values(it, "bookId", "index", "spineIndex", "idRef", "href", "properties", "size")
    }
    val toc = chapters.map {
        values(it, "bookId", "playOrder", "title", "href", "anchor", "epubcfi", "parentOrder")
    }
    var count = 0
    Host234CatalogRuntime(classLoader).transaction(itemRefDao) { sql ->
        count += CatalogTableSync.sync(sql, CatalogTableSync.Table.ITEM_REF, bookId, refs)
        count += CatalogTableSync.sync(sql, CatalogTableSync.Table.CHAPTER, bookId, toc)
    }
    logWebDav("online completion catalog transaction bookId=$bookId changedRows=$count")
}

/** 只更新内容元数据，不把网络请求前或目录重建前的整本快照写回阅读进度。 */
internal fun WebDavDriveHook.updateOnlineContentMetadata(book: Any, target: OnlineDownloadTarget, size: Long) {
    if (Host234CatalogRuntime.supported()) {
        val repository = currentBookshelfRepository() ?: error("BookshelfRepository unavailable")
        val dao = fieldValue(repository, "bookDao") ?: error("BookDao unavailable")
        Host234CatalogRuntime(classLoader).transaction(dao) { sql ->
            OnlineBookContentPatch(
                book.callLong("getId"), book.callLong("getUid"), book.callString("getUuid"), size,
                System.currentTimeMillis(), onlineImportedBookBackupId(target), target.source.id, target.result.coverUrl,
            ).apply(sql)
        }
        return
    }
    val latest = findLocalBookByUuid(book.callLong("getUid"), book.callString("getUuid")) ?: book
    updateLocalBook(copyBookWithBackupAndPublisher(
        book = latest, backupType = BACKUP_TYPE_ONLINE_COMPLETION,
        backupId = bookBackupIdOf(latest).ifBlank { onlineImportedBookBackupId(target) },
        backupCode = latest.callString("getBackupCode").ifBlank { target.source.id },
        publisher = latest.callString("getPublisher"),
        cover = latest.callString("getCover").ifBlank { target.result.coverUrl },
        size = size, updated = System.currentTimeMillis(),
    ))
}
