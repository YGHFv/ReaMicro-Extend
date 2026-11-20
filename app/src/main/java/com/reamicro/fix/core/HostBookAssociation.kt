package com.reamicro.fix.core

/** Record properties and constructor slots come from the current APK. */
class HostBookAssociation {
    private val copy = requireNotNull(HostBookBindings.current) { "Book record contract unavailable" }


    fun snapshot(book: Any) = copy.snapshot(book)

    fun withoutAssociation(book: Any): Any =
        copy.withoutAssociation(book)

    companion object {
        val properties = listOf(
            "id", "uuid", "uid", "title", "subtitle", "author", "cover", "size", "uri", "group",
            "created", "cfiVersion", "embeddedFonts", "epubcfi", "chapter", "progress", "total",
            "listeningTotal", "finished", "updated", "pinnedAt", "cloudId", "backupType",
            "backupId", "backupCode", "publisher", "contentDownloaded",
        )
    }
}
