package com.reamicro.fix.association.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ManualAssociationDraft(
    val title: String,
    val author: String = "",
    val intro: String = "",
    val source: BookSource = BookSource.QiDian,
    val words: String = "",
    val status: String = "",
    val coverUrl: String = "",
) {
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
        introError = when {
            intro.isBlank() -> "简介不能为空"
            intro.trim().length > MAX_INTRO_LENGTH -> "简介不能超过 ${MAX_INTRO_LENGTH} 字"
            else -> null
        },
        wordsError = words.takeIf { it.isBlank() }?.let { "字数不能为空" },
        statusError = status.trim().takeIf { it !in STATUS_OPTIONS }?.let { "请选择连载或完结状态" },
        coverError = coverUrl.trim().takeIf { !isCoverAddress(it) }
            ?.let { "请输入有效的 HTTP 或 HTTPS 封面地址" },
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

    companion object {
        const val MAX_INTRO_LENGTH = 2_000
        val STATUS_OPTIONS = listOf("连载", "完结")

        fun isCoverAddress(value: String): Boolean =
            value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() } &&
                value.toHttpUrlOrNull() != null

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
}

data class ManualAssociationValidation(
    val titleError: String? = null,
    val authorError: String? = null,
    val introError: String? = null,
    val sourceError: String? = null,
    val coverError: String? = null,
    val wordsError: String? = null,
    val statusError: String? = null,
) {
    val isValid: Boolean = titleError == null && authorError == null && introError == null && sourceError == null &&
        coverError == null && wordsError == null && statusError == null
    val firstError: String? = titleError ?: authorError ?: sourceError ?: wordsError ?: statusError ?: coverError ?: introError
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
    val sourceBookId: String = Integer.toHexString("manual:${source.id}:$title:$author".hashCode())

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
        tags = listOf("手动关联", source.displayName),
    )
}
