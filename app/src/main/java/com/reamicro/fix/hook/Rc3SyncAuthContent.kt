package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.SyncAuthCardRenderContext
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** RC3 AuthCard 的内容延后重组，在真实授权分支内恢复原有卡片上下文。 */
internal fun WebDavDriveHook.hookRc3SyncAuthContent() {
    if (!HostSymbols.usesRc3Abi) return
    val type = classLoader.hostClass("ReaMicroHost.SyncAuthContent")
    val branch = type.hostField("branch")
    val setup = type.hostField("onSetupDefaultDir")
    val pick = type.hostField("onPick")
    val method = type.hostMethod("render", *Array(4) { Any::class.java })
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val callback = param.thisObject ?: return
            if (branch.getByte(callback) != 0.toByte()) return
            val previous = syncAuthCardRender.get()
            param.setObjectExtra("rc3SyncContextActive", true)
            param.setObjectExtra("rc3SyncPreviousContext", previous)
            param.setObjectExtra("rc3SyncPreviousRendered", syncWebDavAuthRenderedBeforeAvailable.get())
            syncAuthCardRender.set(SyncAuthCardRenderContext(
                bookId = previous?.bookId,
                onSetupDefaultDir = setup.get(callback),
                onPick = pick.get(callback),
            ))
            syncWebDavAuthRenderedBeforeAvailable.remove()
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.getObjectExtra("rc3SyncContextActive") != true) return
            val previous = param.getObjectExtra("rc3SyncPreviousContext") as? SyncAuthCardRenderContext
            if (previous == null) syncAuthCardRender.remove() else syncAuthCardRender.set(previous)
            val rendered = param.getObjectExtra("rc3SyncPreviousRendered") as? Boolean
            if (rendered == null) syncWebDavAuthRenderedBeforeAvailable.remove()
            else syncWebDavAuthRenderedBeforeAvailable.set(rendered)
        }
    })
    XposedBridge.deoptimize(method)
}
