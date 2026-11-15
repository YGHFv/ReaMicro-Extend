package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.core.BookCoverContentPatch
import com.reamicro.fix.core.CatalogSql
import com.reamicro.fix.core.HostCatalogRuntime
import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.core.HostStorageBindings

/** A selected image changes content even when the native cover filename stays the same. */
internal object HostBookCoverContentSync {
    fun replace(
        activity: Activity,
        identity: AccountHostRuntime.Identity,
        repository: Any,
        book: Any,
        expectedCover: String,
        cover: String,
        checkCurrent: () -> Unit,
    ): Any {
        fun verify() {
            checkCurrent()
            check(AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号已变化，请重新选择封面" }
        }
        verify()
        val host = requireNotNull(HostReaderExtrasBindings.importMetadata)
        val bookId = (host.read("book.id", book) as Number).toLong()
        val uid = (host.read("book.uid", book) as Number).toLong()
        val uuid = host.read("book.uuid", book) as String
        check(uid == identity.accountId) { "图书账号已变化，请重新打开" }
        val catalog = requireNotNull(HostStorageBindings.catalog) { "宿主封面同步接口尚未解析" }
        check(host.type("repository").isInstance(repository) && catalog.type("repository").isInstance(repository)) {
            "图书书库已变化，请重新打开"
        }
        val dao = requireNotNull(catalog.field("repository.book").get(repository))
        val patch = BookCoverContentPatch(bookId, uid, uuid, expectedCover, cover, System.currentTimeMillis())
        // The file has already been published. Evict its old pixels even if a later CAS refuses
        // a concurrently changed cover; both native filenames and the first backup stay intact.
        HostNativeCoverCache.invalidate(activity, uuid, cover)
        verify()
        HostCatalogRuntime(activity.classLoader).transaction(dao) { sql ->
            val guarded = object : CatalogSql {
                override fun execute(statement: String, args: List<Any>) {
                    verify()
                    AccountHostRuntime.withCurrentStorage(identity, activity) {
                        verify()
                        sql.execute(statement, args)
                    }
                }

                override fun query(statement: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> {
                    verify()
                    return AccountHostRuntime.withCurrentStorage(identity, activity) {
                        verify()
                        sql.query(statement, args, numeric)
                    }
                }
            }
            check(patch.apply(guarded)) { "图书封面已变化，请刷新后重新选择" }
            verify()
        }
        verify()
        val stored = requireNotNull(ReaMicroBookMetadataSync.latestBook(repository, book)) { "封面更新后未读到原图书" }
        verify()
        check(host.read("book.cover", stored) == cover) { "封面更新尚未确认，请重新打开检查" }
        HostNativeCoverCache.invalidate(activity, uuid, cover)
        verify()
        return stored
    }
}
