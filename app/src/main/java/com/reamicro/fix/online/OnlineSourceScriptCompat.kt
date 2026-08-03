package com.reamicro.fix.online

import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object OnlineSourceScriptCompat {
    /** 兼容“JSON 字段 + 内联 JS”形式的详情页规则，仅解析 result 的字符串拼接。 */
    fun resolveInlineResultUrl(rawRule: String, selectedValue: String): String? {
        val scriptStart = rawRule.indexOf("<js>", ignoreCase = true)
        if (scriptStart <= 0) return null
        val scriptEnd = rawRule.indexOf("</js>", scriptStart, ignoreCase = true)
            .takeIf { it >= 0 }
            ?: rawRule.length
        val script = rawRule.substring(scriptStart + 4, scriptEnd)
        val candidates = Regex("""(['\"])([^'\"]*)\1\s*\+\s*result\s*\+\s*(['\"])([^'\"]*)\3""")
            .findAll(script)
            .map { match -> match.groupValues[2] + selectedValue.trim() + match.groupValues[4] }
            .filter { value ->
                value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true) ||
                    value.startsWith("/")
            }
            .toList()
        return candidates.lastOrNull()
    }

    /** 兼容书旗详情页中固定算法生成的目录签名地址。 */
    fun resolveShuqiTocUrl(rawRule: String, detailUrl: String, nowSeconds: Long): String? {
        if (!rawRule.contains("ocean.shuqireader.com/api/bcspub/qswebapi/book/chapterlist", ignoreCase = true) ||
            !rawRule.contains("37e81a9d8f02596e1b895d07c171d5c9", ignoreCase = true)
        ) return null
        val bookId = Regex("(?i)/book/(\\d+)").find(detailUrl)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)[?&]bookId=(\\d+)").find(detailUrl)?.groupValues?.getOrNull(1)
            ?: return null
        val userId = "8000000"
        val secret = "37e81a9d8f02596e1b895d07c171d5c9"
        val sign = MessageDigest.getInstance("MD5")
            .digest("$bookId$nowSeconds$userId$secret".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "https://ocean.shuqireader.com/api/bcspub/qswebapi/book/chapterlist" +
            "?_=&bookId=$bookId&user_id=$userId&sign=$sign&timestamp=$nowSeconds"
    }

    /**
     * 兼容书旗小说书源通过 source.get(...) 读取正文接口和 API Key 的章节脚本。
     * 该脚本依赖宿主源变量，模块不会执行任意 JS，因此只在检测到完整特征时启用。
     */
    fun resolveShuqiChapterUrl(
        baseUrl: String,
        rawScript: String,
        chapterId: String,
        loginInfo: Map<String, String>,
    ): String? {
        if (!rawScript.contains("shuqi_oe_api", ignoreCase = true) ||
            !rawScript.contains("shuqi_oe_key", ignoreCase = true) ||
            !rawScript.contains("/api/books/", ignoreCase = true)
        ) return null
        val apiBase = loginInfo["正文接口"].orEmpty().trim().trimEnd('/')
        val apiKey = loginInfo["API Key"].orEmpty().trim()
        val bookId = Regex("(?i)[?&]bookId=(\\d+)").find(baseUrl)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)/book(?:s)?/(\\d+)").find(baseUrl)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)bookId\\s*[=:]\\s*(\\d+)").find(baseUrl)?.groupValues?.getOrNull(1)
        val chapter = chapterId.filter(Char::isDigit).ifBlank { chapterId.trim() }
        if (apiBase.isBlank() || apiKey.isBlank() || bookId.isNullOrBlank() || chapter.isBlank()) return null
        val headers = "{\"headers\":{\"Accept\":\"application/json\",\"X-API-Key\":\"${escapeJson(apiKey)}\"}}"
        return "$apiBase/api/books/$bookId/chapters/$chapter?mode=auto,$headers"
    }

    fun resolveSearchUrl(
        sourceUrl: String,
        rawScript: String,
        rawQuery: String,
        page: Int,
        loginInfo: Map<String, String>,
    ): String? {
        val script = rawScript.removePrefix("@js:").trim()
        val assignments = Regex("""(?m)^\s*result\s*=\s*(.+?)\s*;?\s*$""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()
        if (assignments.isEmpty()) return null
        val (query, tab) = normalizeSearchQuery(script, rawQuery)
        val isNumericDetail = query.length == 19 && query.all(Char::isDigit)
        val expression = when {
            isNumericDetail -> assignments.firstOrNull { it.contains("detail?bookid", ignoreCase = true) }
            else -> assignments.lastOrNull { it.contains("api=search", ignoreCase = true) }
        } ?: return null
        return evaluateConcatenation(
            expression = expression,
            sourceUrl = sourceUrl,
            query = query,
            page = page,
            tab = tab,
            loginInfo = loginInfo,
        )?.let { resolveUrl(sourceUrl, it) }
    }

    fun resolveContentRequestTemplate(
        sourceUrl: String,
        rawScript: String,
        loginInfo: Map<String, String>,
    ): String? {
        val ajaxVariable = Regex("""java\.ajax\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .find(rawScript)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val assignment = Regex(
            """(?m)^\s*(?:let|var|const)\s+${Regex.escape(ajaxVariable)}\s*=\s*(.+?)\s*;\s*$""",
        ).find(rawScript)?.groupValues?.getOrNull(1) ?: return null
        return evaluateConcatenation(
            expression = assignment,
            sourceUrl = sourceUrl,
            query = "",
            page = 1,
            tab = 3,
            loginInfo = loginInfo,
            variables = mapOf(
                "id" to "\${itemId}",
                "_contentType" to "full",
                "_bid" to "",
            ),
        )?.let { resolveUrl(sourceUrl, it) }
    }

    private fun normalizeSearchQuery(script: String, rawQuery: String): Pair<String, Int> {
        var query = rawQuery
        var tab = Regex("""(?m)^\s*tab\s*=\s*(\d+)\s*;?\s*$""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 3
        val prefixRules = Regex(
            """String\s*\(\s*key\s*\)\.startsWith\(\s*['\"]([^'\"]+)['\"]\s*\)[\s\S]{0,220}?tab\s*=\s*(\d+)""",
            RegexOption.IGNORE_CASE,
        ).findAll(script).mapNotNull { match ->
            val prefix = match.groupValues.getOrNull(1).orEmpty()
            val value = match.groupValues.getOrNull(2)?.toIntOrNull()
            if (prefix.isBlank() || value == null) null else prefix to value
        }.sortedByDescending { it.first.length }
        prefixRules.firstOrNull { query.startsWith(it.first, ignoreCase = true) }?.let { (prefix, value) ->
            query = query.drop(prefix.length)
            tab = value
        }
        return query to tab
    }

    private fun evaluateConcatenation(
        expression: String,
        sourceUrl: String,
        query: String,
        page: Int,
        tab: Int,
        loginInfo: Map<String, String>,
        variables: Map<String, String> = emptyMap(),
    ): String? {
        val parts = splitTopLevelPlus(expression)
        if (parts.isEmpty()) return null
        val result = StringBuilder()
        parts.forEach { part ->
            val value = evaluateTerm(part, sourceUrl, query, page, tab, loginInfo, variables)
                ?: return null
            result.append(value)
        }
        return result.toString()
    }

    private fun evaluateTerm(
        term: String,
        sourceUrl: String,
        query: String,
        page: Int,
        tab: Int,
        loginInfo: Map<String, String>,
        variables: Map<String, String>,
    ): String? {
        val text = term.trim().removeSuffix(";").trim()
        unquoteJsString(text)?.let { return it }
        OnlineSourceLoginConfig.expressionValue(text, sourceUrl, loginInfo)?.let { return it }
        variables[text]?.let { return it }
        return when {
            text == "key" -> query
            text == "tab" -> tab.toString()
            text == "page" -> page.toString()
            text.matches(Regex("""-?\d+""")) -> text
            text.contains("page", ignoreCase = true) && text.contains("-1") && text.contains("*10") ->
                ((page - 1) * 10).toString()
            (text.startsWith("encodeURI(", ignoreCase = true) ||
                text.startsWith("encodeURIComponent(", ignoreCase = true)) &&
                text.contains("key", ignoreCase = true) -> encodeUrlComponent(query)
            text.startsWith("java.put(", ignoreCase = true) && text.contains("key", ignoreCase = true) -> query
            text.startsWith("String(", ignoreCase = true) && text.contains("key", ignoreCase = true) -> query
            else -> null
        }
    }

    private fun splitTopLevelPlus(expression: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var depth = 0
        expression.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && quote != null -> {
                    current.append(char)
                    escaped = true
                }
                quote != null -> {
                    current.append(char)
                    if (char == quote) quote = null
                }
                char == '\'' || char == '\"' || char == '`' -> {
                    quote = char
                    current.append(char)
                }
                char == '(' -> {
                    depth++
                    current.append(char)
                }
                char == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                char == '+' && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts.map(String::trim).filter(String::isNotBlank)
    }

    private fun unquoteJsString(raw: String): String? {
        if (raw.length < 2) return null
        val quote = raw.first()
        if (quote !in charArrayOf('\'', '\"', '`') || raw.last() != quote) return null
        var value = raw.substring(1, raw.length - 1)
        value = Regex("""\\u([0-9A-Fa-f]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun encodeUrlComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun resolveUrl(sourceUrl: String, raw: String): String {
        val resolved = runCatching {
            if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                raw
            } else {
                URL(URL(sourceUrl), raw).toString()
            }
        }.getOrDefault(raw)
        return resolved.replace(Regex("""(?<!:)//+"""), "/")
    }
}
