package com.reamicro.fix.online.download

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.reamicro.fix.online.OnlineParagraphCommentCacheStore
import com.reamicro.fix.cloud.webdav.CacheDeleteStat
import com.reamicro.fix.cloud.webdav.CancellableWebDavDownload
import com.reamicro.fix.online.epub.OnlineChapterImageMarkup
import com.reamicro.fix.online.search.cleanOnlineChapterContentValue
import com.reamicro.fix.online.search.decodeOnlineHtmlEntities
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadCancelledException
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadTask
import com.reamicro.fix.cloud.webdav.OnlineDownloadedChapter
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.onlineJsonString
import com.reamicro.fix.hook.cloudPathOf
import com.reamicro.fix.hook.onlineCompletionFailedLogFile
import com.reamicro.fix.hook.sendOnlineCompletionCancelBroadcastToModule
import com.reamicro.fix.logging.logWebDav

internal fun throwIfOnlineCompletionDownloadCancelled(task: OnlineCompletionDownloadTask) {
    task.control.check()
    if (task.cancelRequested || task.cancellation.isCancelled || Thread.currentThread().isInterrupted ||
        !task.checkActive()
    ) {
        throw OnlineCompletionDownloadCancelledException()
    }
    if (!com.reamicro.fix.hook.AccountHostRuntime.isCurrentStorage(task.storageIdentity)) {
        throw OnlineCompletionDownloadCancelledException("账号已切换，已停止旧账号下载")
    }
    val directory = task.importedBookDir
    val key = task.importedBookFileKey
    if (directory != null && key != null && onlineCompletionDirectoryKey(directory) != key) {
        throw OnlineCompletionDownloadCancelledException("书籍已被另一次导入替换，已停止旧任务追加")
    }
}

internal fun cleanupDownloadToken(token: CancellableWebDavDownload) {
    runCatching {
        token.localFile.delete()
        token.localFile.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
    }.onFailure {
        logWebDav("failed to cleanup cancelled download cache: ${it.message}")
    }
}

internal fun cleanupOnlineCompletionDownloadTask(task: OnlineCompletionDownloadTask) {
    task.cancellation.cancel()
    runCatching {
        val stat = CacheDeleteStat()
        deleteCachePath(task.cacheDir, stat)
        logWebDav(
            "online completion cache cleanup id=${task.notificationId} " +
                "files=${stat.files} bytes=${stat.bytes} dir=${task.cacheDir.absolutePath}",
        )
    }.onFailure {
        logWebDav("online completion cache cleanup failed id=${task.notificationId}: ${it.message}")
    }
}

internal fun deleteCachePath(file: File, stat: CacheDeleteStat) {
    if (Files.isSymbolicLink(file.toPath())) {
        file.delete()
        return
    }
    if (!file.exists()) return
    if (file.isDirectory) {
        file.listFiles()?.forEach { deleteCachePath(it, stat) }
    } else {
        stat.files += 1
        stat.bytes += file.length().coerceAtLeast(0L)
    }
    file.delete()
}

internal fun nativeCloudDownloadKey(book: Any, type: Int): String {
    val id = book.callString("getId")
    val fallback = cloudPathOf(book).ifBlank { book.callString("getName") }
    return "$type:${id.ifBlank { fallback }}"
}

internal fun staleTopLevelImportCacheDirs(cacheDir: File): List<File> {
    val cutoff = System.currentTimeMillis() - STALE_IMPORT_CACHE_MIN_AGE_MS
    return cacheDir.listFiles()
        ?.filter { it.isDirectory && UUID_DIR_REGEX.matches(it.name) && it.lastModified() in 1 until cutoff }
        .orEmpty()
}

internal fun paragraphCommentCacheDir(filesDir: File): File =
    File(filesDir, OnlineParagraphCommentCacheStore.DIRECTORY_NAME)

internal fun cancelExistingBackupTasks(tracker: Any, bookId: Long) {
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().cancelBackups(tracker, bookId)
}

internal fun ensureNcxSpineToc(content: String): String {
    if (Regex("""<spine\b[^>]*\btoc\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
        return content
    }
    val ncxId = Regex(
        """<item\b(?=[^>]*\bmedia-type\s*=\s*["']application/x-dtbncx\+xml["'])(?=[^>]*\bid\s*=\s*["']([^"']+)["'])[^>]*>""",
        RegexOption.IGNORE_CASE,
    ).find(content)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return content
    return Regex("""<spine\b""", RegexOption.IGNORE_CASE)
        .replaceFirst(content, "<spine toc=\"$ncxId\"")
}

internal fun onlineChapterUpdatePlan(
    bookDir: File,
    remoteChapters: List<OnlineChapter>,
): Pair<List<String>, List<Int>> {
    val indexed = readOnlineCompletionChapterIndex(bookDir)
    val bootstrap = com.reamicro.fix.cloud.webdav.OnlineBookSyncMarker.needsSourceCatalog(bookDir)
    val stored = if (bootstrap) emptyList() else indexed.ifEmpty { readLegacyOnlineCompletionChapters(bookDir) }
    if (stored.isEmpty() && !bootstrap) error("未找到本地图书章节索引，暂不能安全更新")
    val remote = remoteChapters.map { chapter ->
        RemoteOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
            title = chapter.title,
            volumeTitle = chapter.volumeTitle,
        )
    }
    val plan = OnlineChapterUpdatePlanner.plan(stored, remote)
    val matchedCount = plan.count { it.stored != null }
    if (!bootstrap) OnlineChapterUpdatePlanner.requireSafeUpdate(stored, remote, plan)
    val usedHrefs = stored.mapTo(linkedSetOf()) { it.href }
    val maxFileNumber = File(bookDir, "OEBPS/Text").listFiles()
        ?.mapNotNull { file ->
            ONLINE_COMPLETION_CHAPTER_FILE_REGEX.matchEntire(file.name)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        ?.maxOrNull()
        ?: 0
    var nextFileNumber = maxFileNumber + 1
    val newRemoteIndices = ArrayList<Int>()
    val hrefs = plan.map { slot ->
        val storedHref = slot.stored?.href
            ?.takeIf { href -> onlineCompletionChapterFile(bookDir, href).isFile }
        if (storedHref != null) return@map storedHref
        newRemoteIndices.add(slot.remoteIndex)
        var href: String
        do {
            href = "Text/chapter_${nextFileNumber.toString().padStart(4, '0')}.xhtml"
            nextFileNumber++
        } while (!usedHrefs.add(href) || onlineCompletionChapterFile(bookDir, href).exists())
        href
    }
    logWebDav(
        "online completion id plan local=${stored.size} remote=${remote.size} " +
            "matched=$matchedCount new=${newRemoteIndices.size} indexed=${indexed.isNotEmpty()}",
    )
    return hrefs to newRemoteIndices
}

internal fun onlineCompletionChapterFile(bookDir: File, href: String): File {
    val root = bookDir.canonicalFile
    val relative = href.trim().removePrefix("OEBPS/")
    if (!relative.startsWith("Text/") || !relative.endsWith(".xhtml", ignoreCase = true)) {
        error("Invalid online chapter href: $href")
    }
    val file = File(root, "OEBPS/$relative").canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    if (!file.path.startsWith(rootPrefix)) error("EPUB chapter path escapes book dir")
    return file
}

internal fun detectInvalidOnlineCompletionChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    remoteChapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
    ignoredIndices: Set<Int>,
): List<OnlineFailedChapter> {
    val failures = chapterHrefs.mapIndexedNotNull { index, href ->
        if (index !in remoteChapters.indices || index in ignoredIndices) return@mapIndexedNotNull null
        val chapterFile = onlineCompletionChapterFile(bookDir, href)
        if (!chapterFile.isFile) return@mapIndexedNotNull null
        val reason = runCatching {
            OnlineChapterContentValidator.storedXhtmlFailureReason(chapterFile.readText(Charsets.UTF_8))
        }.getOrElse { error ->
            "读取历史章节失败：${error.message.orEmpty().ifBlank { error.javaClass.name }}"
        } ?: return@mapIndexedNotNull null
        onlineCompletionFailedChapter(
            target = target,
            chapter = remoteChapters[index],
            index = index,
            attempts = 0,
            message = "检测到历史无效章节：$reason",
            href = href,
        )
    }
    if (failures.isNotEmpty()) {
        logWebDav(
            "online completion invalid stored chapters detected title=${target.result.name} " +
                "count=${failures.size} indices=${failures.take(20).joinToString { (it.index + 1).toString() }}",
        )
    }
    return failures
}

internal fun remapOnlineCompletionFailures(
    failures: List<OnlineFailedChapter>,
    remoteChapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
): List<OnlineFailedChapter> {
    if (failures.isEmpty()) return emptyList()
    val remote = remoteChapters.map { chapter ->
        RemoteOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
            title = chapter.title,
            volumeTitle = chapter.volumeTitle,
        )
    }
    val storedFailures = failures.map { failure ->
        StoredOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(failure.url),
            title = failure.title,
            volumeTitle = failure.volumeTitle,
            href = failure.href,
        )
    }
    val matches = OnlineChapterUpdatePlanner.plan(storedFailures, remote)
    val byStored = matches.mapNotNull { slot -> slot.stored?.let { it to slot.remoteIndex } }.toMap()
    return failures.mapIndexedNotNull { failureIndex, failure ->
        val stored = storedFailures[failureIndex]
        val remoteIndex = byStored[stored] ?: return@mapIndexedNotNull null
        val chapter = remoteChapters[remoteIndex]
        failure.copy(
            index = remoteIndex,
            title = chapter.title,
            url = chapter.url,
            volumeTitle = chapter.volumeTitle,
            level = chapter.level,
            href = chapterHrefs.getOrNull(remoteIndex).orEmpty(),
        )
    }.distinctBy { it.index }
}

internal fun onlineCompletionFailedChapter(
    target: OnlineDownloadTarget,
    chapter: OnlineChapter,
    index: Int,
    attempts: Int,
    message: String,
    href: String = "",
): OnlineFailedChapter =
    OnlineFailedChapter(
        index = index,
        title = chapter.title.ifBlank { "第 ${index + 1} 章" },
        url = chapter.url,
        volumeTitle = chapter.volumeTitle,
        level = chapter.level,
        attempts = attempts,
        message = message,
        updatedAt = System.currentTimeMillis(),
        sourceId = target.source.id,
        detailUrl = target.result.detailUrl,
        href = href,
    )

internal fun onlineCompletionRetryDelayMs(round: Int): Long =
    (ONLINE_COMPLETION_RETRY_DELAY_MS * round.coerceAtLeast(1)).coerceAtMost(ONLINE_COMPLETION_RETRY_DELAY_MAX_MS)

internal fun sleepOnlineCompletionRetryDelay(task: OnlineCompletionDownloadTask?, delayMs: Long) {
    val deadline = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(0L)
    while (true) {
        com.reamicro.fix.reload.TaskCancellation.checkCurrent()
        task?.let { throwIfOnlineCompletionDownloadCancelled(it) }
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) break
        Thread.sleep(remaining.coerceAtMost(500L))
    }
    com.reamicro.fix.reload.TaskCancellation.checkCurrent()
    task?.let { throwIfOnlineCompletionDownloadCancelled(it) }
}

internal fun fanqieContentBody(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return body
    val data = root.optJSONObject("data") ?: return body
    return (data.optJSONObject("data") ?: data).optString("content")
}

internal fun onlineChapterVolumeTitle(node: Any?): String =
    listOf(
        "$.volume_name",
        "$.volumeName",
        "$.volume",
        "$.part_name",
        "$.partName",
        "$.section_name",
        "$.sectionName",
        "$.group_name",
        "$.groupName",
        "volume_name",
        "volumeName",
        "volume",
        "part_name",
        "partName",
        "section_name",
        "sectionName",
        "group_name",
        "groupName",
    ).asSequence()
        .mapNotNull { rule -> onlineJsonString(node, rule).takeIf { it.isNotBlank() }?.cleanOnlineText() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()

internal fun fallbackOnlineChapterRawUrl(node: Any?): String =
    listOf(
        "$.itemId", "$.item_id", "$.chapterId", "$.chapter_id", "$.id", "$.url", "$.href",
        "itemId", "item_id", "chapterId", "chapter_id", "id",
    )
        .asSequence()
        .map { onlineJsonString(node, it) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

internal fun shuqiChapterListNodes(root: Any, chapterListRule: String): List<Any?> {
    if (!chapterListRule.substringBefore("<js>").trim()
            .equals("$.data.chapterList[0].volumeList", ignoreCase = true)
    ) return emptyList()
    val data = (root as? JSONObject)?.optJSONObject("data") ?: return emptyList()
    val volumes = data.optJSONArray("chapterList") ?: return emptyList()
    val chapters = volumes.optJSONObject(0)?.optJSONArray("volumeList") ?: return emptyList()
    return (0 until chapters.length()).mapNotNull { index ->
        chapters.opt(index)?.takeIf { it != JSONObject.NULL }
    }
}

internal fun evaluateOnlineChapterUrlJs(urlRule: String, result: String): String? {
    if (!urlRule.contains("java.base64Encode(result)", ignoreCase = true)) return null
    val template = Regex("`([^`]*java\\.base64Encode\\(result\\)[^`]*)`")
        .find(urlRule)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    val encoded = Base64.encodeToString(result.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return template.replace("\${java.base64Encode(result)}", encoded)
}

internal fun extractOnlineHtmlChapterContent(html: String): String {
    val cleaned = html
        .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
    val container = listOf(
        Regex("""(?is)<article\b[^>]*>(.*?)</article>"""),
        Regex("""(?is)<div\b[^>]*(?:id|class)\s*=\s*["'][^"']*(?:content|chapter|reader|read)[^"']*["'][^>]*>(.*?)</div>"""),
        Regex("""(?is)<body\b[^>]*>(.*?)</body>"""),
    ).asSequence()
        .mapNotNull { it.find(cleaned)?.groupValues?.getOrNull(1) }
        .firstOrNull()
        ?: cleaned
    return container
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
        .let(::normalizeOnlineChapterText)
}

internal fun normalizeOnlineChapterText(raw: String): String {
    if (raw.isBlank()) return ""
    return OnlineChapterImageMarkup.preserve(raw)
        .let(::cleanOnlineChapterContentValue)
        .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .decodeOnlineHtmlEntities()
        .replace(Regex("[ \\t\\r\\f]+"), " ")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

internal fun onlineCompletionOnDemandPendingChapter(
    chapter: OnlineChapter,
    index: Int,
): OnlineDownloadedChapter = OnlineDownloadedChapter(
    title = chapter.title.ifBlank { "第 ${index + 1} 章" },
    content = "本章尚未下载。\n\n打开本章时将自动下载正文，请保持网络连接。",
    volumeTitle = chapter.volumeTitle,
    level = chapter.level,
    sourceUrl = chapter.url,
)

internal fun onlineCompletionPausedPendingChapter(
    chapter: OnlineChapter,
    index: Int,
): OnlineDownloadedChapter = OnlineDownloadedChapter(
    title = chapter.title.ifBlank { "第 ${index + 1} 章" },
    content = "本章尚未下载。\n\n返回书架，长按本书并选择更新，可继续下载。",
    volumeTitle = chapter.volumeTitle,
    level = chapter.level,
    sourceUrl = chapter.url,
)

internal fun writeOnlineCompletionTextAtomically(target: File, content: String, checkCurrent: () -> Unit) {
    writeOnlineCompletionTextAtomically(target, content, commit = { it() }, checkCurrent = checkCurrent)
}

internal fun writeOnlineCompletionTextAtomically(
    target: File,
    content: String,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
) {
    writeOnlineCompletionBytesAtomically(target, content.toByteArray(Charsets.UTF_8), commit, checkCurrent)
}

internal fun writeOnlineCompletionBytesAtomically(target: File, content: ByteArray, checkCurrent: () -> Unit) {
    writeOnlineCompletionBytesAtomically(target, content, commit = { it() }, checkCurrent = checkCurrent)
}

internal fun writeOnlineCompletionBytesAtomically(
    target: File,
    content: ByteArray,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    val parent = target.absoluteFile.parentFile ?: error("EPUB target directory missing: ${target.name}")
    commit {
        checkCurrent()
        require(parent.isDirectory || parent.mkdirs()) { "EPUB target directory unavailable: ${target.name}" }
    }
    val previous = target.takeIf { it.exists() }?.let(com.reamicro.fix.epub.editor.VerifiedFileIO::digest)
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    val temp = File.createTempFile(".reamicro-online-", ".tmp", parent)
    try {
        temp.outputStream().use { output ->
            var from = 0
            while (from < content.size) {
                checkCurrent()
                val count = minOf(64 * 1024, content.size - from)
                output.write(content, from, count)
                hash.update(content, from, count)
                from += count
            }
            output.fd.sync()
        }
        val expected = com.reamicro.fix.epub.editor.VerifiedFileIO.Digest(
            content.size.toLong(), hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
        )
        check(com.reamicro.fix.epub.editor.VerifiedFileIO.digest(temp) == expected) {
            "EPUB staged bytes changed before commit"
        }
        checkCurrent()
        check(if (previous == null) !target.exists() else com.reamicro.fix.epub.editor.VerifiedFileIO.digest(target) == previous) {
            "EPUB target changed before commit"
        }
        var committed = false
        commit {
            check(!committed) { "EPUB staged bytes were already committed" }
            checkCurrent()
            check(if (previous == null) !target.exists() else com.reamicro.fix.epub.editor.VerifiedFileIO.digest(target) == previous) {
                "EPUB target changed while waiting for commit"
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            committed = true
        }
        check(committed) { "EPUB staged bytes were not committed" }
        com.reamicro.fix.epub.editor.VerifiedFileIO.requireMatches(target, expected)
    } finally {
        temp.delete()
    }
}

internal fun onlineCompletionChapterIndexFile(bookDir: File): File =
    File(bookDir, "OEBPS/$ONLINE_COMPLETION_CHAPTER_INDEX")

internal fun readOnlineCompletionChapterIndex(bookDir: File): List<StoredOnlineChapter> =
    runCatching {
        val file = onlineCompletionChapterIndexFile(bookDir)
        if (!file.isFile) return emptyList()
        val items = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("chapters") ?: return emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val href = item.optString("href").trim()
                if (href.isBlank()) continue
                add(
                    StoredOnlineChapter(
                        sourceChapterId = item.optString("sourceChapterId"),
                        title = item.optString("title"),
                        volumeTitle = item.optString("volumeTitle"),
                        href = href,
                    ),
                )
            }
        }
    }.onFailure {
        logWebDav("online completion chapter index read failed: ${it.message ?: it.javaClass.name}")
    }.getOrDefault(emptyList())

internal fun readLegacyOnlineCompletionChapters(bookDir: File): List<StoredOnlineChapter> =
    runCatching {
        val tocFile = File(bookDir, "OEBPS/toc.ncx")
        if (!tocFile.isFile) return emptyList()
        OnlineChapterUpdatePlanner.parseLegacyNcx(tocFile.readText(Charsets.UTF_8))
    }.onFailure {
        logWebDav("online completion legacy toc read failed: ${it.message ?: it.javaClass.name}")
    }.getOrDefault(emptyList())

internal fun onlineCompletionChapterIndexJson(
    target: OnlineDownloadTarget,
    chapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
): String {
    val items = JSONArray()
    chapters.forEachIndexed { index, chapter ->
        items.put(
            JSONObject()
                .put("sourceChapterId", OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url))
                .put("url", chapter.url)
                .put("title", chapter.title)
                .put("volumeTitle", chapter.volumeTitle)
                .put("href", chapterHrefs.getOrNull(index).orEmpty()),
        )
    }
    return JSONObject()
        .put("version", 1)
        .put("sourceId", target.source.id)
        .put("detailUrl", target.result.detailUrl)
        .put("chapters", items)
        .toString(2)
}

internal fun writeOnlineCompletionChapterIndex(
    bookDir: File,
    target: OnlineDownloadTarget,
    chapters: List<OnlineChapter>,
    chapterHrefs: List<String>,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
): Boolean {
    checkCurrent()
    val file = onlineCompletionChapterIndexFile(bookDir)
    val content = onlineCompletionChapterIndexJson(target, chapters, chapterHrefs)
    if (file.isFile && file.readText(Charsets.UTF_8) == content) return false
    writeOnlineCompletionTextAtomically(file, content, commit = commit, checkCurrent = checkCurrent)
    return true
}

internal fun readOnlineCompletionFailedChapters(bookDir: File): List<OnlineFailedChapter> =
    runCatching {
        val file = onlineCompletionFailedLogFile(bookDir)
        if (!file.isFile) return emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val chapters = root.optJSONArray("chapters") ?: return emptyList()
        buildList {
            for (i in 0 until chapters.length()) {
                val item = chapters.optJSONObject(i) ?: continue
                val index = if (item.has("index")) {
                    item.optInt("index", -1)
                } else {
                    item.optInt("chapterNumber", 0) - 1
                }
                if (index < 0) continue
                add(
                    OnlineFailedChapter(
                        index = index,
                        title = item.optString("title").ifBlank { "第 ${index + 1} 章" },
                        url = item.optString("url"),
                        volumeTitle = item.optString("volumeTitle"),
                        level = item.optInt("level", 0),
                        attempts = item.optInt("attempts", 0),
                        message = item.optString("message"),
                        updatedAt = item.optLong("updatedAt", 0L),
                        sourceId = item.optString("sourceId"),
                        detailUrl = item.optString("detailUrl"),
                        href = item.optString("href"),
                    ),
                )
            }
        }.distinctBy { it.index }.sortedBy { it.index }
    }.onFailure {
        logWebDav("online completion failed chapter log read failed: ${it.message ?: it.javaClass.name}")
    }.getOrDefault(emptyList())

internal fun writeOnlineCompletionFailedChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    val file = onlineCompletionFailedLogFile(bookDir)
    if (failures.isEmpty()) {
        commit { checkCurrent(); if (file.exists()) file.delete() }
        return
    }
    writeOnlineCompletionTextAtomically(file, onlineCompletionFailedChaptersJson(target, failures),
        commit = commit, checkCurrent = checkCurrent)
    logWebDav(
        "online completion failed chapter log written book=${target.result.name} " +
            "count=${failures.size} path=${file.absolutePath}",
    )
}

internal fun mergeOnlineCompletionFailedChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    val merged = (readOnlineCompletionFailedChapters(bookDir) + failures)
        .associateBy { it.index }
        .values
        .sortedBy { it.index }
    writeOnlineCompletionFailedChapters(bookDir, target, merged, commit = commit, checkCurrent = checkCurrent)
}

internal fun onlineCompletionFailedChaptersJson(
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
): String {
    val chapters = JSONArray()
    failures.sortedBy { it.index }.forEach { failure ->
        chapters.put(
            JSONObject()
                .put("index", failure.index)
                .put("chapterNumber", failure.index + 1)
                .put("title", failure.title)
                .put("url", failure.url)
                .put("volumeTitle", failure.volumeTitle)
                .put("level", failure.level)
                .put("attempts", failure.attempts)
                .put("message", failure.message)
                .put("updatedAt", failure.updatedAt)
                .put("sourceId", failure.sourceId.ifBlank { target.source.id })
                .put("detailUrl", failure.detailUrl.ifBlank { target.result.detailUrl })
                .put("href", failure.href),
        )
    }
    return JSONObject()
        .put("version", 1)
        .put("bookName", target.result.name)
        .put("sourceId", target.source.id)
        .put("sourceName", target.source.name)
        .put("detailUrl", target.result.detailUrl)
        .put("updatedAt", System.currentTimeMillis())
        .put("chapters", chapters)
        .toString(2)
}

internal fun cancelOnlineCompletionNotification(context: Context, id: Int, key: String) {
    cancelOnlineCompletionHostNotification(context, id)
    sendOnlineCompletionCancelBroadcastToModule(context, id, key)
}

internal fun cancelOnlineCompletionHostNotification(context: Context, id: Int) {
    if (id <= 0) return
    runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(id)
    }.onFailure {
        logWebDav("online completion host notification cancel failed id=$id: ${it.message}")
    }
}

internal fun importCacheFile(cacheDir: File?, root: String, fileName: String): File =
    File(
        File(cacheDir ?: File("/data/local/tmp"), "${root}/${System.currentTimeMillis()}_${UUID.randomUUID()}"),
        fileName.safeWebDavFileName(),
    )

internal fun enqueueNativeImport(workerManager: Any, platformFile: Any): Any? =
    com.reamicro.fix.core.HostWorkBindings.requireCurrent().method("import").invoke(workerManager, platformFile, null, false)

internal fun findImportBookMethod(bookshelf: Any): Method {
    val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.imports) { "Native import contract unavailable" }
    check(host.type("repository").isInstance(bookshelf))
    return host.method("normal")
}
