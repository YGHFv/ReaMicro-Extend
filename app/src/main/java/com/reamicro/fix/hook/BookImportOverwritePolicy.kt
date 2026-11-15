package com.reamicro.fix.hook

/** A matching UUID or source is more specific than a shared title. */
internal fun <T> selectImportConflict(uuid: T?, source: T?, title: T?): T? = uuid ?: source ?: title

/**
 * Native duplicate imports copy the existing entity; native imports of a cloud-only placeholder
 * otherwise delete it and insert a fresh entity. Keep the original owner in both branches.
 */
internal object BookImportOverwritePolicy {
    private val contentFields = setOf(
        "title", "subtitle", "author", "cover", "size", "cfiVersion", "embeddedFonts",
        "contentDownloaded", "updated",
    )
    private val identityFields = listOf("id", "uid", "uuid")

    fun changes(
        incoming: Map<String, Any?>,
        existing: Map<String, Any?>?,
        metadata: BookMetadataPatch,
        now: Long,
        preserveLatestOwner: Boolean = false,
    ): Map<String, Any?> {
        if (existing == null) return metadata.changes() + ("updated" to now)
        require(incoming.keys == existing.keys) { "覆盖图书字段不完整" }
        require((existing["id"] as? Number)?.toLong()?.let { it > 0L } == true) { "覆盖图书主键无效" }
        require(incoming["uid"] == existing["uid"] && incoming["uuid"] == existing["uuid"]) {
            "覆盖导入图书归属发生变化"
        }
        require(incoming["id"] == existing["id"] || (incoming["id"] as? Number)?.toLong() == 0L) {
            "覆盖导入正在操作另一条书架记录"
        }
        val sameOwner = identityFields.all { incoming[it] == existing[it] }
        val restoreOwner = !sameOwner || preserveLatestOwner
        val base = if (restoreOwner) existing else incoming
        val changes = buildMap {
            if (restoreOwner) {
                putAll(existing)
                contentFields.forEach { field -> if (incoming.containsKey(field)) put(field, incoming[field]) }
                if ((existing["created"] as? Number)?.toLong()?.let { it > 0L } != true) {
                    put("created", (incoming["created"] as? Number)?.toLong()?.takeIf { it > 0L } ?: now)
                }
                if ((existing["uri"] as? String).isNullOrBlank()) put("uri", incoming["uri"])
            }
            putAll(metadata.changes().filterKeys { it != "cloudId" && it != "publisher" })
            // A cloud-only placeholder takes the fresh-entity branch and can have an empty cover.
            // Match the native duplicate branch: a missing new cover retains the original.
            val importedCover = metadata.cover?.takeIf(String::isNotBlank) ?: incoming["cover"] as? String
            if (importedCover.isNullOrBlank() && !(existing["cover"] as? String).isNullOrBlank()) {
                put("cover", existing["cover"])
            }
            put("cloudId", base["cloudId"])
            put("publisher", base["publisher"])
            put("updated", now)
        }
        val result = incoming + changes
        require(identityFields.all { result[it] == existing[it] }) { "覆盖导入更改了原图书身份" }
        return changes
    }
}
