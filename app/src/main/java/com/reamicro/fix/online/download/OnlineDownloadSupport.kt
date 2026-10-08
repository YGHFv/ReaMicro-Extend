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
import com.reamicro.fix.online.download.OnlineChapterContentValidator
import com.reamicro.fix.online.download.OnlineChapterUpdatePlanner
import com.reamicro.fix.online.download.RemoteOnlineChapter
import com.reamicro.fix.online.download.StoredOnlineChapter
import com.reamicro.fix.xposed.XposedBridge
import java.io.BufferedInputStream
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.search.onlineJsonString
import com.reamicro.fix.hook.cloudPathOf
import com.reamicro.fix.hook.onlineCompletionFailedLogFile
import com.reamicro.fix.hook.sendOnlineCompletionCancelBroadcastToModule
import com.reamicro.fix.logging.logWebDav

internal fun throwIfOnlineCompletionDownloadCancelled(task: OnlineCompletionDownloadTask) {
    if (task.cancelRequested || Thread.currentThread().isInterrupted) {
        throw OnlineCompletionDownloadCancelledException()
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

internal fun orphanProfileBackgroundFiles(filesDir: File, activeImagePath: String): List<File> {
    val dir = File(filesDir, PROFILE_BACKGROUND_FILES_DIR)
    if (!dir.isDirectory) return emptyList()
    val active = activeImagePath.trim()
        .takeIf { it.isNotBlank() }
        ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
    return dir.listFiles()
        ?.filter { it.isFile }
        ?.filterNot { file ->
            active != null && runCatching { file.canonicalPath }.getOrElse { file.path } == active
        }
        .orEmpty()
}

internal fun paragraphCommentCacheDir(filesDir: File): File =
    File(filesDir, OnlineParagraphCommentCacheStore.DIRECTORY_NAME)

internal fun cancelExistingBackupTasks(tracker: Any, bookId: Long) {
    runCatching {
        val worksFlow = tracker.javaClass.methods.first {
            it.name == "works" && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }.invoke(tracker)
        val works = worksFlow?.javaClass?.methods?.firstOrNull {
            it.name == "getValue" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(worksFlow) as? Iterable<*> ?: return
        val ids = works.mapNotNull { work ->
            val kind = work?.javaClass?.methods?.firstOrNull { it.name == "getKind" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(work)
                ?.toString()
            val label = work?.javaClass?.methods?.firstOrNull { it.name == "getLabel" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(work)
                ?.toString()
            if (kind?.startsWith("backup") == true && label == bookId.toString()) {
                work.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
                    ?.apply { isAccessible = true }
                    ?.invoke(work)
                    ?.toString()
            } else {
                null
            }
        }
        val cancel = tracker.javaClass.methods.firstOrNull {
            it.name == "cancelWorkById" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }
        ids.forEach { cancel?.invoke(tracker, it) }
        tracker.javaClass.methods.firstOrNull {
            it.name == "pruneWork" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(tracker)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to cancel old WebDAV backup tasks: ${it.stackTraceToString()}")
    }
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
    val stored = indexed.ifEmpty { readLegacyOnlineCompletionChapters(bookDir) }
    if (stored.isEmpty()) error("未找到本地图书章节索引，暂不能安全更新")
    val remote = remoteChapters.map { chapter ->
        RemoteOnlineChapter(
            sourceChapterId = OnlineChapterUpdatePlanner.stableSourceChapterId(chapter.url),
            title = chapter.title,
            volumeTitle = chapter.volumeTitle,
        )
    }
    val plan = OnlineChapterUpdatePlanner.plan(stored, remote)
    val matchedCount = plan.count { it.stored != null }
    if (remote.size < stored.size && matchedCount < stored.size) {
        error("远端目录少于本地目录，已停止更新以避免误删章节")
    }
    if (indexed.isEmpty() && stored.size >= 20 && matchedCount * 100 < stored.size * 80) {
        error("旧书目录与远端章节匹配率过低，已停止更新以避免重复下载")
    }
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
        task?.let { throwIfOnlineCompletionDownloadCancelled(it) }
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) break
        Thread.sleep(remaining.coerceAtMost(500L))
    }
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
        .map { onlineJsonString(node, it).cleanOnlineText() }
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

internal fun onlineCompletionPendingChapter(chapter: OnlineChapter, index: Int): OnlineDownloadedChapter =
    OnlineDownloadedChapter(
        title = chapter.title.ifBlank { "第 ${index + 1} 章" },
        content = "本章正在后台下载中。\n\n退出本书后重新进入，可刷新已经下载完成的正文。",
        volumeTitle = chapter.volumeTitle,
        level = chapter.level,
        sourceUrl = chapter.url,
    )

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

internal fun writeOnlineCompletionTextAtomically(target: File, content: String) {
    writeOnlineCompletionBytesAtomically(target, content.toByteArray(Charsets.UTF_8))
}

internal fun writeOnlineCompletionBytesAtomically(target: File, content: ByteArray) {
    val parent = target.parentFile ?: error("EPUB target directory missing: ${target.name}")
    parent.mkdirs()
    val temp = File(
        parent,
        ".${target.name}.tmp-${Thread.currentThread().id}-${System.nanoTime()}",
    )
    temp.writeBytes(content)
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: Exception) {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
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
): Boolean {
    val file = onlineCompletionChapterIndexFile(bookDir)
    val content = onlineCompletionChapterIndexJson(target, chapters, chapterHrefs)
    if (file.isFile && file.readText(Charsets.UTF_8) == content) return false
    file.parentFile?.mkdirs()
    file.writeText(content, Charsets.UTF_8)
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
) {
    val file = onlineCompletionFailedLogFile(bookDir)
    if (failures.isEmpty()) {
        if (file.exists()) file.delete()
        return
    }
    file.parentFile?.mkdirs()
    file.writeText(onlineCompletionFailedChaptersJson(target, failures), Charsets.UTF_8)
    logWebDav(
        "online completion failed chapter log written book=${target.result.name} " +
            "count=${failures.size} path=${file.absolutePath}",
    )
}

internal fun mergeOnlineCompletionFailedChapters(
    bookDir: File,
    target: OnlineDownloadTarget,
    failures: List<OnlineFailedChapter>,
) {
    val merged = (readOnlineCompletionFailedChapters(bookDir) + failures)
        .associateBy { it.index }
        .values
        .sortedBy { it.index }
    writeOnlineCompletionFailedChapters(bookDir, target, merged)
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

internal fun unzipOnlineCompletionEpub(file: File): File {
    val outputDir = File(
        file.parentFile ?: file.absoluteFile.parentFile ?: File("/data/local/tmp"),
        "${file.nameWithoutExtension}_unzipped",
    )
    if (outputDir.exists()) outputDir.deleteRecursively()
    outputDir.mkdirs()
    val root = outputDir.canonicalFile
    ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            try {
                val target = File(root, entry.name).canonicalFile
                val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
                if (target != root && !target.path.startsWith(rootPrefix)) {
                    error("EPUB entry escapes output dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out -> zip.copyTo(out) }
                }
            } finally {
                zip.closeEntry()
            }
        }
    }
    logWebDav("online completion epub unzipped dir=${outputDir.absolutePath}")
    return outputDir
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

internal fun enqueueNativeImport(workerManager: Any, platformFile: Any): Any? {

    val candidates = (workerManager.javaClass.methods.asSequence() +
        workerManager.javaClass.declaredMethods.asSequence())
        .filter { it.name == WORKER_ENQUEUE_IMPORT_METHOD }
        .distinct()
        .toList()

    candidates.firstOrNull { it.parameterTypes.size == 1 }?.let {
        it.isAccessible = true
        return it.invoke(workerManager, platformFile)
    }

    candidates.firstOrNull {
        it.parameterTypes.size == 2 &&
            (it.parameterTypes[1] == java.lang.Boolean.TYPE || it.parameterTypes[1] == java.lang.Boolean::class.java)
    }?.let {
        it.isAccessible = true
        return it.invoke(workerManager, platformFile, false)
    }

    candidates.firstOrNull {
        it.parameterTypes.size == 3 &&
            it.parameterTypes[1] == String::class.java &&
            (it.parameterTypes[2] == java.lang.Boolean.TYPE || it.parameterTypes[2] == java.lang.Boolean::class.java)
    }?.let {
        it.isAccessible = true
        return it.invoke(workerManager, platformFile, null, false)
    }

    val fallback = candidates.minByOrNull { it.parameterTypes.size }
        ?: error("WorkerManager.$WORKER_ENQUEUE_IMPORT_METHOD not found")
    fallback.isAccessible = true
    val args = fallback.parameterTypes.mapIndexed { index, type ->
        when {
            index == 0 -> platformFile
            type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> false
            else -> null
        }
    }.toTypedArray()
    return fallback.invoke(workerManager, *args)
}

internal fun findImportBookMethod(bookshelf: Any): Method =
    (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
        .filter {
            it.name == BOOKSHELF_IMPORT_BOOK_METHOD &&
                it.parameterTypes.size >= 6 &&
                it.parameterTypes.last().name == KOTLIN_CONTINUATION_CLASS
        }
        .minByOrNull { it.parameterTypes.size }
        ?.apply { isAccessible = true }
        ?: error("阅微 importBook 方法未找到")
