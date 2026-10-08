package com.reamicro.fix.reader

import com.google.re2j.Pattern

internal object ReaderHighlightRegex {
    const val MAX_PATTERN = 512
    const val MAX_INPUT = 32_768
    const val MAX_RESULTS = 1024
    const val HELP = "高亮正则使用 RE2/J，不支持环视、反向引用、占有量词；默认的 $ 只匹配输入末尾（不会匹配末尾换行之前）。模式最多 512 字符，每段输入最多 32768 字符、结果最多 1024 项。超限跳过，不截断文本匹配。"
    data class Result(val ranges: List<IntRange>, val error: String? = null)
    private data class Compiled(val pattern: Pattern?, val error: String?)
    private val cache = object : LinkedHashMap<Pair<String, Boolean>, Compiled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, Compiled>?): Boolean = size > 64
    }

    fun validationError(pattern: String): String? = compile(pattern, false).error

    fun find(text: String, pattern: String, crossParagraph: Boolean): Result {
        val compiled = compile(pattern, crossParagraph)
        compiled.error?.let { return Result(emptyList(), it) }
        if (text.length > MAX_INPUT) return Result(emptyList(), "正则输入超过 $MAX_INPUT 字符，本段已跳过")
        val matcher = compiled.pattern!!.matcher(text)
        val ranges = ArrayList<IntRange>()
        var matches = 0
        while (matcher.find()) {
            if (++matches > MAX_RESULTS) return Result(emptyList(), "正则结果超过 $MAX_RESULTS 项，本段已跳过")
            if (matcher.end() > matcher.start()) ranges.add(matcher.start()..matcher.end())
        }
        return Result(ranges)
    }

    @Synchronized
    private fun compile(source: String, crossParagraph: Boolean): Compiled {
        if (source.isBlank() || source.length > MAX_PATTERN) return Compiled(null, "正则模式须为 1..$MAX_PATTERN 字符")
        return cache.getOrPut(source to crossParagraph) {
            val error = complexityError(source)
            if (error != null) return@getOrPut Compiled(null, error)
            try {
                Compiled(Pattern.compile(source, if (crossParagraph) Pattern.DOTALL else 0), null)
            } catch (error: com.google.re2j.PatternSyntaxException) {
                Compiled(null, "RE2/J 不支持或无法解析此正则，请修改规则：${error.message?.take(160)}")
            }
        }
    }

    private fun complexityError(source: String): String? {
        if (source.isBlank() || source.length > MAX_PATTERN) return "正则模式须为 1..$MAX_PATTERN 字符"
        // 编译前保守限制嵌套和计数量词展开，避免短模式构建出巨大的自动机。
        var depth = 0
        var expansion = source.length.toLong()
        var index = 0
        var inClass = false
        while (index < source.length) {
            val c = source[index++]
            if (c == '\\') { index++; continue }
            if (c == '[') inClass = true
            if (c == ']') inClass = false
            if (inClass) continue
            if (c == '(' && ++depth > 16) return "正则分组嵌套不能超过 16 层"
            if (c == ')') depth--
            if (c == '{') {
                val end = source.indexOf('}', index)
                if (end < 0) continue
                val count = source.substring(index, end).split(',').mapNotNull { it.toLongOrNull() }.maxOrNull() ?: continue
                if (count > 100 || count > 0 && expansion > 32_768 / count) return "正则计数量词展开过大，请简化规则"
                expansion *= count.coerceAtLeast(1)
            }
        }
        return null
    }
}
