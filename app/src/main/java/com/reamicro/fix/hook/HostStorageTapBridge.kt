package com.reamicro.fix.hook

import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.BACKUP_TYPE_WEBDAV
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Running module downloads consume taps; other books continue through the host dispatcher. */
internal class HostStorageTapBridge(private val drive: WebDavDriveHook) {
    fun install() {
        val c = requireNotNull(HostStorageBindings.tap) { "Storage tap contract unavailable" }
        val tap = c.type("tap"); val book = c.field("book"); val kind = c.field("kind")
        val method = c.method("dispatch")
        val handle = HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                if (kind.getInt(param.thisObject) != BACKUP_TYPE_WEBDAV) return
                val intent = param.args[0]?.takeIf(tap::isInstance) ?: return
                if (drive.tryHandleRunningWebDavDownloadTap(book.get(intent))) param.result = null
            }
        })
        try { listOf(method, c.method("entry")).forEach(HookRuntime::deoptimize) }
        catch (error: Throwable) { runCatching { handle.unhook() }.onFailure(error::addSuppressed); throw error }
    }
}
