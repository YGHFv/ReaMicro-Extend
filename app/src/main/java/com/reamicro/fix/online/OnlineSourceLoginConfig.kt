package com.reamicro.fix.online

import org.json.JSONArray
import org.json.JSONObject

data class OnlineSourceLoginField(
    val name: String,
    val type: String,
    val defaultValue: String = "",
) {
    val isSecret: Boolean
        get() = type.equals("password", ignoreCase = true)
}

object OnlineSourceLoginConfig {
    private val inputTypes = setOf("text", "password", "email", "number", "tel", "phone")
    private val credentialNamePattern = Regex(
        """(?i)(账号|帐号|用户名|邮箱|手机号|手机|密码|密钥|秘钥|口令|授权码|令牌|token|api[\s_-]*key|secret(?:[\s_-]*key)?|access[\s_-]*key|\bkey\b)""",
    )
    private val loginInfoGetPattern = Regex(
        """source\.getLoginInfoMap\(\)\s*\.get\(\s*['\"]([^'\"]+)['\"]\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val loginInfoIndexPattern = Regex(
        """source\.getLoginInfoMap\(\)\s*\[\s*['\"]([^'\"]+)['\"]\s*]""",
        RegexOption.IGNORE_CASE,
    )

    fun parseFields(raw: String): List<OnlineSourceLoginField> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val array = runCatching {
            when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> {
                    val root = JSONObject(text)
                    root.optJSONArray("fields")
                        ?: root.optJSONArray("items")
                        ?: JSONArray().put(root)
                }
                else -> return emptyList()
            }
        }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = firstString(item, "name", "label", "title", "key")
            val type = firstString(item, "type", "inputType").lowercase()
            if (name.isBlank() || type.isBlank()) return@mapNotNull null
            OnlineSourceLoginField(
                name = name,
                type = type,
                defaultValue = firstString(item, "default", "value"),
            )
        }.distinctBy { it.name }
    }

    fun credentialFields(raw: String, referencedScripts: String = ""): List<OnlineSourceLoginField> {
        val inputs = parseFields(raw).filter { it.type in inputTypes }
        if (inputs.isEmpty()) return emptyList()
        val credentials = inputs.filter { field ->
            field.isSecret ||
                credentialNamePattern.containsMatchIn(field.name) ||
                scriptReferencesLoginField(referencedScripts, field.name)
        }
        return when {
            credentials.isNotEmpty() -> credentials
            inputs.size == 1 -> inputs
            else -> emptyList()
        }
    }

    private fun scriptReferencesLoginField(script: String, fieldName: String): Boolean {
        if (script.isBlank() || fieldName.isBlank()) return false
        val escapedName = Regex.escape(fieldName)
        return listOf(
            Regex("""(?i)getLoginInfoMap\s*\(\s*\)\s*\.get\s*\(\s*['\"]$escapedName['\"]\s*\)"""),
            Regex("""(?i)getLoginInfoMap\s*\(\s*\)\s*\[\s*['\"]$escapedName['\"]\s*]"""),
            Regex("""(?i)\bshuqiValue\s*\(\s*[^,]+,\s*['\"]$escapedName['\"]\s*\)"""),
        ).any { it.containsMatchIn(script) }
    }

    fun browserUrl(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            return text.lineSequence().firstOrNull().orEmpty().trim()
        }
        return Regex(
            """java\.startBrowser\(\s*['\"](https?://[^'\"]+)['\"]""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

    fun encode(values: Map<String, String>): String =
        JSONObject().apply {
            values.forEach { (name, value) -> put(name, value) }
        }.toString()

    fun decode(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { name -> json.optString(name, "") }
        }.getOrDefault(emptyMap())
    }

    fun loginInfoName(expression: String): String? {
        val text = expression.trim()
        return loginInfoGetPattern.matchEntire(text)?.groupValues?.getOrNull(1)
            ?: loginInfoIndexPattern.matchEntire(text)?.groupValues?.getOrNull(1)
    }

    fun expressionValue(
        expression: String,
        sourceUrl: String,
        loginInfo: Map<String, String>,
    ): String? {
        val text = expression.trim()
        if (text.equals("source.key", ignoreCase = true)) return sourceUrl
        val name = loginInfoName(text) ?: return null
        return loginInfo[name].orEmpty()
    }

    private fun firstString(json: JSONObject, vararg names: String): String =
        names.asSequence()
            .map { name -> json.optString(name, "").trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
}
