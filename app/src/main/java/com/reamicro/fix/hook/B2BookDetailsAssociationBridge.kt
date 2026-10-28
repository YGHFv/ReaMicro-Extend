package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context as AndroidContext
import com.reamicro.fix.core.HostAndroidViews
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.HostSymbols
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
    private val androidViews by lazy { HostAndroidViews(loader) }
    private var lastBarcode = ""
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
                    val callback = function("kotlin.jvm.functions.Function3") { values ->
                        val composer = values?.getOrNull(1) ?: return@function unit()
                        renderBarcode(context, barcode, composer)
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

    private fun renderBarcode(context: Context, value: String, composer: Any) {
        if (b4) {
            val factory = function("kotlin.jvm.functions.Function1") { args ->
                val activity = requireNotNull(activityProvider())
                TextView((args?.firstOrNull() as? AndroidContext) ?: activity).apply {
                    val density = resources.displayMetrics.density
                    setPadding((16 * density).toInt(), (10 * density).toInt(),
                        (16 * density).toInt(), (10 * density).toInt())
                    typeface = EmbeddedHostUi.uiTypeface(activity)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    text = value
                    contentDescription = value
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    isFocusable = true
                    setOnClickListener { open(context) }
                }
            }
            val update = function("kotlin.jvm.functions.Function1") { args ->
                val view = args?.firstOrNull() as? TextView ?: return@function unit()
                view.text = value
                view.contentDescription = value
                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                activityProvider()?.let { activity ->
                    EmbeddedHostUi.snapshot(activity)?.roles?.get("OnBackground")?.let(view::setTextColor)
                }
                view.setOnClickListener { open(context) }
                unit()
            }
            val modifier = loader.hostClass("ReaMicroHost.Modifier").getDeclaredField("b").get(null)
            androidViews.render(factory, requireNotNull(modifier), composer, update)
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
        method.invoke(null, value, modifier, color, 0L, null, null, 0L, null, null, 0L,
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
        Thread({
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
        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        val dark = (activity.resources.configuration.uiMode and 0x30) == 0x20
        val native = EmbeddedHostUi.snapshot(activity)
        val fill = native?.brightArgb ?: if (dark) Color.rgb(35, 35, 35) else Color.WHITE
        val foreground = native?.roles?.get("OnBackground") ?: if (dark) Color.WHITE else Color.BLACK
        val primary = native?.roles?.get("Primary") ?: Color.rgb(57, 126, 184)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22*density).toInt(), (22*density).toInt(), (22*density).toInt(), (18*density).toInt())
            background = GradientDrawable().apply { setColor(fill); cornerRadius = 24*density }
        }
        card.addView(TextView(activity).apply {
            text = "关联操作"; textSize = 19f; setTextColor(foreground)
            typeface = Typeface.create(EmbeddedHostUi.uiTypeface(activity), Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (16*density).toInt() }
        })
        fun button(title: String, backgroundColor: Int, textColor: Int, action: () -> Unit) {
            card.addView(TextView(activity).apply {
                text = title; textSize = 16f; gravity = Gravity.CENTER; setTextColor(textColor)
                typeface = EmbeddedHostUi.uiTypeface(activity)
                setPadding((16*density).toInt(), (14*density).toInt(), (16*density).toInt(), (14*density).toInt())
                background = GradientDrawable().apply { setColor(backgroundColor); cornerRadius = 12*density }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10*density).toInt() }
                setOnClickListener { if (current(context)) { dialog.dismiss(); action() } else dialog.dismiss() }
            })
        }
        val settings = settingsProvider()
        if (settings.canUseAssociationCoverFix) button("封面修复", fill, primary) {
            val vm = context.vm.get()
            val book = context.verifiedBook
            if (vm == null || book == null || !coverFix(vm, book)) {
                XposedBridge.logAlways("ReaMicro association cover context unavailable localId=${context.localId}")
                Toast.makeText(activity, "详情页面已变化，请重新打开后修复封面", Toast.LENGTH_LONG).show()
            }
        }
        if (settings.canRunAssociation && settings.associationUnlinkEnabled) {
            button("取消关联", Color.argb(24, 190, 40, 40), Color.rgb(190, 40, 40)) { unlink(context, false) }
            button("重新关联", primary, native?.roles?.get("OnPrimary") ?: Color.WHITE) { unlink(context, true) }
        }
        button("取消操作", fill, foreground) { }
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun unlink(context: Context, again: Boolean) {
        if (!current(context) || !writing.compareAndSet(false, true)) return
        Thread({
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
