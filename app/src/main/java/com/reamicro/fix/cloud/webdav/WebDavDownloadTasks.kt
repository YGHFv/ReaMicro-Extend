package com.reamicro.fix.cloud.webdav

import java.io.File
import com.reamicro.fix.hook.AccountHostRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

internal data class OnlineDownloadedChapter(
    val title: String,
    val content: String,
    val volumeTitle: String = "",
    val level: Int = 0,
    val sourceUrl: String = "",
    val transport: String = "single",
)

internal class CancellableWebDavDownload(
    val storageIdentity: AccountHostRuntime.Identity,
    val id: String,
    val key: String,
    val name: String,
    val tracker: Any,
    val localFile: File,
    val createdAtMs: Long,
    val handle: Any,
) {
    @Volatile var cancelRequested: Boolean = false
    @Volatile var thread: Thread? = null
}

internal class NativeCloudDownload(
    val id: String,
    val key: String,
    val name: String,
    val type: Int,
    val tracker: Any,
    val cacheDir: File,
) {
    @Volatile var cancelRequested: Boolean = false
}

internal class OnlineCompletionDownloadTask(
    val storageIdentity: AccountHostRuntime.Identity,
    val notificationId: Int,
    val key: String,
    val name: String,
    val cacheDir: File,
    val tracker: Any?,
    val workId: String?,
    val checkActive: () -> Boolean = { true },
) {
    val control = com.reamicro.fix.online.download.OnlineDownloadControl()
    val cancellation get() = control.cancellation
    val networkCancellation get() = control.networkCancellation
    @Volatile var cancelRequested: Boolean = false
    @Volatile var thread: Thread? = null
    @Volatile var importedBookDir: File? = null
    @Volatile var importedBookFileKey: Any? = null
    val downloadedChapters = ConcurrentHashMap<Int, OnlineDownloadedChapter>()
    @Volatile var tocSnapshot: com.reamicro.fix.hook.webdav.OnlineTocSnapshot? = null
    @Volatile var downloadedCover: com.reamicro.fix.hook.webdav.OnlineBinaryPayload? = null
    val bookDirWriteLock = ReentrantLock()
}

internal class CacheDeleteStat(
    var files: Int = 0,
    var bytes: Long = 0L,
)

internal class CloudDownloadCancelledException(message: String = "download cancelled") : CancellationException(message)
internal class OnlineCompletionDownloadCancelledException(message: String = "online completion download cancelled") : CancellationException(message)
