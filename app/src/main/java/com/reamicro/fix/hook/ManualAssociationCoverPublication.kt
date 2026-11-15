package com.reamicro.fix.hook

import com.reamicro.fix.epub.editor.VerifiedFileIO
import java.io.File
import java.util.UUID

/** A native association failure must restore our pixels without undoing a newer cover edit. */
internal class ManualAssociationCoverPublication private constructor(
    private val target: File,
    private val original: File?,
    private val published: VerifiedFileIO.Digest,
    private val validatePath: () -> Unit,
) {
    private var finished = false

    @Synchronized fun finish(success: Boolean) {
        if (finished) return
        if (!success) {
            validatePath()
            check(target.isFile && VerifiedFileIO.digest(target) == published) {
                "封面已被其他操作修改，已保留新修改"
            }
            if (original != null) {
                VerifiedFileIO.copyAtomic(original, target, checkCurrent = validatePath, commit = { action ->
                    validatePath()
                    check(target.isFile && VerifiedFileIO.digest(target) == published) {
                        "封面已被其他操作修改，已保留新修改"
                    }
                    action()
                })
            } else {
                validatePath()
                check(VerifiedFileIO.digest(target) == published && target.delete()) { "无法撤销未保存的封面" }
            }
        }
        finished = true
        original?.delete()
    }

    companion object {
        fun publish(
            source: File, target: File, checkCurrent: () -> Unit,
            commit: (() -> Unit) -> Unit, validatePath: () -> Unit,
        ): ManualAssociationCoverPublication {
            checkCurrent(); validatePath()
            require(!target.exists() || target.isFile) { "封面目标不是普通文件" }
            val original = target.takeIf { it.isFile }?.let {
                File(source.parentFile, ".restore-${UUID.randomUUID()}").also { snapshot ->
                    VerifiedFileIO.copyAtomic(it, snapshot, checkCurrent = checkCurrent)
                }
            }
            val digest = VerifiedFileIO.digest(source)
            val previous = original?.let(VerifiedFileIO::digest)
            val result = ManualAssociationCoverPublication(target, original, digest, validatePath)
            var didPublish = false
            try {
                VerifiedFileIO.copyAtomic(source, target, checkCurrent = checkCurrent, commit = { action ->
                    commit {
                        checkCurrent(); validatePath()
                        check(if (previous == null) !target.exists() else
                            target.isFile && VerifiedFileIO.digest(target) == previous) { "图书封面已变化，未覆盖新修改" }
                        action()
                        didPublish = true
                    }
                })
                checkCurrent()
                return result
            } catch (error: Throwable) {
                if (didPublish) {
                    runCatching { result.finish(false) }.onFailure(error::addSuppressed)
                } else original?.delete()
                throw error
            }
        }
    }
}
