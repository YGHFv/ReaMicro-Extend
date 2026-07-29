package com.reamicro.fix.online

internal object OnlineParagraphIndexMapper {
    private val titlePattern = Regex(
        "^(?:第.{0,30}[章节卷回]|[Cc]hapter\\s*\\d|序[章幕]|楔子|引子|尾声|番外)",
    )
    private val imagePattern = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tagPattern = Regex("<[^>]+>")

    data class Paragraph(
        val paraIndex: Int,
        val lineIndex: Int,
        val text: String,
        val imageOnly: Boolean,
    )

    fun mapFullV2Content(content: String): List<Paragraph> {
        val textParagraphs = ArrayList<Paragraph>()
        val imageParagraphs = ArrayList<Paragraph>()
        var textIndex = 0
        var imageIndex = 0
        var firstMeaningfulTextObserved = false
        content.replace("\r", "")
            .split('\n')
            .forEachIndexed { lineIndex, line ->
                val hasImage = imagePattern.containsMatchIn(line)
                val text = tagPattern.replace(line, "").trim()
                if (hasImage && text.isBlank()) {
                    imageParagraphs += Paragraph(
                        paraIndex = IMAGE_PARAGRAPH_BASE + imageIndex++,
                        lineIndex = lineIndex,
                        text = "",
                        imageOnly = true,
                    )
                    return@forEachIndexed
                }
                if (text.isBlank()) return@forEachIndexed
                if (!firstMeaningfulTextObserved) {
                    firstMeaningfulTextObserved = true
                    if (titlePattern.containsMatchIn(text)) return@forEachIndexed
                }
                textParagraphs += Paragraph(
                    paraIndex = textIndex++,
                    lineIndex = lineIndex,
                    text = text,
                    imageOnly = false,
                )
            }
        return textParagraphs + imageParagraphs
    }

    fun commentsForCounts(content: String, counts: Map<Int, Int>): List<Pair<Paragraph, Int>> {
        if (counts.isEmpty()) return emptyList()
        val paragraphs = mapFullV2Content(content).associateBy(Paragraph::paraIndex)
        return counts.entries.mapNotNull { (paraIndex, count) ->
            if (count <= 0) return@mapNotNull null
            paragraphs[paraIndex]?.let { it to count }
        }
    }

    const val IMAGE_PARAGRAPH_BASE = 20_000
}
