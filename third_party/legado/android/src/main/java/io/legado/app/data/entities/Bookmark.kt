package io.legado.app.data.entities


data class Bookmark(
    val time: Long = System.currentTimeMillis(),
    val bookName: String = "",
    val bookAuthor: String = "",
    /** 创建时的源（源指纹）：跳转校验用。bookmarks 仍按书名+作者关联，换源后保留。 */
    val bookUrl: String = "",
    var chapterIndex: Int = 0,
    var chapterPos: Int = 0,
    var chapterName: String = "",
    var bookText: String = "",
    var content: String = ""
)
