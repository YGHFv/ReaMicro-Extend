package com.reamicro.fix.online.download

import com.reamicro.fix.hook.AccountHostRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

internal data class OnlineOnDemandChapterRequest(
    val bookDir: File,
    val chapterIndex: Int,
    val metadata: OnlineOnDemandMetadata,
    val chapter: OnlineOnDemandChapter,
    val storageIdentity: AccountHostRuntime.Identity,
    val directoryKey: Any?,
)

internal data class OnlineOnDemandChapterResult(
    val request: OnlineOnDemandChapterRequest,
    val error: Throwable? = null,
) {
    val isSuccess: Boolean get() = error == null
}

internal object OnlineOnDemandBridge {
    private val lock = Any()
    private var attachment: Attachment? = null
    private val inFlight = HashMap<String, PendingDownload>()
    // A cancelled callback does not prove that its downloader has stopped touching files.
    // Entries cover admission through the worker's finally (including start failure).
    private val workers = HashSet<PendingDownload>()
    private val publishingDirs = HashSet<File>()

    /** Reserve this book only; inventory, copying and DAO work must not block other books. */
    fun <T> withIdleBook(bookDir: File, action: () -> T): T {
        val path = bookDir.canonicalFile
        synchronized(lock) {
            check(path !in publishingDirs && workers.none { it.isForDirectory(path) }) {
                "本书正在逐章下载或更新，请完成后再操作"
            }
            publishingDirs.add(path)
        }
        return try { action() } finally { synchronized(lock) { publishingDirs.remove(path) } }
    }

    private data class StorageContext(
        val identity: AccountHostRuntime.Identity, val bookDir: File, val directoryKey: Any?,
    )

    private fun directoryKey(directory: File): Any? =
        Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()

    private fun storageBookDirectory(bookDir: File, identity: AccountHostRuntime.Identity): File? = runCatching {
        require(identity.accountId >= 0L)
        val absolute = bookDir.absoluteFile.normalize()
        val books = requireNotNull(absolute.parentFile)
        val account = requireNotNull(books.parentFile)
        require(books.name == "books" && account.name == identity.accountId.toString())
        val files = requireNotNull(account.parentFile).canonicalFile
        val expected = File(files, "${identity.accountId}/books")
        require(expected.canonicalFile == expected && books.canonicalFile == expected)
        absolute.canonicalFile.takeIf { it.parentFile == expected && it.name == absolute.name && it.isDirectory }
    }.getOrNull()

    private fun requestData(bookDir: File): Pair<StorageContext, OnlineOnDemandMetadata>? {
        val identity = AccountHostRuntime.storageSnapshot() ?: return null
        val directory = storageBookDirectory(bookDir, identity) ?: return null
        if (synchronized(lock) { directory in publishingDirs }) return null
        val key = runCatching { directoryKey(directory) }.getOrElse { return null }
        val metadata = runCatching { OnlineOnDemandMetadataStore.read(directory) }.getOrNull() ?: return null
        val storage = StorageContext(identity, directory, key)
        if (synchronized(lock) {
            directory in publishingDirs || !storageCurrent(storage)
        }) return null
        return storage to metadata
    }

    private fun storageCurrent(storage: StorageContext): Boolean = runCatching {
        storageAccountCurrent(storage.identity, storage.bookDir) &&
            (storage.directoryKey == null || directoryKey(storage.bookDir) == storage.directoryKey)
    }.getOrDefault(false)

    private fun storageAccountCurrent(
        identity: AccountHostRuntime.Identity,
        bookDir: File,
    ): Boolean = AccountHostRuntime.isCurrentStorage(identity) &&
        storageBookDirectory(bookDir, identity) == bookDir

    private fun requestCurrent(request: OnlineOnDemandChapterRequest): Boolean =
        storageCurrent(StorageContext(request.storageIdentity, request.bookDir, request.directoryKey))

    private fun requireRequestCurrent(request: OnlineOnDemandChapterRequest) {
        if (Thread.currentThread().isInterrupted || !requestCurrent(request)) {
            throw CancellationException("账号或图书目录已变化，按需下载已取消")
        }
    }

    /** Check only the target chapter; unrelated chapter progress may change while this is queued. */
    private fun requireChapterCurrent(request: OnlineOnDemandChapterRequest) {
        requireRequestCurrent(request)
        val latest = OnlineOnDemandMetadataStore.read(request.bookDir)
        val chapter = latest?.chapter(request.chapterIndex)
        val before = request.chapter
        if (latest == null || chapter == null ||
            latest.sourceId != request.metadata.sourceId || latest.detailUrl != request.metadata.detailUrl ||
            latest.mode != request.metadata.mode ||
            normalizeHref(chapter.href) != normalizeHref(before.href) ||
            chapter.sourceChapterId != before.sourceChapterId || chapter.url != before.url ||
            chapter.title != before.title || chapter.volumeTitle != before.volumeTitle
        ) throw CancellationException("章节目录已变化，按需下载已取消")
        requireRequestCurrent(request)
    }

    class Attachment internal constructor(
        internal val downloader: (OnlineOnDemandChapterRequest) -> Unit,
    ) : AutoCloseable {
        override fun close() = detach(this)
    }

    fun attach(downloader: (OnlineOnDemandChapterRequest) -> Unit): Attachment {
        val next = Attachment(downloader)
        val abandoned = synchronized(lock) {
            attachment = next
            inFlight.values.map(PendingDownload::cancel).also { inFlight.clear() }
        }
        abandoned.forEach { it() }
        return next
    }

    private fun detach(owner: Attachment) {
        val abandoned = synchronized(lock) {
            if (attachment !== owner) return
            attachment = null
            inFlight.values.map(PendingDownload::cancel).also { inFlight.clear() }
        }
        abandoned.forEach { it() }
    }

    fun pendingRequest(bookDir: File, href: String): OnlineOnDemandChapterRequest? {
        val (storage, metadata) = requestData(bookDir) ?: return null
        if (metadata.mode != OnlineBookDownloadMode.ON_DEMAND) return null
        val normalizedHref = normalizeHref(href)
        val index = metadata.chapters.indexOfFirst { normalizeHref(it.href) == normalizedHref }
        if (index < 0) return null
        val chapter = metadata.chapters[index]
        if (chapter.state == OnlineChapterState.READY) return null
        return OnlineOnDemandChapterRequest(
            bookDir = storage.bookDir,
            chapterIndex = index,
            metadata = metadata,
            chapter = chapter,
            storageIdentity = storage.identity,
            directoryKey = storage.directoryKey,
        )
    }

    fun readyRequest(bookDir: File, href: String): OnlineOnDemandChapterRequest? =
        requestForState(bookDir, href, OnlineChapterState.READY)

    fun pendingRequest(bookDir: File, chapterIndex: Int): OnlineOnDemandChapterRequest? {
        val (storage, metadata) = requestData(bookDir) ?: return null
        if (metadata.mode != OnlineBookDownloadMode.ON_DEMAND) return null
        val chapter = metadata.chapter(chapterIndex) ?: return null
        if (chapter.state == OnlineChapterState.READY) return null
        return OnlineOnDemandChapterRequest(
            bookDir = storage.bookDir,
            chapterIndex = chapterIndex,
            metadata = metadata,
            chapter = chapter,
            storageIdentity = storage.identity,
            directoryKey = storage.directoryKey,
        )
    }

    private fun requestForState(
        bookDir: File,
        href: String,
        state: OnlineChapterState,
    ): OnlineOnDemandChapterRequest? {
        val (storage, metadata) = requestData(bookDir) ?: return null
        if (metadata.mode != OnlineBookDownloadMode.ON_DEMAND) return null
        val normalizedHref = normalizeHref(href)
        val index = metadata.chapters.indexOfFirst { normalizeHref(it.href) == normalizedHref }
        if (index < 0) return null
        val chapter = metadata.chapters[index]
        if (chapter.state != state) return null
        return OnlineOnDemandChapterRequest(
            bookDir = storage.bookDir,
            chapterIndex = index,
            metadata = metadata,
            chapter = chapter,
            storageIdentity = storage.identity,
            directoryKey = storage.directoryKey,
        )
    }

    fun request(
        request: OnlineOnDemandChapterRequest,
        callback: (OnlineOnDemandChapterResult) -> Unit,
    ): Boolean {
        if (!requestCurrent(request)) return false
        val identity = request.storageIdentity
        val key = "${identity.accountId}:${identity.generation}|${request.bookDir.absolutePath}|${normalizeHref(request.chapter.href)}"
        var created = false
        val pending = synchronized(lock) {
            val owner = attachment ?: return false
            // A publication can finish between request capture and admission. Recheck its inode
            // while holding only the short registry lock; metadata validation runs in the worker.
            if (request.bookDir in publishingDirs || !requestCurrent(request)) return false
            inFlight.getOrPut(key) {
                // A replaced owner can no longer receive callbacks, but its old worker
                // must finish before a new owner starts writing the same chapter.
                if (workers.any { it.key == key }) return false
                created = true
                PendingDownload(key, request, owner).also(workers::add)
            }
        }
        pending.addCallback(callback)
        if (!created) return true
        try {
            com.reamicro.fix.reload.ModuleResources.thread({
                var result: OnlineOnDemandChapterResult? = null
                try {
                    if (pending.isComplete()) return@thread
                    result = runCatching {
                        requireChapterCurrent(request)
                        // 进程恢复涉及元数据读写，只能在下载线程执行。
                        if (request.chapter.state == OnlineChapterState.DOWNLOADING) {
                            OnlineOnDemandMetadataStore.update(request.bookDir,
                                commit = { action -> AccountHostRuntime.withCurrentStorage(request.storageIdentity) {
                                    requireRequestCurrent(request); action()
                                } },
                                checkCurrent = { requireRequestCurrent(request) },
                            ) { metadata ->
                                metadata.updateChapter(request.chapterIndex) { chapter ->
                                    if (normalizeHref(chapter.href) == normalizeHref(request.chapter.href)) {
                                        chapter.recoverAfterProcessRestart()
                                    } else chapter
                                }
                            }
                        }
                        requireRequestCurrent(request)
                        if (!pending.isComplete()) pending.owner.downloader(request)
                        requireRequestCurrent(request)
                    }.fold(
                        onSuccess = { OnlineOnDemandChapterResult(request) },
                        onFailure = { OnlineOnDemandChapterResult(request, it) },
                    )
                } finally {
                    finish(key, pending, result ?: OnlineOnDemandChapterResult(
                        request, CancellationException("按需下载已退出"),
                    ))
                }
            }, "ReaMicroOnDemandChapter").start()
        } catch (error: Throwable) {
            finish(key, pending, OnlineOnDemandChapterResult(request, error))
            return false
        }
        return true
    }

    private fun finish(key: String, pending: PendingDownload, result: OnlineOnDemandChapterResult) {
        val deliver = synchronized(lock) {
            if (inFlight[key] === pending) inFlight.remove(key)
            workers.remove(pending)
            pending.complete(result)
        }
        deliver()
    }

    private fun normalizeHref(value: String): String =
        value.trim()
            .replace('\\', '/')
            .removePrefix("OEBPS/")
            .removePrefix("./")

    private class PendingDownload(
        val key: String,
        private val request: OnlineOnDemandChapterRequest,
        val owner: Attachment,
    ) {
        private val directory = request.bookDir.canonicalFile
        private val callbacks = mutableListOf<(OnlineOnDemandChapterResult) -> Unit>()
        private var result: OnlineOnDemandChapterResult? = null

        fun addCallback(callback: (OnlineOnDemandChapterResult) -> Unit) {
            val completed = synchronized(this) {
                result?.also { return@synchronized it }
                callbacks += callback
                null
            }
            if (completed != null) deliver(callback, completed)
        }

        fun isComplete(): Boolean = synchronized(this) { result != null }

        fun isForDirectory(directory: File): Boolean =
            this.directory == directory

        fun cancel() = complete(OnlineOnDemandChapterResult(
            request, CancellationException("按需下载回调所属模块已卸载"),
        ))

        private fun deliver(
            callback: (OnlineOnDemandChapterResult) -> Unit,
            completed: OnlineOnDemandChapterResult,
        ) {
            if (!runCatching { AccountHostRuntime.isCurrentStorage(request.storageIdentity) }.getOrDefault(false)) return
            val currentOwner = synchronized(lock) { attachment === owner }
            val delivered = when {
                !currentOwner -> completed.copy(error = CancellationException("按需下载回调所属模块已卸载"))
                !requestCurrent(request) -> completed.copy(error = CancellationException("图书目录已变化，按需下载已取消"))
                else -> completed
            }
            runCatching { callback(delivered) }
        }

        fun complete(result: OnlineOnDemandChapterResult): () -> Unit {
            val targets = synchronized(this) {
                if (this.result != null) return {}
                this.result = result
                callbacks.toList().also { callbacks.clear() }
            }
            return {
                // A same-account directory replacement must terminate the old request visibly,
                // so reader leases release. Retired owners get cancellation, never success;
                // account changes remain silent. Recheck between callbacks as each can publish.
                targets.forEach { callback -> deliver(callback, result) }
            }
        }
    }
}
