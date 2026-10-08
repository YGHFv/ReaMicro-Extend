package com.reamicro.fix.epub.editor

internal object EpubMetadataFields {
    data class Field(val key: String, val label: String, val allowEmpty: Boolean = false)

    val all = listOf(
        Field("title", "标题 dc:title title-type=\"main\""),
        Field("subtitle", "副标题 dc:title title-type=\"edition\"", allowEmpty = true),
        Field("author", "作者 dc:creator id=\"role\" aut"),
        Field("uuid", "标识"),
        Field("language", "语言 dc:language"),
        Field("publisher", "出版 dc:publisher"),
        Field("date", "出版日期 dc:date"),
    )

    val editable = all.filterNot { it.key == "uuid" }
    fun allowsEmpty(key: String): Boolean = editable.any { it.key == key && it.allowEmpty }

    fun validate(key: String, value: String) {
        val field = requireNotNull(editable.firstOrNull { it.key == key }) { "不支持的元数据字段" }
        require(field.allowEmpty || value.isNotBlank()) {
            "${field.label.substringBefore(" dc:")}不能为空"
        }
    }
}
