package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.HostSymbols
import android.widget.Toast
import com.reamicro.fix.core.B2BookAssociation
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** 条码菜单独占已核实的 B2/B4 写回；仅从本地书详情导航取得身份，不从数字文本猜书籍。 */
internal class B2BookDetailsAssociationBridge(
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
    private val copier by lazy { B2BookAssociation(loader) }
    private val ui by lazy { B2NativeUi(loader) }
    private val b4 = HostSymbols.abiProfileId == "2.3.3-b4"
    private val navigation = requireNotNull(HostNativeAbi.navigation(HostSymbols.profileId))
    private val editAction by lazy { HostBookEditAction(loader) }
    private var lastBarcode = ""
    private var associationDialog = WeakReference<HostAssociationDialog>(null)
    private val topBarContext = ThreadLocal<Context?>()

    fun install() {
        HookInstallReport.install("BookDetailsAssociationActionHook", if (b4) "b4BarcodeAssociation" else "b2BarcodeAssociation") {
            val vmType = loader.hostClass("app.zhendong.reamicro.ui.book.BookDetailsViewModel")
            XposedBridge.hookAllConstructors(vmType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val vm = param.thisObject ?: return
                    val args = requireNotNull(vm.javaClass.hostField("args").get(vm))
                    val context = Context(
                        args.javaClass.hostField("bookId").getLong(args),
                        args.javaClass.hostField("compat").getBoolean(args), WeakReference(vm),
                        WeakReference(vm.javaClass.hostField("bookRepository").get(vm)),
                    )
                    contexts.removeAll { it.vm.get() == null }
                    if (contexts.size >= 8) contexts.removeAt(0)
                    contexts += context
                    bind(context)
                }
            })
            val click = loader.hostClass("ReaMicroHost.BookOverviewClick")
            XposedBridge.hookMethod(click.getDeclaredMethod("d"), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.thisObject ?: return
                    if (click.getDeclaredField("a").getByte(callback).toInt() == (if (b4) 3 else 1)) {
                        navigatingBook.set(click.getDeclaredField("c").get(callback))
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) { navigatingBook.remove() }
            })
            XposedBridge.deoptimize(click.getDeclaredMethod("d"))
            val scope = loader.hostClass("app.zhendong.reamicro.NavGraphScope")
            XposedBridge.hookMethod(scope.getDeclaredMethod(navigation.navigate, scope, Any::class.java), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    active = null
                    lastBarcode = ""
                    screenId = null
                    sourceLocalId = null
                    val route = param.args?.getOrNull(1) ?: return
                    if (route.javaClass != loader.hostClass("app.zhendong.reamicro.Route\$BookDetail")) return
                    val id = route.javaClass.hostField("bookId").getLong(route)
                    val book = navigatingBook.get() ?: return
                    if ((XposedHelpers.callMethod(book, "getCloudId") as Number).toLong() == id &&
                        !route.javaClass.hostField("compat").getBoolean(route)) {
                        sourceLocalId = (XposedHelpers.callMethod(book, "getId") as Number).toLong().takeIf { it > 0 }
                    }
                }
            })
            val screen = loader.hostClass("app.zhendong.reamicro.ui.book.BookDetailsScreenKt")
                .hostMethodsNamed("BookDetailsScreen").single { it.parameterCount == 4 }
            XposedBridge.hookMethod(screen, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args?.getOrNull(1) ?: return
                    screenId = args.javaClass.hostField("bookId").getLong(args)
                    screenCompat = args.javaClass.hostField("compat").getBoolean(args)
                    screenScope = WeakReference(param.args?.getOrNull(0))
                    contexts.lastOrNull { it.id == screenId && it.compat == screenCompat }?.let(::bind)
                }
            })
            val topLambda = loader.hostClass("ReaMicroHost.BookDetailsTopBar")
            XposedBridge.hookMethod(topLambda.getDeclaredMethod("C", Any::class.java, Any::class.java), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.thisObject ?: return
                    if (topLambda.getDeclaredField("a").getByte(callback).toInt() != 0) return
                    val context = active?.takeIf(::current) ?: return
                    val captured = topLambda.getDeclaredField("c").get(callback) ?: return
                    if (XposedHelpers.callMethod(captured, "getValue") !==
                        XposedHelpers.callMethod(requireNotNull(context.vm.get()), "getCurrentState")) return
                    topBarContext.set(context)
                }
                override fun afterHookedMethod(param: MethodHookParam) { topBarContext.remove() }
            })
            XposedBridge.deoptimize(topLambda.getDeclaredMethod("C", Any::class.java, Any::class.java))
            val topBar = loader.hostClass("app.zhendong.reamicro.arch.components.AppTopBarKt")
                .declaredMethods.single { it.name == "a" && it.parameterCount == 11 }
            XposedBridge.hookMethod(topBar, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = topBarContext.get()?.takeIf(::current) ?: return
                    val barcode = barcode(context).takeIf { it.length >= 8 && it.all(Char::isDigit) } ?: return
                    if (context.localId == null || !settingsProvider().canRunAssociation) return
                    val originalActions = param.args[7]
                    val callback = function("kotlin.jvm.functions.Function3") { values ->
                        val composer = values?.getOrNull(1) ?: return@function unit()
                        if (originalActions != null) XposedHelpers.callMethod(originalActions, "invoke",
                            values?.getOrNull(0), composer, values?.getOrNull(2) ?: 0)
                        if (current(context)) renderAssociationAction(context, composer)
                        unit()
                    }
                    param.args[7] = loader.hostClass("ReaMicroHost.ComposableLambda")
                        .getDeclaredConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Any::class.java)
                        .newInstance(0x524d7042, true, callback)
                    param.args[10] = (param.args[10] as Int) and 128.inv()
                    param.args[9] = com.reamicro.fix.core.HostUiArguments.b2TopBarActionsFlags(param.args[9] as Int)
                    if (lastBarcode != barcode) {
                        lastBarcode = barcode
                        XposedBridge.logAlways("ReaMicro B2 barcode menu bound localId=${context.localId} detailId=${context.id}")
                    }
                }
            })
            val controller = loader.hostClass("ReaMicroHost.NavController")
            XposedBridge.hookMethod(controller.getDeclaredMethod(navigation.controllerPop), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val scope = active?.scope?.get() ?: return
                    if (HostNavigation.controller(scope) === param.thisObject && param.result == true) {
                        active = null
                        screenId = null
                        sourceLocalId = null
                    }
                }
            })
            XposedBridge.logAlways("ReaMicro B2 association menu installed: exact local source/barcode/27-field copy")
        }
    }

    private fun unit(): Any? = loader.hostClass("kotlin.Unit").hostField("INSTANCE").get(null)

    private fun function(type: String, block: (Array<Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(loader.hostClass(type))) { proxy, method, values ->
            when (com.reamicro.fix.core.HostSymbols.logicalMethodName(method)) {
                "invoke" -> block(values)
                "toString" -> "ReaMicroBarcodeAssociation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === values?.firstOrNull()
                else -> null
            }
        }

    private fun renderAssociationAction(context: Context, composer: Any) {
        EmbeddedHostUi.captureTypography(loader, composer)
        BookImageDialogStyle.capture(loader, composer)
        if (b4) {
            editAction.render("关联操作", composer) { open(context) }
            return
        }
        val onClick = function("kotlin.jvm.functions.Function0") { open(context); unit() }
        val base = ui.padding(ui.modifier(), ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
        val modifier = ui.clickable(base, onClick)
        val scheme = ui.theme(composer, "getColorScheme")
        val typography = ui.theme(composer, "getTypography")
        val color = XposedHelpers.callMethod(scheme, "getOnBackground") as Long
        val style = XposedHelpers.callMethod(typography, "getLabelMedium")
        val method = loader.hostClass("androidx.compose.material3.TextKt")
            .hostMethodsNamed("Text-Nvy7gAk").single { it.parameterCount == 20 }
        method.invoke(null, "关联操作", modifier, color, 0L, null, null, 0L, null, null, 0L,
            0, false, 1, 1, null, style, composer, 0, 0, 131064 and (16384 or 32768).inv())
    }

    private fun bind(context: Context) {
        if (context.id != screenId || context.compat != screenCompat) return
        context.scope = screenScope
        context.localId = sourceLocalId
        if (active !== context) {
            XposedBridge.logAlways("ReaMicro B2 details source bound localId=${context.localId} detailId=${context.id}")
        }
        active = context
    }

    private fun current(context: Context): Boolean =
        active === context && context.id == screenId && context.vm.get() != null && context.scope.get() != null

    private fun barcode(context: Context): String {
        val vm = context.vm.get() ?: return ""
        val state = XposedHelpers.callMethod(vm, "getCurrentState") ?: return ""
        return XposedHelpers.callMethod(state, "getBarcode") as? String ?: ""
    }

    private fun load(context: Context): Pair<Any, Any> {
        check(current(context)) { "详情页面已变化" }
        val localId = requireNotNull(context.localId) { "没有明确的本地书身份" }
        val repository = requireNotNull(context.repository.get())
        val bookshelf = requireNotNull(repository.javaClass.hostField("bookshelfRepository").get(repository))
        val dao = requireNotNull(bookshelf.javaClass.hostField("bookDao").get(bookshelf))
        val find = dao.javaClass.hostMethod(if (b4) "b" else "findById", Long::class.javaPrimitiveType,
            loader.hostClass("kotlin.coroutines.Continuation"))
        val book = requireNotNull(HostCoroutines.invoke(loader, find, dao, localId)) { "本地书已不存在" }
        check((XposedHelpers.callMethod(book, "getId") as Number).toLong() == localId)
        check((XposedHelpers.callMethod(book, "getCloudId") as Number).toLong() == context.id) { "关联已变化，请重新打开详情" }
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
                XposedBridge.logAlways("ReaMicro B2 association copy verified fields=${before.size} localId=${context.localId} changed=cloudId,publisher")
            }
            activity.runOnUiThread {
                if (!current(context)) return@runOnUiThread
                result.onSuccess { dialog(activity, context) }.onFailure {
                    XposedBridge.logError("ReaMicro B2 association menu unavailable", it)
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
                    XposedBridge.logAlways("ReaMicro association cover context unavailable localId=${context.localId}")
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
                val continuation = loader.hostClass("kotlin.coroutines.Continuation")
                val update = repository.javaClass.hostMethodsNamed("updateBook").single { method ->
                    method.parameterTypes.size == 2 && method.parameterTypes[0] == original.javaClass &&
                        continuation.isAssignableFrom(method.parameterTypes[1])
                }
                HostCoroutines.invoke(loader, update, repository, replacement)
                val dao = requireNotNull(repository.javaClass.hostField("bookDao").get(repository))
                val find = dao.javaClass.hostMethod(if (b4) "b" else "findById", Long::class.javaPrimitiveType,
                    loader.hostClass("kotlin.coroutines.Continuation"))
                val readBack = requireNotNull(HostCoroutines.invoke(loader, find, dao, requireNotNull(context.localId)))
                check(copier.snapshot(readBack) == copier.snapshot(replacement)) { "关联更新回读不一致" }
                XposedBridge.logAlways("ReaMicro B2 association update verified fields=27 localId=${context.localId}")
            }
            writing.set(false)
            activityProvider()?.runOnUiThread {
                result.onSuccess {
                    Toast.makeText(activityProvider(), "已取消关联", Toast.LENGTH_SHORT).show()
                    if (current(context)) context.scope.get()?.let { scope ->
                        HostNavigation.pop(scope)
                        if (again) {
                            val route = loader.hostClass("app.zhendong.reamicro.Route\$BookPublish")
                                .getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(context.localId)
                            HostNavigation.navigate(loader, scope, route)
                        }
                    }
                }.onFailure {
                    XposedBridge.logError("ReaMicro B2 association update failed", it)
                    Toast.makeText(activityProvider(), it.message ?: "取消关联失败", Toast.LENGTH_LONG).show()
                }
            }
        }, "ReaMicroAssociationUpdate").start()
    }
}
