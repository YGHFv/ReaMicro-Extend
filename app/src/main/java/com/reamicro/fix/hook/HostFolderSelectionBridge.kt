package com.reamicro.fix.hook

import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Corrects the extension root only in the branch proven to request cloud folders. */
internal class HostFolderSelectionBridge(private val drive: WebDavDriveHook) {
    fun install() {
        val c = requireNotNull(HostStorageBindings.folder) { "Folder selection contract unavailable" }
        requireNotNull(HostStorageBindings.records) { "Folder records unavailable" }
        val invoke = c.method("resume")
        val handle = HookRuntime.hookMethod(invoke, object : HookCallback() {
            override fun before(param: Call) {
                val task = param.thisObject ?: return
                if (c.field("phase").getByte(task).toInt() != c.value("phase")) return
                val model = c.field("owner").get(task)?.takeIf(c.type("vm")::isInstance) ?: return
                val args = c.field("args").get(model)
                val kind = c.field("kind").getInt(args)
                if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) return
                val current = c.field("selected").get(task)?.takeIf(c.type("folder")::isInstance) ?: return
                val path = c.field("path").get(current) as String
                if (path != "root" && path != "/") return
                val title = if (kind == BACKUP_TYPE_WEBDAV) WEBDAV_TITLE else LOCAL_LIBRARY_TITLE
                c.field("selected").set(task, drive.newStorageFolder(title, "/", false, 0L))
            }
        })
        try { listOf(invoke, c.constructor("vm"), c.constructor("worker")).forEach(HookRuntime::deoptimize) }
        catch (error: Throwable) { runCatching { handle.unhook() }.onFailure(error::addSuppressed); throw error }
    }
}
