package com.reamicro.fix.epub.editor

import com.reamicro.fix.online.download.writeOnlineCompletionBytesAtomically
import java.io.File

/** Native UserStorage images belong only to files/{uid}/books/{uuid}. */
internal object AccountBookImageFiles {
    fun validUuid(uuid: String): Boolean = uuid.isNotBlank() && uuid != "." && uuid != ".." &&
        uuid.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }

    fun directory(filesDir: File, accountId: Long, uuid: String): File {
        require(accountId >= 0L && validUuid(uuid)) { "图书账号或目录无效" }
        val parent = filesDir.canonicalFile
        val account = File(parent, accountId.toString()).absoluteFile
        val books = File(account, "books").absoluteFile
        val book = File(books, uuid).absoluteFile
        for (path in listOf(account, books, book)) {
            require(path.canonicalFile == path) { "图书目录已重定向，请重新打开" }
        }
        require(book.parentFile == books && books.parentFile == account && account.parentFile == parent) {
            "图书目录超出账号存储"
        }
        return book
    }

    fun child(directory: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') &&
            relative.none { it == '\\' || it == ':' || it == '\u0000' }) { "图片路径无效" }
        val parts = relative.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) { "图片路径无效" }
        val root = directory.absoluteFile
        require(root.canonicalFile == root) { "图书目录已重定向" }
        var child = root
        for (part in parts) {
            child = File(child, part).absoluteFile
            require(child.canonicalFile == child) { "图片路径已重定向，请重新打开" }
        }
        require(child != root && child.toPath().startsWith(root.toPath())) { "图片路径超出图书目录" }
        return child
    }

    fun write(
        filesDir: File,
        accountId: Long,
        uuid: String,
        relative: String,
        bytes: ByteArray,
        checkCurrent: () -> Unit,
        commit: (() -> Unit) -> Unit = { it() },
    ): File {
        require(bytes.isNotEmpty()) { "图片为空" }
        checkCurrent()
        val root = directory(filesDir, accountId, uuid)
        val target = child(root, relative)
        fun checkOwnerAndPath() {
            checkCurrent()
            check(directory(filesDir, accountId, uuid) == root && child(root, relative) == target) {
                "图书或图片目录已经变化"
            }
        }
        if (!root.isDirectory) commit {
            checkOwnerAndPath()
            require(root.isDirectory || root.mkdirs()) { "书籍目录不可用" }
        }
        require(root.isDirectory && (!target.exists() || target.isFile)) { "图片目标不是普通文件" }
        writeOnlineCompletionBytesAtomically(target, bytes,
            commit = { action -> commit { checkOwnerAndPath(); action() } },
            checkCurrent = ::checkOwnerAndPath,
        )
        return target
    }

    /** A stale operation may not update the native book or report a current-page success. */
    fun notifyOwner(
        checkCurrent: () -> Unit,
        commit: (() -> Unit) -> Unit,
        onUpdated: () -> Unit,
    ) {
        checkCurrent()
        commit { checkCurrent(); onUpdated() }
        checkCurrent()
    }
}
