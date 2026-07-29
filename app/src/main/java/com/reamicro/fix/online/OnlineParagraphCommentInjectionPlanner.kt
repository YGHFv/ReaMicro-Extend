package com.reamicro.fix.online

internal data class OnlineParagraphCommentInsertion(
    val stableId: String,
    val offset: Int,
    val comment: OnlineParagraphCommentCount,
)

internal data class OnlineParagraphCommentInjectionPlan(
    val originalText: String,
    val text: String,
    val insertions: List<OnlineParagraphCommentInsertion>,
) {
    fun shiftedOffset(originalOffset: Int, includeInsertionAtOffset: Boolean = false): Int {
        val bounded = originalOffset.coerceIn(0, originalText.length)
        val insertedBefore = insertions.count { insertion ->
            insertion.offset < bounded || (includeInsertionAtOffset && insertion.offset == bounded)
        }
        return bounded + insertedBefore
    }

    fun shiftedRange(start: Int, endExclusive: Int): IntRange {
        val boundedStart = start.coerceIn(0, originalText.length)
        val boundedEnd = endExclusive.coerceIn(boundedStart, originalText.length)
        return shiftedOffset(
            boundedStart,
            includeInsertionAtOffset = true,
        )..shiftedOffset(
            boundedEnd,
            includeInsertionAtOffset = true,
        )
    }

    fun insertedOffset(insertion: OnlineParagraphCommentInsertion): Int {
        val insertionIndex = insertions.indexOf(insertion)
        require(insertionIndex >= 0) { "插入项不属于当前规划" }
        return insertion.offset + insertionIndex
    }
}

internal object OnlineParagraphCommentInjectionPlanner {
    const val REPLACEMENT_CHAR: Char = '\uFFFD'
    const val RUNTIME_ID_PREFIX = "reamicro-online-comment:"

    fun plan(
        text: String,
        comments: List<OnlineParagraphCommentCount>,
        existingIds: Set<String> = emptySet(),
    ): OnlineParagraphCommentInjectionPlan? {
        if (text.isBlank() || comments.isEmpty()) return null
        val normalizedText = normalizedTextWithSourceMap(text)
        if (normalizedText.text.isBlank()) return null

        // ContentDom 会把 EPUB 的每个 <p> 拆成独立实例，因此不能要求一整个章节文本里存在完整段落。
        // 先匹配段评文本被当前 ContentDom 文本包含的情况，再保留章节级文本的顺序匹配作为回退。
        var normalizedSearchFrom = 0
        val planned = comments
            .asSequence()
            .filter { it.count > 0 && it.paragraphText.isNotBlank() }
            .sortedWith(compareBy<OnlineParagraphCommentCount> { it.paraIndex }.thenBy { it.paragraphHash })
            .mapNotNull { comment ->
                val stableId = stableId(comment)
                if (stableId in existingIds) return@mapNotNull null
                val paragraph = normalizeText(comment.paragraphText)
                if (paragraph.isBlank()) return@mapNotNull null
                var index = normalizedText.text.indexOf(paragraph, normalizedSearchFrom)
                if (index < 0 && normalizedSearchFrom > 0) {
                    index = normalizedText.text.indexOf(paragraph)
                }
                var matchedEnd = index + paragraph.length
                if (index < 0 && paragraph.endsWith(normalizedText.text)) {
                    // 段落被宿主拆分时，仅接受段尾片段，确保图标仍追加在原段落的末尾。
                    index = paragraph.length - normalizedText.text.length
                    matchedEnd = normalizedText.text.length
                }
                if (index < 0 || matchedEnd <= 0) return@mapNotNull null
                val sourceEnd = (normalizedText.sourceIndices.getOrNull(matchedEnd - 1) ?: return@mapNotNull null) + 1
                normalizedSearchFrom = (index + paragraph.length).coerceAtMost(normalizedText.text.length)
                OnlineParagraphCommentInsertion(
                    stableId = stableId,
                    offset = sourceEnd.coerceIn(0, text.length),
                    comment = comment,
                )
            }
            .distinctBy { it.stableId }
            .sortedWith(compareBy<OnlineParagraphCommentInsertion> { it.offset }.thenBy { it.stableId })
            .toList()
        if (planned.isEmpty()) return null

        val nextText = StringBuilder(text.length + planned.size)
        var sourceCursor = 0
        planned.groupBy { it.offset }.toSortedMap().forEach { (offset, sameOffset) ->
            nextText.append(text, sourceCursor, offset)
            repeat(sameOffset.size) { nextText.append(REPLACEMENT_CHAR) }
            sourceCursor = offset
        }
        nextText.append(text, sourceCursor, text.length)
        return OnlineParagraphCommentInjectionPlan(text, nextText.toString(), planned)
    }

    fun stableId(comment: OnlineParagraphCommentCount): String =
        RUNTIME_ID_PREFIX + listOf(
            comment.sourceId,
            comment.bookId,
            comment.itemId,
            comment.paraIndex.toString(),
            comment.paragraphHash,
        ).joinToString("|").onlineParagraphHash().take(24)

    internal fun normalizeText(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                if (!char.isWhitespace() && char !in IGNORED_FORMAT_CHARACTERS) append(char)
            }
        }

    private fun normalizedTextWithSourceMap(value: String): NormalizedText {
        val text = StringBuilder(value.length)
        val sourceIndices = ArrayList<Int>(value.length)
        value.forEachIndexed { index, char ->
            if (!char.isWhitespace() && char !in IGNORED_FORMAT_CHARACTERS) {
                text.append(char)
                sourceIndices.add(index)
            }
        }
        return NormalizedText(text.toString(), sourceIndices)
    }

    private val IGNORED_FORMAT_CHARACTERS = setOf('\u200B', '\u200C', '\u200D', '\uFEFF')

    private data class NormalizedText(
        val text: String,
        val sourceIndices: List<Int>,
    )
}
