package com.reamicro.fix.tts

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class TtsSourceEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val contentType: String,
    val concurrentRate: String,
    val loginUrl: String,
    val loginUi: String,
    val loginCheckJs: String,
    val header: String,
    val enabledCookieJar: Boolean,
    val origin: String,
) {
    fun toJson(): String =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("url", url)
            .put("contentType", contentType)
            .put("concurrentRate", concurrentRate)
            .put("loginUrl", loginUrl)
            .put("loginUi", loginUi)
            .put("loginCheckJs", loginCheckJs)
            .put("header", header)
            .put("enabledCookieJar", enabledCookieJar)
            .toString()
}

object TtsSourceStore {
    private const val SOURCE_DIR = "reamicro_tts_sources"
    private val sourceExtensions = setOf("rmtts", "json", "txt")

    fun list(context: Context?): List<TtsSourceEntry> {
        context ?: return emptyList()
        return sourceDir(context)
            .listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in sourceExtensions }
            ?.flatMap { file ->
                runCatching {
                    parseSources(file.readBytes(), file.nameWithoutExtension, file.name)
                        .map { it.copy(fileName = file.name) }
                }.getOrDefault(emptyList())
            }
            ?.distinctBy { it.id }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun importFromUrl(context: Context, rawUrl: String): List<TtsSourceEntry> {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            error("clipboard does not contain a TTS source URL")
        }
        if (!url.contains("{{")) {
            val downloaded = runCatching { download(url) }.getOrNull()
            if (downloaded != null) {
                val displayName = url.substringAfterLast('/').substringBefore('?').ifBlank { "tts_source.json" }
                runCatching { importBytes(context, downloaded, displayName, url) }
                    .getOrNull()
                    ?.let { return it }
                if (looksLikeJson(downloaded)) {
                    error("downloaded JSON is not a supported TTS source")
                }
            }
        }
        return importDirectUrl(context, url)
    }

    fun importDirectUrl(context: Context, rawUrl: String): List<TtsSourceEntry> {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            error("clipboard does not contain a TTS source URL")
        }
        val name = directUrlName(url)
        val json = JSONObject()
            .put("name", name)
            .put("url", url)
            .put("contentType", "audio/*")
            .toString()
        return importBytes(context, json.toByteArray(Charsets.UTF_8), "$name.rmtts", url)
    }

    fun importBytes(context: Context, bytes: ByteArray, displayName: String, origin: String = ""): List<TtsSourceEntry> {
        val parsed = parseSources(bytes, displayName.substringBeforeLast('.', displayName), displayName)
            .map { it.copy(origin = origin) }
        if (parsed.isEmpty()) error("TTS source format is not supported")
        val dir = sourceDir(context).apply { mkdirs() }
        parsed.forEach { source ->
            val target = File(dir, "${safeFileName(source.id)}.rmtts")
            FileOutputStream(target).use { output ->
                output.write(source.toJson().toByteArray(Charsets.UTF_8))
            }
        }
        return parsed.map { it.copy(fileName = "${safeFileName(it.id)}.rmtts") }
    }

    fun save(context: Context, source: TtsSourceEntry): TtsSourceEntry {
        val clean = source.copy(
            name = cleanString(source.name).ifBlank { directUrlName(cleanString(source.url)) },
            url = cleanString(source.url),
            contentType = cleanString(source.contentType),
            concurrentRate = cleanString(source.concurrentRate),
            loginUrl = cleanString(source.loginUrl),
            loginUi = cleanString(source.loginUi),
            loginCheckJs = cleanString(source.loginCheckJs),
            header = cleanString(source.header),
        )
        if (!clean.url.startsWith("http://", ignoreCase = true) &&
            !clean.url.startsWith("https://", ignoreCase = true)
        ) {
            error("TTS source URL must start with http:// or https://")
        }
        val dir = sourceDir(context).apply { mkdirs() }
        val fileName = "${safeFileName(clean.id)}.rmtts"
        remove(context, clean.id)
        FileOutputStream(File(dir, fileName)).use { output ->
            output.write(clean.copy(fileName = fileName).toJson().toByteArray(Charsets.UTF_8))
        }
        return clean.copy(fileName = fileName)
    }

    fun remove(context: Context?, sourceId: String): Boolean {
        context ?: return false
        val dir = sourceDir(context)
        val safeId = safeFileName(sourceId)
        var removed = false
        val direct = File(dir, "$safeId.rmtts")
        if (direct.isFile && direct.delete()) removed = true
        dir.listFiles()
            ?.filter { file ->
                file.isFile && runCatching {
                    parseSources(file.readBytes(), file.nameWithoutExtension, file.name).any { it.id == sourceId }
                }.getOrDefault(false)
            }
            ?.forEach { file ->
                if (file.delete()) removed = true
            }
        return removed
    }

    fun looksLikeTtsSource(bytes: ByteArray): Boolean =
        runCatching {
            val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
            val json = when {
                text.startsWith("[") -> JSONArray(text).optJSONObject(0)
                text.startsWith("{") -> JSONObject(text)
                else -> null
            } ?: return@runCatching false
            val hasTtsFields = json.has("url") &&
                (json.has("name") || json.has("contentType") || json.has("concurrentRate"))
            val hasBookFields = json.has("bookSourceName") || json.has("bookSourceUrl") ||
                json.has("searchUrl") || json.has("ruleSearch") || json.has("ruleToc")
            hasTtsFields && !hasBookFields
        }.getOrDefault(false)

    private fun sourceDir(context: Context): File =
        File(context.filesDir, SOURCE_DIR)

    private fun looksLikeJson(bytes: ByteArray): Boolean =
        runCatching {
            val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
            text.startsWith("{") || text.startsWith("[")
        }.getOrDefault(false)

    private fun parseSources(bytes: ByteArray, fallbackName: String, fileName: String): List<TtsSourceEntry> {
        val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        if (text.isBlank()) error("TTS source is empty")
        val objects = when {
            text.startsWith("[") -> JSONArray(text).let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
            text.startsWith("{") -> listOf(JSONObject(text))
            else -> error("TTS source format is not JSON")
        }
        return objects.mapNotNull { json ->
            val url = firstString(json, "url", "ttsUrl", "sourceUrl")
            if (url.isBlank()) return@mapNotNull null
            val name = firstString(json, "name", "sourceName", "title")
                .ifBlank { fallbackName }
                .ifBlank { "TTS" }
            val rawId = firstString(json, "id")
            TtsSourceEntry(
                id = sourceId(rawId, name, url),
                name = name,
                fileName = fileName,
                url = url,
                contentType = firstString(json, "contentType"),
                concurrentRate = firstString(json, "concurrentRate", "rateLimit", "requestRate"),
                loginUrl = firstString(json, "loginUrl", "loginURL", "login"),
                loginUi = rawJsonString(json, "loginUi").ifBlank { rawJsonString(json, "loginUI") },
                loginCheckJs = firstString(json, "loginCheckJs", "loginCheckJS"),
                header = rawJsonString(json, "header").ifBlank { rawJsonString(json, "headers") },
                enabledCookieJar = if (json.has("enabledCookieJar")) json.optBoolean("enabledCookieJar", false) else false,
                origin = "",
            )
        }
    }

    private fun firstString(json: JSONObject, vararg names: String): String =
        names.asSequence()
            .map { cleanJsonString(json, it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun rawJsonString(json: JSONObject, name: String): String {
        if (!json.has(name) || json.isNull(name)) return ""
        val value = json.opt(name) ?: return ""
        return when (value) {
            is JSONObject, is JSONArray -> value.toString()
            else -> cleanString(value.toString())
        }
    }

    private fun cleanJsonString(json: JSONObject, name: String): String {
        if (!json.has(name) || json.isNull(name)) return ""
        val value = json.opt(name) ?: return ""
        return if (value === JSONObject.NULL) "" else cleanString(value.toString())
    }

    private fun cleanString(value: String?): String =
        value?.trim().orEmpty()

    private fun stableId(value: String): String =
        "tts_" + MessageDigest.getInstance("SHA-256")
            .digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun sourceId(rawId: String, name: String, url: String): String {
        val id = cleanString(rawId)
        if (id.startsWith("tts_") && id.length > 4) return id
        return stableId(id.ifBlank { "$name|$url" })
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]+"), "_").ifBlank { "tts_source" }

    private fun directUrlName(url: String): String =
        runCatching {
            val parsed = URL(url)
            val host = parsed.host.orEmpty().ifBlank { "TTS" }
            val lastPath = parsed.path.substringAfterLast('/').substringBeforeLast('.').trim()
            listOf(host, lastPath)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "TTS" }
        }.getOrDefault("TTS")

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "ReaMicro-Extend/tts-source")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) error("TTS source download failed: HTTP $code")
            bytes
        } finally {
            connection.disconnect()
        }
    }
}
