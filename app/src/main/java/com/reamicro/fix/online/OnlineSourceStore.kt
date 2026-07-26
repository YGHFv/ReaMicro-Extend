package com.reamicro.fix.online

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class OnlineSourceEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val sourceUrl: String,
    val loginUrl: String,
    val loginUi: String,
    val loginCheckJs: String,
    val concurrentRate: String,
    val header: String,
    val enabledCookieJar: Boolean,
    val searchUrl: String,
    val ruleSearch: String,
    val ruleBookInfo: String,
    val ruleToc: String,
    val ruleContent: String,
    val respondTime: Int,
    val origin: String,
    val configuredRequestsPerSecond: Int? = null,
    val dailyChapterLimit: Int? = null,
) {
    val hasLoginConfig: Boolean
        get() = loginUrl.isNotBlank() || loginUi.isNotBlank() || loginCheckJs.isNotBlank()

    val webLoginUrl: String
        get() = loginUrl.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }.orEmpty()
}

data class OnlineConcurrentRecord(
    var time: Long,
    val accessLimit: Int,
    val interval: Int,
    var frequency: Int,
)

object OnlineConcurrentRateLimiter {
    private val records = java.util.concurrent.ConcurrentHashMap<String, OnlineConcurrentRecord>()

    fun updateConcurrentRate(key: String, concurrentRate: String) {
        records.compute(key) { _, record ->
            val parsed = parseConcurrentRate(concurrentRate, System.currentTimeMillis(), 0)
                ?: return@compute null
            if (record?.accessLimit == parsed.accessLimit && record.interval == parsed.interval) record else parsed
        }
    }

    fun waitTurn(source: OnlineSourceEntry): OnlineConcurrentRecord? {
        val concurrentRate = OnlineSourceDownloadPolicyStore.effectiveConcurrentRate(source)
        if (concurrentRate.isBlank() || concurrentRate == "0") {
            records.remove(source.id)
            return null
        }
        updateConcurrentRate(source.id, concurrentRate)
        while (true) {
            val waitTime = waitTime(source.id, concurrentRate)
            if (waitTime <= 0L) return records[source.id]
            Thread.sleep(waitTime)
        }
    }

    inline fun <T> withLimitBlocking(source: OnlineSourceEntry, block: () -> T): T {
        waitTurn(source)
        return block()
    }

    private fun waitTime(key: String, concurrentRate: String): Long {
        var isNewRecord = false
        val record = records.computeIfAbsent(key) {
            isNewRecord = true
            parseConcurrentRate(concurrentRate, System.currentTimeMillis(), 1)
                ?: OnlineConcurrentRecord(System.currentTimeMillis(), 1, 0, 1)
        }
        if (isNewRecord) return 0L
        synchronized(record) {
            val nextTime = record.time + record.interval.toLong()
            val now = System.currentTimeMillis()
            if (now >= nextTime) {
                record.time = now
                record.frequency = 1
                return 0L
            }
            if (record.frequency < record.accessLimit) {
                record.frequency++
                return 0L
            }
            return nextTime - now
        }
    }

    private fun parseConcurrentRate(rate: String, time: Long, frequency: Int): OnlineConcurrentRecord? {
        val text = rate.trim()
        if (text.isBlank() || text == "0") return null
        return runCatching {
            val splitIndex = text.indexOf("/")
            if (splitIndex > 0) {
                val accessLimit = text.take(splitIndex).toInt()
                val interval = text.substring(splitIndex + 1).toInt()
                if (accessLimit <= 0 || interval <= 0) error("invalid concurrentRate")
                OnlineConcurrentRecord(time, accessLimit, interval, frequency)
            } else {
                val interval = text.toInt()
                if (interval <= 0) error("invalid concurrentRate")
                OnlineConcurrentRecord(time, 1, interval, frequency)
            }
        }.getOrNull()
    }
}

object OnlineSourceStore {
    private const val SOURCE_DIR = "reamicro_online_sources"
    private val sourceExtensions = setOf("rmonline", "json", "txt")

    fun list(context: Context?): List<OnlineSourceEntry> {
        context ?: return emptyList()
        return sourceDir(context)
            .listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in sourceExtensions }
            ?.mapNotNull { file ->
                runCatching {
                    OnlineSourceDownloadPolicyStore.attach(
                        context,
                        parseSingleSource(file.readBytes(), file.nameWithoutExtension, file.name)
                            .copy(fileName = file.name),
                    )
                }.getOrNull()
            }
            ?.distinctBy { it.id }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun importFromUrl(context: Context, rawUrl: String): OnlineSourceEntry {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            error("剪贴板中没有在线源链接")
        }
        val bytes = download(url)
        return importBytes(context, bytes, url.substringAfterLast('/').ifBlank { "online_source.json" }, url)
    }

    fun importBytes(context: Context, bytes: ByteArray, displayName: String, origin: String = ""): OnlineSourceEntry {
        val parsed = parseSingleSource(bytes, displayName.substringBeforeLast('.', displayName), displayName)
            .copy(origin = origin)
        val dir = sourceDir(context).apply { mkdirs() }
        val target = File(dir, "${safeFileName(parsed.id)}.rmonline")
        FileOutputStream(target).use { it.write(bytes) }
        return OnlineSourceDownloadPolicyStore.attach(context, parsed.copy(fileName = target.name))
    }

    fun remove(context: Context?, sourceId: String): Boolean {
        context ?: return false
        val dir = sourceDir(context)
        val safeId = safeFileName(sourceId)
        val direct = File(dir, "$safeId.rmonline")
        if (direct.isFile && direct.delete()) return true
        return dir.listFiles()
            ?.firstOrNull { file ->
                file.isFile && runCatching {
                    parseSingleSource(file.readBytes(), file.nameWithoutExtension, file.name).id == sourceId
                }.getOrDefault(false)
            }
            ?.delete()
            ?: false
    }

    private fun sourceDir(context: Context): File =
        File(context.filesDir, SOURCE_DIR)

    private fun parseSingleSource(bytes: ByteArray, fallbackName: String, fileName: String): OnlineSourceEntry {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) error("在线源为空")
        val json = when {
            text.startsWith("[") -> {
                val array = JSONArray(text)
                if (array.length() != 1) error("一次最多导入一个在线源")
                array.optJSONObject(0) ?: error("在线源格式不正确")
            }
            text.startsWith("{") -> JSONObject(text)
            else -> error("在线源格式不正确")
        }
        val name = firstString(json, "bookSourceName", "sourceName", "name", "title")
            .ifBlank { fallbackName }
            .ifBlank { "在线源" }
        val sourceUrl = firstString(json, "bookSourceUrl", "sourceUrl", "url", "id")
        val loginUrl = firstString(json, "loginUrl", "loginURL", "login", "loginPage")
        val loginUi = rawJsonString(json, "loginUi").ifBlank { rawJsonString(json, "loginUI") }
        val loginCheckJs = firstString(json, "loginCheckJs", "loginCheckJS")
        val concurrentRate = firstString(json, "concurrentRate", "rateLimit", "requestRate")
        val header = rawJsonString(json, "header").ifBlank { rawJsonString(json, "headers") }
        val searchUrl = firstString(json, "searchUrl", "searchURL", "search")
        val ruleSearch = rawJsonString(json, "ruleSearch")
        val ruleBookInfo = rawJsonString(json, "ruleBookInfo")
        val ruleToc = rawJsonString(json, "ruleToc")
        val ruleContent = rawJsonString(json, "ruleContent")
        val respondTime = firstString(json, "respondTime", "timeout")
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 180_000
        val idBase = sourceUrl.ifBlank { name }
        return OnlineSourceEntry(
            id = stableId(idBase),
            name = name,
            fileName = fileName,
            sourceUrl = sourceUrl,
            loginUrl = loginUrl,
            loginUi = loginUi,
            loginCheckJs = loginCheckJs,
            concurrentRate = concurrentRate,
            header = header,
            enabledCookieJar = if (json.has("enabledCookieJar")) json.optBoolean("enabledCookieJar", true) else true,
            searchUrl = searchUrl,
            ruleSearch = ruleSearch,
            ruleBookInfo = ruleBookInfo,
            ruleToc = ruleToc,
            ruleContent = ruleContent,
            respondTime = respondTime,
            origin = "",
        )
    }

    private fun firstString(json: JSONObject, vararg names: String): String =
        names.asSequence()
            .map { json.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun rawJsonString(json: JSONObject, name: String): String {
        if (!json.has(name) || json.isNull(name)) return ""
        val value = json.opt(name) ?: return ""
        return when (value) {
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString().trim()
        }
    }

    private fun stableId(value: String): String =
        "online_" + MessageDigest.getInstance("SHA-256")
            .digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]+"), "_").ifBlank { "online_source" }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "ReaMicro-Extend/online-source")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) error("下载在线源失败：HTTP $code")
            bytes
        } finally {
            connection.disconnect()
        }
    }
}
