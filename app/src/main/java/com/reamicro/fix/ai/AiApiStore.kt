package com.reamicro.fix.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

data class AiApiConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true,
    val name: String = "",
) {
    val displayName: String
        get() = name.ifBlank { model.ifBlank { baseUrl } }
}

data class AiApiTestResult(
    val success: Boolean,
    val message: String,
)

data class AiApiModelFetchResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList(),
)

data class AiDictionaryPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val builtIn: Boolean = false,
)

enum class AiImagePresetTarget(val id: String, val title: String) {
    Cover("cover", "\u5c01\u9762\u9884\u8bbe"),
    Banner("banner", "\u6a2a\u5e45\u9884\u8bbe"),
}

data class AiImagePreset(
    val id: String,
    val target: AiImagePresetTarget,
    val name: String,
    val prompt: String,
    val builtIn: Boolean = false,
)

data class AiDictionarySettings(
    val apiId: String = "",
    val presetId: String = AiApiStore.DEFAULT_DICTIONARY_PRESET_ID,
    val disableThinking: Boolean = true,
    val singleUsePreset: Boolean = true,
)

data class AiImageSettings(
    val apiId: String = "",
    val coverPresetId: String = AiApiStore.DEFAULT_IMAGE_COVER_PRESET_ID,
    val bannerPresetId: String = AiApiStore.DEFAULT_IMAGE_BANNER_PRESET_ID,
)

object AiApiStore {
    private const val CONFIG_FILE_NAME = "reamicro_ai_apis.json"
    private const val DICTIONARY_SETTINGS_FILE_NAME = "reamicro_dictionary_settings.json"
    private const val IMAGE_SETTINGS_FILE_NAME = "reamicro_image_settings.json"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val IMAGE_READ_TIMEOUT_MS = 90_000
    private const val DICTIONARY_MAX_TOKENS = 300
    const val DEFAULT_DICTIONARY_PRESET_ID = "dictionary_short"
    const val DEFAULT_IMAGE_COVER_PRESET_ID = "image_cover_default"
    const val DEFAULT_IMAGE_BANNER_PRESET_ID = "image_banner_default"
    private val reasoningEffortUnsupportedApis = Collections.synchronizedSet(mutableSetOf<String>())

    val BUILTIN_DICTIONARY_PRESETS = listOf(
        AiDictionaryPreset(
            id = DEFAULT_DICTIONARY_PRESET_ID,
            name = "\u8bcd\u5178\u91ca\u4e49",
            prompt = "\u8bf7\u7b80\u6d01\u5730\u89e3\u91ca\u300c{{text}}\u300d\u7684\u542b\u4e49\u3002",
            builtIn = true,
        ),
        AiDictionaryPreset(
            id = "dictionary_encyclopedia",
            name = "\u8be6\u7ec6\u767e\u79d1",
            prompt = "\u8bf7\u8be6\u7ec6\u4ecb\u7ecd\u300c{{text}}\u300d\uff0c\u5305\u62ec\u5b9a\u4e49\u3001\u80cc\u666f\u3001\u76f8\u5173\u77e5\u8bc6\u3002",
            builtIn = true,
        ),
        AiDictionaryPreset(
            id = "dictionary_classical",
            name = "\u6587\u8a00\u6587/\u53e4\u8bd7\u8bcd",
            prompt = "\u8bf7\u89e3\u91ca\u300c{{text}}\u300d\u5728\u53e4\u6587\u4e2d\u7684\u542b\u4e49\uff0c\u5305\u62ec\u51fa\u5904\u548c\u7528\u6cd5\u3002",
            builtIn = true,
        ),
    )

    val BUILTIN_IMAGE_PRESETS = listOf(
        AiImagePreset(
            id = DEFAULT_IMAGE_COVER_PRESET_ID,
            target = AiImagePresetTarget.Cover,
            name = "\u9ed8\u8ba4\u5c01\u9762",
            prompt = "\u5e2e\u6211\u5236\u4f5c\u5c0f\u8bf4\u300a{title}\u300b\u7684\u7ad6\u7248\u5c01\u9762\uff0c\u6bd4\u4f8b\u4e3a3:4\uff0c\u53ef\u4ee5\u53c2\u8003\u539f\u4e66\u7c4d\u5c01\u9762\u3002\u753b\u9762\u9002\u5408\u5c0f\u8bf4\u9605\u8bfb \u5e94\u7528\u9996\u9875\u6a2a\u5e45\uff0c\u4fdd\u7559\u4e66\u7c4d\u6c14\u8d28\uff0c\u7cbe\u81f4\u3001\u6e05\u6670\u3001\u65e0\u6c34\u5370\u3002",
            builtIn = true,
        ),
        AiImagePreset(
            id = DEFAULT_IMAGE_BANNER_PRESET_ID,
            target = AiImagePresetTarget.Banner,
            name = "\u9ed8\u8ba4\u6a2a\u5e45",
            prompt = "\u5e2e\u6211\u5236\u4f5c\u5c0f\u8bf4\u300a{title}\u300b\u7684\u6a2a\u5e45\u5c01\u9762\uff0c\u6bd4\u4f8b\u4e3a2:1\uff0c\u53ef\u4ee5\u53c2\u8003\u539f\u4e66\u7c4d\u5c01\u9762\u3002\u753b\u9762\u9002\u5408\u5c0f\u8bf4\u9605\u8bfb \u5e94\u7528\u9996\u9875\u6a2a\u5e45\uff0c\u4fdd\u7559\u4e66\u7c4d\u6c14\u8d28\uff0c\u7cbe\u81f4\u3001\u6e05\u6670\u3001\u65e0\u6c34\u5370\u3002",
            builtIn = true,
        ),
    )

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
            configs
        }.getOrElse { emptyList() }
    }

    fun add(context: Context, baseUrl: String, apiKey: String, model: String, name: String = ""): AiApiConfig {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedApiKey = apiKey.trim()
        val normalizedModel = model.trim()
        val normalizedName = name.trim()
        require(normalizedBaseUrl.isNotBlank()) { "Base URL \u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedApiKey.isNotBlank()) { "API Key \u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedModel.isNotBlank()) { "Model \u4e0d\u80fd\u4e3a\u7a7a" }
        val config = AiApiConfig(
            id = stableId("$normalizedBaseUrl|$normalizedApiKey|$normalizedModel"),
            baseUrl = normalizedBaseUrl,
            apiKey = normalizedApiKey,
            model = normalizedModel,
            name = normalizedName,
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

    fun update(context: Context, id: String, baseUrl: String, apiKey: String, model: String, name: String = ""): AiApiConfig {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedApiKey = apiKey.trim()
        val normalizedModel = model.trim()
        val normalizedName = name.trim()
        require(normalizedBaseUrl.isNotBlank()) { "Base URL \u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedApiKey.isNotBlank()) { "API Key \u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedModel.isNotBlank()) { "Model \u4e0d\u80fd\u4e3a\u7a7a" }
        val configs = list(context)
        val previous = configs.firstOrNull { it.id == id }
        val updated = AiApiConfig(
            id = stableId("$normalizedBaseUrl|$normalizedApiKey|$normalizedModel"),
            baseUrl = normalizedBaseUrl,
            apiKey = normalizedApiKey,
            model = normalizedModel,
            enabled = previous?.enabled ?: true,
            name = normalizedName,
        )
        // Editing can change the stable id, so remove both the old row and any row with the new id.
        val next = (configs.filterNot { it.id == id || it.id == updated.id } + updated)
            .sortedBy { it.displayName.lowercase() }
        write(context, next)
        return updated
    }

    fun fetchModels(baseUrl: String, apiKey: String): AiApiModelFetchResult {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedApiKey = apiKey.trim()
        if (normalizedBaseUrl.isBlank() || normalizedApiKey.isBlank()) {
            return AiApiModelFetchResult(false, "\u8bf7\u5148\u586b\u5199 base_url \u548c api_key")
        }
        return runCatching {
            val connection = (URL(modelsUrl(normalizedBaseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $normalizedApiKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ReaMicro-Extend/ai-config")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code in 200..299) {
                    val models = parseModelIds(text)
                    if (models.isEmpty()) {
                        AiApiModelFetchResult(false, "\u63a5\u53e3\u8fd4\u56de\u6210\u529f\uff0c\u4f46\u6ca1\u6709\u627e\u5230\u53ef\u9009\u6a21\u578b")
                    } else {
                        AiApiModelFetchResult(true, "\u5df2\u83b7\u53d6 ${models.size} \u4e2a\u6a21\u578b", models)
                    }
                } else {
                    AiApiModelFetchResult(false, "HTTP $code: ${extractError(text).ifBlank { text.take(160) }}")
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            AiApiModelFetchResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    fun test(baseUrl: String, apiKey: String, model: String): AiApiTestResult {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val normalizedApiKey = apiKey.trim()
        val normalizedModel = model.trim()
        if (normalizedBaseUrl.isBlank() || normalizedApiKey.isBlank() || normalizedModel.isBlank()) {
            return AiApiTestResult(false, "\u8bf7\u5148\u586b\u5b8c base_url\u3001api_key \u548c model")
        }
        return runCatching {
            if (isImageGenerationApi(normalizedBaseUrl, normalizedModel)) {
                val response = testImageGeneration(normalizedBaseUrl, normalizedApiKey, normalizedModel)
                if (!response.success) {
                    return AiApiTestResult(false, response.message)
                }
                return AiApiTestResult(true, "\u751f\u56fe\u6a21\u578b\u6d4b\u8bd5\u901a\u8fc7")
            }
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

    fun dictionaryApi(context: Context?): AiApiConfig? {
        context ?: return null
        val configs = list(context)
        val settings = dictionarySettings(context)
        return configs.firstOrNull { it.id == settings.apiId }
            ?: configs.firstOrNull { it.enabled }
    }

    fun imageApi(context: Context?): AiApiConfig? {
        context ?: return null
        val configs = list(context)
        val settings = imageSettings(context)
        return configs.firstOrNull { it.id == settings.apiId }
            ?: configs.firstOrNull { it.enabled }
    }

    fun setEnabled(context: Context?, id: String, enabled: Boolean): Boolean {
        context ?: return false
        val configs = list(context)
        if (configs.none { it.id == id }) return false
        write(
            context,
            configs.map { config ->
                if (config.id == id) config.copy(enabled = enabled) else config
            },
        )
        return true
    }

    fun dictionarySettings(context: Context?): AiDictionarySettings =
        readDictionaryState(context).settings

    fun imageSettings(context: Context?): AiImageSettings =
        readImageState(context).settings

    fun dictionaryPresets(context: Context?): List<AiDictionaryPreset> {
        val custom = readDictionaryState(context).customPresets
        return (BUILTIN_DICTIONARY_PRESETS + custom)
            .distinctBy { it.id }
    }

    fun dictionaryPreset(context: Context?, presetId: String): AiDictionaryPreset =
        dictionaryPresets(context).firstOrNull { it.id == presetId }
            ?: BUILTIN_DICTIONARY_PRESETS.first()

    fun imagePresets(context: Context?, target: AiImagePresetTarget): List<AiImagePreset> {
        val custom = readImageState(context).customPresets
        return (BUILTIN_IMAGE_PRESETS.filter { it.target == target } + custom.filter { it.target == target })
            .distinctBy { it.id }
    }

    fun imagePreset(context: Context?, target: AiImagePresetTarget, presetId: String): AiImagePreset =
        imagePresets(context, target).firstOrNull { it.id == presetId }
            ?: defaultImagePreset(target)

    fun setDictionaryApiId(context: Context?, apiId: String): Boolean {
        context ?: return false
        val state = readDictionaryState(context)
        writeDictionaryState(context, state.copy(settings = state.settings.copy(apiId = apiId.trim())))
        return true
    }

    fun setImageApiId(context: Context?, apiId: String): Boolean {
        context ?: return false
        val state = readImageState(context)
        writeImageState(context, state.copy(settings = state.settings.copy(apiId = apiId.trim())))
        return true
    }

    fun setDictionaryPresetId(context: Context?, presetId: String): Boolean {
        context ?: return false
        val target = dictionaryPresets(context).firstOrNull { it.id == presetId } ?: return false
        val state = readDictionaryState(context)
        writeDictionaryState(context, state.copy(settings = state.settings.copy(presetId = target.id)))
        return true
    }

    fun setImagePresetId(context: Context?, target: AiImagePresetTarget, presetId: String): Boolean {
        context ?: return false
        val preset = imagePresets(context, target).firstOrNull { it.id == presetId } ?: return false
        val state = readImageState(context)
        writeImageState(context, state.copy(settings = state.settings.withImagePresetId(target, preset.id)))
        return true
    }

    fun setDictionaryDisableThinking(context: Context?, disabled: Boolean): Boolean {
        context ?: return false
        val state = readDictionaryState(context)
        writeDictionaryState(context, state.copy(settings = state.settings.copy(disableThinking = disabled)))
        return true
    }

    fun setDictionarySingleUsePreset(context: Context?, enabled: Boolean): Boolean {
        context ?: return false
        val state = readDictionaryState(context)
        writeDictionaryState(context, state.copy(settings = state.settings.copy(singleUsePreset = enabled)))
        return true
    }

    fun addDictionaryPreset(context: Context, name: String, prompt: String): AiDictionaryPreset {
        val normalizedName = name.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedName.isNotBlank()) { "\u9884\u8bbe\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedPrompt.isNotBlank()) { "\u63d0\u793a\u8bcd\u4e0d\u80fd\u4e3a\u7a7a" }
        val preset = AiDictionaryPreset(
            id = "preset_" + stableId("$normalizedName|$normalizedPrompt").removePrefix("ai_"),
            name = normalizedName,
            prompt = normalizedPrompt,
        )
        val state = readDictionaryState(context)
        val nextCustom = (state.customPresets.filterNot { it.id == preset.id } + preset)
            .sortedBy { it.name.lowercase() }
        writeDictionaryState(
            context,
            state.copy(
                settings = state.settings.copy(presetId = preset.id),
                customPresets = nextCustom,
            ),
        )
        return preset
    }

    fun addImagePreset(context: Context, target: AiImagePresetTarget, name: String, prompt: String): AiImagePreset {
        val normalizedName = name.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedName.isNotBlank()) { "\u9884\u8bbe\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedPrompt.isNotBlank()) { "\u63d0\u793a\u8bcd\u4e0d\u80fd\u4e3a\u7a7a" }
        val preset = AiImagePreset(
            id = "image_${target.id}_" + stableId("${target.id}|$normalizedName|$normalizedPrompt").removePrefix("ai_"),
            target = target,
            name = normalizedName,
            prompt = normalizedPrompt,
        )
        val state = readImageState(context)
        val nextCustom = (state.customPresets.filterNot { it.id == preset.id } + preset)
            .sortedWith(compareBy<AiImagePreset> { it.target.id }.thenBy { it.name.lowercase() })
        writeImageState(
            context,
            state.copy(
                settings = state.settings.withImagePresetId(target, preset.id),
                customPresets = nextCustom,
            ),
        )
        return preset
    }

    fun updateDictionaryPreset(context: Context, id: String, name: String, prompt: String): AiDictionaryPreset {
        require(BUILTIN_DICTIONARY_PRESETS.none { it.id == id }) { "\u5185\u7f6e\u9884\u8bbe\u4e0d\u80fd\u4fee\u6539" }
        val normalizedName = name.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedName.isNotBlank()) { "\u9884\u8bbe\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedPrompt.isNotBlank()) { "\u63d0\u793a\u8bcd\u4e0d\u80fd\u4e3a\u7a7a" }
        val state = readDictionaryState(context)
        val previous = state.customPresets.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("\u672a\u627e\u5230\u9884\u8bbe")
        val updated = previous.copy(name = normalizedName, prompt = normalizedPrompt)
        val nextCustom = state.customPresets.map { if (it.id == id) updated else it }
            .sortedBy { it.name.lowercase() }
        writeDictionaryState(context, state.copy(customPresets = nextCustom))
        return updated
    }

    fun updateImagePreset(context: Context, id: String, name: String, prompt: String): AiImagePreset {
        require(BUILTIN_IMAGE_PRESETS.none { it.id == id }) { "\u5185\u7f6e\u9884\u8bbe\u4e0d\u80fd\u4fee\u6539" }
        val normalizedName = name.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedName.isNotBlank()) { "\u9884\u8bbe\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a" }
        require(normalizedPrompt.isNotBlank()) { "\u63d0\u793a\u8bcd\u4e0d\u80fd\u4e3a\u7a7a" }
        val state = readImageState(context)
        val previous = state.customPresets.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("\u672a\u627e\u5230\u9884\u8bbe")
        val updated = previous.copy(name = normalizedName, prompt = normalizedPrompt)
        val nextCustom = state.customPresets.map { if (it.id == id) updated else it }
            .sortedWith(compareBy<AiImagePreset> { it.target.id }.thenBy { it.name.lowercase() })
        writeImageState(context, state.copy(customPresets = nextCustom))
        return updated
    }

    fun removeDictionaryPreset(context: Context?, id: String): Boolean {
        context ?: return false
        if (BUILTIN_DICTIONARY_PRESETS.any { it.id == id }) return false
        val state = readDictionaryState(context)
        val nextCustom = state.customPresets.filterNot { it.id == id }
        if (nextCustom.size == state.customPresets.size) return false
        val nextSettings = if (state.settings.presetId == id) {
            state.settings.copy(presetId = DEFAULT_DICTIONARY_PRESET_ID)
        } else {
            state.settings
        }
        writeDictionaryState(context, state.copy(settings = nextSettings, customPresets = nextCustom))
        return true
    }

    fun removeImagePreset(context: Context?, id: String): Boolean {
        context ?: return false
        if (BUILTIN_IMAGE_PRESETS.any { it.id == id }) return false
        val state = readImageState(context)
        val removed = state.customPresets.firstOrNull { it.id == id } ?: return false
        val nextCustom = state.customPresets.filterNot { it.id == id }
        val nextSettings = when (removed.target) {
            AiImagePresetTarget.Cover -> if (state.settings.coverPresetId == id) {
                state.settings.copy(coverPresetId = DEFAULT_IMAGE_COVER_PRESET_ID)
            } else {
                state.settings
            }
            AiImagePresetTarget.Banner -> if (state.settings.bannerPresetId == id) {
                state.settings.copy(bannerPresetId = DEFAULT_IMAGE_BANNER_PRESET_ID)
            } else {
                state.settings
            }
        }
        writeImageState(context, state.copy(settings = nextSettings, customPresets = nextCustom))
        return true
    }

    fun renderDictionaryPrompt(template: String, text: String): String {
        val target = text.trim()
        val prompt = template.trim()
        return if (prompt.contains("{{text}}")) {
            prompt.replace("{{text}}", target)
        } else {
            "$prompt\n\n\u300c$target\u300d"
        }
    }

    fun dictionary(config: AiApiConfig, text: String): AiApiTestResult {
        return dictionary(config, text, BUILTIN_DICTIONARY_PRESETS.first(), disableThinking = true)
    }

    fun dictionary(context: Context?, config: AiApiConfig, text: String): AiApiTestResult {
        val settings = dictionarySettings(context)
        return dictionary(config, text, dictionaryPreset(context, settings.presetId), settings.disableThinking)
    }

    fun dictionary(context: Context?, config: AiApiConfig, text: String, preset: AiDictionaryPreset): AiApiTestResult {
        val settings = dictionarySettings(context)
        return dictionary(config, text, preset, settings.disableThinking)
    }

    private fun dictionary(
        config: AiApiConfig,
        text: String,
        preset: AiDictionaryPreset,
        disableThinking: Boolean,
    ): AiApiTestResult {
        val target = text.trim()
        if (target.isBlank()) return AiApiTestResult(false, "\u9009\u4e2d\u6587\u672c\u4e3a\u7a7a")
        val systemPrompt = if (disableThinking) {
            "\u4f60\u662f\u8bcd\u5178\u52a9\u624b\u3002\u4e0d\u8981\u8f93\u51fa\u601d\u8003\u8fc7\u7a0b\u6216\u63a8\u7406\u8fc7\u7a0b\uff0c\u53ea\u8f93\u51fa\u6700\u7ec8\u7b54\u6848\u3002"
        } else {
            "\u4f60\u662f\u8bcd\u5178\u52a9\u624b\u3002"
        }
        val includeReasoningEffort = disableThinking && config.id !in reasoningEffortUnsupportedApis
        val body = buildDictionaryRequestBody(
            config = config,
            systemPrompt = systemPrompt,
            userPrompt = renderDictionaryPrompt(preset.prompt, target),
            includeReasoningEffort = includeReasoningEffort,
        )
        val first = requestChatCompletion(config.baseUrl, config.apiKey, body)
        val response = if (includeReasoningEffort && first.isUnsupportedReasoningEffortError()) {
            reasoningEffortUnsupportedApis.add(config.id)
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

    internal fun buildDictionaryRequestBody(
        config: AiApiConfig,
        systemPrompt: String,
        userPrompt: String,
        includeReasoningEffort: Boolean,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userPrompt),
                    ),
            )
            .put("temperature", 0.2)
            .put("max_tokens", DICTIONARY_MAX_TOKENS)
            .put("stream", false)
        if (includeReasoningEffort) {
            body.put("reasoning_effort", "minimal")
        }
        return body
    }

    private fun AiApiTestResult.isUnsupportedReasoningEffortError(): Boolean {
        if (success) return false
        val text = message.lowercase()
        if (!text.contains("reasoning")) return false
        return text.contains("reasoning_effort") ||
            text.contains("unsupported") ||
            text.contains("not supported") ||
            text.contains("does not support") ||
            text.contains("unknown") ||
            text.contains("unrecognized") ||
            text.contains("invalid")
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
            name = json.optString("name").trim(),
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
                    .put("enabled", config.enabled)
                    .put("name", config.name),
            )
        }
        configFile(context).writeText(array.toString(2), Charsets.UTF_8)
    }

    private fun configFile(context: Context): File =
        File(context.filesDir, CONFIG_FILE_NAME)

    private fun dictionarySettingsFile(context: Context): File =
        File(context.filesDir, DICTIONARY_SETTINGS_FILE_NAME)

    private fun imageSettingsFile(context: Context): File =
        File(context.filesDir, IMAGE_SETTINGS_FILE_NAME)

    private fun readDictionaryState(context: Context?): DictionaryStoreState {
        context ?: return DictionaryStoreState()
        val file = dictionarySettingsFile(context)
        if (!file.isFile) return DictionaryStoreState()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val settings = AiDictionarySettings(
                apiId = json.optString("api_id").trim(),
                presetId = json.optString("preset_id").ifBlank { DEFAULT_DICTIONARY_PRESET_ID },
                disableThinking = if (json.has("disable_thinking")) {
                    json.optBoolean("disable_thinking", true)
                } else {
                    true
                },
                singleUsePreset = if (json.has("single_use_preset")) {
                    json.optBoolean("single_use_preset", true)
                } else {
                    true
                },
            )
            val customPresets = buildList {
                val array = json.optJSONArray("custom_presets") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val preset = AiDictionaryPreset(
                        id = item.optString("id").trim(),
                        name = item.optString("name").trim(),
                        prompt = item.optString("prompt").trim(),
                    )
                    if (
                        preset.id.isNotBlank() &&
                        preset.name.isNotBlank() &&
                        preset.prompt.isNotBlank() &&
                        BUILTIN_DICTIONARY_PRESETS.none { it.id == preset.id }
                    ) {
                        add(preset)
                    }
                }
            }.distinctBy { it.id }
            DictionaryStoreState(settings, customPresets)
        }.getOrElse { DictionaryStoreState() }
    }

    private fun writeDictionaryState(context: Context, state: DictionaryStoreState) {
        val customPresets = state.customPresets
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.prompt.isNotBlank() && !it.builtIn }
            .distinctBy { it.id }
        val presetId = if (
            BUILTIN_DICTIONARY_PRESETS.any { it.id == state.settings.presetId } ||
            customPresets.any { it.id == state.settings.presetId }
        ) {
            state.settings.presetId
        } else {
            DEFAULT_DICTIONARY_PRESET_ID
        }
        val array = JSONArray()
        customPresets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("prompt", preset.prompt),
            )
        }
        val json = JSONObject()
            .put("api_id", state.settings.apiId)
            .put("preset_id", presetId)
            .put("disable_thinking", state.settings.disableThinking)
            .put("single_use_preset", state.settings.singleUsePreset)
            .put("custom_presets", array)
        dictionarySettingsFile(context).writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun readImageState(context: Context?): ImageStoreState {
        context ?: return ImageStoreState()
        val file = imageSettingsFile(context)
        if (!file.isFile) return ImageStoreState()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val settings = AiImageSettings(
                apiId = json.optString("api_id").trim(),
                coverPresetId = json.optString("cover_preset_id").ifBlank { DEFAULT_IMAGE_COVER_PRESET_ID },
                bannerPresetId = json.optString("banner_preset_id").ifBlank { DEFAULT_IMAGE_BANNER_PRESET_ID },
            )
            val customPresets = buildList {
                val array = json.optJSONArray("custom_presets") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val target = imagePresetTarget(item.optString("target")) ?: continue
                    val preset = AiImagePreset(
                        id = item.optString("id").trim(),
                        target = target,
                        name = item.optString("name").trim(),
                        prompt = item.optString("prompt").trim(),
                    )
                    if (
                        preset.id.isNotBlank() &&
                        preset.name.isNotBlank() &&
                        preset.prompt.isNotBlank() &&
                        BUILTIN_IMAGE_PRESETS.none { it.id == preset.id }
                    ) {
                        add(preset)
                    }
                }
            }.distinctBy { it.id }
            ImageStoreState(settings, customPresets)
        }.getOrElse { ImageStoreState() }
    }

    private fun writeImageState(context: Context, state: ImageStoreState) {
        val customPresets = state.customPresets
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.prompt.isNotBlank() && !it.builtIn }
            .distinctBy { it.id }
        val coverPresetId = validImagePresetId(AiImagePresetTarget.Cover, state.settings.coverPresetId, customPresets)
        val bannerPresetId = validImagePresetId(AiImagePresetTarget.Banner, state.settings.bannerPresetId, customPresets)
        val array = JSONArray()
        customPresets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("target", preset.target.id)
                    .put("name", preset.name)
                    .put("prompt", preset.prompt),
            )
        }
        val json = JSONObject()
            .put("api_id", state.settings.apiId)
            .put("cover_preset_id", coverPresetId)
            .put("banner_preset_id", bannerPresetId)
            .put("custom_presets", array)
        imageSettingsFile(context).writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun defaultImagePreset(target: AiImagePresetTarget): AiImagePreset =
        BUILTIN_IMAGE_PRESETS.first { it.target == target }

    private fun validImagePresetId(
        target: AiImagePresetTarget,
        presetId: String,
        customPresets: List<AiImagePreset>,
    ): String =
        if (
            BUILTIN_IMAGE_PRESETS.any { it.target == target && it.id == presetId } ||
            customPresets.any { it.target == target && it.id == presetId }
        ) {
            presetId
        } else {
            defaultImagePreset(target).id
        }

    private fun imagePresetTarget(value: String): AiImagePresetTarget? =
        AiImagePresetTarget.values().firstOrNull { it.id == value }

    private fun AiImageSettings.withImagePresetId(target: AiImagePresetTarget, presetId: String): AiImageSettings =
        when (target) {
            AiImagePresetTarget.Cover -> copy(coverPresetId = presetId)
            AiImagePresetTarget.Banner -> copy(bannerPresetId = presetId)
        }

    private fun normalizeBaseUrl(value: String): String =
        value.trim().trimEnd('/')

    private fun completionUrl(baseUrl: String): String =
        when {
            baseUrl.endsWith("/chat/completions", ignoreCase = true) -> baseUrl
            baseUrl.endsWith("/images/generations", ignoreCase = true) ->
                baseUrl.removeSuffixIgnoreCase("/images/generations") + "/chat/completions"
            baseUrl.endsWith("/v1", ignoreCase = true) || isVersionedApiBaseUrl(baseUrl) ->
                "$baseUrl/chat/completions"
            else -> "$baseUrl/v1/chat/completions"
        }

    private fun imageGenerationUrl(baseUrl: String): String =
        when {
            baseUrl.endsWith("/images/generations", ignoreCase = true) -> baseUrl
            baseUrl.endsWith("/chat/completions", ignoreCase = true) ->
                baseUrl.removeSuffixIgnoreCase("/chat/completions") + "/images/generations"
            baseUrl.endsWith("/v1", ignoreCase = true) || isVersionedApiBaseUrl(baseUrl) ->
                "$baseUrl/images/generations"
            else -> "$baseUrl/v1/images/generations"
        }

    private fun modelsUrl(baseUrl: String): String =
        when {
            baseUrl.endsWith("/models", ignoreCase = true) -> baseUrl
            baseUrl.endsWith("/chat/completions", ignoreCase = true) ->
                baseUrl.removeSuffixIgnoreCase("/chat/completions") + "/models"
            baseUrl.endsWith("/images/generations", ignoreCase = true) ->
                baseUrl.removeSuffixIgnoreCase("/images/generations") + "/models"
            baseUrl.endsWith("/v1", ignoreCase = true) || isVersionedApiBaseUrl(baseUrl) ->
                "$baseUrl/models"
            else -> "$baseUrl/v1/models"
        }

    private fun isVersionedApiBaseUrl(baseUrl: String): Boolean =
        Regex(""".*/api/v\d+$""", RegexOption.IGNORE_CASE).matches(baseUrl)

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private fun isImageGenerationApi(baseUrl: String, model: String): Boolean {
        val lowerUrl = baseUrl.lowercase()
        val lowerModel = model.lowercase()
        return lowerUrl.endsWith("/images/generations") ||
            lowerModel.contains("seedream") ||
            lowerModel.contains("gpt-image") ||
            lowerModel.contains("dall-e") ||
            lowerModel.contains("imagen") ||
            lowerModel.contains("flux")
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

    private fun testImageGeneration(baseUrl: String, apiKey: String, model: String): AiApiTestResult {
        val candidates = listOf(
            JSONObject()
                .put("model", model)
                .put("prompt", "test")
                .put("n", 1)
                .put("size", "1024x1024")
                .put("response_format", "url"),
            JSONObject()
                .put("model", model)
                .put("prompt", "test")
                .put("n", 1)
                .put("size", "1024x1024"),
            JSONObject()
                .put("model", model)
                .put("prompt", "test")
                .put("n", 1),
        )
        var last = AiApiTestResult(false, "\u751f\u56fe\u6d4b\u8bd5\u5931\u8d25")
        for (body in candidates) {
            val result = requestImageGeneration(baseUrl, apiKey, body)
            if (result.success) return result
            last = result
            val lower = result.message.lowercase()
            if (
                !lower.contains("size") &&
                !lower.contains("response_format") &&
                !lower.contains("unsupported") &&
                !lower.contains("invalid") &&
                !lower.contains("unknown") &&
                !lower.contains("unrecognized")
            ) {
                break
            }
        }
        return last
    }

    private fun requestImageGeneration(baseUrl: String, apiKey: String, body: JSONObject): AiApiTestResult {
        val connection = (URL(imageGenerationUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = IMAGE_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json,image/*")
            setRequestProperty("User-Agent", "ReaMicro-Extend/ai-config")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val text = bytes.toString(Charsets.UTF_8)
            if (code in 200..299) {
                AiApiTestResult(true, text.ifBlank { connection.contentType.orEmpty() })
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

    private fun parseModelIds(text: String): List<String> {
        val ids = linkedSetOf<String>()
        fun addModelId(value: String) {
            val id = value.trim()
            if (id.isNotBlank()) ids.add(id)
        }
        fun addArray(array: JSONArray) {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is JSONObject -> addModelId(
                        item.optString("id")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("model") },
                    )
                    is String -> addModelId(item)
                }
            }
        }
        return runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                addArray(JSONArray(trimmed))
            } else {
                val json = JSONObject(trimmed)
                listOf("data", "models", "items").forEach { key ->
                    json.optJSONArray(key)?.let { addArray(it) }
                }
                json.optJSONObject("data")?.let { data ->
                    listOf("models", "items").forEach { key ->
                        data.optJSONArray(key)?.let { addArray(it) }
                    }
                }
            }
            ids.sortedBy { it.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun stableId(value: String): String =
        "ai_" + MessageDigest.getInstance("SHA-256")
            .digest(value.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private data class DictionaryStoreState(
        val settings: AiDictionarySettings = AiDictionarySettings(),
        val customPresets: List<AiDictionaryPreset> = emptyList(),
    )

    private data class ImageStoreState(
        val settings: AiImageSettings = AiImageSettings(),
        val customPresets: List<AiImagePreset> = emptyList(),
    )
}
