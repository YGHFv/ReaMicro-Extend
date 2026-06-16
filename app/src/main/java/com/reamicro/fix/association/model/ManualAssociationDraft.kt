package com.reamicro.fix.association.model

data class ManualAssociationDraft(
    val title: String,
    val author: String = "",
    val intro: String = "",
    val source: BookSource = BookSource.QiDian,
    val words: String = "",
    val status: String = "",
    val coverUrl: String = "",
) {
    companion object {
        const val MAX_INTRO_LENGTH = 2_000
        val STATUS_OPTIONS = listOf("", "连载中", "已完结")

        fun fromFile(
            fileMetadata: AssociatedFileMetadata,
            defaultSource: BookSource = BookSource.QiDian,
        ): ManualAssociationDraft = ManualAssociationDraft(
            title = fileMetadata.bestTitle,
            author = fileMetadata.author,
            intro = fileMetadata.intro,
            source = defaultSource,
        )
    }

    fun normalized(): ManualAssociationDraft = copy(
        title = title.trim(),
        author = author.trim(),
        intro = intro.trim(),
        words = words.trim(),
        status = status.trim(),
        coverUrl = coverUrl.trim(),
    )

    fun validate(): ManualAssociationValidation = ManualAssociationValidation(
        titleError = title.trim().takeIf { it.isEmpty() }?.let { "书名不能为空" },
        authorError = author.trim().takeIf { it.isEmpty() }?.let { "作者不能为空" },
        introError = intro.trim().takeIf { it.length > MAX_INTRO_LENGTH }?.let { "简介不能超过 ${MAX_INTRO_LENGTH} 字" },
    )

    fun toCandidate(): ManualAssociationCandidate {
        val normalized = normalized()
        val validation = normalized.validate()
        require(validation.isValid) { validation.firstError ?: "手动关联信息不完整" }
        return ManualAssociationCandidate(
            title = normalized.title,
            author = normalized.author,
            intro = normalized.intro,
            source = normalized.source,
            words = normalized.words,
            status = normalized.status,
            coverUrl = normalized.coverUrl,
        )
    }

}

data class ManualAssociationValidation(
    val titleError: String? = null,
    val authorError: String? = null,
    val introError: String? = null,
    val sourceError: String? = null,
) {
    val isValid: Boolean = titleError == null && authorError == null && introError == null && sourceError == null
    val firstError: String? = titleError ?: authorError ?: introError ?: sourceError
}

data class ManualAssociationCandidate(
    val title: String,
    val author: String,
    val intro: String,
    val source: BookSource,
    val words: String = "",
    val status: String = "",
    val coverUrl: String = "",
) {
    val sourceBookId: String = Integer.toHexString("manual:${source.id}:${title}:${author}".hashCode())

    fun toSearchResult(): BookSearchResult = BookSearchResult(
        title = title,
        author = author,
        source = source,
        sourceBookId = sourceBookId,
        coverUrl = coverUrl,
        intro = intro,
        words = words,
        status = status,
        displaySourceName = source.displayName,
        tags = listOfNotNull("手动关联", source.displayName),
    )
}
