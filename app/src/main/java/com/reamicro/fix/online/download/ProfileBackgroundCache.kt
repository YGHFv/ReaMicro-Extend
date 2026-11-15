package com.reamicro.fix.online.download

import com.reamicro.fix.hook.webdav.PROFILE_BACKGROUND_FILES_DIR
import java.io.File

/** A freshly copied image may still be waiting for its settings commit. */
internal fun orphanProfileBackgroundFiles(
    filesDir: File,
    activeImagePath: String,
    now: Long = System.currentTimeMillis(),
): List<File> {
    val dir = File(filesDir, PROFILE_BACKGROUND_FILES_DIR)
    if (!dir.isDirectory) return emptyList()
    val active = activeImagePath.trim().takeIf { it.isNotBlank() }
        ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
    val cutoff = now - 24L * 60L * 60_000L
    return dir.listFiles().orEmpty().filter { file ->
        file.isFile && file.lastModified() in 1..cutoff &&
            (active == null || runCatching { file.canonicalPath }.getOrElse { file.path } != active)
    }
}
