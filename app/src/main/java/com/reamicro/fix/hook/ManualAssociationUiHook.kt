package com.reamicro.fix.hook

import android.app.Activity
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.association.ManualAssociationService
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostExactRecordCopy
import com.reamicro.fix.core.HostManualAssociationAbi
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.HostUiArguments
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiMethodByArity
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** Drafts enter only this publisher VM's candidate list; the native confirmation performs writes. */
internal class ManualAssociationUiHook(
    private val loader: ClassLoader,
    private val activity: () -> Activity?,
    private val settings: () -> ModuleSettingsSnapshot,
) {
    private data class Target(val vm: WeakReference<Any>, val bookId: Long)
    private data class Page(val bookId: Long, val scope: Any)
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val ui = B2NativeUi(loader)
    private val service = ManualAssociationService()
    private val factory = ReaMicroThirdPartyBookFactory(loader)
    private val publisher = loader.hostClass("app.zhendong.reamicro.ui.book.BookPublishViewModel")
    private val stateType = loader.hostClass("app.zhendong.reamicro.ui.book.BookPublishUIState")
    private val stateCopy = HostExactRecordCopy(stateType,
        listOf("book", "recommend", "thirdPartyBooks", "searching", "relating", "isRelateSuccess"))
    private val pageStack = ThreadLocal.withInitial { ArrayList<Page>() }
    private val targets = ArrayList<Target>()
    @Volatile private var current: Target? = null
    private var currentScope = WeakReference<Any>(null)
    @Volatile private var preview: Any? = null
    private var draft: ManualAssociationDraft? = null
    private var editor = WeakReference<HostManualAssociationEditor>(null)
    private val renderGuard = ThreadLocal<Boolean>()
    private val toolbarContext = ThreadLocal<Target?>()
    private val editAction = HostBookEditAction(loader)

    fun install() {
        check(HostManualAssociationAbi.supports())
        HookInstallReport.install("ManualAssociationUiHook", "nativePublisherEditor") {
            val page = loader.hostStaticMethod(HostManualAssociationAbi.SCREEN, HostManualAssociationAbi.SCREEN_METHOD,
                loader.hostClass("app.zhendong.reamicro.NavGraphScope"), Long::class.javaPrimitiveType,
                loader.hostClass("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType)
            XposedBridge.hookAllConstructors(publisher, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val vm = param.thisObject ?: return
                    val target = Target(WeakReference(vm), publisher.hostField("bookId").getLong(vm))
                    targets.removeAll { it.vm.get() == null }
                    targets += target
                    val pageContext = pageStack.get().lastOrNull()
                    if (pageContext?.bookId == target.bookId) {
                        bind(target)
                        currentScope = WeakReference(pageContext.scope)
                    }
                }
            })
            XposedBridge.hookMethod(page, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val id = (param.args[1] as Number).toLong()
                    pageStack.get().add(Page(id, param.args[0]))
                    targets.lastOrNull { it.bookId == id && it.vm.get() != null }?.let(::bind)
                    currentScope = WeakReference(param.args[0])
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = pageStack.get()
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            })
            val scaffold = loader.hostClass("ReaMicroHost.Scaffold").hostAbiMethodByArity("a", 13)
            XposedBridge.hookMethod(scaffold, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pageContext = pageStack.get().lastOrNull() ?: return
                    val target = current?.takeIf { it.bookId == pageContext.bookId } ?: return
                    val original = param.args[1] ?: return
                    val content = function("kotlin.jvm.functions.Function2", "ManualEditorPageToolbar") { values ->
                        if (current === target) toolbarContext.set(target)
                        try {
                            XposedHelpers.callMethod(original, "invoke",
                                values?.getOrNull(0), values?.getOrNull(1) ?: 0)
                        } finally { toolbarContext.remove() }
                    }
                    // This callback is retained by native SubcomposeLayout after the screen returns.
                    param.args[1] = ui.lambda(0x524d7152, content)
                }
            })
            val toolbar = loader.hostClass("app.zhendong.reamicro.arch.components.AppTopBarKt")
                .hostMethodsNamed("AppTopBar-1YH7lEI").single { it.parameterCount == 11 }
            XposedBridge.hookMethod(toolbar, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = toolbarContext.get() ?: return
                    if (renderGuard.get() == true || !canRender(target)) return
                    val original = param.args[7]
                    val content = function("kotlin.jvm.functions.Function3", "ManualEditorToolbar") { values ->
                        val composer = requireNotNull(values?.getOrNull(1))
                        renderGuard.set(true)
                        try {
                            if (original != null) XposedHelpers.callMethod(original, "invoke",
                                values?.getOrNull(0), composer, values?.getOrNull(2) ?: 0)
                            if (canRender(target)) renderEdit(target, composer)
                        } finally { renderGuard.remove() }
                        unit()
                    }
                    param.args[7] = ui.lambda(0x524d7151, content)
                    param.args[10] = (param.args[10] as Int) and 128.inv()
                    param.args[9] = HostUiArguments.b2TopBarActionsFlags(param.args[9] as Int)
                }
            })
            XposedBridge.deoptimize(page)
            val flow = loader.hostClass("kotlinx.coroutines.flow.StateFlowImpl")
            fun observer(index: Int) = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = current ?: return
                    val vm = target.vm.get() ?: return
                    if (param.thisObject !== publisher.hostField("_uiState").get(vm)) return
                    val next = param.args.getOrNull(index) ?: return
                    if (stateType.isInstance(next)) param.args[index] = merge(next, target)
                }
            }
            XposedBridge.hookMethod(flow.hostMethod("compareAndSet", Any::class.java, Any::class.java), observer(1))
            XposedBridge.hookMethod(flow.hostMethod("setValue", Any::class.java), observer(0))
            XposedBridge.hookMethod(publisher.hostMethod("onCleared"), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    targets.removeAll { it.vm.get() === param.thisObject || it.vm.get() == null }
                    if (current?.vm?.get() === param.thisObject) retire()
                }
            })
            val navigation = requireNotNull(HostNativeAbi.navigation(HostSymbols.profileId))
            val controller = loader.hostClass("ReaMicroHost.NavController")
            XposedBridge.hookMethod(controller.getDeclaredMethod(navigation.controllerPop), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val scope = currentScope.get() ?: return
                    if (param.result == true && HostNavigation.controller(scope) === param.thisObject) retire()
                }
            })
            XposedBridge.logAlways("ReaMicro built-in manual association editor installed: native preview/confirmation")
        }
    }

    private fun bind(target: Target) {
        if (current === target) return
        retire()
        current = target
    }

    private fun canRender(target: Target): Boolean {
        if (!settings().canUseManualEdit || current !== target) return false
        val vm = target.vm.get() ?: return false
        return publisher.hostField("bookId").getLong(vm) == target.bookId
    }

    private fun usable(target: Target): Boolean {
        if (!canRender(target)) return false
        val vm = target.vm.get() ?: return false
        val state = XposedHelpers.callMethod(vm, "getCurrentState") ?: return false
        if (stateType.hostField("isRelateSuccess").getBoolean(state) ||
            stateType.hostField("relating").getBoolean(state)) return false
        val book = stateType.hostField("book").get(state) ?: return false
        return book.javaClass.hostField("id").getLong(book) == target.bookId
    }

    private fun renderEdit(target: Target, composer: Any) {
        EmbeddedHostUi.captureTypography(loader, composer)
        BookImageDialogStyle.capture(loader, composer)
        ManualAssociationEditorStyle.capture(loader, composer)
        editAction.render("手动编辑", composer) { open(target) }
    }

    private fun open(target: Target) {
        val host = activity() ?: return
        host.runOnUiThread {
            if (host.isFinishing || host.isDestroyed || !canRender(target)) return@runOnUiThread
            if (!usable(target)) {
                Toast.makeText(host, "书籍正在加载或关联，请稍后重试", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            val vm = requireNotNull(target.vm.get())
            val state = requireNotNull(XposedHelpers.callMethod(vm, "getCurrentState"))
            val book = requireNotNull(stateType.hostField("book").get(state))
            val initial = draft ?: ManualAssociationDraft(
                title = text(book, "title"), author = text(book, "author"),
                coverUrl = text(book, "cover"))
            editor.get()?.dismiss()
            val page = HostManualAssociationEditor(host, initial, service.selectableSources, { usable(target) }) { next ->
                check(usable(target)) { "关联页面已变化，请重新打开" }
                val candidate = service.buildCandidate(next)
                val group = factory.createThirdPartyGroup(ManualAssociationPreview.GROUP_PREFIX + candidate.source.displayName,
                    listOf(factory.create(candidate, text(book, "cover"))))
                draft = next.normalized()
                preview = group
                try { update(vm, target) } catch (error: Throwable) {
                    preview = null
                    throw error
                }
                Toast.makeText(host, "已生成候选，请选择后确认关联", Toast.LENGTH_SHORT).show()
            }
            editor = WeakReference(page)
            page.show()
        }
    }

    private fun merge(state: Any, target: Target): Any {
        val groups = (stateType.hostField("thirdPartyBooks").get(state) as? List<*>).orEmpty()
        val success = stateType.hostField("isRelateSuccess").getBoolean(state)
        val book = stateType.hostField("book").get(state)
        val bookMatches = book != null && book.javaClass.hostField("id").getLong(book) == target.bookId
        val group = preview?.takeIf { !success && bookMatches && current === target && settings().canUseManualEdit }
        val next = ManualAssociationPreview.merge(groups, group) { it?.let { value -> text(value, "publisher") }.orEmpty() }
        return if (groups == next) state else stateCopy.copy(state, mapOf("thirdPartyBooks" to next))
    }

    private fun update(vm: Any, target: Target) {
        val reducer = function("kotlin.jvm.functions.Function1", "ManualEditorPreview") { args ->
            merge(requireNotNull(args?.firstOrNull()), target)
        }
        XposedHelpers.callMethod(vm, "updateUiState", reducer)
    }

    private fun retire() {
        val previous = current
        val previousPreview = preview
        current = null
        currentScope.clear()
        preview = null
        draft = null
        val dialog = editor.get()
        editor.clear()
        main.post {
            dialog?.dismiss()
            previous?.vm?.get()?.let { vm ->
                // Old cleanup must not re-enter merge with a newly opened page's preview.
                runCatching { removePreview(vm, previousPreview) }
                    .onFailure { XposedBridge.logError("ReaMicro manual preview cleanup failed", it) }
            }
        }
    }

    private fun removePreview(vm: Any, previousPreview: Any?) {
        if (previousPreview == null) return
        val reducer = function("kotlin.jvm.functions.Function1", "ManualEditorCleanup") { args ->
            val state = requireNotNull(args?.firstOrNull())
            val groups = (stateType.hostField("thirdPartyBooks").get(state) as? List<*>).orEmpty()
            val next = groups.filterNot { it === previousPreview }
            if (groups == next) state else stateCopy.copy(state, mapOf("thirdPartyBooks" to next))
        }
        XposedHelpers.callMethod(vm, "updateUiState", reducer)
    }

    private fun text(value: Any, property: String): String =
        (value.javaClass.hostField(property).get(value) as? String).orEmpty()

    private fun unit(): Any? = loader.hostClass("kotlin.Unit").hostField("INSTANCE").get(null)
    private fun function(type: String, name: String, block: (Array<Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(loader.hostClass(type))) { self, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> block(args)
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "ReaMicro$name"
                else -> null
            }
        }

}
