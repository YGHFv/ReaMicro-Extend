package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.hook.AccountHostRuntime
import java.io.File

internal data class WebDavBackupSnapshot(
    val book: Any,
    val backup: Any,
    val storageIdentity: AccountHostRuntime.Identity,
    val uuid: String,
)

internal class WebDavOperationTicket(
    val storageIdentity: AccountHostRuntime.Identity,
    val credentials: WebDavCredentials,
    val connectionGeneration: Long,
    val backupDir: String,
) {
    override fun toString(): String = "WebDavOperationTicket(accountId=${storageIdentity.accountId})"
}

internal class WebDavImportSource(
    val sourceUrl: String,
    val sourceSize: Long?,
    val storageIdentity: AccountHostRuntime.Identity,
    val localFile: File,
    private val checkActive: () -> Unit,
) {
    @Volatile private var workerFinished = false
    fun isWorkerFinished(): Boolean = workerFinished
    fun finishWorker() { workerFinished = true }

    fun checkCurrent() {
        checkActive()
        if (Thread.currentThread().isInterrupted || !AccountHostRuntime.isCurrentStorage(storageIdentity)) {
            throw CloudDownloadCancelledException("账号已切换，请重新导入")
        }
    }

    override fun toString(): String = "WebDavImportSource(accountId=${storageIdentity.accountId})"
}
