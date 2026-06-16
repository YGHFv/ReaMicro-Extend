package com.reamicro.fix.association.model

data class AssociatedFileMetadata(
    val title: String,
    val author: String = "",
    val intro: String = "",
    val fileName: String = "",
) {
    val bestTitle: String = title.ifBlank { fileName.substringBeforeLast('.').trim() }
}
