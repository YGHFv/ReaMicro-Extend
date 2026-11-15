package com.reamicro.fix.hook


internal data class BookCopyPatch(
    val title: String? = null,
    val subtitle: String? = null,
    val author: String? = null,
    val cover: String? = null,
    val size: Long? = null,
    val updated: Long? = null,
    val pinnedAt: Long? = null,
    val cloudId: Long? = null,
    val backupType: Int? = null,
    val backupId: String? = null,
    val backupCode: String? = null,
    val publisher: String? = null,
)

internal object BookCopyCompat {
    fun copy(book: Any, patch: BookCopyPatch): Any {
        val changes = buildMap<String, Any?> {
            patch.title?.let { put("title", it) }
            patch.subtitle?.let { put("subtitle", it) }
            patch.author?.let { put("author", it) }
            patch.cover?.takeIf(String::isNotBlank)?.let { put("cover", it) }
            patch.size?.takeIf { it > 0 }?.let { put("size", it) }
            patch.updated?.takeIf { it > 0 }?.let { put("updated", it) }
            patch.pinnedAt?.let { put("pinnedAt", it) }
            patch.cloudId?.let { put("cloudId", it) }
            patch.backupType?.let { put("backupType", it) }
            patch.backupId?.let { put("backupId", it) }
            patch.backupCode?.let { put("backupCode", it) }
            patch.publisher?.let { put("publisher", it) }
        }
        return requireNotNull(com.reamicro.fix.core.HostBookBindings.current).copy(book, changes).also { requireSameStorageIdentity(book, it) }
    }
    internal fun requireSameStorageIdentity(before: Any, after: Any) {
        val host = requireNotNull(com.reamicro.fix.core.HostBookBindings.current)
        val left = host.snapshot(before)
        val right = host.snapshot(after)
        check(listOf("id", "uuid", "uid").all { left.getValue(it) == right.getValue(it) }) {
            "拒绝改变书籍身份字段，已阻止书架回写"
        }
    }
}
