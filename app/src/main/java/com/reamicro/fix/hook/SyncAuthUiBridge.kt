package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.SyncAuthCardRenderContext
import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.cloud.webdav.webDavDisplayDir
import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException

/** Keep native animation/layout and identify rows from their real click callbacks. */
internal class SyncAuthUiBridge(private val drive: WebDavDriveHook) {
    private val host = requireNotNull(HostBookUiBindings.syncUi)
    private val ui = requireNotNull(HostUiBindings.current)
    private val forwarded = ThreadLocal<Any?>()
    private val available = ThreadLocal<Boolean?>()
    private val callbackBooks = arrayListOf<Pair<WeakReference<Any>, Long>>()
    private fun remember(callback: Any, bookId: Long) = synchronized(callbackBooks) {
        callbackBooks.removeAll { it.first.get().let { value -> value == null || value === callback } }
        callbackBooks += WeakReference(callback) to bookId
    }
    private fun bookId(callback: Any): Long? = synchronized(callbackBooks) {
        callbackBooks.removeAll { it.first.get() == null }
        callbackBooks.firstOrNull { it.first.get() === callback }?.second
    }

    fun install() {
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        fun hook(role: String, callback: HookCallback) { handles += HookRuntime.hookMethod(host.method(role), callback) }
        try {
            hook("root", object : HookCallback() {
                override fun before(param: Call) {
                    val id = (param.args[host.number("root.bookId")] as Number).toLong()
                    val pick = requireNotNull(param.args[host.number("root.pick")])
                    remember(pick, id)
                    param.setObjectExtra("sync.previous", drive.syncAuthCardRender.get())
                    drive.syncAuthCardRender.set(SyncAuthCardRenderContext(id,
                        param.args[host.number("root.setup")], pick, param.args[host.number("root.authorize")]))
                }
                override fun after(param: Call) {
                    restoreContext(param.getObjectExtra("sync.previous") as? SyncAuthCardRenderContext)
                }
            })
            hook("content", object : HookCallback() {
                override fun before(param: Call) {
                    val original = param.thisObject ?: return
                    if (forwarded.get() === original || !host.isSyncContent(original)) return
                    val pick = requireNotNull(host.field("pick").get(original))
                    val setup = requireNotNull(host.field("setup").get(original))
                    val authorize = requireNotNull(host.field("authorize").get(original))
                    if (!host.type("function2").isInstance(pick) || !host.type("function1").isInstance(setup) ||
                        !host.type("function1").isInstance(authorize)) return
                    val previous = drive.syncAuthCardRender.get()
                    val running = param.args[host.number("content.running")] == true
                    param.setObjectExtra("sync.active", true)
                    param.setObjectExtra("sync.previous", previous)
                    drive.syncAuthCardRender.set(SyncAuthCardRenderContext(bookId(pick) ?: previous?.bookId,
                        setup, pick, authorize, running))
                    val provider = (host.field("provider").get(original) as? Number)?.toInt()
                    if (!running || provider != BACKUP_TYPE_WEBDAV) return
                    // A private copy supplies the native 115 branch. Session auth and the
                    // original deferred callback remain untouched for later recomposition.
                    val oldForward = forwarded.get()
                    val oldDepth = drive.webDavRunningSyncAuthCardDepth.get()
                    try {
                        val state = requireNotNull(ui.call("state", drive.webDavYun115Auth()))
                        val copy = host.runningSyncContent(original, state)
                        forwarded.set(copy)
                        drive.webDavRunningSyncAuthCardDepth.set((oldDepth ?: 0) + 1)
                        val args = param.args.copyOf()
                        val flags = host.number("content.flags")
                        args[flags] = (args[flags] as Int) or 1
                        param.result = host.method("content").invoke(copy, *args)
                    } catch (error: Throwable) {
                        param.throwable = (error as? InvocationTargetException)?.targetException ?: error
                    } finally {
                        if (oldForward == null) forwarded.remove() else forwarded.set(oldForward)
                        if (oldDepth == null) drive.webDavRunningSyncAuthCardDepth.remove()
                        else drive.webDavRunningSyncAuthCardDepth.set(oldDepth)
                    }
                }
                override fun after(param: Call) {
                    if (param.getObjectExtra("sync.active") == true)
                        restoreContext(param.getObjectExtra("sync.previous") as? SyncAuthCardRenderContext)
                }
            })
            hook("card", object : HookCallback() {
                override fun before(param: Call) {
                    if ((drive.webDavRunningSyncAuthCardDepth.get() ?: 0) <= 0) return
                    val frame = drive.syncAuthCardRender.get() ?: return
                    val dir = drive.currentWebDavBackupDir()
                    drive.getComposeWebDavVector()?.let { param.args[0] = it }
                    param.args[1] = "上传到 $WEBDAV_TITLE"
                    param.args[2] = webDavDisplayDir(dir)
                    param.args[3] = true
                    param.args[4] = ui.callback(host.type("function0")) {
                        if (dir.isBlank()) frame.onSetupDefaultDir?.let { host.method("invoke1").invoke(it, BACKUP_TYPE_WEBDAV) }
                        else frame.onPick?.let { host.method("invoke2").invoke(it, BACKUP_TYPE_WEBDAV, dir) }
                    }
                    param.args[5] = ui.callback(host.type("function0")) {
                        frame.onPick?.let { host.method("invoke2").invoke(it, BACKUP_TYPE_WEBDAV, null) }
                    }
                    // Parameters changed at this call site; do not retain native unchanged bits.
                    param.args[7] = 0
                }
            })
            hook("available", object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("sync.available.previous", available.get())
                    param.setObjectExtra("sync.row.previous", drive.syncAvailableProvider.get())
                    available.set(true)
                    drive.syncAvailableProvider.remove()
                    if ((drive.webDavIconDepth.get() ?: 0) > 0) return
                    val frame = drive.syncAuthCardRender.get() ?: return
                    if (frame.isRunning || !drive.canShowWebDavEntry()) return
                    // 宿主已渲染全部授权卡片；未授权 WebDAV 与原有可用网盘共用最后的分组。
                    if (hasWebDavLogin(drive.currentContext())) {
                        if (!frame.webDavRendered) {
                            frame.webDavRendered = true
                            frame.webDavRendered = drive.renderSyncWebDavAuthorizedCard(requireNotNull(param.args[2]),
                                frame.onSetupDefaultDir, frame.onPick)
                        }
                    } else if (!frame.webDavRendered) {
                        val providers = (param.args[0] as List<*>).map { (it as Number).toInt() }
                        param.args[0] = providers.filter { it != BACKUP_TYPE_WEBDAV } + BACKUP_TYPE_WEBDAV
                        param.args[3] = 0
                        frame.webDavRendered = true
                    }
                }
                override fun after(param: Call) {
                    val prior = param.getObjectExtra("sync.available.previous") as? Boolean
                    if (prior == null) available.remove() else available.set(prior)
                    val row = param.getObjectExtra("sync.row.previous") as? Int
                    if (row == null) drive.syncAvailableProvider.remove() else drive.syncAvailableProvider.set(row)
                }
            })
            hook("rowClickable", object : HookCallback() {
                override fun before(param: Call) {
                    if (available.get() != true) return
                    val callback = param.args[4]
                    val matches = callback != null && host.type("row").isInstance(callback) &&
                        (host.field("row.tag").get(callback) as Number).toInt() == host.number("row.tag")
                    if (matches) drive.syncAvailableProvider.set((host.field("row.provider").get(callback) as Number).toInt())
                    else drive.syncAvailableProvider.remove()
                }
            })
            hook("image", object : HookCallback() {
                override fun before(param: Call) {
                    if (available.get() != true || drive.syncAvailableProvider.get() != BACKUP_TYPE_WEBDAV) return
                    drive.getComposeWebDavVector()?.let { param.args[0] = it; param.args[4] = 0 }
                }
            })
            handles += HookRuntime.hookMethod(ui.method("text"), object : HookCallback() {
                override fun before(param: Call) {
                    if (available.get() != true || drive.syncAvailableProvider.get() != BACKUP_TYPE_WEBDAV) return
                    if (param.args[0] !in setOf("百度网盘", "阿里云盘", "115网盘", "本地存储")) return
                    param.args[0] = WEBDAV_TITLE
                    param.args[17] = ((param.args[17] as Int) and 14.inv()) or 6
                    param.args[19] = (param.args[19] as Int) and 1.inv()
                }
            })
            listOf("root", "content", "card", "available").forEach { HookRuntime.deoptimize(host.method(it)) }
            ModuleResources.cleanup {
                forwarded.remove(); available.remove(); drive.syncAuthCardRender.remove()
                drive.syncAvailableProvider.remove(); drive.webDavRunningSyncAuthCardDepth.remove()
                synchronized(callbackBooks) { callbackBooks.clear() }
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
    private fun restoreContext(previous: SyncAuthCardRenderContext?) {
        if (previous == null) drive.syncAuthCardRender.remove() else drive.syncAuthCardRender.set(previous)
    }
}
