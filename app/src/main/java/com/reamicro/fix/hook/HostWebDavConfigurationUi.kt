package com.reamicro.fix.hook

import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostSyncBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap

/** Scoped native S3 rendering; all host S3 callbacks outside these pages retain their parameters. */
internal class HostWebDavConfigurationUi(private val loader: ClassLoader) {
    val host = requireNotNull(HostSyncBindings.current)
    val ui = requireNotNull(HostUiBindings.current)
    private val unit get() = HostCoroutines.unit(loader)
    private val pages = ThreadLocal.withInitial { java.util.ArrayDeque<WebDavSyncConfigurationPage>() }
    private val entryDepth = ThreadLocal.withInitial { 0 }
    private val entryNames = ThreadLocal.withInitial { java.util.ArrayDeque<String>() }
    private val headerDepth = ThreadLocal.withInitial { 0 }
    private val cards = Collections.synchronizedMap(WeakHashMap<Any, WebDavSyncConfigurationPage>())
    private val fields = Collections.synchronizedMap(WeakHashMap<Any, WebDavSyncConfigurationPage>())
    private val headerRestarts = Collections.synchronizedMap(WeakHashMap<Any, WebDavSyncConfigurationPage>())
    val renderingEntry get() = entryDepth.get() > 0

    /** The replacement slot owns state reads, so native child slots cannot restart outside its page. */
    private fun content(original: Any): Any = if (ui.type("lambda").isInstance(original))
        requireNotNull(ui.field("destinationCallback").get(original)) else original

    private fun pageSlot(page: WebDavSyncConfigurationPage, original: Any, key: Int): Any {
        val block = content(original)
        return ui.lambda(key, ui.function(ui.type("function3")) { args ->
            inPage(page) { ui.method("invoke3").invoke(block, *(args ?: emptyArray())) }
        }).also { cards[it] = page }
    }

    private fun <T> inPage(page: WebDavSyncConfigurationPage, action: () -> T): T {
        pages.get().push(page)
        try { return action() } finally { pages.get().pop() }
    }
    private fun <T> inEntry(name: String = entryNames.get().peek() ?: "WebDAV", action: () -> T): T {
        entryDepth.set(entryDepth.get() + 1)
        entryNames.get().push(name)
        try { return action() } finally {
            entryNames.get().pop()
            entryDepth.set(entryDepth.get() - 1)
        }
    }
    fun install(register: (Executable, HookCallback) -> Unit) {
        check(ui.loader === loader)
        register(host.method("configurationCard"), object : HookCallback() {
            override fun before(param: Call) {
                val original = param.args.getOrNull(1) ?: return
                val page = cards[original] ?: pages.get().peek() ?: return
                pages.get().push(page)
                param.setObjectExtra("webdav.page", true)
                if (cards[original] != null) return
                param.args[1] = pageSlot(page, original, 0x524d5743)
            }
            override fun after(param: Call) {
                if (param.getObjectExtra("webdav.page") == true) pages.get().pop()
            }
        })
        register(host.method("configurationHeader"), object : HookCallback() {
            override fun before(param: Call) {
                pages.get().peek() ?: return
                headerDepth.set(headerDepth.get() + 1)
                param.setObjectExtra("webdav.header", true)
            }
            override fun after(param: Call) {
                if (param.getObjectExtra("webdav.header") == true) headerDepth.set(headerDepth.get() - 1)
            }
        })
        register(host.constructor("configurationHeaderRestart"), object : HookCallback() {
            override fun after(param: Call) {
                if (headerDepth.get() == 0) return
                val page = pages.get().peek() ?: return
                param.thisObject?.let { headerRestarts[it] = page }
            }
        })
        register(host.method("configurationHeaderRestart"), object : HookCallback() {
            override fun before(param: Call) {
                val page = param.thisObject?.let(headerRestarts::get) ?: return
                pages.get().push(page)
                param.setObjectExtra("webdav.page", true)
            }
            override fun after(param: Call) {
                if (param.getObjectExtra("webdav.page") == true) pages.get().pop()
            }
        })
        register(host.method("configurationIcon"), object : HookCallback() {
            override fun before(param: Call) {
                if (headerDepth.get() > 0 && param.args[1] == "Other S3")
                    param.args[1] = pages.get().peek()?.providerName ?: return
            }
        })
        register(host.method("configurationField"), object : HookCallback() {
            override fun before(param: Call) {
                val page = pages.get().peek() ?: param.args.getOrNull(6)?.let(fields::get) ?: return
                val key = when (param.args[0]) {
                    "Endpoint", "地址", "私有仓库" -> "address"
                    "Access Key", "用户名", "分支（留空使用默认分支）" -> "username"
                    "Secret Key", "密码", "Token" -> "password"
                    else -> return
                }
                param.args[0] = page.label(key)
                param.args[3] = page.readOnly()
                // Other S3 gives Endpoint a multiline field. WebDAV addresses use the same
                // native single-line geometry as Access Key, including on independent restarts.
                param.args[5] = false
                param.args[9] = (param.args[9] as Int) and (8 or 32).inv()
                // The host remembers this callback and can recompose the input card independently.
                // Keep its identity bound as well as the callback that reaches the module draft.
                param.args.getOrNull(6)?.let { fields[it] = page }
                val callback = ui.callback(ui.type("function1")) { args ->
                    (args?.firstOrNull() as? String)?.let { page.change(key, it) }
                }
                fields[callback] = page
                param.args[6] = callback
                param.args[8] = (param.args[8] as Int) and
                    (14 or (7 shl 10) or (7 shl 16) or (7 shl 19)).inv()
            }
        })
        register(host.method("configurationSurface"), object : HookCallback() {
            override fun before(param: Call) {
                val original = param.args.getOrNull(5) ?: return
                val page = pages.get().peek() ?: cards[original]
                if (page != null) {
                    if (cards[original] == null) param.args[5] = pageSlot(page, original, 0x524d5746)
                    return
                }
                if (!renderingEntry) return
                val block = content(original)
                val name = entryNames.get().peek() ?: "WebDAV"
                param.args[5] = ui.lambda(0x524d5745, ui.function(ui.type("function3")) { args ->
                    inEntry(name) { ui.method("invoke3").invoke(block, *(args ?: emptyArray())) }
                })
            }
        })
        register(ui.method("text"), object : HookCallback() {
            override fun before(param: Call) {
                if (!renderingEntry && headerDepth.get() == 0) return
                val text = param.args.getOrNull(0) as? String ?: return
                if (text == "Other S3" || text.contains("其他") && text.contains("S3")) {
                    param.args[0] = entryNames.get().peek() ?: pages.get().peek()?.providerName ?: "WebDAV"
                    param.args[17] = (param.args[17] as Int) and 14.inv()
                }
            }
        })
        register(ui.method("text"), object : HookCallback() {
            override fun before(param: Call) {
                val page = pages.get().peek() ?: return
                if (!page.github || param.args[0] != host.text("configurationDescription")) return
                param.args[0] = "使用 GitHub 私有仓库同步书架、阅读进度和书籍。仓库填写 所有者/仓库 或 GitHub 地址，分支留空使用默认分支。" +
                    "Token 需要 Contents 读写或 repo 权限。请先在仓库创建一次提交；单个文件最大 40 MiB。保存后使用阅微原生同步。"
                param.args[17] = (param.args[17] as Int) and 14.inv()
            }
        })
        listOf("configurationBody", "configurationSaveBar", "configurationCard", "configurationHeader", "configurationHeaderRestart",
            "configurationField", "configurationSurface", "configurationIcon", "otherProvider").forEach { HookRuntime.deoptimize(host.method(it)) }
        ModuleResources.cleanup {
            cards.clear(); fields.clear(); headerRestarts.clear()
            pages.remove(); entryDepth.remove(); entryNames.remove(); headerDepth.remove()
        }
    }

    fun renderEntry(click: Any, composer: Any, name: String = "WebDAV") = inEntry(name) {
        host.method("otherProvider").invoke(null, click, composer, 0)
    }

    fun entryGap(composer: Any) {
        host.method("configurationProviderGap").invoke(null, host.number("configurationProviderGap"), ui.value("modifier"), composer)
    }

    fun render(page: WebDavSyncConfigurationPage, composer: Any) {
        val top = ui.lambda(0x524d5754, ui.callback(ui.type("function2")) { args ->
            host.method("configurationTopBar").invoke(null, "配置${page.providerName}", null, null, null, null, 0L,
                null, null, requireNotNull(args?.getOrNull(0)), 6, 254)
        })
        val click = ui.callback(ui.type("function0")) { page.submit() }
        val bottom = ui.lambda(0x524d5753, ui.function(ui.type("function2")) { args ->
            // Read config and busy in the footer's own composition, not before Scaffold creates it.
            val saveArgs = arrayListOf<Any?>(click, page.canSave(), true, page.busy)
            if (host.constructor("configurationSaveBar").parameterCount == 5)
                saveArgs += host.number("configurationSaveBar.branch").toByte()
            val save = host.constructor("configurationSaveBar").newInstance(*saveArgs.toTypedArray())
            host.method("configurationSaveBar").invoke(save, requireNotNull(args?.getOrNull(0)),
                ((args.getOrNull(1) as? Int) ?: 0) or 1)
            unit
        })
        val body = ui.lambda(0x524d5742, ui.function(ui.type("function3")) { args ->
            inPage(page) { host.method("configurationBody").invoke(page.body, *(args ?: emptyArray())) }
        })
        val scheme = requireNotNull(ui.call("colorScheme", composer))
        val background = host.method("configurationBackground").invoke(null, scheme) as Long
        // Share the IME viewport with the footer. The native body then observes consumed
        // keyboard insets instead of reserving another keyboard gap below its scroll area.
        val viewport = host.method("configurationImePadding").invoke(null, ui.value("modifier"))
        ui.call("scaffold", viewport, top, bottom, null, null, 0, background, 0L, null, body, composer,
            host.number("configurationScaffold.flags") and 14.inv(),
            host.number("configurationScaffold.mask") and 1.inv())
    }

    fun release(page: WebDavSyncConfigurationPage) {
        synchronized(cards) { cards.entries.removeAll { it.value === page } }
        synchronized(fields) { fields.entries.removeAll { it.value === page } }
        synchronized(headerRestarts) { headerRestarts.entries.removeAll { it.value === page } }
    }
}
