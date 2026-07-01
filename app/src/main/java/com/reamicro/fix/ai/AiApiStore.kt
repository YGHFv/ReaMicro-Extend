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
    val enabled: Boolean = true,
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
    private const val DICTIONARY_MAX_TOKENS = 300

    fun list(context: Context?): List<AiApiConfig> {
        context ?: return emptyList()
        val file = configFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val configs = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val config = parse(item)
                    if (config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) {
                        add(config)
                    }
                }
            }.distinctBy { it.id }
            val firstEnabled = configs.indexOfFirst { it.enabled }
            if (firstEnabled < 0) {
                configs
            } else {
                configs.mapIndexed { index, config ->
                    if (index == firstEnabled) config else config.copy(enabled = false)
                }
            }
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
        val next = (list(context).filterNot { it.id == config.id }.map { it.copy(enabled = false) } + config)
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
            val response = requestChatCompletion(normalizedBaseUrl, normalizedApiKey, body)
            if (!response.success) {
                return AiApiTestResult(false, response.message)
            }
            val json = JSONObject(response.message)
            if (json.optJSONArray("choices")?.length() ?: 0 <= 0) {
                return AiApiTestResult(false, "\u8fde\u63a5\u6210\u529f\uff0c\u4f46\u8fd4\u56de\u4e2d\u6ca1\u6709 choices")
            }
            AiApiTestResult(true, "\u6d4b\u8bd5\u901a\u8fc7")
        }.getOrElse { error ->
            AiApiTestResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    fun enabled(context: Context?): AiApiConfig? =
        list(context).firstOrNull { it.enabled }

    fun setEnabled(context: Context?, id: String, enabled: Boolean): Boolean {
        context ?: return false
        val configs = list(context)
        if (configs.none { it.id == id }) return false
        write(
            context,
            configs.map { config ->
                when {
                    config.id == id -> config.copy(enabled = enabled)
                    enabled -> config.copy(enabled = false)
                    else -> config
                }
            },
        )
        return true
    }

    fun dictionary(config: AiApiConfig, text: String): AiApiTestResult {
        val target = text.trim()
        if (target.isBlank()) return AiApiTestResult(false, "\u9009\u4e2d\u6587\u672c\u4e3a\u7a7a")
        val body = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "\u4f60\u662f\u8bcd\u5178\u52a9\u624b\u3002\u4e0d\u8981\u8f93\u51fa\u601d\u8003\u8fc7\u7a0b\u6216\u63a8\u7406\u8fc7\u7a0b\uff0c\u53ea\u8f93\u51fa\u7b80\u6d01\u91ca\u4e49\u3002"),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "\u8bf7\u7b80\u6d01\u5730\u89e3\u91ca\u300c$target\u300d\u7684\u542b\u4e49\u3002"),
                    ),
            )
            .put("temperature", 0.2)
            .put("max_tokens", DICTIONARY_MAX_TOKENS)
            .put("reasoning_effort", "minimal")
            .put("stream", false)
        val first = requestChatCompletion(config.baseUrl, config.apiKey, body)
        val response = if (!first.success && first.message.contains("reasoning", ignoreCase = true)) {
            requestChatCompletion(config.baseUrl, config.apiKey, JSONObject(body.toString()).apply {
                remove("reasoning_effort")
            })
        } else {
            first
        }
        if (!response.success) return response
        val content = runCatching { extractAssistantContent(JSONObject(response.message)) }
            .getOrDefault("")
            .trim()
        return if (content.isBlank()) {
            AiApiTestResult(false, "\u672a\u83b7\u53d6\u5230\u8bcd\u5178\u7ed3\u679c")
        } else {
            AiApiTestResult(true, content)
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
            enabled = if (json.has("enabled")) json.optBoolean("enabled", true) else true,
        )

    private fun write(context: Context, configs: List<AiApiConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("base_url", config.baseUrl)
                    .put("api_key", config.apiKey)
                    .put("model", config.model)
                    .put("enabled", config.enabled),
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

    private fun requestChatCompletion(baseUrl: String, apiKey: String, body: JSONObject): AiApiTestResult {
        val connection = (URL(completionUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ReaMicro-Extend/ai-config")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code in 200..299) {
                AiApiTestResult(true, text)
            } else {
                AiApiTestResult(false, "HTTP $code: ${extractError(text).ifBlank { text.take(160) }}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractAssistantContent(json: JSONObject): String {
        val choices = json.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message")
        return message?.optString("content").orEmpty()
            .ifBlank { first.optString("text") }
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
