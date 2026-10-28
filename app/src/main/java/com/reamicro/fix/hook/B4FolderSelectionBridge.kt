package com.reamicro.fix.hook

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostField
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B4 默认把未知云盘根目录叫作本地存储；只校正扩展云盘的根节点。 */
internal class B4FolderSelectionBridge(private val drive: WebDavDriveHook) {
    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val vm = drive.cls("app.zhendong.reamicro.ui.storage.CloudFolderViewModel")
        val folder = drive.cls(CLOUD_FOLDER_CLASS)
        val worker = drive.cls("ReaMicroHost.FolderLoadWorker")
        val branch = worker.hostAbiField("e")
        val owner = worker.hostAbiField("h")
        val selected = worker.hostAbiField("i")
        val invoke = worker.hostAbiMethod("v", Any::class.java)
        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val task = param.thisObject ?: return
                if (branch.getByte(task) != 0.toByte()) return
                val model = owner.get(task)?.takeIf(vm::isInstance) ?: return
                val args = vm.hostField("navArgs").get(model)
                val kind = args.javaClass.hostField("type").getInt(args)
                if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) return
                val current = selected.get(task)?.takeIf(folder::isInstance) ?: return
                val path = folder.hostField("path").get(current) as String
                if (path != "root" && path != "/") return
                val title = if (kind == BACKUP_TYPE_WEBDAV) WEBDAV_TITLE else LOCAL_LIBRARY_TITLE
                selected.set(task, drive.newStorageFolder(title, "/", false, 0L))
                XposedBridge.logAlways("$LOG_PREFIX B4 folder picker root corrected kind=$kind")
            }
        })
        XposedBridge.deoptimize(invoke)
        vm.declaredConstructors.forEach(XposedBridge::deoptimize)
    }
}
