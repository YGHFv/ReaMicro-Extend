package com.reamicro.fix.hook

import com.reamicro.fix.core.HostHomeSearchBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.cloud.webdav.HomeSearchRenderContext
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Preserve the native result map and carry extension sections into deferred lazy-list builds. */
internal class HostHomeSearchUiBridge(private val drive: WebDavDriveHook) {
    private val title = ThreadLocal<String?>()
    fun install() {
        val host = HostHomeSearchBindings.requireCurrent()
        val ui = requireNotNull(HostUiBindings.current)
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(host.method("lazy"), object : HookCallback() {
                override fun before(param: Call) {
                    val context = drive.homeWebDavSearchRender.get() ?: return
                    if (context.wrapped) return
                    val callback = param.args[host.slot("lazy.content")] ?: return
                    if (!host.builderMatches(callback, context.intentReceiver)) return
                    val original = host.nativeOnly(callback, context.intentReceiver, setOf(
                        BACKUP_TYPE_WEBDAV, BACKUP_TYPE_LOCAL_LIBRARY, BACKUP_TYPE_ONLINE_COMPLETION,
                    ))
                    context.wrapped = true
                    param.args[host.slot("lazy.content")] = ui.function(host.type("function1")) { args ->
                        val scope = requireNotNull(args?.getOrNull(0))
                        val previous = drive.homeWebDavSearchRender.get()
                        val frame = HomeSearchRenderContext(context.sections, context.intentReceiver,
                            context.generation, wrapped = true, navGraphScope = context.navGraphScope)
                        drive.homeWebDavSearchRender.set(frame)
                        try {
                            host.method("invoke").invoke(original, scope)
                        } finally {
                            if (previous == null) drive.homeWebDavSearchRender.remove()
                            else drive.homeWebDavSearchRender.set(previous)
                        }
                    }
                }
            })
            handles += HookRuntime.hookMethod(host.method("footer"), object : HookCallback() {
                override fun before(param: Call) {
                    val context = drive.homeWebDavSearchRender.get() ?: return
                    if (context.rendered) return
                    val scope = param.args[0] ?: return
                    context.rendered = true
                    context.sections.forEach { section ->
                        drive.addHomeWebDavSearchSection(scope, section.type, section.title, section.results,
                            context.intentReceiver, context.generation, context.navGraphScope)
                    }
                }
            })
            handles += HookRuntime.hookMethod(host.method("results"), object : HookCallback() {
                override fun before(param: Call) {
                    param.setObjectExtra("reamicro.search.title", title.get())
                    val kind = param.args[host.slot("results.type")] as Int
                    title.set(when {
                        kind == BACKUP_TYPE_WEBDAV -> WEBDAV_TITLE
                        kind == BACKUP_TYPE_LOCAL_LIBRARY -> LOCAL_LIBRARY_TITLE
                        drive.isOnlineCompletionRenderType(kind) -> drive.onlineCompletionTitleForType(kind)
                        else -> null
                    })
                }
                override fun after(param: Call) {
                    val previous = param.getObjectExtra("reamicro.search.title") as? String
                    if (previous == null) title.remove() else title.set(previous)
                }
            })
            handles += HookRuntime.hookMethod(host.method("text"), object : HookCallback() {
                override fun before(param: Call) {
                    val replacement = title.get() ?: return
                    val label = param.args[0] as? String ?: return
                    if (label.matches(Regex("本地存储\\(\\d+\\)"))) {
                        param.args[0] = replacement + label.removePrefix("本地存储")
                    }
                }
            })
            listOf("root", "builder", "lazy", "footer", "results").forEach { HookRuntime.deoptimize(host.method(it)) }
            ModuleResources.cleanup { title.remove(); drive.homeWebDavSearchRender.remove() }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
}
