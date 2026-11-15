package com.reamicro.fix.hook

internal data class BookMetadataPatch(
    val title: String? = null,
    val subtitle: String? = null,
    val author: String? = null,
    val cover: String? = null,
    val size: Long? = null,
    val cloudId: Long? = null,
    val publisher: String? = null,
) {
    fun changes(): Map<String, Any?> = buildMap {
        title?.takeIf(String::isNotBlank)?.let { put("title", it) }
        subtitle?.let { put("subtitle", it) }
        author?.let { put("author", it) }
        cover?.takeIf(String::isNotBlank)?.let { put("cover", it) }
        this@BookMetadataPatch.size?.takeIf { it > 0L }?.let { put("size", it) }
        cloudId?.let { put("cloudId", it) }
        publisher?.let { put("publisher", it) }
    }

    fun mergeWith(fallback: BookMetadataPatch): BookMetadataPatch = BookMetadataPatch(
        title = title ?: fallback.title,
        subtitle = subtitle ?: fallback.subtitle,
        author = author ?: fallback.author,
        cover = cover ?: fallback.cover,
        size = size ?: fallback.size,
        cloudId = cloudId ?: fallback.cloudId,
        publisher = publisher ?: fallback.publisher,
    )

    fun withSize(value: Long?): BookMetadataPatch =
        if (value != null && value > 0L) copy(size = value) else this
}

/** Both hosts retain these two values verbatim when replacing an existing book's content. */
internal fun BookMetadataPatch.withPreservedAssociation(cloudId: Long?, publisher: String?): BookMetadataPatch =
    if (cloudId == null) this else copy(cloudId = cloudId, publisher = publisher.orEmpty())
