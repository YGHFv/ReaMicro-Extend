"""定点修复本地功能源的正式版ABI，规则和旧宿主路径保持原样。"""

from pathlib import Path

p = Path("external-sources/src/main/kotlin/com/reamicro/fix/hook/ManualAssociationUiHook.kt")
raw = p.read_bytes()
nl = "\r\n" if b"\r\n" in raw else "\n"


def edit(old, new, count=1):
    global raw
    before, after = old.replace("\n", nl).encode("utf-8"), new.replace("\n", nl).encode("utf-8")
    assert raw.count(before) == count, (old[:100], raw.count(before))
    raw = raw.replace(before, after, count)


edit("import android.app.Activity", """import android.app.Activity
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostMethodsNamed""")
edit("    fun install() {\n        hookSearchByThird()", """    fun install() {
        if (HostSymbols.profileId == "2.3.3") {
            installReleaseManualEditing()
            return
        }
        hookSearchByThird()""")
edit("    private fun hookBookDetailsViewModel() {", """    private var releasePublishFlowRef: WeakReference<Any>? = null
    private var releaseBookKey = ""

    private fun installReleaseManualEditing() {
        val vmType = classLoader.hostClass(BOOK_PUBLISH_VIEW_MODEL_CLASS)
        val stateType = classLoader.hostClass("app.zhendong.reamicro.ui.book.BookPublishUIState")
        val flowType = classLoader.hostClass("kotlinx.coroutines.flow.StateFlowImpl")
        fun observer(index: Int, compare: Boolean) = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== releasePublishFlowRef?.get()) return
                val state = param.args?.getOrNull(index) ?: return
                if (stateType.isInstance(state)) param.args[index] = appendManualGroups(state, buildManualGroups())
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null || (compare && param.result != true)) return
                if (param.thisObject !== releasePublishFlowRef?.get()) return
                val vm = viewModelRef?.get() ?: return
                observeReleaseBook(vm)
                handleCoverFixStateUpdate(vm)
            }
        }
        XposedBridge.hookMethod(flowType.hostMethod("setValue", Any::class.java), observer(0, false))
        XposedBridge.hookMethod(flowType.hostMethod("compareAndSet", Any::class.java, Any::class.java), observer(1, true))
        XposedBridge.hookAllConstructors(vmType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val vm = param.thisObject ?: return
                hideFloatingButton()
                currentDraft = null
                baseDraft = null
                currentCoverPath = null
                releaseBookKey = ""
                viewModelRef = WeakReference(vm)
                releasePublishFlowRef = WeakReference(XposedHelpers.getObjectField(vm, "_uiState"))
                observeReleaseBook(vm)
            }
        })
        // 使用宿主原销毁入口，不能把只安装到已删除的 onCleared 当作清理成功。
        val lifecycle = classLoader.hostClass("androidx.lifecycle.ViewModel")
            .hostMethod("clear$lifecycle_viewmodel")
        XposedBridge.hookMethod(lifecycle, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== viewModelRef?.get()) return
                hideFloatingButton()
                viewModelRef = null
                releasePublishFlowRef = null
                currentDraft = null
                baseDraft = null
                currentBookRef = null
                releaseBookKey = ""
                clearLatestSearchResults()
            }
        })
        val scopeType = classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS)
        val navigate = scopeType.getDeclaredMethod("b", scopeType, Any::class.java)
        XposedBridge.hookMethod(navigate, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val route = param.args[1] ?: return
                if (route.javaClass != classLoader.hostClass(ROUTE_BOOK_PUBLISH_CLASS)) hideFloatingButton()
            }
        })
        XposedBridge.deoptimize(navigate)
        // 已有条码菜单由模块负责；此源只恢复手动表单及原有提交后封面补交。
        val repository = classLoader.hostClass("app.zhendong.reamicro.repository.BookRepository")
        val third = classLoader.hostClass("app.zhendong.reamicro.data.search.ThirdPartyBook")
        val relate = repository.getDeclaredMethod("k", classLoader.hostClass(BOOK_CLASS), third,
            classLoader.hostClass(KOTLIN_CONTINUATION_CLASS))
        XposedBridge.hookMethod(relate, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!settingsProvider().canUseAssociationCoverFix) return
                val vm = viewModelRef?.get() ?: return
                if (fieldValue(vm, "repository") !== param.thisObject) return
                val book = param.args[0] ?: return
                val state = XposedHelpers.callMethod(vm, "getCurrentState") ?: return
                val current = XposedHelpers.callMethod(state, "getBook") ?: return
                if (callLong(book, "getId") != callLong(current, "getId")) return
                val url = callString(requireNotNull(param.args[1]), "getCover").trim()
                if (!isRemoteCoverUrl(url)) return
                pendingCoverFixes[System.identityHashCode(vm)] = PendingCoverFix(
                    callLong(book, "getId"), url, WeakReference(book), WeakReference(param.thisObject))
            }
        })
        XposedBridge.logAlways("$LOG_PREFIX local manual-edit host 2.3.3 installed")
    }

    private fun observeReleaseBook(vm: Any) {
        val state = XposedHelpers.callMethod(vm, "getCurrentState") ?: return
        val book = XposedHelpers.callMethod(state, "getBook") ?: return
        val key = callLong(book, "getId").toString()
        if (key == releaseBookKey) return
        releaseBookKey = key
        activityProvider()?.runOnUiThread {
            if (viewModelRef?.get() !== vm || releaseBookKey != key) return@runOnUiThread
            refreshManualSearchResults(vm)
            if (settingsProvider().canUseManualEdit) showFloatingButton() else hideFloatingButton()
        }
    }

    private fun hookBookDetailsViewModel() {""".replace('clear$lifecycle_viewmodel', 'clear\\$lifecycle_viewmodel'))
edit('        val api = fieldValue(repository, "api") ?: error("Api unavailable")', '''        val api = fieldValue(repository, "api") ?: error("Api unavailable")''')
edit('        val method = (api.javaClass.methods.asSequence() + api.javaClass.declaredMethods.asSequence())', """        if (HostSymbols.profileId == "2.3.3") {
            val post = api.javaClass.hostAbiMethod("a1", requestClass,
                classLoader.hostClass("ReaMicroHost.SuspendContinuation"))
            val envelope = invokeSuspendBlocking(post, api, request)
            val data = classLoader.hostClass("ReaMicroHost.EnvelopeData").hostAbiMethod(
                "b", requireNotNull(envelope).javaClass).invoke(null, envelope)
            return requireNotNull(data).javaClass.getDeclaredField("a").getLong(data)
        }
        val method = (api.javaClass.methods.asSequence() + api.javaClass.declaredMethods.asSequence())""")
edit('        XposedHelpers.callMethod(\n            state, "copy",', '        copyManualState(\n            state,')
edit('    private fun associationGroupPublisher(sourceName: String): String =', """    private fun copyManualState(state: Any, vararg values: Any?): Any {
        if (HostSymbols.profileId == "2.3.3") {
            // full copy 被删除，主构造器仍是同顺序六字段；保留 searching/relating/success。
            return state.javaClass.getDeclaredConstructor(
                classLoader.hostClass(BOOK_CLASS),
                classLoader.hostClass("app.zhendong.reamicro.data.storage.CloudBook"),
                List::class.java, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).newInstance(*values)
        }
        return requireNotNull(XposedHelpers.callMethod(state, "copy", *values))
    }

    private fun associationGroupPublisher(sourceName: String): String =""")
edit("    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {", """    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
        if (HostSymbols.profileId == "2.3.3") return HostCoroutines.invokeTimed(
            classLoader, method, target, 30_000L, *args)
""")
edit("        for (name in names) {\n            var type: Class<*>? = target.javaClass", """        for (name in names) {
            if (HostSymbols.profileId == "2.3.3") {
                runCatching { target.javaClass.hostField(name).get(target) }.getOrNull()?.let { return it }
            }
            var type: Class<*>? = target.javaClass""")
edit('        clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)', '        clazz.hostField("INSTANCE").apply { isAccessible = true }.get(null)')
edit('            when (method.name) {\n                "invoke" -> block((args as? Array<*>)?.getOrNull(0))', '            when (HostSymbols.logicalMethodName(method)) {\n                "invoke" -> block((args as? Array<*>)?.getOrNull(0))')
p.write_bytes(raw)
print("Local manual-edit adaptation written, original newline preserved")
