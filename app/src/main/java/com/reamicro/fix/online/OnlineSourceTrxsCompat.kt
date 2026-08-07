package com.reamicro.fix.online

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * “起点限免（同人小说网）”书源的受控脚本适配。
 *
 * 该源通过 jsLib 的 getApiUrl/requestApiUrl 生成全部请求，并依赖 source.getVariable() 中的
 * Bearer Token。模块不直接执行带 Java/Packages 权限的任意书源脚本，只复现该源实际使用的
 * URL、响应解包和目录转换语义。
 */
object OnlineSourceTrxsCompat {
    private const val DEFAULT_API_BASE = "https://www.trxs666.com"
    private val supportedTypes = setOf("novel", "comic", "audio")

    data class CatalogChapter(
        val title: String,
        val url: String,
        val volumeTitle: String = "",
        val isVolume: Boolean = false,
    )

    fun isSource(source: OnlineSourceEntry): Boolean =
        source.sourceUrl.contains("#同人小说网", ignoreCase = true) &&
            (source.jsLib.contains("trxs666.com", ignoreCase = true) ||
                source.name.contains("起点限免"))

    fun shouldAuthorize(source: OnlineSourceEntry, requestUrl: String): Boolean {
        if (!isSource(source) || requestUrl.isBlank()) return false
        val apiHost = runCatching { java.net.URL(apiBase(source)).host }.getOrNull().orEmpty()
        val requestHost = runCatching { java.net.URL(requestUrl).host }.getOrNull().orEmpty()
        return apiHost.isNotBlank() && apiHost.equals(requestHost, ignoreCase = true)
    }

    fun apiBase(source: OnlineSourceEntry): String {
        if (!isSource(source)) return ""
        val scripted = Regex(
            """(?i)\b(?:const|let|var)\s+api\s*=\s*['\"](https?://[^'\"]+)['\"]""",
        ).find(source.jsLib)?.groupValues?.getOrNull(1).orEmpty()
        return scripted.ifBlank { DEFAULT_API_BASE }.trimEnd('/')
    }

    fun searchUrl(source: OnlineSourceEntry, rawQuery: String, page: Int): String? {
        if (!isSource(source)) return null
        var query = rawQuery.trim()
        var type = "novel"
        var sharedSearch = true
        when {
            query.startsWith("g:", true) || query.startsWith("g：", true) -> query = query.drop(2).trim()
            query.startsWith("a:", true) || query.startsWith("a：", true) -> {
                query = query.drop(2).trim()
                type = "audio"
                sharedSearch = false
            }
            query.startsWith("c:", true) || query.startsWith("c：", true) -> {
                query = query.drop(2).trim()
                type = "comic"
                sharedSearch = false
            }
        }
        if (query.isBlank()) return null
        val params = linkedMapOf("keyword" to query)
        if (sharedSearch) params["s"] = "1"
        params["page"] = page.coerceAtLeast(1).toString()
        return apiUrl(source, "/$type/search", params)
    }

    fun detailUrl(source: OnlineSourceEntry, node: Any?): String? {
        if (!isSource(source)) return null
        val id = jsonString(node, "id", "novelId", "bookId")
        if (id.isBlank()) return null
        val type = normalizedType(jsonString(node, "type"))
        return apiUrl(source, "/$type/info", linkedMapOf("novelId" to id))
    }

    fun catalogUrl(source: OnlineSourceEntry, detailUrl: String, detailBody: String): String? {
        if (!isSource(source)) return null
        val data = responseData(detailBody)
        val id = jsonString(data, "id", "novelId", "bookId")
            .ifBlank { queryValue(detailUrl, "novelId") }
        if (id.isBlank()) return null
        val type = normalizedType(jsonString(data, "type").ifBlank { queryType(detailUrl) })
        return apiUrl(source, "/$type/catalog", linkedMapOf("novelId" to id))
    }

    fun catalogChapters(source: OnlineSourceEntry, catalogUrl: String, body: String): List<CatalogChapter>? {
        if (!isSource(source)) return null
        val novelId = queryValue(catalogUrl, "novelId")
        if (novelId.isBlank()) return emptyList()
        val type = queryType(catalogUrl)
        if (type != "novel") return emptyList()
        val data = responseData(body)
        val volumes = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("volumes") ?: data.optJSONArray("catalog") ?: JSONArray()
            else -> JSONArray()
        }
        val result = mutableListOf<CatalogChapter>()
        for (volumeIndex in 0 until volumes.length()) {
            val volume = volumes.optJSONObject(volumeIndex) ?: continue
            val volumeTitle = jsonString(volume, "volumeName", "name", "title")
            if (volumeTitle.isNotBlank()) {
                result += CatalogChapter(volumeTitle, "", volumeTitle, isVolume = true)
            }
            val chapters = volume.optJSONArray("chapters") ?: volume.optJSONArray("chapterList") ?: continue
            for (chapterIndex in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(chapterIndex) ?: continue
                val chapterId = jsonString(chapter, "chapId", "chapterId", "id")
                if (chapterId.isBlank()) continue
                val title = jsonString(chapter, "chapName", "chapterName", "name", "title")
                    .ifBlank { "第 ${result.count { !it.isVolume } + 1} 章" }
                result += CatalogChapter(
                    title = title,
                    url = apiUrl(
                        source,
                        "/novel/chap",
                        linkedMapOf("novelId" to novelId, "chapId" to chapterId),
                    ),
                    volumeTitle = volumeTitle,
                )
            }
        }
        return result
    }

    fun responseData(body: String): Any? {
        val root = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
        return root.opt("data")?.takeUnless { it == JSONObject.NULL }
    }

    fun accountManagementUrl(source: OnlineSourceEntry, token: String): String? =
        token.trim().takeIf { isSource(source) && it.isNotBlank() }
            ?.let { "${apiBase(source)}/console/login?token=${encode(it)}" }

    fun inviteUrl(source: OnlineSourceEntry, androidId: String, inviteCode: String): String? {
        if (!isSource(source) || androidId.isBlank() || inviteCode.isBlank()) return null
        return apiUrl(
            source,
            "/user/invite",
            linkedMapOf("id" to androidId.trim(), "code" to inviteCode.trim()),
        )
    }

    fun tokenTestUrl(source: OnlineSourceEntry): String? =
        apiBase(source).takeIf { it.isNotBlank() }?.plus("/user/test")

    fun tokenFromInviteResponse(body: String): String =
        (responseData(body) as? JSONObject)?.optString("token", "").orEmpty().trim()

    fun responseMessage(body: String): String {
        val root = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return ""
        val data = root.optJSONObject("data")
        return data?.optString("msg", "").orEmpty()
            .ifBlank { root.optString("msg", "") }
            .trim()
    }

    private fun apiUrl(source: OnlineSourceEntry, path: String, params: LinkedHashMap<String, String>): String =
        buildString {
            append(apiBase(source))
            append(path)
            if (params.isNotEmpty()) {
                append('?')
                append(params.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" })
            }
        }

    private fun normalizedType(value: String): String =
        value.trim().lowercase().takeIf { it in supportedTypes } ?: "novel"

    private fun queryType(url: String): String =
        Regex("""(?i)/(novel|comic|audio)/""").find(url)?.groupValues?.getOrNull(1)?.lowercase() ?: "novel"

    private fun queryValue(url: String, name: String): String =
        Regex("""(?:[?&])${Regex.escape(name)}=([^&,]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1).orEmpty()

    private fun jsonString(value: Any?, vararg names: String): String {
        val json = value as? JSONObject ?: return ""
        return names.asSequence()
            .map { json.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
