package com.reamicro.fix.epub.editor

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

internal class EpubStorageFiles(directory: File) {
    val root: File = directory.canonicalFile.also { require(it.isDirectory) { "图书目录不存在" } }

    fun child(path: String, allowRoot: Boolean = false): File {
        val normalized = path.replace('\\', '/')
        require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "不能使用绝对路径" }
        val file = File(root, normalized).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && (allowRoot || file != root)) { "路径超出图书目录" }
        return file
    }

    fun relative(file: File): String {
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(root.toPath()) && canonical != root) { "路径超出图书目录" }
        return canonical.relativeTo(root).invariantSeparatorsPath
    }

    fun directory(path: String): File = child(path, allowRoot = true).also {
        require(it.isDirectory) { "目录不存在" }
    }

    fun visibleFiles(): List<File> {
        data class SortEntry(val file: File, val path: String, val group: String, val typeOrder: Int)
        val entries = root.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith(".reamicro-") }

            .filter { it.name != "mimetype" && it.name != ".font-obfuscated" && !it.name.startsWith(".reamicro-") &&
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .map {
                val path = relative(it)
                SortEntry(it, path, path.substringBeforeLast('/', ""), structure130TypeOrder(it.name))
            }.toList()
        val spineOrder = EpubStructureOrder.spineOrder(root, entries.map { it.file })

        return entries.sortedWith(compareBy<SortEntry> { it.group }.thenBy { it.typeOrder }
            .thenBy { if (usesScriptaEpubEditor(it.path)) spineOrder[it.path] ?: Int.MAX_VALUE else Int.MAX_VALUE }
            .thenComparator { a, b -> EpubStructureOrder.compareNames(a.file.name, b.file.name) })
            .map { it.file }
    }
    fun byteSize(): Long = root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !it.name.startsWith(".reamicro-") }
        .sumOf { it.length() }

    fun rename(path: String, name: String): File {
        val source = child(path)
        require(source.isFile) { "文件不存在" }
        val target = File(source.parentFile, validName(name))
        require(!target.exists()) { "同名文件已存在" }

        val expected = VerifiedFileIO.digest(source)
        val moved = Files.move(source.toPath(), target.toPath()).toFile()
        VerifiedFileIO.requireMatches(moved, expected)
        VerifiedFileIO.requireMissing(source)
        return moved
    }

    fun create(path: String, name: String): File {
        val target = File(directory(path), validName(name))
        require(target.createNewFile()) { "同名文件已存在" }
        VerifiedFileIO.requireMatches(target, VerifiedFileIO.Digest(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
        return target
    }

    fun importFile(path: String, name: String, input: InputStream, checkCancelled: () -> Unit = {}): File {
        val dir = directory(path)
        val cleanName = validName(name)
        val temp = File.createTempFile(".reamicro-import-", ".tmp", dir)
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_IMPORT_BYTES) { "导入文件超过 256 MB 安全上限" }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            checkCancelled()
            val expected = VerifiedFileIO.digest(temp)
            val stem = cleanName.substringBeforeLast('.', cleanName)
            val ext = cleanName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            for (i in 0..999) {
                val target = File(dir, if (i == 0) cleanName else "$stem-$i$ext")
                try {
                    val moved = Files.move(temp.toPath(), target.toPath()).toFile()
                    VerifiedFileIO.requireMatches(moved, expected)
                    return moved
                } catch (_: java.nio.file.FileAlreadyExistsException) {

                }
            }
            error("无法生成唯一文件名")
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 256L * 1024L * 1024L

        fun validName(name: String): String {
            val clean = name.trim()
            require(clean.isNotEmpty() && clean != "." && clean != ".." && clean.length <= 180) { "文件名无效" }
            require(clean.none { it < ' ' || it in "\\/:*?\"<>|" }) { "文件名含有不允许的字符" }
            require(!clean.startsWith(".reamicro-")) { "不能使用内部暂存文件名" }
            return clean
        }

        internal fun structure130TypeOrder(name: String): Int {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val stem = name.substringBeforeLast('.', name)
            return when (extension) {
                "opf" -> 1
                "ncx" -> 2
                "xml" -> if (stem == "container") 3 else 100
                "css" -> 4
                "jpg", "jpeg", "png", "gif", "webp", "svg" -> 8
                "html", "xhtml", "htm" -> 9
                "ttf", "otf" -> 10
                else -> if (stem == "bookmarks") 7 else 100
            }
        }
    }
}
