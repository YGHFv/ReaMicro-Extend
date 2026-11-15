package com.reamicro.fix.hook

import com.reamicro.fix.online.download.copyOnlineCompletionFileAtomically
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** Preserve the bytes behind a local cover reference before replacing the old content directory. */
internal fun preserveImportCoverFile(
    originalDirectory: File,
    stagedDirectory: File,
    previousCover: String?,
    incomingCover: String?,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
): String? {
    if (!incomingCover.isNullOrBlank() || previousCover.isNullOrBlank()) return null
    val relative = previousCover
    if (File(relative).isAbsolute || '\\' in relative || ':' in relative ||
        relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    checkCurrent()
    val original = originalDirectory.canonicalFile
    val staged = stagedDirectory.canonicalFile
    if (original == staged) return relative
    val source = File(original, relative)
    // Reject redirected parents as well as a symlink at the leaf.
    if (source.canonicalFile != source.absoluteFile ||
        !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
    check(source.toPath().startsWith(original.toPath())) { "原封面超出图书目录" }
    check(staged.isDirectory) { "导入暂存目录不可用" }
    var destination = File(staged, relative)
    val redirected = destination.canonicalFile != destination.absoluteFile
    val blockedParent = generateSequence(destination.parentFile) { it.parentFile }
        .takeWhile { it != staged }.any { it.exists() && !it.isDirectory }
    if (redirected || blockedParent || Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        val extension = source.extension.takeIf { it.length in 1..10 && it.all { char -> char.isLetterOrDigit() } }
            ?.let { ".$it" }.orEmpty()
        do {
            // This is durable book content; export intentionally excludes ".reamicro-" staging files.
            destination = File(staged, "reamicro-preserved-cover-${UUID.randomUUID()}$extension")
        } while (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
    copyOnlineCompletionFileAtomically(source, destination, commit, checkCurrent)
    return destination.relativeTo(staged).invariantSeparatorsPath
}
