package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethodByArity

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.cloud.local.localLibraryPathArg
import com.reamicro.fix.cloud.webdav.webDavPathArg
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B4 将 auth/userInfo/library 门面内联到 ViewModel；只投影扩展类型，保留原生账号。 */
internal class B4StorageDataBridge(private val drive: WebDavDriveHook) {
    private val constructing = ThreadLocal<Int?>()

    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val vm = drive.cls(CLOUD_STORAGE_VIEW_MODEL_CLASS)
        val constructor = vm.getDeclaredConstructor(
            Int::class.javaPrimitiveType, drive.cls(WORKER_MANAGER_CLASS),
            drive.cls(CLOUD_STORAGE_REPOSITORY_CLASS),
        )
        XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.setObjectExtra("reamicro.b4.storage.previous", constructing.get())
                constructing.set((param.args[0] as? Int)?.takeIf(::isExtension))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                constructing.set(param.getObjectExtra("reamicro.b4.storage.previous") as? Int)
            }
        })
        val cache = drive.cls("ReaMicroHost.PagingCache").hostAbiMethodByArity("c", 2)
        XposedBridge.hookMethod(cache, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val kind = constructing.get() ?: return
                param.args[0] = if (kind == BACKUP_TYPE_WEBDAV) drive.webDavLibraryFlow()
                    else drive.localLibraryFlow()
                XposedBridge.logAlways("ReaMicro B4 storage paging projected kind=$kind")
            }
        })
        val collector = drive.cls("ReaMicroHost.StorageCollector")
        val owner = collector.hostAbiField("g").apply { isAccessible = true }
        val phase = collector.hostAbiField("e").apply { isAccessible = true }
        val collect = drive.cls("ReaMicroHost.CollectLatest").hostAbiMethodByArity("a", 3)
        XposedBridge.hookMethod(collect, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val continuation = param.args[2] ?: return
                if (!collector.isInstance(continuation)) return
                val viewModel = owner.get(continuation) ?: return
                val kind = vm.hostField("type").getInt(viewModel)
                if (!isExtension(kind)) return
                val branch = phase.getByte(continuation).toInt()
                check(branch == 0 || branch == 1) { "Unverified B4 storage collector branch" }
                param.args[0] = if (branch == 0) {
                    if (kind == BACKUP_TYPE_WEBDAV) drive.webDavAccountAuthFlow()
                    else drive.localLibraryAccountAuthFlow()
                } else {
                    drive.flowOf(if (kind == BACKUP_TYPE_WEBDAV) drive.webDavCloudUserInfo()
                        else drive.localLibraryCloudUserInfo())
                }
                XposedBridge.logAlways("ReaMicro B4 storage metadata projected kind=$kind phase=$branch")
            }
        })
        listOf(constructor, cache, collect, collector.hostAbiMethod("v", Any::class.java))
            .forEach(XposedBridge::deoptimize)
        installOpenFolder()
        B4StorageOperations(drive).install()
        B4FolderSelectionBridge(drive).install()
    }

    private fun isExtension(kind: Int): Boolean =
        kind == BACKUP_TYPE_WEBDAV || kind == BACKUP_TYPE_LOCAL_LIBRARY

    private fun installOpenFolder() {
        val method = drive.cls(CLOUD_STORAGE_REPOSITORY_CLASS).hostAbiMethod(
            "n", Int::class.javaPrimitiveType, drive.cls("ReaMicroHost.SuspendContinuation"),
            String::class.java, String::class.java,
        )
        check(method.returnType == Any::class.java)
        val failure = drive.classLoader.hostStaticMethod(
            "kotlin.ResultKt", "createFailure", Throwable::class.java,
        )
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val kind = param.args[0] as? Int ?: return
                if (!isExtension(kind)) return
                drive.rememberCloudStorageRepository(param.thisObject)
                // B4 原生 Result 已擦除；成功直接返回 Unit，失败保留宿主 Failure。
                param.result = runCatching {
                    val rawPath = param.args[2] as String
                    if (kind == BACKUP_TYPE_WEBDAV) {
                        val path = webDavPathArg(rawPath)
                        drive.saveWebDavBrowseDir(path)
                        drive.updateWebDavStorageTrees(path)
                        drive.refreshWebDavLibrary(path)
                    } else {
                        val path = localLibraryPathArg(rawPath)
                        drive.saveLocalLibraryBrowseDir(path)
                        drive.updateLocalLibraryStorageTrees(path)
                        drive.refreshLocalLibrary(path)
                    }
                    XposedBridge.logAlways("ReaMicro B4 storage folder opened kind=$kind")
                    drive.targetUnit()
                }.getOrElse {
                    XposedBridge.logError("ReaMicro B4 storage folder failed", it)
                    failure.invoke(null, it)
                }
            }
        })
        XposedBridge.deoptimize(method)
    }
}
