package com.reamicro.fix.hook

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B4 按已取证的成员签名复用文件操作，不能按参数数量猜测新旧方法对应关系。 */
internal class B4StorageOperations(private val drive: WebDavDriveHook) {
    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val repository = drive.cls(CLOUD_STORAGE_REPOSITORY_CLASS)
        val continuation = drive.cls("ReaMicroHost.SuspendContinuation")
        val int = Int::class.javaPrimitiveType!!
        val string = String::class.java
        val failure = drive.classLoader.hostStaticMethod(
            "kotlin.ResultKt", "createFailure", Throwable::class.java,
        ).returnType
        val contracts = listOf(
            Triple("g", "i", arrayOf(int, continuation, string, string)),
            Triple("h", "j", arrayOf(int, string, continuation)),
            Triple("k", "m", arrayOf(int, string, continuation)),
            Triple("l", "n", arrayOf(int, string, continuation)),
            Triple("m", "o", arrayOf(int, continuation, string, string)),
            Triple("o", "q", arrayOf(int, drive.cls(CLOUD_BOOK_CLASS), string, continuation)),
            Triple("p", "r", arrayOf(int, string, continuation)),
            Triple("q", "s", arrayOf(int, List::class.java, continuation)),
        )
        val operations = B2StorageOperations(drive)
        for ((actual, shared, types) in contracts) {
            HookInstallReport.install(FEATURE_ID, "b4Repository_$actual") {
                val method = repository.hostAbiMethod(actual, *types)
                check(method.returnType == Any::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val kind = param.args[0] as? Int ?: return
                        if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) return
                        drive.rememberCloudStorageRepository(param.thisObject)
                        val arguments = param.args.copyOf()
                        if (shared == "q") {
                            // B4 重命名输入仅含主文件名，宿主对云盘分支会自行补回扩展名。
                            val current = cloudPathOf(arguments[1]).substringAfterLast('/')
                            val extension = current.substringAfterLast('.', "")
                            val requested = arguments[2] as String
                            if (extension.isNotBlank() && !requested.endsWith(".$extension", true)) {
                                arguments[2] = "$requested.$extension"
                            }
                        }
                        param.result = drive.webDavResult { operations.operate(shared, kind, arguments) }
                        XposedBridge.logAlways("ReaMicro B4 storage operation=$actual kind=$kind " +
                            "failed=${failure.isInstance(param.result)}")
                    }
                })
                XposedBridge.deoptimize(method)
            }
        }
    }
}
