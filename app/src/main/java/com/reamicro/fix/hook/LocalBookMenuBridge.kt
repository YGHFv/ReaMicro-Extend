package com.reamicro.fix.hook

import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Method

/** The native click closure owns the book even when the menu recomposes after its root returns. */
internal class LocalBookMenuBridge(private val drive: WebDavDriveHook) {
    private val host = requireNotNull(HostBookUiBindings.localSheet)
    private val ui = requireNotNull(HostUiBindings.current)
    private val bookContext = ThreadLocal<Any?>()
    private val injecting = ThreadLocal<Boolean?>()
    private val clickBooks = (0 until host.number("clicks")).map { index ->
        host.type("click.$index") to host.field("clickBook.$index")
    }

    fun install() {
        check(host.type("composer") == ui.composerClass && host.type("modifier") == ui.modifierClass)
        check(host.type("function0") == ui.method("clickable").parameterTypes[4])
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        fun hook(method: Method, callback: HookCallback) { handles += HookRuntime.hookMethod(method, callback) }
        fun scope(method: Method, read: (HookCallback.Call) -> Any?) {
            hook(method, object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("localMenu.book", bookContext.get())
                    val book = read(param)?.takeIf(host.type("book")::isInstance)
                    restore(bookContext, book)
                }
                override fun after(param: Call) {
                    restore(bookContext, param.getObjectExtra("localMenu.book"))
                }
            })
        }
        try {
            scope(host.method("root")) { it.args[0] }
            scope(host.method("content")) { host.field("contentBook").get(it.thisObject) }
            scope(host.method("info")) { it.args[0] }
            hook(host.method("title"), object : HookCallback() {
                override fun before(param: Call) {
                    val book = bookContext.get() ?: return
                    val source = sourceName(book) ?: return
                    val index = host.number("subtitle")
                    param.args[index] = source
                    val changed = host.number("changed")
                    val mask = 14 shl (3 * (index % 10))
                    param.args[changed] = (param.args[changed] as Int) and mask.inv()
                }
            })
            hook(host.method("group"), object : HookCallback() {
                override fun before(param: Call) {
                    if (injecting.get() == true) return
                    val callback = param.args[2] ?: return
                    val book = clickBooks.singleOrNull { (type, _) -> type.isInstance(callback) }
                        ?.second?.get(callback) ?: return
                    val info = drive.onlineImportedBookSourceInfo(book) ?: return
                    val composer = param.args[3] ?: return
                    val icon = drive.onlineCompletionUpdateImageVector() ?: error("Local menu update icon binding unavailable")
                    val update = ui.callback(host.type("function0")) { drive.startOnlineCompletionChapterUpdate(book, info) }
                    injecting.set(true)
                    try {
                        render(icon, update, param.args[0], callback, composer)
                        param.result = null
                    } finally { injecting.remove() }
                }
            })
            for (role in listOf("root", "content", "info", "title", "group")) HookRuntime.deoptimize(host.method(role))
            ModuleResources.cleanup { bookContext.remove(); injecting.remove() }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    private fun sourceName(book: Any): String? = with(drive) {
        onlineImportedBookSourceInfo(book)?.sourceName?.takeUnless {
            it.isBlank() || it == "未知源" || isPathLikeOnlineSourceName(it)
        } ?: when (val type = bookBackupTypeOf(book)) {
            BACKUP_TYPE_BAIDU, BACKUP_TYPE_YUN115, BACKUP_TYPE_ALIYUN -> cloudTypeTitle(type)
            BACKUP_TYPE_WEBDAV -> "WebDAV"
            else -> null
        }
    }

    private fun render(updateIcon: Any, update: Any, groupIcon: Any?, group: Any, composer: Any) {
        val content = ui.callback(ui.type("function3")) { args ->
            val scope = requireNotNull(args?.getOrNull(0))
            val inner = requireNotNull(args?.getOrNull(1))
            val modifier = requireNotNull(ui.instance("weight", scope, ui.value("modifier"), 1f))
            host.method("cell").invoke(null, modifier, updateIcon, "更新", update, inner, 0)
            val color = ui.call("borderVariant", ui.call("colorScheme", inner)) as Long
            val width = ui.call("dpDouble", 0.8) as Float
            val height = ui.call("dpInt", 56) as Float
            val divider = ui.call("background",
                ui.call("height", ui.call("width", ui.value("modifier"), width), height), color, ui.value("rectangle"))
            ui.call("box", divider, inner, 0)
            host.method("cell").invoke(null, modifier, groupIcon, "分组", group, inner, 0)
        }
        ui.node(true, requireNotNull(ui.call("fillWidth", ui.value("modifier"), 1f)), null, null, content, composer)
    }

    private fun <T> restore(local: ThreadLocal<T?>, previous: T?) {
        if (previous == null) local.remove() else local.set(previous)
    }
}
