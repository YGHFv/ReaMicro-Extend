package com.reamicro.fix.online.download

import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadCancelledException
import com.reamicro.fix.cloud.webdav.OnlineCompletionDownloadTask
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** A cancelled or departed task must not enter an import/write queue after its wait. */
internal fun <T> withOnlineCompletionTaskLock(
    task: OnlineCompletionDownloadTask,
    lock: ReentrantLock,
    onWaiting: (() -> Unit)? = null,
    action: () -> T,
): T {
    throwIfOnlineCompletionDownloadCancelled(task)
    var locked = lock.tryLock()
    try {
        if (!locked) {
            onWaiting?.invoke()
            while (!locked) {
                throwIfOnlineCompletionDownloadCancelled(task)
                locked = lock.tryLock(100L, TimeUnit.MILLISECONDS)
            }
        }
        throwIfOnlineCompletionDownloadCancelled(task)
        return action()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OnlineCompletionDownloadCancelledException()
    } finally {
        if (locked) lock.unlock()
    }
}

/** Copy large fonts outside the identity lock and publish only their final move. */
internal fun copyOnlineCompletionFileAtomically(
    source: File,
    target: File,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    val destination = target.canonicalFile
    val directory = requireNotNull(destination.parentFile)
    commit { checkCurrent(); check(directory.isDirectory || directory.mkdirs()) { "无法创建文件暂存目录" } }
    val staged = File(directory, ".${destination.name}.${UUID.randomUUID()}.tmp")
    try {
        val originalSize = source.length()
        val originalModified = source.lastModified()
        check(source.isFile) { "导入文件已不存在" }
        source.inputStream().use { input ->
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        check(source.length() == originalSize && source.lastModified() == originalModified &&
            staged.length() == originalSize) { "导入文件在复制期间发生变化" }
        checkCurrent()
        var published = false
        commit {
            check(!published) { "文件提交回调重复执行" }
            checkCurrent()
            moveOnlineCompletionPath(staged, destination, replace = true)
            published = true
        }
        check(published) { "文件尚未提交" }
    } finally {
        staged.delete()
    }
}

/** The native EPUB parser can do long IO in staging; only renames touch the account bookshelf. */
internal class OnlineCompletionDirectoryPublication internal constructor(
    private val staged: File,
    private val target: File,
    private val commit: (() -> Unit) -> Unit,
    private val checkCurrent: () -> Unit,
    private val checkRollbackCurrent: () -> Unit,
) {
    private val backup = File(requireNotNull(target.parentFile), ".${target.name}.reamicro-backup-${UUID.randomUUID()}")
    private val stagedKey = fileKey(staged)
    private var backedUp = false
    private var published = false
    private var completed = false

    internal fun publish() {
        commit {
            checkCurrent()
            check(!published && !backedUp && !backup.exists()) { "图书目录提交已发生变化" }
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "无法创建书库目录" }
            if (target.exists()) {
                moveOnlineCompletionPath(target, backup)
                backedUp = true
            }
            try {
                moveOnlineCompletionPath(staged, target)
                published = true
            } catch (failure: Throwable) {
                if (backedUp && !target.exists()) {
                    runCatching { moveOnlineCompletionPath(backup, target); backedUp = false }
                        .onFailure(failure::addSuppressed)
                }
                throw failure
            }
        }
        check(published) { "图书目录尚未提交" }
    }

    /** Roll back only this directory and only while the captured account is still current. */
    fun rollback() {
        if (completed) return
        var discarded: File? = null
        commit {
            checkRollbackCurrent()
            if (!published || stagedKey == null || fileKey(target) != stagedKey) return@commit
            val failed = File(requireNotNull(target.parentFile), ".${target.name}.reamicro-failed-${UUID.randomUUID()}")
            moveOnlineCompletionPath(target, failed)
            discarded = failed
            published = false
            if (backedUp) {
                try {
                    moveOnlineCompletionPath(backup, target)
                    backedUp = false
                } catch (failure: Throwable) {
                    runCatching { moveOnlineCompletionPath(failed, target); published = true; discarded = null }
                        .onFailure(failure::addSuppressed)
                    throw failure
                }
            }
        }
        discarded?.let(::deleteOnlineCompletionTree)
    }

    fun complete() {
        completed = true
        if (backedUp) {
            deleteOnlineCompletionTree(backup)
            backedUp = false
        }
    }
}

internal fun publishOnlineCompletionDirectory(
    staged: File,
    target: File,
    commit: (() -> Unit) -> Unit = { it() },
    checkCurrent: () -> Unit = {},
    checkRollbackCurrent: () -> Unit = checkCurrent,
): OnlineCompletionDirectoryPublication {
    checkCurrent()
    val source = staged.canonicalFile
    val destination = target.canonicalFile
    check(source.isDirectory && source != destination && !destination.toPath().startsWith(source.toPath())) {
        "图书暂存目录不可用"
    }
    val publication = OnlineCompletionDirectoryPublication(source, destination, commit, checkCurrent, checkRollbackCurrent)
    try {
        publication.publish()
        return publication
    } catch (failure: Throwable) {
        runCatching { publication.rollback() }.onFailure(failure::addSuppressed)
        throw failure
    }
}

internal fun onlineCompletionDirectoryKey(directory: File): Any? = fileKey(directory)

private fun fileKey(file: File): Any? =
    if (file.exists()) Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    else null

private fun moveOnlineCompletionPath(source: File, target: File, replace: Boolean = false) {
    try {
        if (replace) Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        if (replace) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source.toPath(), target.toPath())
    }
}

private fun deleteOnlineCompletionTree(file: File) {
    val path = file.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
