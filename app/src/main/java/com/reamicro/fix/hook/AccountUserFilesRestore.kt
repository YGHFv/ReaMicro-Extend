package com.reamicro.fix.hook

import java.io.File

/** Extract beside the account directory and retain its old tree until the database transaction finishes. */
internal class AccountUserFilesRestore(private val root: File) : AutoCloseable {
    val staged: File = temporaryDirectory(root.parentFile ?: error("Missing account files parent"))
    private val backup = File(staged.parentFile, staged.name + ".previous")
    private var installed = false
    private var complete = false

    fun install() {
        check(!installed && !complete) { "Account files restore already installed" }
        if (root.exists()) check(root.renameTo(backup)) { "无法暂存现有账号图书" }
        try {
            check(staged.renameTo(root)) { "无法恢复账号图书" }
            installed = true
        } catch (error: Throwable) {
            if (backup.exists()) runCatching {
                check(backup.renameTo(root)) { "无法恢复原账号图书，备份已保留" }
            }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun complete() {
        check(installed) { "Account files restore not installed" }
        complete = true
        backup.deleteRecursively()
    }

    override fun close() {
        if (!complete && installed) {
            check(root.deleteRecursively()) { "无法回滚导入图书" }
            if (backup.exists()) check(backup.renameTo(root)) { "无法恢复原账号图书，备份已保留" }
        }
        staged.deleteRecursively()
    }

    private fun temporaryDirectory(parent: File): File {
        check(parent.isDirectory) { "账号图书目录不可用" }
        return File.createTempFile(".reamicro_account_import_", ".tmp", parent).apply {
            check(delete() && mkdir()) { "无法暂存导入图书" }
        }
    }
}
