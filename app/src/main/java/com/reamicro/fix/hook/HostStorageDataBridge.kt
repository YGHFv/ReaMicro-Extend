package com.reamicro.fix.hook

import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface

/** Projects extension kinds at the verified VM paging and metadata boundaries. */
internal class HostStorageDataBridge(private val drive: WebDavDriveHook) {
    private val constructing = ThreadLocal<Int?>()
    fun install() {
        val c = requireNotNull(HostStorageBindings.data) { "Storage data contract unavailable" }
        requireNotNull(HostStorageBindings.records) { "Storage records unavailable" }
        requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.paging) { "Native paging unavailable" }
        val handles = arrayListOf<XposedInterface.HookHandle>()
        try {
            val constructor = c.constructor("vm")
            handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("reamicro.storage.previous", constructing.get())
                    constructing.set((param.args[0] as? Int)?.takeIf(::isExtension))
                }
                override fun after(param: Call) {
                    constructing.set(param.getObjectExtra("reamicro.storage.previous") as? Int)
                }
            })
            val cache = c.method("cache")
            handles += HookRuntime.hookMethod(cache, object : HookCallback() {
                override fun before(param: Call) {
                    val kind = constructing.get() ?: return
                    try {
                        param.args[0] = if (kind == BACKUP_TYPE_WEBDAV) drive.webDavLibraryFlow() else drive.localLibraryFlow()
                    } catch (error: Throwable) { param.throwable = error }
                }
            })
            val collector = c.type("collector")
            val owner = c.field("owner"); val phase = c.field("phase"); val kindField = c.field("kind")
            val collect = c.method("collect")
            handles += HookRuntime.hookMethod(collect, object : HookCallback() {
                override fun before(param: Call) {
                    val continuation = param.args[2]?.takeIf(collector::isInstance) ?: return
                    val model = owner.get(continuation) ?: return
                    val kind = kindField.getInt(model)
                    if (!isExtension(kind)) return
                    try {
                        drive.rememberCloudStorageRepository(c.field("repository").get(model))
                        param.args[0] = when (phase.getByte(continuation).toInt()) {
                            c.value("authPhase") -> if (kind == BACKUP_TYPE_WEBDAV) drive.webDavAccountAuthFlow()
                                else drive.localLibraryAccountAuthFlow()
                            c.value("infoPhase") -> drive.flowOf(if (kind == BACKUP_TYPE_WEBDAV) drive.webDavCloudUserInfo()
                                else drive.localLibraryCloudUserInfo())
                            else -> error("Unexpected storage collector phase")
                        }
                    } catch (error: Throwable) { param.throwable = error }
                }
            })
            listOf(constructor, cache, collect, c.method("resume")).forEach(HookRuntime::deoptimize)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
    private fun isExtension(kind: Int) = kind == BACKUP_TYPE_WEBDAV || kind == BACKUP_TYPE_LOCAL_LIBRARY
}
