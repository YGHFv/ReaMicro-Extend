package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.widget.Toast
import com.reamicro.fix.core.HostBookAssociation
import com.reamicro.fix.core.HostBookBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** A queried navigation callback supplies the local book identity for association changes. */
internal class HostBookDetailsAssociationBridge(
    private val loader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
    private val coverFix: (Any, Any) -> Boolean,
) {
    private data class Context(
        val id: Long, val compat: Boolean, val vm: WeakReference<Any>, val repository: WeakReference<Any>,
        var scope: WeakReference<Any> = WeakReference(null), var localId: Long? = null,
        var verifiedBook: Any? = null,
    )
    private val contexts = ArrayList<Context>()
    private val navigatingBook = ThreadLocal<Any?>()
    private var sourceLocalId: Long? = null
    private var screenId: Long? = null
    private var screenCompat = false
    private var screenScope = WeakReference<Any>(null)
    private var active: Context? = null
    private val writing = AtomicBoolean()
    private val copier by lazy { HostBookAssociation(loader) }
    private val symbols get() = requireNotNull(HostBookBindings.current)
    private val ui get() = requireNotNull(HostUiBindings.current)
    private val editAction by lazy { HostBookEditAction(loader) }
    private var lastBarcode = ""
    private var associationDialog = WeakReference<HostAssociationDialog>(null)
    private val topBarContext = ThreadLocal<Context?>()

    fun install() {
        HookInstallReport.install("BookDetailsAssociationActionHook", "dynamicBarcodeAssociation") {
            val bound = symbols
            val native = ui
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                handles += HookRuntime.hookMethod(bound.constructor("vm"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val vm = param.thisObject ?: return
                        val args = requireNotNull(bound.field("args").get(vm))
                        val context = Context(
                            bound.field("args#bookId").getLong(args),
                            bound.field("args#compat").getBoolean(args), WeakReference(vm),
                            WeakReference(bound.field("repository").get(vm)),
                        )
                        contexts.removeAll { it.vm.get() == null }
                        if (contexts.size >= 8) contexts.removeAt(0)
                        contexts += context
                        bind(context)
                    }
                })
                handles += HookRuntime.hookMethod(bound.method("sourceClick"), object : HookCallback() {
                    override fun before(param: Call) {
                        param.setObjectExtra("reamicro.previousBook", navigatingBook.get())
                        navigatingBook.set(param.thisObject?.let { bound.field("sourceBook").get(it) })
                    }
                    override fun after(param: Call) {
                        navigatingBook.set(param.getObjectExtra("reamicro.previousBook"))
                    }
                })
                HookRuntime.deoptimize(bound.method("sourceClick"))
                handles += HookRuntime.hookMethod(HostNavigation.navigateMethod(loader), object : HookCallback() {
                    override fun before(param: Call) {
                        active = null
                        lastBarcode = ""
                        screenId = null
                        sourceLocalId = null
                        val route = param.args?.getOrNull(1) ?: return
                        if (!bound.type("route").isInstance(route)) return
                        val id = bound.field("route#bookId").getLong(route)
                        val book = navigatingBook.get() ?: return
                        if (bound.field("book#cloudId").getLong(book) == id &&
                            !bound.field("route#compat").getBoolean(route)) {
                            sourceLocalId = bound.field("book#id").getLong(book).takeIf { it > 0 }
                        }
                    }
                })
                handles += HookRuntime.hookMethod(bound.method("screen"), object : HookCallback() {
                    override fun before(param: Call) {
                        val args = param.args?.getOrNull(1) ?: return
                        screenId = bound.field("args#bookId").getLong(args)
                        screenCompat = bound.field("args#compat").getBoolean(args)
                        screenScope = WeakReference(param.args?.getOrNull(0))
                        contexts.lastOrNull { it.id == screenId && it.compat == screenCompat }?.let(::bind)
                    }
                })
                handles += HookRuntime.hookMethod(bound.method("topRender"), object : HookCallback() {
                    override fun before(param: Call) {
                        param.setObjectExtra("reamicro.previousTopBar", topBarContext.get())
                        topBarContext.remove()
                        val callback = param.thisObject ?: return
                        val context = active?.takeIf(::current) ?: return
                        val vm = context.vm.get() ?: return
                        val state = bound.method("currentState").invoke(vm) ?: return
                        if (bound.isTopCallback(callback, state)) topBarContext.set(context)
                    }
                    override fun after(param: Call) {
                        topBarContext.set(param.getObjectExtra("reamicro.previousTopBar") as? Context)
                    }
                })
                HookRuntime.deoptimize(bound.method("topRender"))
                handles += HookRuntime.hookMethod(bound.method("topBar"), object : HookCallback() {
                    override fun before(param: Call) {
                        val context = topBarContext.get()?.takeIf(::current) ?: return
                        val barcode = barcode(context).takeIf { it.length >= 8 && it.all(Char::isDigit) } ?: return
                        if (context.localId == null || !settingsProvider().canRunAssociation) return
                        val original = param.args[7]
                        val functionType = native.type("function3")
                        val invoke = native.method("invoke3")
                        val callback = native.callback(functionType) { args ->
                            val composer = args?.getOrNull(1) ?: return@callback
                            if (original != null) invoke.invoke(original, args[0], composer, args.getOrNull(2) ?: 0)
                            if (current(context)) editAction.render("关联操作", composer) { open(context) }
                        }
                        param.args[7] = native.lambda(0x524d7042, callback)
                        param.args[10] = (param.args[10] as Int) and 128.inv()
                        param.args[9] = com.reamicro.fix.core.HostUiArguments.topBarActionsFlags(param.args[9] as Int)
                        if (lastBarcode != barcode) {
                            lastBarcode = barcode
                            HookRuntime.logAlways("ReaMicro barcode menu bound localId=${context.localId} detailId=${context.id}")
                        }
                    }
                })
                handles += HookRuntime.hookMethod(HostNavigation.controllerPopMethod(loader), object : HookCallback() {
                    override fun after(param: Call) {
                        val scope = active?.scope?.get() ?: return
                        if (HostNavigation.controller(scope) === param.thisObject && param.result == true) {
                            active = null
                            screenId = null
                            sourceLocalId = null
                        }
                    }
                })
                HookRuntime.logAlways("ReaMicro association menu installed: dynamic navigation, toolbar, record copy")
            } catch (error: Throwable) {
                handles.asReversed().forEach { handle -> runCatching { handle.unhook() }.onFailure(error::addSuppressed) }
                contexts.clear()
                active = null
                navigatingBook.remove()
                topBarContext.remove()
                throw error
            }
        }
    }

    private fun bind(context: Context) {
        if (context.id != screenId || context.compat != screenCompat) return
        context.scope = screenScope
        context.localId = sourceLocalId
        if (active !== context) {
            HookRuntime.logAlways("ReaMicro details source bound localId=${context.localId} detailId=${context.id}")
        }
        active = context
    }

    private fun current(context: Context): Boolean =
        active === context && context.id == screenId && context.vm.get() != null && context.scope.get() != null

    private fun barcode(context: Context): String {
        val vm = context.vm.get() ?: return ""
        val state = symbols.method("currentState").invoke(vm) ?: return ""
        return symbols.field("state#barcode").get(state) as? String ?: ""
    }

    private fun load(context: Context): Pair<Any, Any> {
        check(current(context)) { "详情页面已变化" }
        val localId = requireNotNull(context.localId) { "没有明确的本地书身份" }
        val repository = requireNotNull(context.repository.get())
        val bookshelf = requireNotNull(symbols.field("shelf").get(repository))
        val dao = requireNotNull(symbols.field("dao").get(bookshelf))
        val find = symbols.method("find")
        val book = requireNotNull(HostCoroutines.invoke(loader, find, dao, localId)) { "本地书已不存在" }
        check(symbols.field("book#id").getLong(book) == localId)
        check(symbols.field("book#cloudId").getLong(book) == context.id) { "关联已变化，请重新打开详情" }
        return bookshelf to book
    }

    private fun open(context: Context) {
        if (!current(context)) return
        val activity = activityProvider() ?: return
        com.reamicro.fix.reload.ModuleResources.thread({
            val result = runCatching {
                val (_, book) = load(context)
                context.verifiedBook = book
                val before = copier.snapshot(book)
                val changed = copier.withoutAssociation(book)
                check(copier.snapshot(changed) == before + mapOf("cloudId" to 0L, "publisher" to ""))
                HookRuntime.logAlways("ReaMicro association copy verified fields=${before.size} localId=${context.localId} changed=cloudId,publisher")
            }
            activity.runOnModuleUiThread {
                if (!current(context)) return@runOnModuleUiThread
                result.onSuccess { dialog(activity, context) }.onFailure {
                    HookRuntime.logError("ReaMicro association menu unavailable", it)
                    Toast.makeText(activity, it.message ?: "无法读取本地书", Toast.LENGTH_LONG).show()
                }
            }
        }, "ReaMicroAssociationRead").start()
    }

    private fun dialog(activity: Activity, context: Context) {
        val settings = settingsProvider()
        val actions = buildList {
            if (settings.canUseAssociationCoverFix) add(HostAssociationDialog.Action("封面修复", "重新获取关联书籍的封面") {
                val vm = context.vm.get()
                val book = context.verifiedBook
                if (vm == null || book == null || !coverFix(vm, book)) {
                    HookRuntime.logAlways("ReaMicro association cover context unavailable localId=${context.localId}")
                    Toast.makeText(activity, "详情页面已变化，请重新打开后修复封面", Toast.LENGTH_LONG).show()
                }
            })
            if (settings.canRunAssociation && settings.associationUnlinkEnabled) {
                add(HostAssociationDialog.Action("重新关联", "解除当前关联后，选择新的关联书籍") { unlink(context, true) })
                add(HostAssociationDialog.Action("取消关联", "保留本地书籍，解除当前书库关联", destructive = true) { unlink(context, false) })
            }
        }
        associationDialog.get()?.dismiss()
        val dialog = HostAssociationDialog(activity, actions) { current(context) }
        associationDialog = WeakReference(dialog)
        dialog.show()
    }

    private fun unlink(context: Context, again: Boolean) {
        if (!current(context) || !writing.compareAndSet(false, true)) return
        com.reamicro.fix.reload.ModuleResources.thread({
            val result = runCatching {
                val (repository, original) = load(context)
                val replacement = copier.withoutAssociation(original)
                check(current(context))
                HostCoroutines.invoke(loader, symbols.method("update"), repository, replacement)
                val dao = requireNotNull(symbols.field("dao").get(repository))
                val find = symbols.method("find")
                val readBack = requireNotNull(HostCoroutines.invoke(loader, find, dao, requireNotNull(context.localId)))
                check(copier.snapshot(readBack) == copier.snapshot(replacement)) { "关联更新回读不一致" }
                HookRuntime.logAlways("ReaMicro association update verified fields=${copier.snapshot(replacement).size} localId=${context.localId}")
            }
            writing.set(false)
            activityProvider()?.runOnModuleUiThread {
                result.onSuccess {
                    Toast.makeText(activityProvider(), "已取消关联", Toast.LENGTH_SHORT).show()
                    if (current(context)) context.scope.get()?.let { scope ->
                        HostNavigation.pop(scope)
                        if (again) {
                            val route = symbols.constructor("publish").newInstance(context.localId)
                            HostNavigation.navigate(loader, scope, route)
                        }
                    }
                }.onFailure {
                    HookRuntime.logError("ReaMicro association update failed", it)
                    Toast.makeText(activityProvider(), it.message ?: "取消关联失败", Toast.LENGTH_LONG).show()
                }
            }
        }, "ReaMicroAssociationUpdate").start()
    }
}
