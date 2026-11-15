package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostEpubServicesBindings
import java.io.File

/** The native picker replaces the current cover and preserves its first cover~backup file. */
internal object AccountBookCoverFiles {
    data class Plan(val path: String, val backup: String?)

    fun nativePlan(loader: ClassLoader, current: String, selectedName: String): Plan {
        val host = requireNotNull(HostEpubServicesBindings.metadata) { "宿主封面写入接口尚未解析" }
        check(host.type("coverPlan").classLoader === loader) { "宿主封面接口已变化" }
        val pair = requireNotNull(host.method("coverNames").invoke(null, current, selectedName))
        return Plan(host.field("cover.primary").get(pair) as String, host.field("cover.backup").get(pair) as? String)
    }

    fun prepare(directory: File, plan: Plan, checkCurrent: () -> Unit, commit: (() -> Unit) -> Unit) {
        val target = AccountBookImageFiles.child(directory, plan.path)
        val backupPath = plan.backup ?: return
        val backup = AccountBookImageFiles.child(directory, backupPath)
        require(target != backup) { "封面备份路径无效" }
        checkCurrent()
        if (target.isFile && !backup.exists()) {
            VerifiedFileIO.copyAtomic(target, backup,
                commit = { action -> commit {
                    checkCurrent()
                    check(AccountBookImageFiles.child(directory, plan.path) == target &&
                        AccountBookImageFiles.child(directory, backupPath) == backup && !backup.exists()) {
                        "封面或备份已经变化，请重新打开"
                    }
                    action()
                } },
                checkCurrent = {
                    checkCurrent()
                    AccountBookImageFiles.child(directory, plan.path)
                    AccountBookImageFiles.child(directory, backupPath)
                })
        }
    }

    fun copy(directory: File, source: File, plan: Plan, checkCurrent: () -> Unit,
        commit: (() -> Unit) -> Unit): File {
        checkCurrent()
        val relative = source.relativeTo(directory).invariantSeparatorsPath
        check(AccountBookImageFiles.child(directory, relative) == source.absoluteFile && source.isFile) {
            "所选图片已经变化，请重新打开"
        }
        prepare(directory, plan, checkCurrent, commit)
        val target = AccountBookImageFiles.child(directory, plan.path)
        VerifiedFileIO.copyAtomic(source, target,
            commit = { action -> commit {
                checkCurrent()
                AccountBookImageFiles.child(directory, relative)
                AccountBookImageFiles.child(directory, plan.path)
                action()
            } },
            checkCurrent = {
                checkCurrent()
                AccountBookImageFiles.child(directory, relative)
                AccountBookImageFiles.child(directory, plan.path)
            })
        return target
    }
}
