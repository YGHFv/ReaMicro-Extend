package com.reamicro.fix.hook

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostField
import com.reamicro.fix.hook.webdav.BACKUP_TYPE_WEBDAV
import com.reamicro.fix.hook.webdav.CLOUD_BOOK_CLASS
import com.reamicro.fix.hook.webdav.CLOUD_STORAGE_UI_EVENT_CLASS
import com.reamicro.fix.hook.webdav.CLOUD_STORAGE_UI_EVENT_TAP_CLASS
import com.reamicro.fix.hook.webdav.CLOUD_STORAGE_VIEW_MODEL_CLASS
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** 只拦截模块的运行中任务；打开已下载书和首次下载仍由宿主原生派发。 */
internal class B4StorageTapBridge(private val drive: WebDavDriveHook) {
    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val vm = drive.cls(CLOUD_STORAGE_VIEW_MODEL_CLASS)
        val tap = drive.cls(CLOUD_STORAGE_UI_EVENT_TAP_CLASS)
        val book = tap.hostAbiField("a").apply { isAccessible = true }
        val kind = vm.hostField("type").apply { isAccessible = true }
        check(book.type == drive.cls(CLOUD_BOOK_CLASS) && kind.type == Int::class.javaPrimitiveType)
        val method = vm.hostAbiMethod("l", drive.cls(CLOUD_STORAGE_UI_EVENT_CLASS))
        check(method.returnType == Void.TYPE)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (kind.getInt(param.thisObject) != BACKUP_TYPE_WEBDAV) return
                val intent = param.args[0] ?: return
                if (!tap.isInstance(intent)) return
                if (drive.tryHandleRunningWebDavDownloadTap(book.get(intent))) param.result = null
            }
        })
        XposedBridge.deoptimize(method)
        vm.hostAbiMethod("i", drive.cls("app.zhendong.reamicro.arch.UiIntent"))
            .let(XposedBridge::deoptimize)
    }
}
