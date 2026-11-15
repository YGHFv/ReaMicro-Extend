package com.reamicro.fix.epub.editor

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale

/** Native UserStorage keeps each account's fonts in files/{uid}/fonts, including uid 0 guests. */
internal object AccountFontFiles {
    fun directory(filesDir: File, accountId: Long): File {
        require(accountId >= 0L) { "字体账号无效" }
        val directory = File(File(filesDir.canonicalFile, accountId.toString()), "fonts").absoluteFile
        require(directory.canonicalFile == directory) { "字体目录已变更，请重新进入" }
        require(!directory.exists() || directory.isDirectory) { "字体目录不可用" }
        return directory
    }

    fun files(filesDir: File, accountId: Long): List<File> {
        val fontDir = directory(filesDir, accountId)
        return fontDir.listFiles().orEmpty().filter { contains(fontDir, it) }
    }

    fun resolve(filesDir: File, accountId: Long, selection: String): File? {
        val directory = directory(filesDir, accountId)
        return FontSelectionResolution.file(selection, listOf(directory))
            ?.takeIf { contains(directory, it) }?.canonicalFile
    }

    fun contains(directory: File, file: File): Boolean =
        file.isFile && isFontName(file.name) && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == directory

    fun isFontName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("ttf", "otf")

    fun importFont(
        filesDir: File,
        accountId: Long,
        name: String,
        input: InputStream,
        checkCurrent: () -> Unit,
        validate: (File) -> Unit = {},
        commit: (() -> File) -> File = { it() },
    ): File {
        val fontDir = directory(filesDir, accountId)
        val cleanName = EpubStorageFiles.validName(name)
        require(isFontName(cleanName)) { "请选择 ttf/otf 字体文件" }
        checkCurrent()
        require(fontDir.isDirectory || fontDir.mkdirs()) { "无法创建字体目录" }
        require(directory(filesDir, accountId) == fontDir) { "字体目录已变更，请重新进入" }
        require(files(filesDir, accountId).none { it.name.equals(cleanName, ignoreCase = true) }) { "同名字体已存在" }
        val temp = File.createTempFile(".reamicro-font-", ".tmp", fontDir)
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkCurrent()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= EpubStorageFiles.MAX_IMPORT_BYTES) { "字体文件超过 256 MB" }
                    output.write(buffer, 0, read)
                }
                require(total > 0L) { "字体文件为空" }
                output.fd.sync()
            }
            checkCurrent()
            val expected = VerifiedFileIO.digest(temp)
            validate(temp)
            val target = commit {
                checkCurrent()
                require(directory(filesDir, accountId) == fontDir) { "字体目录已变更，请重新进入" }
                require(files(filesDir, accountId).none { it.name.equals(cleanName, ignoreCase = true) }) { "同名字体已存在" }
                Files.move(temp.toPath(), File(fontDir, cleanName).toPath()).toFile()
            }
            VerifiedFileIO.requireMatches(target, expected)
            return target
        } finally {
            temp.delete()
        }
    }
}
