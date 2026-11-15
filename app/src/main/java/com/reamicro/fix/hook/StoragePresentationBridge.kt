package com.reamicro.fix.hook

import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Executable

/** Deferred native labels capture the replacement before leaving the import row. */
internal class StoragePresentationBridge(private val drive: WebDavDriveHook) {
    private val host = requireNotNull(HostStorageBindings.presentation)
    private val importKind = ThreadLocal<Int?>()
    private val treeKind = ThreadLocal<Int?>()
    private val localDetail = ThreadLocal<Boolean?>()
    private val onlineBook = ThreadLocal<Boolean?>()
    private val hiddenDuration = ThreadLocal<String?>()
    private data class CloudRow(val book: Any, val title: String, val extension: String, val updatedAt: Long,
        var titleSeen: Boolean = false, var extensionRead: Boolean = false, var extensionHidden: Boolean = false, var finished: Boolean = false)
    private val cloudRow = ThreadLocal<CloudRow?>()

    fun install() {
        val ui = requireNotNull(HostUiBindings.current)
        check(host.method("text") == ui.method("text"))
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        fun hook(method: Executable, callback: HookCallback) { handles += HookRuntime.hookMethod(method, callback) }
        try {
            for (role in listOf("authorized", "unauthorized")) hook(host.method(role), object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("presentation.import", importKind.get())
                    importKind.set((param.args[0] as Number).toInt())
                }
                override fun after(param: Call) { restore(importKind, param.getObjectExtra("presentation.import") as? Int) }
            })
            hook(host.constructor("label"), object : HookCallback() {
                override fun before(param: Call) {
                    val tag = (param.args[1] as Number).toInt()
                    if (tag !in listOf(host.value("label.authorized"), host.value("label.unauthorized"))) return
                    when (importKind.get()) {
                        BACKUP_TYPE_WEBDAV -> param.args[0] = WEBDAV_TITLE
                        BACKUP_TYPE_LOCAL_LIBRARY -> param.args[0] = LOCAL_LIBRARY_TITLE
                    }
                }
            })
            hook(host.constructor("detail"), object : HookCallback() {
                override fun before(param: Call) {
                    if (importKind.get() != BACKUP_TYPE_LOCAL_LIBRARY) return
                    param.args[0] = LOCAL_LIBRARY_BROWSE_TEXT
                    param.args[1] = ""
                }
            })
            hook(host.method("detail"), object : HookCallback() {
                override fun before(param: Call) {
                    val content = param.thisObject ?: return
                    param.setObjectExtra("presentation.detail.active", true)
                    param.setObjectExtra("presentation.detail", localDetail.get())
                    localDetail.set((host.field("detail.tag").get(content) as Number).toInt() == host.value("detail.tag") &&
                        host.field("detail.first").get(content) == LOCAL_LIBRARY_BROWSE_TEXT && host.field("detail.second").get(content) == "")
                }
                override fun after(param: Call) {
                    if (param.getObjectExtra("presentation.detail.active") == true)
                        restore(localDetail, param.getObjectExtra("presentation.detail") as? Boolean)
                }
            })
            hook(host.method("tree"), object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("presentation.tree", treeKind.get())
                    treeKind.set((param.args[host.value("tree.provider")] as Number).toInt())
                }
                override fun after(param: Call) { restore(treeKind, param.getObjectExtra("presentation.tree") as? Int) }
            })
            hook(host.method("android"), object : HookCallback() {
                override fun before(param: Call) {
                    if (treeKind.get() == BACKUP_TYPE_WEBDAV) drive.getComposeWebDavVector()?.let { param.result = it }
                }
            })
            hook(host.method("bookInfo"), object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("presentation.online", onlineBook.get())
                    onlineBook.set(param.args[1]?.let { isOnlineCompletionLocalBook(it) } == true)
                }
                override fun after(param: Call) { restore(onlineBook, param.getObjectExtra("presentation.online") as? Boolean) }
            })
            hook(host.method("duration"), object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("presentation.duration", hiddenDuration.get())
                    hiddenDuration.remove()
                    if (onlineBook.get() != true) return
                    val read = host.method("durationFormat").invoke(null, (param.args[1] as Number).toLong()) as String
                    val audio = (param.args[2] as Number).toLong()
                    hiddenDuration.set(read + if (audio > 0) "（伴听${host.method("durationFormat").invoke(null, audio)}）" else "")
                }
                override fun after(param: Call) { restore(hiddenDuration, param.getObjectExtra("presentation.duration") as? String) }
            })
            hook(host.method("cloudRow"), object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("presentation.cloud", cloudRow.get())
                    cloudRow.remove()
                    if (!drive.canUseCloudExtendedDisplay()) return
                    val book = param.args[1] ?: return
                    val path = host.field("cloud.path").get(book) as String
                    if (isOnlineCompletionPath(path)) return
                    val updatedAt = host.field("cloud.updatedAt").getLong(book)
                    if (updatedAt <= 0L) return
                    val title = host.method("title").invoke(book) as String
                    val extension = cloudBookExtensionLabel(host.method("extension").invoke(book) as String)
                    cloudRow.set(CloudRow(book, title, extension, updatedAt))
                }
                override fun after(param: Call) { restore(cloudRow, param.getObjectExtra("presentation.cloud") as? CloudRow) }
            })
            hook(host.method("extension"), object : HookCallback() {
                override fun after(param: Call) {
                    val row = cloudRow.get() ?: return
                    if (param.thisObject === row.book && row.titleSeen && !row.finished) row.extensionRead = true
                }
            })
            hook(host.method("text"), object : HookCallback() {
                override fun before(param: Call) {
                    val text = param.args[0] as? String ?: return
                    if (localDetail.get() == true && text == "$LOCAL_LIBRARY_BROWSE_TEXT / ") {
                        replaceText(param, LOCAL_LIBRARY_BROWSE_TEXT)
                        return
                    }
                    if ((drive.localLibraryDefaultFolderDepth.get() ?: 0) > 0 && text.isEmpty()) {
                        param.result = null
                        return
                    }
                    if (hiddenDuration.get() == text) { replaceText(param, ""); return }
                    val row = cloudRow.get() ?: return
                    if (row.finished) return
                    if (!row.titleSeen) { if (text == row.title) row.titleSeen = true; return }
                    if (row.extensionRead && !row.extensionHidden && text == row.extension) {
                        row.extensionHidden = true
                        param.result = null
                        return
                    }
                    if (row.extensionHidden && text.isNotBlank() && '\n' !in text) {
                        val time = formatCloudUpdatedTime(row.updatedAt)
                        if (time.isNotBlank()) {
                            replaceText(param, "${row.extension}  $text\n$time")
                            param.args[12] = 2
                            // maxLines is parameter 12, in the second changed/default masks.
                            param.args[18] = ((param.args[18] as Int) and (14 shl 6).inv()) or (6 shl 6)
                            param.args[19] = (param.args[19] as Int) and (1 shl 12).inv()
                        }
                        row.finished = true
                    }
                }
            })
            for (role in listOf("authorized", "unauthorized", "detail", "tree", "bookInfo", "duration", "cloudRow"))
                HookRuntime.deoptimize(host.method(role))
            ModuleResources.cleanup {
                importKind.remove(); treeKind.remove(); localDetail.remove(); onlineBook.remove(); hiddenDuration.remove(); cloudRow.remove()
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
    private fun replaceText(param: HookCallback.Call, value: String) {
        param.args[0] = value
        param.args[17] = ((param.args[17] as Int) and 14.inv()) or 6
        param.args[19] = (param.args[19] as Int) and 1.inv()
    }
    private fun <T> restore(local: ThreadLocal<T?>, previous: T?) {
        if (previous == null) local.remove() else local.set(previous)
    }
}
