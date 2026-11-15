package com.reamicro.fix.hook

import com.reamicro.fix.core.HostHomeSearchBindings
import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Dispatch through the native search events, including the host's local-book lookup coroutine. */
internal class HostHomeSearchEvents(private val drive: WebDavDriveHook) {
    private val host = HostHomeSearchBindings.requireCurrent()
    val method = host.method("dispatch")
    fun query(intent: Any?): String? = host.query(intent)
    fun deoptimize() {
        HookRuntime.deoptimize(method)
        HookRuntime.deoptimize(host.method("entry"))
    }
    fun installTapGuard() {
        val records = requireNotNull(HostStorageBindings.records)
        val handle = HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val intent = param.args[0] ?: return
                if (!host.type("tap").isInstance(intent)) return
                val book = requireNotNull(host.field("tap.book").get(intent))
                val kind = (records.field("book.type").get(book) as Number).toInt()
                if (isOnlineCompletionPath(cloudPathOf(book))) {
                    if (drive.handleOnlineCompletionSearchTap(book)) param.result = null
                } else if (drive.tryHandleRunningCloudDownloadTap(book, kind)) {
                    param.result = null
                }
            }
            override fun after(param: Call) {
                if (host.type("tap").isInstance(param.args[0]) && param.throwable != null) {
                    HookRuntime.logError("$LOG_PREFIX home search tap failed", param.throwable)
                }
            }
        })
        try { deoptimize() } catch (error: Throwable) {
            runCatching { handle.unhook() }.onFailure(error::addSuppressed)
            throw error
        }
    }
    companion object {
        fun open(receiver: Any, book: Any) = HostHomeSearchBindings.requireCurrent().open(receiver, book)
    }
}
