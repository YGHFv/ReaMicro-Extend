package top.yukonga.scripta.editor.input

import androidx.compose.ui.platform.ClipEntry

internal expect fun plainTextClipEntry(text: String): ClipEntry

internal expect fun ClipEntry.plainText(): String?
