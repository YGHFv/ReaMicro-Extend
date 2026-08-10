package com.reamicro.fix.online.download

import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class OnlineOnDemandChapterRequest(
    val bookDir: File,
    val chapterIndex: Int,
    val metadata: OnlineOnDemandMetadata,
    val chapter: OnlineOnDemandChapter,
)

internal data class OnlineOnDemandChapterResult(
    val request: OnlineOnDemandChapterRequest,
    val error: Throwable? = null,
) {
    val isSuccess: Boolean get() = error == null
}

internal object OnlineOnDemandBridge {
    @Volatile
    private var downloader: ((OnlineOnDemandChapterRequest) -> Unit)? = null
    private val inFlight = ConcurrentHashMap<String, PendingDownload>()

    fun attach(downloader: (OnlineOnDemandChapterRequest) -> Unit) {
        this.downloader = downloader
    }

    fun detach() {
        downloader = null
        inFlight.clear()
    }

    fun pendingRequest(bookDir: File, href: String): OnlineOnDemandChapterRequest? {
        val metadata = runCatching { OnlineOnDemandMetadataStore.read(bookDir) }.getOrNull() ?: return null
        if (metadata.mode != OnlineBookDownloadMode.ON_DEMAND) return null
        val normalizedHref = normalizeHref(href)
        val index = metadata.chapters.indexOfFirst { normalizeHref(it.href) == normalizedHref }
        if (index < 0) return null
        val chapter = metadata.chapters[index]
        if (chapter.state == OnlineChapterState.READY) return null
        return OnlineOnDemandChapterRequest(
            bookDir = bookDir.canonicalFile,
            chapterIndex = index,
            metadata = metadata,
            chapter = chapter,
        )
    }

    fun readyRequest(bookDir: File, href: String): OnlineOnDemandChapterRequest? =
        requestForState(bookDir, href, OnlineChapterState.READY)

    fun pendingRequest(bookDir: File, chapterIndex: Int): OnlineOnDemandChapterRequest? {
        val metadata = runCatching { OnlineOnDemandMetadataStore.read(bookDir) }.getOrNull() ?: return null
        if (metadata.mode != OnlineBookDownloadMode.ON_DEMAND) return null
        val chapter = metadata.chapter(chapterIndex) ?: return null
        if (chapter.state == OnlineChapterState.READY) return null
        return OnlineOnDemandChapterRequest(
            bookDir = bookDir.canonicalFile,
            chapterIndex = chapterIndex,
            metadata = metadata,
            chapter = chapter,
        )
    }

    private fun requestForState(
        bookDir: File,
        href: String,
        state: OnlineChapterState,
    ): OnlineOnDemandChapterRequest? {
        val metadata = runCatching { OnlineOnDemandMetadataStore.read(bookDir) }.getOrNull() ?: return null
        if (metadata.mode != OnlineBookDownloadMode.ON_DEMAND) return null
        val normalizedHref = normalizeHref(href)
        val index = metadata.chapters.indexOfFirst { normalizeHref(it.href) == normalizedHref }
        if (index < 0) return null
        val chapter = metadata.chapters[index]
        if (chapter.state != state) return null
        return OnlineOnDemandChapterRequest(
            bookDir = bookDir.canonicalFile,
            chapterIndex = index,
            metadata = metadata,
            chapter = chapter,
        )
    }

    fun request(
        request: OnlineOnDemandChapterRequest,
        callback: (OnlineOnDemandChapterResult) -> Unit,
    ): Boolean {
        val action = downloader ?: return false
        val key = "${request.bookDir.absolutePath}|${normalizeHref(request.chapter.href)}"
        val pending = PendingDownload(request)
        val active = inFlight.putIfAbsent(key, pending) ?: pending
        active.addCallback(callback)
        if (active !== pending) return true
        if (request.chapter.state == OnlineChapterState.DOWNLOADING) {
            runCatching {
                OnlineOnDemandMetadataStore.update(request.bookDir) { metadata ->
                    metadata.updateChapter(request.chapterIndex) { it.recoverAfterProcessRestart() }
                }
            }
        }
        Thread({
            val result = runCatching { action(request) }
                .fold(
                    onSuccess = { OnlineOnDemandChapterResult(request) },
                    onFailure = { OnlineOnDemandChapterResult(request, it) },
                )
            pending.complete(result)
            inFlight.remove(key, pending)
        }, "ReaMicroOnDemandChapter").start()
        return true
    }

    private fun normalizeHref(value: String): String =
        value.trim()
            .substringBefore('#')
            .replace('\\', '/')
            .removePrefix("OEBPS/")
            .removePrefix("./")

    private class PendingDownload(
        private val request: OnlineOnDemandChapterRequest,
    ) {
        private val callbacks = mutableListOf<(OnlineOnDemandChapterResult) -> Unit>()
        private var result: OnlineOnDemandChapterResult? = null

        fun addCallback(callback: (OnlineOnDemandChapterResult) -> Unit) {
            val completed = synchronized(this) {
                result?.also { return@synchronized it }
                callbacks += callback
                null
            }
            if (completed != null) callback(completed)
        }

        fun complete(result: OnlineOnDemandChapterResult) {
            val targets = synchronized(this) {
                if (this.result != null) return
                this.result = result
                callbacks.toList().also { callbacks.clear() }
            }
            targets.forEach { callback ->
                runCatching { callback(result) }
            }
        }
    }
}
