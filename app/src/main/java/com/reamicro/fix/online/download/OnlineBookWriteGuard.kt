package com.reamicro.fix.online.download

import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadCancelledException
import com.reamicro.fix.reload.TaskCancellation
import java.io.File

/** Every published chapter belongs to the account and directory captured before the request. */
internal class OnlineBookWriteGuard(
    bookDirectory: File,
    private val isCurrent: () -> Boolean,
    private val commitCurrent: (() -> Unit) -> Unit,
) {
    private val directory = bookDirectory.canonicalFile
    private val key = onlineCompletionDirectoryKey(directory)

    fun checkCurrent() {
        TaskCancellation.checkCurrent()
        if (Thread.currentThread().isInterrupted || !isCurrent() || !directory.isDirectory ||
            key != null && onlineCompletionDirectoryKey(directory) != key
        ) throw OnlineCompletionDownloadCancelledException("图书或账号状态已变化，已停止章节更新")
    }

    fun commit(action: () -> Unit) {
        checkCurrent()
        commitCurrent {
            checkCurrent()
            action()
        }
    }
}
