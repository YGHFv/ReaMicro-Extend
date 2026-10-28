package com.reamicro.fix.hook

import com.reamicro.fix.cloud.local.localLibraryPathArg
import com.reamicro.fix.cloud.webdav.webDavPathArg
import com.reamicro.fix.cloud.webdav.parentWebDavPath
import com.reamicro.fix.cloud.webdav.normalizeWebDavPath
import com.reamicro.fix.cloud.webdav.childWebDavPath
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.hostField
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B2 仓库调用按逐方法体核实的业务槽位派发，不能靠同型参数猜操作。 */
internal class B2StorageOperations(private val drive: WebDavDriveHook) {
    fun install() {
        val repository = drive.cls(CLOUD_STORAGE_REPOSITORY_CLASS)
        val continuation = drive.cls("ReaMicroHost.SuspendContinuation")
        val contracts = linkedMapOf(
            "i" to arrayOf(Int::class.javaPrimitiveType!!, continuation, String::class.java, String::class.java),
            "j" to arrayOf(Int::class.javaPrimitiveType!!, String::class.java, continuation),
            "m" to arrayOf(Int::class.javaPrimitiveType!!, String::class.java, continuation),
            "n" to arrayOf(Int::class.javaPrimitiveType!!, String::class.java, continuation),
            "o" to arrayOf(Int::class.javaPrimitiveType!!, continuation, String::class.java, String::class.java),
            "p" to arrayOf(Int::class.javaPrimitiveType!!, continuation, String::class.java, String::class.java),
            "q" to arrayOf(Int::class.javaPrimitiveType!!, drive.cls(CLOUD_BOOK_CLASS), String::class.java, continuation),
            "r" to arrayOf(Int::class.javaPrimitiveType!!, String::class.java, continuation),
            "s" to arrayOf(Int::class.javaPrimitiveType!!, List::class.java, continuation),
        )
        for ((name, types) in contracts) {
            HookInstallReport.install(FEATURE_ID, "b2Repository_$name") {
                val method = repository.getDeclaredMethod(name, *types)
                check(method.returnType == Any::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val kind = args[0] as? Int ?: return
                        if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) return
                        drive.rememberCloudStorageRepository(param.thisObject)
                        param.result = drive.webDavResult { operate(name, kind, args) }
                        XposedBridge.logAlways("ReaMicro B2 storage operation=$name kind=$kind failed=${drive.cls("kotlin.Result\$Failure").isInstance(param.result)}")
                    }
                })
                XposedBridge.deoptimize(method)
            }
        }
    }

    fun operate(name: String, kind: Int, args: Array<Any?>): Any? = with(drive) {
        val local = kind == BACKUP_TYPE_LOCAL_LIBRARY
        fun path(index: Int): String {
            val value = args[index] as String
            return if (local) localLibraryPathArg(value) else webDavPathArg(value)
        }
        when (name) {
            "p" -> {
                val folder = path(2)
                if (local) {
                    saveLocalLibraryBrowseDir(folder)
                    updateLocalLibraryStorageTrees(folder)
                    refreshLocalLibrary(folder)
                } else {
                    saveWebDavBrowseDir(folder)
                    updateWebDavStorageTrees(folder)
                    refreshWebDavLibrary(folder)
                }
                targetUnit()
            }
            "m" -> {
                val folder = path(1)
                if (local) listLocalLibrary(folder).filter { it.isDirectory }.map { newLocalLibraryCloudFolder(it) }
                else listWebDav(folder).filter { it.isDirectory }.map { newCloudFolder(it) }
            }
            "n" -> {
                val file = path(1)
                if (local) {
                    val entry = localLibraryEntry(file) ?: error("本地书库文件不存在")
                    newLocalLibraryCloudBook(entry)
                } else {
                    val entry = listWebDav(parentWebDavPath(file)).firstOrNull {
                        normalizeWebDavPath(it.path) == normalizeWebDavPath(file) && !it.isDirectory
                    } ?: error("WebDAV 文件不存在")
                    newCloudBook(entry)
                }
            }
            "r" -> {
                val query = args[1] as String
                if (local) searchLocalLibraryBooks(query) else searchWebDavBooks(query)
            }
            "i" -> {
                val parent = path(2)
                val title = args[3] as String
                if (local) {
                    createLocalLibraryFolder(parent, title)
                    refreshLocalLibrary(parent)
                } else {
                    webDavMkcol(childWebDavPath(parent, title))
                    refreshWebDavLibrary(parent)
                }
                targetUnit()
            }
            "j" -> {
                val file = path(1)
                if (local) {
                    deleteLocalLibraryEntry(file)
                    refreshLocalLibrary(parentLocalLibraryPath(file))
                } else {
                    webDavDelete(file)
                    refreshWebDavLibrary(parentWebDavPath(file))
                }
                targetUnit()
            }
            "o" -> {
                val file = path(2)
                val destination = path(3)
                if (local) {
                    moveLocalLibraryEntry(file, destination)
                    refreshLocalLibrary(destination)
                } else {
                    webDavMove(file, childWebDavPath(destination, normalizeWebDavPath(file).substringAfterLast('/')))
                    refreshWebDavLibrary(destination)
                }
                targetUnit()
            }
            "q" -> {
                val file = if (local) localLibraryPathArg(cloudPathOf(args[1])) else webDavPathArg(cloudPathOf(args[1]))
                val title = args[2] as String
                if (local) {
                    renameLocalLibraryEntry(file, title)
                    refreshLocalLibrary(parentLocalLibraryPath(file))
                } else {
                    webDavMove(file, childWebDavPath(parentWebDavPath(file), title))
                    refreshWebDavLibrary(parentWebDavPath(file))
                }
                targetUnit()
            }
            "s" -> {
                if (local) launchLocalLibraryFolderPicker() else {
                    val folders = args[1] as List<*>
                    val folder = folders.lastOrNull()
                    val destination = folder?.javaClass?.hostField("path")?.get(folder) as? String
                    if (!destination.isNullOrBlank()) saveWebDavBackupDir(destination)
                }
                targetUnit()
            }
            else -> error("Unverified B2 storage operation: $name")
        }
    }
}
