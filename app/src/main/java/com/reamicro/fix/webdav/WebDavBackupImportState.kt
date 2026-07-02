package com.reamicro.fix.webdav

internal data class WebDavBackupSnapshot(
    val book: Any,
    val backup: Any,
)

internal data class WebDavImportSource(
    val sourceUrl: String,
    val sourceSize: Long?,
)
