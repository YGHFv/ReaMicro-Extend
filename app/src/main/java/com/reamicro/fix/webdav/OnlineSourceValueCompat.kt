package com.reamicro.fix.webdav

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URL
import org.json.JSONObject

internal data class OnlineChapterListRuleCompat(
    val jsonPath: String,
    val sortField: String = "",
    val descending: Boolean = false,
)

internal data class OnlineUrlRequestCompat(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 将书源规则生成的相对 URL 按当前请求地址解析，绝对 URL 保持不变。 */
internal fun resolveOnlineUrlCompat(baseUrl: String, value: String): String {
    val raw = value.trim()
    if (raw.isBlank()) return ""
    return runCatching {
        when {
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            baseUrl.isBlank() -> raw
            else -> URL(URL(baseUrl), raw).toString()
        }
    }.getOrDefault(raw)
}

/** 拆分 Legado URL 末尾的 JSON 请求选项；无法完整解析时保留原 URL。 */
internal fun parseOnlineUrlRequestCompat(raw: String): OnlineUrlRequestCompat {
    val index = raw.indexOf(",{")
    if (index <= 0) return OnlineUrlRequestCompat(raw)
    val options = runCatching { JSONObject(raw.substring(index + 1)) }.getOrNull()
        ?: return OnlineUrlRequestCompat(raw)
    val headersJson = options.optJSONObject("headers") ?: return OnlineUrlRequestCompat(raw.substring(0, index))
    val headers = headersJson.keys().asSequence().associateWith { name -> headersJson.optString(name, "") }
    return OnlineUrlRequestCompat(raw.substring(0, index), headers)
}

/**
 * 兼容 QQ 阅读书源中由 book_id 和内联 JS 拼接出来的腾讯云封面地址。
 */
internal fun evaluateQqReaderCoverRule(rawRule: String, selectedValue: String): String? {
    if (!rawRule.contains("@js:", ignoreCase = true)) return null
    if (!rawRule.contains("wfqqreader-1252317822.image.myqcloud.com/cover/", ignoreCase = true)) return null
    val bookId = selectedValue.filter(Char::isDigit)
    if (bookId.isBlank()) return ""
    val bucketDigits = bookId.takeLast(3)
    val bucket = bucketDigits.toIntOrNull()?.toString() ?: bucketDigits
    return "https://wfqqreader-1252317822.image.myqcloud.com/cover/$bucket/$bookId/t7_$bookId.webp"
}

/**
 * 兼容“先用 JS 重组 JSON，再用 JSONPath 取章节”的目录规则。
 *
 * 这里只解析明确的数组别名包装和数字字段排序，不执行任意 JS；无法识别时返回 null，
 * 调用方继续使用原有 JSONPath 逻辑，避免影响其他书源。
 */
internal fun resolveOnlineChapterListRuleCompat(rawRule: String): OnlineChapterListRuleCompat? {
    val text = rawRule.trim()
    if (!text.startsWith("<js>", ignoreCase = true)) return null
    val closeMatch = Regex("</js>", RegexOption.IGNORE_CASE).find(text) ?: return null
    val script = text.substring(0, closeMatch.range.last + 1)
    val tailRule = text.substring(closeMatch.range.last + 1).trim()
    if (tailRule.isBlank()) return null

    val arrayAssignment = Regex(
        """(?is)(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*data((?:\.[A-Za-z_$][\w$]*)+)\s*\|\|\s*\[\]""",
    ).find(script) ?: return null
    val arrayVariable = arrayAssignment.groupValues[1]
    val sourcePath = arrayAssignment.groupValues[2].trimStart('.')
    if (sourcePath.isBlank()) return null

    val output = Regex(
        """(?is)JSON\.stringify\s*\(\s*\{\s*([A-Za-z_$][\w$]*)\s*:\s*([A-Za-z_$][\w$]*)\s*\}\s*\)""",
    ).find(script) ?: return null
    val outputName = output.groupValues[1]
    if (output.groupValues[2] != arrayVariable) return null
    val outputPrefix = "$.${outputName}"
    if (!tailRule.startsWith(outputPrefix)) return null
    val jsonPath = "$.${sourcePath}" + tailRule.removePrefix(outputPrefix)

    val sortCall = Regex(
        """(?is)\b${Regex.escape(arrayVariable)}\.sort\s*\(\s*function\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+(.+?)\s*;?\s*\}\s*\)""",
    ).find(script)
    val sort = sortCall?.let { match ->
        val firstArgument = match.groupValues[1]
        val secondArgument = match.groupValues[2]
        val operands = Regex(
            """Number\s*\(\s*([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\s*\)""",
            RegexOption.IGNORE_CASE,
        ).findAll(match.groupValues[3]).take(2).toList()
        if (operands.size != 2) return@let null
        val leftVariable = operands[0].groupValues[1]
        val rightVariable = operands[1].groupValues[1]
        val field = operands[0].groupValues[2]
        if (field != operands[1].groupValues[2]) return@let null
        when {
            leftVariable == secondArgument && rightVariable == firstArgument -> field to true
            leftVariable == firstArgument && rightVariable == secondArgument -> field to false
            else -> null
        }
    }
    return OnlineChapterListRuleCompat(
        jsonPath = jsonPath,
        sortField = sort?.first.orEmpty(),
        descending = sort?.second ?: false,
    )
}

internal fun applyOnlineChapterListRuleCompat(
    values: List<Any?>,
    compat: OnlineChapterListRuleCompat,
): List<Any?> {
    if (values.size < 2 || compat.sortField.isBlank()) return values
    return values.withIndex().sortedWith { left, right ->
        val leftValue = onlineChapterSortValue(left.value, compat.sortField)
        val rightValue = onlineChapterSortValue(right.value, compat.sortField)
        when {
            leftValue == null && rightValue == null -> left.index.compareTo(right.index)
            leftValue == null -> 1
            rightValue == null -> -1
            else -> {
                val compared = if (compat.descending) rightValue.compareTo(leftValue) else leftValue.compareTo(rightValue)
                if (compared != 0) compared else left.index.compareTo(right.index)
            }
        }
    }.map { it.value }
}

/**
 * 兼容章节 URL 规则中通过 baseUrl 提取 bookId，再与 chapter_id 拼接地址的内联 JS。
 */
internal fun evaluateOnlineChapterUrlRule(rawRule: String, selectedValue: String, baseUrl: String): String? {
    if (!rawRule.contains("<js>", ignoreCase = true)) return null
    if (!rawRule.contains("String(baseUrl", ignoreCase = true)) return null
    val bookId = Regex("""(?i)/books/(\d+)""").find(baseUrl)?.groupValues?.getOrNull(1) ?: return null
    val script = rawRule.substring(rawRule.indexOf("<js>", ignoreCase = true))
    val selected = selectedValue.trim()
    val directAssignment = Regex(
        """(?is)\bresult\s*=\s*['"]([^'"]*)['"]\s*\+\s*bid\s*\+\s*['"]([^'"]*)['"]\s*\+\s*result\s*(?:\+\s*['"]([^'"]*)['"])?\s*;?""",
    ).find(script)
    if (directAssignment != null) {
        val prefix = unescapeOnlineJsString(directAssignment.groupValues[1])
        val middle = unescapeOnlineJsString(directAssignment.groupValues[2])
        val suffix = unescapeOnlineJsString(directAssignment.groupValues.getOrNull(3).orEmpty())
        return prefix + bookId + middle + selected + suffix
    }
    val conditionalAssignment = Regex(
        """(?is)\bresult\s*=\s*[^;]*?\?\s*['"]\s*['"]\s*:\s*\(\s*m\s*\?\s*['"]([^'"]*)['"]\s*\+\s*m\s*\[\s*1\s*]\s*\+\s*['"]([^'"]*)['"]\s*\+\s*([A-Za-z_$][\w$]*)\s*\+\s*['"]([^'"]*)['"]\s*:\s*['"]\s*['"]\s*\)""",
    ).find(script) ?: return null
    val idVariable = conditionalAssignment.groupValues[3]
    if (!Regex("""(?is)\b${Regex.escape(idVariable)}\s*=\s*String\s*\(\s*result""").containsMatchIn(script)) return null
    val prefix = unescapeOnlineJsString(conditionalAssignment.groupValues[1])
    val middle = unescapeOnlineJsString(conditionalAssignment.groupValues[2])
    val suffix = unescapeOnlineJsString(conditionalAssignment.groupValues[4])
    return prefix + bookId + middle + selected + suffix
}

/** 仅使用强特征从末章标题推断完结，避免把普通章节或断更作品误标为完结。 */
internal fun inferOnlineStatusFromLastChapterTitle(raw: String): String {
    val title = raw.trim()
    if (title.isBlank()) return ""
    return if (
        listOf("完结感言", "完本感言", "完结撒花", "全文完", "全书完", "大结局").any(title::contains) ||
        Regex("""^新书\s*[《〈]""").containsMatchIn(title)
    ) {
        "完结"
    } else {
        ""
    }
}

/** 清理章节标题末尾明确表示更新次数的标记，例如“（三更）”，保留其他括号内容。 */
internal fun cleanOnlineChapterTitleValue(raw: String): String =
    raw.replace(
        Regex("""\s*[（(]\s*[零〇一二三四五六七八九十百两0-9]+\s*更\s*[）)]\s*$"""),
        "",
    ).trim()

/** 只移除在线正文中的明确章末标记，不影响其他括号文本。 */
internal fun cleanOnlineChapterContentValue(raw: String): String =
    raw.replace(Regex("""[（(]\s*本[章韋韦]完\s*[）)]"""), " ")

/** 从在线源 HTTP 错误响应中提取可读原因，避免界面只显示状态码。 */
internal fun onlineHttpErrorDetail(rawBody: String): String {
    val text = rawBody.trim()
    if (text.isBlank()) return ""
    val value = runCatching {
        val root = JSONObject(text)
        listOf("detail", "error", "msg", "message")
            .asSequence()
            .map { root.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
            .orEmpty()
    }.getOrDefault("")
    return value.ifBlank {
        text.takeIf { !it.startsWith("<") && it.length <= 160 }.orEmpty()
    }
}

/**
 * 统一在线书源返回的字数字段，兼容小数、万/亿单位以及缺少“字”后缀的值。
 */
internal fun formatOnlineWordCountValue(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    val compact = text.replace(",", "").replace(Regex("\\s+"), "")
        .trimStart('.', '·', '•', '。', ':', '：')
    if (Regex("^[零〇一二三四五六七八九十百千万亿两]+(?:字)?$").matches(compact)) return ""
    val unitMatch = Regex("^([0-9]+(?:\\.[0-9]+)?)(万|亿)(?:字)?$").matchEntire(compact)
    if (unitMatch != null) {
        val number = normalizeOnlineDecimal(unitMatch.groupValues[1])
        return if (number.isBlank() || number == "0") "" else "$number${unitMatch.groupValues[2]}字"
    }
    val plain = compact.removeSuffix("字").toBigDecimalOrNull()
    if (plain != null) {
        if (plain <= BigDecimal.ZERO) return ""
        if (plain >= BigDecimal("10000")) {
            val tenThousands = plain.divide(BigDecimal("10000"), 1, RoundingMode.HALF_UP)
            return "${normalizeOnlineDecimal(tenThousands.toPlainString())}万字"
        }
        return "${normalizeOnlineDecimal(plain.toPlainString())}字"
    }
    return text
}

internal fun isOnlineChapterCountSelector(rawRule: String): Boolean {
    val normalized = rawRule.lowercase()
    return listOf(
        "chapter_count",
        "chaptercount",
        "chapter_num",
        "chapternum",
        "total_chapters",
        "totalchapter",
        "chapters_count",
    ).any(normalized::contains)
}

/**
 * 在线搜索相关性：书名精确匹配优先，其次作者精确匹配，再按包含关系排序。
 */
internal fun onlineSearchRelevanceScore(query: String, name: String, author: String): Int {
    val needle = normalizeOnlineSearchValue(query)
    if (needle.isBlank()) return 0
    val normalizedName = normalizeOnlineSearchValue(name)
    val normalizedAuthor = normalizeOnlineSearchValue(author)
    return when {
        normalizedName == needle -> 1_000
        normalizedAuthor == needle -> 900
        normalizedName.startsWith(needle) -> 800
        normalizedName.contains(needle) -> 700
        normalizedAuthor.startsWith(needle) -> 600
        normalizedAuthor.contains(needle) -> 500
        else -> 0
    }
}

private fun normalizeOnlineSearchValue(value: String): String =
    value.trim().lowercase().replace(Regex("""[\s·•・:：,，。!！?？《》〈〉【】\[\]()（）_-]+"""), "")

private fun normalizeOnlineDecimal(raw: String): String =
    raw.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()

private fun onlineChapterSortValue(value: Any?, field: String): BigDecimal? =
    (value as? JSONObject)?.opt(field)?.toString()?.trim()?.toBigDecimalOrNull()

private fun unescapeOnlineJsString(raw: String): String =
    raw.replace("\\/", "/")
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
