package com.reamicro.fix.core

/** B2/B4 Book 的 27 字段均由构造器与 toString 交叉核实，禁止按类型找值。 */
class B2BookAssociation(loader: ClassLoader) {
    private val copy = HostExactRecordCopy(loader.hostClass("app.zhendong.reamicro.data.db.entity.Book"), properties)

    init {
        check(HostSymbols.abiProfileId in setOf("2.3.3-b2", "2.3.3-b4")) { "Unverified association host" }
    }

    fun snapshot(book: Any) = copy.snapshot(book)

    fun withoutAssociation(book: Any): Any =
        copy.copy(book, mapOf("cloudId" to 0L, "publisher" to ""))

    companion object {
        val properties = listOf(
            "id", "uuid", "uid", "title", "subtitle", "author", "cover", "size", "uri", "group",
            "created", "cfiVersion", "embeddedFonts", "epubcfi", "chapter", "progress", "total",
            "listeningTotal", "finished", "updated", "pinnedAt", "cloudId", "backupType",
            "backupId", "backupCode", "publisher", "contentDownloaded",
        )
    }
}
