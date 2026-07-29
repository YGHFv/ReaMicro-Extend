package com.reamicro.fix.online

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal data class OnlineParagraphCommentCount(
    val sourceId: String,
    val bookId: String,
    val itemId: String,
    val chapterIndex: Int,
    val paraIndex: Int,
    val paragraphText: String,
    val paragraphHash: String = paragraphText.onlineParagraphHash(),
    val count: Int,
    val updatedAtMs: Long,
)

internal data class OnlineParagraphCommentCache(
    val sourceId: String,
    val bookId: String,
    val itemId: String,
    val chapterIndex: Int,
    val comments: List<OnlineParagraphCommentCount>,
    val updatedAtMs: Long,
    val version: Int = CURRENT_VERSION,
) {
    fun comment(paraIndex: Int): OnlineParagraphCommentCount? =
        comments.firstOrNull { it.paraIndex == paraIndex && it.count > 0 }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal object OnlineParagraphCommentCacheCodec {
    fun encode(cache: OnlineParagraphCommentCache): String {
        val comments = JSONArray()
        cache.comments.forEach { comment ->
            comments.put(
                JSONObject()
                    .put("sourceId", comment.sourceId)
                    .put("bookId", comment.bookId)
                    .put("itemId", comment.itemId)
                    .put("chapterIndex", comment.chapterIndex)
                    .put("paraIndex", comment.paraIndex)
                    .put("paragraphText", comment.paragraphText)
                    .put("paragraphHash", comment.paragraphHash)
                    .put("count", comment.count)
                    .put("updatedAtMs", comment.updatedAtMs),
            )
        }
        return JSONObject()
            .put("version", cache.version)
            .put("sourceId", cache.sourceId)
            .put("bookId", cache.bookId)
            .put("itemId", cache.itemId)
            .put("chapterIndex", cache.chapterIndex)
            .put("updatedAtMs", cache.updatedAtMs)
            .put("comments", comments)
            .toString(2)
    }

    fun decode(content: String): OnlineParagraphCommentCache {
        val root = JSONObject(content)
        val sourceId = root.optString("sourceId")
        val bookId = root.optString("bookId")
        val itemId = root.optString("itemId")
        val chapterIndex = root.optInt("chapterIndex", -1)
        val updatedAtMs = root.optLong("updatedAtMs", 0L).coerceAtLeast(0L)
        val values = root.optJSONArray("comments") ?: JSONArray()
        val comments = buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val paraIndex = value.optInt("paraIndex", -1)
                val count = value.optInt("count", 0)
                if (paraIndex < 0 || count <= 0) continue
                val paragraphText = value.optString("paragraphText")
                add(
                    OnlineParagraphCommentCount(
                        sourceId = value.optString("sourceId").ifBlank { sourceId },
                        bookId = value.optString("bookId").ifBlank { bookId },
                        itemId = value.optString("itemId").ifBlank { itemId },
                        chapterIndex = value.optInt("chapterIndex", chapterIndex),
                        paraIndex = paraIndex,
                        paragraphText = paragraphText,
                        paragraphHash = value.optString("paragraphHash")
                            .ifBlank { paragraphText.onlineParagraphHash() },
                        count = count,
                        updatedAtMs = value.optLong("updatedAtMs", updatedAtMs).coerceAtLeast(0L),
                    ),
                )
            }
        }
        return OnlineParagraphCommentCache(
            version = root.optInt("version", OnlineParagraphCommentCache.CURRENT_VERSION),
            sourceId = sourceId,
            bookId = bookId,
            itemId = itemId,
            chapterIndex = chapterIndex,
            comments = comments,
            updatedAtMs = updatedAtMs,
        )
    }
}

internal object OnlineParagraphCommentCacheStore {
    const val DIRECTORY_NAME = "online-paragraph-comments"

    private val locks = ConcurrentHashMap<String, Any>()

    fun file(rootDir: File, sourceId: String, bookId: String, itemId: String): File =
        File(
            File(rootDir, DIRECTORY_NAME),
            listOf(sourceId, bookId, itemId)
                .joinToString("|")
                .onlineParagraphHash() + ".json",
        )

    fun read(rootDir: File, sourceId: String, bookId: String, itemId: String): OnlineParagraphCommentCache? {
        val target = file(rootDir, sourceId, bookId, itemId)
        return synchronized(lockFor(target)) {
            target.takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.let(OnlineParagraphCommentCacheCodec::decode)
        }
    }

    fun write(rootDir: File, cache: OnlineParagraphCommentCache) {
        val target = file(rootDir, cache.sourceId, cache.bookId, cache.itemId).canonicalFile
        synchronized(lockFor(target)) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
            temporary.writeText(OnlineParagraphCommentCacheCodec.encode(cache), Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                temporary.delete()
                error("无法替换段评缓存：${target.name}")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun lockFor(file: File): Any =
        locks.computeIfAbsent(file.canonicalFile.absolutePath) { Any() }
}

internal object FanqieParagraphCommentApi {
    const val BASE_URL = "http://103.52.152.82:7788/api_v2.php"

    fun bookId(detailUrl: String): String =
        Regex("(?:^|[?&#])book[_-]?id=(\\d+)", RegexOption.IGNORE_CASE)
            .find(detailUrl)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    fun itemId(chapterUrl: String): String {
        val encoded = chapterUrl.substringAfter("base64,", "")
            .substringBefore(",{")
            .substringBefore(';')
            .trim()
        if (encoded.isNotBlank()) {
            runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return Regex("(?:[?&]|\\b)(?:item_ids?|itemId)=([^&#,;]+)", RegexOption.IGNORE_CASE)
            .find(chapterUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            .orEmpty()
    }

    fun countsUrl(bookId: String, itemId: String, apiKey: String): String =
        url(
            "api" to "idea_counts",
            "book_id" to bookId,
            "item_id" to itemId,
            "max_para" to "0",
            "sort" to "2",
            "api_key" to apiKey,
        )

    fun commentsUrl(
        bookId: String,
        itemId: String,
        paraIndex: Int,
        offset: Int = 0,
        count: Int = 20,
        apiKey: String,
    ): String =
        url(
            "api" to "idea_comments",
            "book_id" to bookId,
            "item_id" to itemId,
            "count" to count.coerceAtLeast(1).toString(),
            "offset" to offset.coerceAtLeast(0).toString(),
            "para_index" to paraIndex.coerceAtLeast(0).toString(),
            "sort" to "0",
            "api_key" to apiKey,
        )

    fun fullV2ContentUrl(bookId: String, itemId: String, apiKey: String): String =
        url(
            "api" to "content",
            "item_ids" to itemId,
            "api_type" to "full_v2",
            "book_id" to bookId,
            "api_key" to apiKey,
        )

    fun parseCounts(content: String): Map<Int, Int> {
        val root = runCatching { JSONObject(content) }.getOrNull() ?: return emptyMap()
        val data = root.optJSONObject("data") ?: root
        val nested = data.optJSONObject("data") ?: data
        val raw = nested.opt("idea_data") ?: data.opt("idea_data") ?: return emptyMap()
        val result = linkedMapOf<Int, Int>()
        when (raw) {
            is JSONObject -> raw.keys().forEach { key ->
                val paraIndex = key.toIntOrNull() ?: return@forEach
                parseCount(raw.opt(key))?.takeIf { it > 0 }?.let { result[paraIndex] = it }
            }
            is JSONArray -> for (index in 0 until raw.length()) {
                val value = raw.optJSONObject(index) ?: continue
                val paraIndex = firstInt(value, "para_index", "paraIndex", "index") ?: continue
                val count = firstInt(value, "count", "comment_count", "idea_count", "value") ?: continue
                if (count > 0) result[paraIndex] = count
            }
        }
        return result
    }

    private fun parseCount(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            is JSONObject -> firstInt(value, "count", "comment_count", "idea_count", "value")
            else -> null
        }

    private fun firstInt(value: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            when (val candidate = value.opt(key)) {
                is Number -> candidate.toInt()
                is String -> candidate.toIntOrNull()
                else -> null
            }
        }

    private fun url(vararg parameters: Pair<String, String>): String =
        BASE_URL + "?" + parameters.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal fun String.onlineParagraphHash(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
