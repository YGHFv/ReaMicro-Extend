package com.reamicro.fix.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class AiApiConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val displayName: String
        get() = model.ifBlank { baseUrl }
}

data class AiApiTestResult(
    val success: Boolean,
    val message: String,
)

object AiApiStore {
    private const val CONFIG_FILE_NAME = "reamicro_ai_apis.json"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000

    fun list(context: Context?): List<AiApiConfig> {
        context ?: return emptyList()
        val file = configFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val config = parse(item)
                    if (config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) {
                        add(config)
                    }
                }
            }.distinctBy { it.id }
        }.getOrElse { emptyList() }
    }

    fun add(context: Context, baseUrl: String, apiKey: String, model: String): AiApiConfig {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedApiKey = apiKey.trim()
        val normalizedModel = model.trim()
        require(normalizedBaseUrl.isNotBlank()) { "Base URL \u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedApiKey.isNotBlank()) { "API Key \u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedModel.isNotBlank()) { "Model \u4e0d\u80fd\u4e3a\u7a7a" }
        val config = AiApiConfig(
            id = stableId("$normalizedBaseUrl|$normalizedApiKey|$normalizedModel"),
            baseUrl = normalizedBaseUrl,
            apiKey = normalizedApiKey,
            model = normalizedModel,
        )
        val next = (list(context).filterNot { it.id == config.id } + config)
            .sortedBy { it.displayName.lowercase() }
        write(context, next)
        return config
    }

    fun remove(context: Context?, id: String): Boolean {
        context ?: return false
        val before = list(context)
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        write(context, after)
        return true
    }

    fun test(baseUrl: String, apiKey: String, model: String): AiApiTestResult {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedApiKey = apiKey.trim()
        val normalizedModel = model.trim()
        if (normalizedBaseUrl.isBlank() || normalizedApiKey.isBlank() || normalizedModel.isBlank()) {
            return AiApiTestResult(false, "\u8bf7\u5148\u586b\u5b8c base_url\u3001api_key \u548c model")
        }
        return runCatching {
            val url = completionUrl(normalizedBaseUrl)
            val body = JSONObject()
                .put("model", normalizedModel)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "ping"),
                    ),
                )
                .put("max_tokens", 8)
                .put("stream", false)
                .toString()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $normalizedApiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ReaMicro-Extend/ai-config")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) {
                    return AiApiTestResult(false, "HTTP $code: ${extractError(text).ifBlank { text.take(160) }}")
                }
                val json = JSONObject(text)
                if (json.optJSONArray("choices")?.length() ?: 0 <= 0) {
                    return AiApiTestResult(false, "\u8fde\u63a5\u6210\u529f\uff0c\u4f46\u8fd4\u56de\u4e2d\u6ca1\u6709 choices")
                }
                AiApiTestResult(true, "\u6d4b\u8bd5\u901a\u8fc7")
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            AiApiTestResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    fun maskedKey(apiKey: String): String {
        val value = apiKey.trim()
        if (value.length <= 10) return "*".repeat(value.length.coerceAtLeast(4))
        return value.take(4) + "..." + value.takeLast(4)
    }

    private fun parse(json: JSONObject): AiApiConfig =
        AiApiConfig(
            id = json.optString("id").ifBlank {
                stableId(
                    json.optString("base_url") + "|" +
                        json.optString("api_key") + "|" +
                        json.optString("model"),
                )
            },
            baseUrl = json.optString("base_url").trim(),
            apiKey = json.optString("api_key").trim(),
            model = json.optString("model").trim(),
        )

    private fun write(context: Context, configs: List<AiApiConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("base_url", config.baseUrl)
                    .put("api_key", config.apiKey)
                    .put("model", config.model),
            )
        }
        configFile(context).writeText(array.toString(2), Charsets.UTF_8)
    }

    private fun configFile(context: Context): File =
        File(context.filesDir, CONFIG_FILE_NAME)

    private fun normalizeBaseUrl(value: String): String =
        value.trim().trimEnd('/')

    private fun completionUrl(baseUrl: String): String =
        when {
            baseUrl.endsWith("/chat/completions", ignoreCase = true) -> baseUrl
            baseUrl.endsWith("/v1", ignoreCase = true) -> "$baseUrl/chat/completions"
            else -> "$baseUrl/v1/chat/completions"
        }

    private fun extractError(text: String): String =
        runCatching {
            val json = JSONObject(text)
            val error = json.optJSONObject("error") ?: return@runCatching ""
            error.optString("message")
        }.getOrDefault("")

    private fun stableId(value: String): String =
        "ai_" + MessageDigest.getInstance("SHA-256")
            .digest(value.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
