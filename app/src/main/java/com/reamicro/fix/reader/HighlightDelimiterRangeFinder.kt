package com.reamicro.fix.reader

/**
 * 区间匹配：高亮「起始符 … 结束符」之间的部分。
 *
 * 用户在规则里填一串界定符，对半分成起止两半。填「【】」就是高亮以【开头、以】结尾的片段，
 * 填「<<>>」是 << 到 >>，填单个字符「|」则起止同符。高亮范围含界定符本身，
 * 与双引号对话规则连引号一起高亮的行为保持一致。
 *
 * 这里只负责在一段文本里找范围。跨段与否由调用方决定：不跨段就直接传当前段文本，
 * 跨段则由 [com.reamicro.fix.reader.CrossParagraphRangeMapper] 把上下文拼起来后再裁回当前段。
 */
object HighlightDelimiterRangeFinder {

    /** 一对界定符。[open] 与 [close] 都非空；起止同符时两者相等。 */
    data class Delimiters(val open: String, val close: String)

    /**
     * 把用户填的界定符串对半分。
     *
     * 长度为偶数时前一半是起始符、后一半是结束符；长度为奇数（含单字符）时起止同符，整串既当起也当止。
     * 空白输入返回 null，调用方按「没配」处理。
     */
    fun parseDelimiters(pattern: String): Delimiters? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length % 2 != 0) return Delimiters(trimmed, trimmed)
        val half = trimmed.length / 2
        return Delimiters(trimmed.substring(0, half), trimmed.substring(half))
    }

    /**
     * 找出 [text] 里所有「起始符 … 结束符」范围。
     *
     * 返回的 IntRange 沿用本模块高亮范围的既有约定：first 是起点，**last 存排他终点**
     * （即 `start..endExclusive`，见 ReaderDialogueHighlightHook.exclusiveRange），
     * 不是 Kotlin IntRange 通常的闭区间语义。
     *
     * 不重叠：一段匹配结束后从其右端继续找下一段。找不到结束符的起始符直接跳过，不会把剩余全文吃掉。
     */
    fun findRanges(text: String, pattern: String, maxParagraphs: Int = 1): List<IntRange> {
        val delimiters = parseDelimiters(pattern) ?: return emptyList()
        return findRanges(text, delimiters, maxParagraphs)
    }

    fun findRanges(text: String, delimiters: Delimiters, maxParagraphs: Int = 1): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val open = delimiters.open
        val close = delimiters.close
        if (open.isEmpty() || close.isEmpty()) return emptyList()
        val separatorLimit = maxParagraphs.coerceAtLeast(1) - 1
        val ranges = ArrayList<IntRange>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(open, index)
            if (start < 0) break
            val matched = findPair(text, start, open, close, separatorLimit)
            if (matched == null) {
                // 这个起始符找不到配对的结束符：跳过它继续找，避免把后面整段误高亮。
                index = start + open.length
                continue
            }
            ranges.add(matched)
            index = matched.last
        }
        return ranges
    }

    /**
     * 从 [openStart] 处的起始符出发找出一对界定符，允许跨过至多 [separatorLimit] 个换行。
     *
     * 就近配对：扫描途中又遇到起始符时，改从那个新起点重来。这样「【一 然后【二】」只高亮
     * 「【二】」，一个落单的起始符不会把它到下一个结束符之间的内容全吃掉。
     * 起止同符（例如「|」）没有嵌套概念，只按顺序两两配对，且不接受空内容。
     */
    private fun findPair(
        text: String,
        openStart: Int,
        open: String,
        close: String,
        separatorLimit: Int,
    ): IntRange? {
        val nestable = open != close
        var start = openStart
        while (true) {
            val contentStart = start + open.length
            var separators = 0
            var index = contentStart
            var restart = -1
            while (index < text.length) {
                if (text[index] == '\n') {
                    separators++
                    if (separators > separatorLimit) return null
                    index++
                    continue
                }
                // 起止同符时要求内容非空，否则「||」会被当成一段；不同符则「【】」算合法空区间。
                if (text.startsWith(close, index) && (nestable || index > contentStart)) {
                    return start..(index + close.length)
                }
                if (nestable && text.startsWith(open, index)) {
                    restart = index
                    break
                }
                index++
            }
            if (restart < 0) return null
            start = restart
        }
    }
}
