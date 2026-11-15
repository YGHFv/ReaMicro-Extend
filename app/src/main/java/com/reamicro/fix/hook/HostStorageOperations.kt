package com.reamicro.fix.hook

import com.reamicro.fix.cloud.local.localLibraryPathArg
import com.reamicro.fix.cloud.webdav.webDavPathArg
import com.reamicro.fix.cloud.webdav.parentWebDavPath
import com.reamicro.fix.cloud.webdav.normalizeWebDavPath
import com.reamicro.fix.cloud.webdav.childWebDavPath
import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface

/** Operation names come from verified repository callers, independently of obfuscated names. */
internal class HostStorageOperations(private val drive: WebDavDriveHook) {
    private val records by lazy { requireNotNull(HostStorageBindings.records) { "Storage records unavailable" } }
    fun install() {
        val c = requireNotNull(HostStorageBindings.operations) { "Storage operations unavailable" }
        records
        val handles = arrayListOf<XposedInterface.HookHandle>()
        try {
            for (name in HostStorageBindings.OPERATIONS) {
                val method = c.method(name)
                handles += HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) {
                        val kind = param.args[0] as? Int ?: return
                        if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) return
                        try {
                            drive.rememberCloudStorageRepository(param.thisObject)
                            param.result = drive.webDavResult { operate(name, kind, param.args) }
                        } catch (error: Throwable) { param.throwable = error }
                    }
                })
                HookRuntime.deoptimize(method)
            }
            val auth = c.method("auth")
            handles += HookRuntime.hookMethod(auth, object : HookCallback() {
                override fun before(param: Call) {
                    val kind = param.args[0] as? Int ?: return
                    if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) return
                    try {
                        drive.rememberCloudStorageRepository(param.thisObject)
                        param.result = if (kind == BACKUP_TYPE_WEBDAV) drive.webDavAccountAuthFlow() else drive.localLibraryAccountAuthFlow()
                    } catch (error: Throwable) { param.throwable = error }
                }
            })
            HookRuntime.deoptimize(auth)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
    fun operate(name: String, kind: Int, args: Array<Any?>): Any? = with(drive) {
        val local = kind == BACKUP_TYPE_LOCAL_LIBRARY
        fun path(index: Int): String {
            val value = args[index] as String
            return if (local) localLibraryPathArg(value) else webDavPathArg(value)
        }
        when (name) {
            "openFolder" -> {
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
            "listFolders" -> {
                val folder = path(1)
                if (local) listLocalLibrary(folder).filter { it.isDirectory }.map { newLocalLibraryCloudFolder(it) }
                else listWebDav(folder).filter { it.isDirectory }.map { newCloudFolder(it) }
            }
            "getBook" -> {
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
            "search" -> {
                val query = args[1] as String
                if (local) searchLocalLibraryBooks(query) else searchWebDavBooks(query)
            }
            "createFolder" -> {
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
            "delete" -> {
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
            "move" -> {
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
            "rename" -> {
                val file = if (local) localLibraryPathArg(cloudPathOf(args[1])) else webDavPathArg(cloudPathOf(args[1]))
                val requested = (args[2] as String).trim()
                check(requested.isNotEmpty()) { "文件名不能为空" }
                if (local) {
                    renameLocalLibraryEntry(file, requested)
                    refreshLocalLibrary(parentLocalLibraryPath(file))
                } else {
                    val extension = args[1]?.callString("getName").orEmpty().substringAfterLast('.', "")
                    val title = if (extension.isNotBlank() && !requested.contains('.')) "$requested.$extension" else requested
                    webDavMove(file, childWebDavPath(parentWebDavPath(file), title))
                    refreshWebDavLibrary(parentWebDavPath(file))
                }
                targetUnit()
            }
            "setBackupDir" -> {
                if (local) launchLocalLibraryFolderPicker() else {
                    val folders = args[1] as List<*>
                    val folder = folders.lastOrNull()
                    val destination = folder?.let { records.field("folder.path").get(it) } as? String
                    if (!destination.isNullOrBlank()) saveWebDavBackupDir(destination)
                }
                targetUnit()
            }
            else -> error("Unknown storage operation: $name")
        }
    }
}
