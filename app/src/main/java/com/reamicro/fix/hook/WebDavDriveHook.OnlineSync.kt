package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.OnlineBookSyncMarker
import com.reamicro.fix.core.HostCatalogRuntime
import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.download.*
import com.reamicro.fix.xposed.HookRuntime
import java.io.File

internal fun WebDavDriveHook.refreshReceivedOnlineSyncBook(uuid: String, identity: AccountHostRuntime.Identity) {
    if (!AccountHostRuntime.isCurrentStorage(identity, activityProvider())) return
    val context = currentContext() ?: return
    val root = File(context.filesDir, "${identity.accountId}/books/$uuid")
    if (OnlineBookSyncMarker.uuid(root) != uuid) return
    val shelf = currentBookshelfRepository() ?: return
    val host = requireNotNull(HostStorageBindings.catalog)
    val dao = requireNotNull(host.field("repository.book").get(shelf))
    var rowId: Long? = null
    HostCatalogRuntime(classLoader).transaction(dao) { sql ->
        rowId = sql.query("SELECT id FROM books WHERE uid=? AND uuid=?", listOf(identity.accountId, uuid), setOf(0))
            .singleOrNull()?.single() as? Long
    }
    val book = rowId?.let { findLocalBookById(it, identity) }?.takeIf { it.callString("getUuid") == uuid } ?: return
    val info = onlineImportedBookSourceInfo(book) ?: return
    if (info.source == null) {
        HookRuntime.logAlways("在线同步标记已保留，书源尚未就绪")
        return
    }
    // 标记持续保留：仅同步进度时 EPUB 哈希不变，后续原生恢复仍需识别书源更新方式。
    startOnlineCompletionChapterUpdate(book, info, silent = true)
}
