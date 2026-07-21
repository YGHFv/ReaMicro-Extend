package com.reamicro.fix.webdav

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

internal data class OnlineDownloadedChapter(
    val title: String,
    val content: String,
    val volumeTitle: String = "",
    val level: Int = 0,
    val sourceUrl: String = "",
)

internal class CancellableWebDavDownload(
    val id: String,
    val key: String,
    val name: String,
    val tracker: Any,
    val localFile: File,
    val createdAtMs: Long,
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
    val notificationId: Int,
    val key: String,
    val name: String,
    val cacheDir: File,
    val tracker: Any?,
    val workId: String?,
) {
    @Volatile var cancelRequested: Boolean = false
    @Volatile var thread: Thread? = null
    @Volatile var importedBookDir: File? = null
    val downloadedChapters = ConcurrentHashMap<Int, OnlineDownloadedChapter>()
    val bookDirWriteLock = ReentrantLock()
}

internal class CacheDeleteStat(
    var files: Int = 0,
    var bytes: Long = 0L,
)

internal class CloudDownloadCancelledException : CancellationException("download cancelled")
internal class OnlineCompletionDownloadCancelledException : CancellationException("online completion download cancelled")
