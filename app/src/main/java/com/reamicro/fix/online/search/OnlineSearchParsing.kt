package com.reamicro.fix.online.search

import android.util.Base64
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineJsonPathCompat
import com.reamicro.fix.online.search.formatOnlineWordCountValue
import com.reamicro.fix.online.search.inferOnlineStatusFromLastChapterTitle
import com.reamicro.fix.online.search.resolveOnlineUrlCompat
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.webdav.*

internal fun sanitizeHostCloudSearchResults(param: XC_MethodHook.MethodHookParam, map: Map<*, *>) {
    runCatching {

        val hasOnlineEntry = map.containsKey(BACKUP_TYPE_ONLINE_COMPLETION)
        if (!hasOnlineEntry) return
        val sanitized = LinkedHashMap<Any?, Any?>()
        map.forEach { (key, value) ->
            if (key == BACKUP_TYPE_ONLINE_COMPLETION) return@forEach

            if (value is List<*> && value.isEmpty()) return@forEach
            sanitized[key] = value
        }
        param.args?.set(2, sanitized)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX sanitize host cloud results failed: ${it.stackTraceToString()}")
    }
}

internal fun indexOfOptionsBlock(raw: String): Int {

    val candidates = listOf(",{", ", {")
    for (sep in candidates) {
        val idx = raw.indexOf(sep)
        if (idx > 0) {
            val candidate = raw.substring(idx).removePrefix(",").trim()
            if (candidate.startsWith("{") && candidate.endsWith("}")) return idx
        }
    }
    return -1
}

internal fun parseOptionsJson(raw: String?): JSONObject =
    if (raw.isNullOrBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

internal fun onlineDataUrlStringResponse(url: String): String {
    val dataPart = url.substringAfter("base64,", "")
        .substringBefore(",{")
        .substringBefore(";")
        .trim()
    if (dataPart.isBlank()) return ""
    val bytes = Base64.decode(dataPart, Base64.DEFAULT)
    return if (url.substringAfter(dataPart, "").contains(""""type"""")) {
        bytes.toLowerHexString()
    } else {
        String(bytes, Charsets.UTF_8)
    }
}

internal fun parseOnlineJsonRoot(body: String): Any? =
    runCatching {
        val text = body.trim()
        if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
    }.getOrNull()

internal fun parseOnlineHtmlResults(
    source: OnlineSourceEntry,
    query: String,
    baseUrl: String,
    html: String,
): List<OnlineBookSearchResult> {
    val results = mutableListOf<OnlineBookSearchResult>()
    val anchorRegex = Regex("""(?is)<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
    anchorRegex.findAll(html).forEach { match ->
        if (results.size >= ONLINE_COMPLETION_RESULT_LIMIT) return@forEach
        val title = match.groupValues[2].cleanOnlineText()
        if (title.isBlank() || title.length > 120) return@forEach
        if (!title.contains(query, ignoreCase = true) && results.size >= 3) return@forEach
        val href = match.groupValues[1].trim()
        val start = (match.range.first - 240).coerceAtLeast(0)
        val end = (match.range.last + 360).coerceAtMost(html.length)
        val window = html.substring(start, end)
        val author = Regex("""(?is)(?:作者|author)\s*[:：]?\s*</?[^>]*>\s*([^<\s]{1,40})""")
            .find(window)?.groupValues?.getOrNull(1).orEmpty().cleanOnlineText()
        val cover = Regex("""(?is)<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
            .find(window)?.groupValues?.getOrNull(1).orEmpty()
        results.add(
            OnlineBookSearchResult(
                sourceName = source.name,
                name = title,
                author = author,
                coverUrl = resolveOnlineUrl(baseUrl, cover),
                detailUrl = resolveOnlineUrl(baseUrl, href),
                intro = "",
            ),
        )
    }
    return results
}

internal fun firstJsonString(json: JSONObject, vararg names: String): String =
    names.asSequence()
        .map { json.optString(it, "").trim() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        .orEmpty()

internal fun onlineFirstJsonString(node: Any?, fields: List<String>): String =
    fields.asSequence()
        .flatMap { field -> sequenceOf(field, "$.$field", "$..$field") }
        .map { onlineJsonString(node, it).cleanOnlineText() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        .orEmpty()

internal fun onlineFirstJsonInt(node: Any?, fields: List<String>): Int =
    fields.asSequence()
        .flatMap { field -> sequenceOf(field, "$.$field", "$..$field") }
        .mapNotNull { rule ->
            onlineJsonString(node, rule)
                .replace(",", "")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.toDoubleOrNull()
                ?.toInt()
        }
        .firstOrNull { it > 0 }
        ?: 0

internal fun onlineCompletionSearchMetaLine(result: OnlineBookSearchResult): String {
    val tail = if (result.sourceName.isWanFengLiSourceName()) {
        result.platformName.ifBlank { result.updateTime }
    } else {
        result.updateTime
    }
    return listOfNotNull(
        result.status.ifBlank { null },
        result.wordCount.ifBlank { null },
        result.chapterCount.takeIf { it > 0 }?.let { "${it}章" },
        tail.ifBlank { null },
    ).joinToString(" / ").ifBlank { "详情加载中" }
}

internal fun onlineCompletionStatusText(kind: String, node: Any?, statusHint: String = ""): String {
    val text = kind.cleanOnlineText()
    statusTextFromHumanText(text)?.let { return it }
    statusTextFromHumanText(statusHint.cleanOnlineText())?.let { return it }
    onlineFirstJsonString(node, ONLINE_STATUS_TEXT_FIELDS)
        .takeIf { it.isNotBlank() }
        ?.let { statusTextFromHumanText(it) }
        ?.let { return it }
    onlineJsonString(node, "$..creation_status").trim().takeIf { it.isNotBlank() }?.let { value ->
        return when (value.toIntOrNull()) {
            1 -> "连载"
            4 -> "断更"
            else -> "完结"
        }
    }
    onlineJsonString(node, "$..tomato_book_status").trim().takeIf { it.isNotBlank() }?.let { value ->
        return when (value.toIntOrNull()) {
            3 -> "下架"
            else -> ""
        }
    }
    listOf("is_finish", "isFinished", "finished", "complete", "completed").forEach { field ->
        val value = onlineJsonString(node, "$..$field").trim().lowercase(Locale.ROOT)
        if (value.isNotBlank()) {
            return if (value in setOf("true", "1", "yes")) "完结" else "连载"
        }
    }
    inferOnlineStatusFromLastChapterTitle(statusHint)
        .ifBlank {
            inferOnlineStatusFromLastChapterTitle(
                onlineFirstJsonString(
                    node,
                    listOf("last_chapter_title", "lastChapterTitle", "latest_chapter_title", "latestChapterTitle"),
                ),
            )
        }
        .takeIf { it.isNotBlank() }
        ?.let { return it }
    val statusSource = text.ifBlank {
        sequenceOf(
            "status",
            "bookStatus",
            "book_status",
            "creation_status",
            "tomato_book_status",
            "is_finish",
            "complete",
            "finished",
        ).map { onlineJsonString(node, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
    val normalized = statusSource.lowercase(Locale.ROOT)
    return when {
        statusSource.contains("断更") -> "断更"
        statusSource.contains("下架") -> "下架"
        statusSource.contains("连载") || normalized in setOf("1", "serial", "ongoing", "false") -> "连载"
        statusSource.contains("完结") || statusSource.contains("已完") ||
            normalized in setOf("0", "2", "3", "4", "finish", "finished", "complete", "completed", "true") -> "完结"
        else -> ""
    }
}

internal fun statusTextFromHumanText(text: String): String? =
    when {
        text.contains("断更") -> "断更"
        text.contains("下架") -> "下架"
        text.contains("连载") -> "连载"
        text.contains("完结") || text.contains("已完") -> "完结"
        else -> null
    }

internal fun formatOnlineWordCount(raw: String): String {
    val text = raw.cleanOnlineText()
    return formatOnlineWordCountValue(text)
}

internal fun formatOnlineUpdateTime(raw: String): String {
    val text = raw.cleanOnlineText()
    if (text.isBlank()) return ""
    val numeric = text.toLongOrNull()
    if (numeric != null && numeric > 0L) {
        val millis = if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        return SimpleDateFormat("yyyy MM dd", Locale.getDefault()).format(java.util.Date(millis))
    }
    val normalized = text.replace('T', ' ').replace(Regex("""\.\d+Z?$"""), "").trim()
    val parsed = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
    ).asSequence().mapNotNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(normalized) }.getOrNull()
    }.firstOrNull()
    return if (parsed != null) {
        SimpleDateFormat("yyyy MM dd", Locale.getDefault()).format(parsed)
    } else {
        normalized.take(10).replace('-', ' ').replace('/', ' ')
    }
}

internal fun onlineJsonString(node: Any?, rule: String): String =
    onlineJsonValues(node, rule).firstOrNull()?.let(::onlineJsonPrimitive).orEmpty()

internal fun onlineJsonInt(node: Any?, vararg rules: String): Int =
    rules.asSequence()
        .map { onlineJsonString(node, it).trim() }
        .firstOrNull { it.isNotBlank() && it != "null" }
        ?.toDoubleOrNull()
        ?.toInt()
        ?: 0

internal fun onlineJsonRuleValues(node: Any?, rule: String): List<Any?> {
    if (node == null || rule.isBlank()) return emptyList()
    val selectorRule = rule.substringBefore("<js>", rule).substringBefore("@js:", rule).trim()
    if (selectorRule.isBlank()) return emptyList()
    onlineJsonCandidateRoots(node).forEach { candidate ->
        val values = onlineJsonValues(candidate, selectorRule).flatMap(::onlineJsonRuleItems)
        if (values.isNotEmpty()) return values
    }
    return emptyList()
}

internal fun onlineJsonRuleItems(value: Any?): List<Any?> =
    when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        else -> listOfNotNull(value).filter { it != JSONObject.NULL }
    }

internal fun onlineJsonCandidateRoots(node: Any?): List<Any?> {
    val roots = linkedSetOf<Any?>()
    fun add(value: Any?) {
        if (value != null && value != JSONObject.NULL) roots.add(value)
    }
    add(node)
    if (node is JSONObject) {
        listOf("data", "result", "book", "chapter", "rows", "ret_data").forEach { key ->
            add(node.opt(key))
        }
        (node.opt("data") as? JSONObject)?.let { data ->
            listOf("data", "result", "book", "chapter", "rows", "ret_data").forEach { key ->
                add(data.opt(key))
            }
        }
    }
    return roots.toList()
}

internal fun onlineJsonValues(node: Any?, rawRule: String): List<Any?> =
    OnlineJsonPathCompat.values(node, rawRule)

internal fun onlineJsonPrimitive(value: Any?): String =
    when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> value.toString()
    }.trim()

internal fun replaceFanqieCover(raw: String): String {
    val clean = raw.trim().substringBefore('~').substringBefore('?').trim()
    if (clean.isBlank()) return ""
    val path = runCatching {
        val normalized = if (clean.startsWith("//")) "https:$clean" else clean
        if (normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        ) {
            URI(normalized).rawPath.orEmpty().trimStart('/')
        } else {
            normalized.trimStart('/')
        }
    }.getOrDefault(clean.trimStart('/'))
        .removePrefix("origin/")
        .removePrefix("/origin/")
        .trimStart('/')
    val imagePath = when {
        path.isBlank() -> return ""

        else -> path.substringAfterLast("novel-pic/", path).ifBlank { path }.let { normalized ->
            when {
                normalized.startsWith("novel-pic/", ignoreCase = true) -> normalized
                normalized.startsWith("novel-images/", ignoreCase = true) -> normalized
                normalized.startsWith("novel-static/", ignoreCase = true) -> normalized
                normalized.contains('/') -> normalized
                else -> "novel-pic/$normalized"
            }
        }
    }
    return "https://p6-novel.byteimg.com/origin/$imagePath"
}

internal fun sourceBaseUrl(source: OnlineSourceEntry): String =
    Regex("""https?://[^\s#]+""").find(source.sourceUrl)?.value.orEmpty()

internal fun resolveOnlineUrl(baseUrl: String, value: String): String =
    resolveOnlineUrlCompat(baseUrl, value)

internal fun homeCloudSearchResults(state: Any): Map<*, *>? =
    state.javaClass.methods.firstOrNull {
        it.name == "getCloudSearchResults" && it.parameterTypes.isEmpty()
    }?.apply { isAccessible = true }?.invoke(state) as? Map<*, *>

internal fun homeCloudSearchResultsComponentIndex(state: Any, parameterCount: Int): Int? {
    val current = homeCloudSearchResults(state) ?: return null
    for (index in 0 until parameterCount) {
        val component = state.invokeNoArg("component${index + 1}")
        if (component === current) return index
    }
    return null
}
