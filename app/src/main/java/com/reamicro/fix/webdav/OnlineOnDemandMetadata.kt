package com.reamicro.fix.webdav

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal enum class OnlineBookDownloadMode {
    FULL,
    ON_DEMAND,
}

internal enum class OnlineChapterState {
    PENDING,
    DOWNLOADING,
    READY,
    FAILED,
}

internal data class OnlineOnDemandChapter(
    val sourceChapterId: String,
    val url: String,
    val title: String,
    val volumeTitle: String,
    val href: String,
    val state: OnlineChapterState = OnlineChapterState.PENDING,
    val downloadedAtMs: Long = 0L,
    val attempts: Int = 0,
    val lastError: String = "",
) {
    fun beginDownload(): OnlineOnDemandChapter = copy(
        state = OnlineChapterState.DOWNLOADING,
        attempts = attempts + 1,
        lastError = "",
    )

    fun markReady(downloadedAtMs: Long): OnlineOnDemandChapter = copy(
        state = OnlineChapterState.READY,
        downloadedAtMs = downloadedAtMs.coerceAtLeast(0L),
        lastError = "",
    )

    fun markFailed(message: String): OnlineOnDemandChapter = copy(
        state = OnlineChapterState.FAILED,
        lastError = message.trim(),
    )

    fun recoverAfterProcessRestart(): OnlineOnDemandChapter =
        if (state == OnlineChapterState.DOWNLOADING) {
            copy(
                state = OnlineChapterState.PENDING,
                lastError = "上次下载未完成，等待重试",
            )
        } else {
            this
        }
}

internal data class OnlineOnDemandMetadata(
    val sourceId: String,
    val detailUrl: String,
    val bookName: String,
    val mode: OnlineBookDownloadMode,
    val chapters: List<OnlineOnDemandChapter>,
    val version: Int = CURRENT_VERSION,
) {
    fun chapter(index: Int): OnlineOnDemandChapter? = chapters.getOrNull(index)

    fun updateChapter(
        index: Int,
        transform: (OnlineOnDemandChapter) -> OnlineOnDemandChapter,
    ): OnlineOnDemandMetadata {
        if (index !in chapters.indices) return this
        return copy(
            chapters = chapters.mapIndexed { chapterIndex, chapter ->
                if (chapterIndex == index) transform(chapter) else chapter
            },
        )
    }

    fun recoverAfterProcessRestart(): OnlineOnDemandMetadata = copy(
        chapters = chapters.map(OnlineOnDemandChapter::recoverAfterProcessRestart),
    )

    companion object {
        const val CURRENT_VERSION = 2
    }
}

internal object OnlineOnDemandMetadataCodec {
    fun encode(metadata: OnlineOnDemandMetadata): String {
        val chapters = JSONArray()
        metadata.chapters.forEach { chapter ->
            chapters.put(
                JSONObject()
                    .put("sourceChapterId", chapter.sourceChapterId)
                    .put("url", chapter.url)
                    .put("title", chapter.title)
                    .put("volumeTitle", chapter.volumeTitle)
                    .put("href", chapter.href)
                    .put("state", chapter.state.name)
                    .put("downloadedAtMs", chapter.downloadedAtMs)
                    .put("attempts", chapter.attempts)
                    .put("lastError", chapter.lastError),
            )
        }
        return JSONObject()
            .put("version", metadata.version)
            .put("sourceId", metadata.sourceId)
            .put("detailUrl", metadata.detailUrl)
            .put("bookName", metadata.bookName)
            .put("mode", metadata.mode.name)
            .put("chapters", chapters)
            .toString(2)
    }

    fun decode(content: String): OnlineOnDemandMetadata {
        val root = JSONObject(content)
        val items = root.optJSONArray("chapters") ?: JSONArray()
        val chapters = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val href = item.optString("href").trim()
                if (href.isBlank()) continue
                add(
                    OnlineOnDemandChapter(
                        sourceChapterId = item.optString("sourceChapterId"),
                        url = item.optString("url"),
                        title = item.optString("title"),
                        volumeTitle = item.optString("volumeTitle"),
                        href = href,
                        state = enumValueOrDefault(
                            item.optString("state"),
                            OnlineChapterState.PENDING,
                        ),
                        downloadedAtMs = item.optLong("downloadedAtMs", 0L).coerceAtLeast(0L),
                        attempts = item.optInt("attempts", 0).coerceAtLeast(0),
                        lastError = item.optString("lastError"),
                    ),
                )
            }
        }
        return OnlineOnDemandMetadata(
            version = root.optInt("version", OnlineOnDemandMetadata.CURRENT_VERSION),
            sourceId = root.optString("sourceId"),
            detailUrl = root.optString("detailUrl"),
            bookName = root.optString("bookName"),
            mode = enumValueOrDefault(
                root.optString("mode"),
                OnlineBookDownloadMode.FULL,
            ),
            chapters = chapters,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value.trim().uppercase() } ?: fallback
}

internal object OnlineOnDemandMetadataStore {
    const val FILE_NAME = "reamicro-online-chapters.json"

    private val locks = ConcurrentHashMap<String, Any>()

    fun file(bookDir: File): File = File(bookDir, "OEBPS/$FILE_NAME")

    fun read(bookDir: File): OnlineOnDemandMetadata? = synchronized(lockFor(bookDir)) {
        readUnlocked(bookDir)
    }

    fun write(bookDir: File, metadata: OnlineOnDemandMetadata) {
        synchronized(lockFor(bookDir)) {
            writeUnlocked(bookDir, metadata)
        }
    }

    fun update(
        bookDir: File,
        transform: (OnlineOnDemandMetadata) -> OnlineOnDemandMetadata,
    ): OnlineOnDemandMetadata = synchronized(lockFor(bookDir)) {
        val current = readUnlocked(bookDir) ?: error("按需章节元数据不存在")
        val updated = transform(current)
        writeUnlocked(bookDir, updated)
        updated
    }

    private fun readUnlocked(bookDir: File): OnlineOnDemandMetadata? =
        file(bookDir)
            .takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.let(OnlineOnDemandMetadataCodec::decode)

    private fun writeUnlocked(bookDir: File, metadata: OnlineOnDemandMetadata) {
        val target = file(bookDir).canonicalFile
        target.parentFile?.mkdirs()
        val temp = File(
            target.parentFile,
            "${target.name}.tmp-${System.nanoTime()}",
        ).canonicalFile
        temp.writeText(OnlineOnDemandMetadataCodec.encode(metadata), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("无法替换按需章节元数据：${target.name}")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun lockFor(bookDir: File): Any =
        locks.computeIfAbsent(bookDir.canonicalFile.absolutePath) { Any() }
}
