package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethodByArity

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostNativeUiAbi
import com.reamicro.fix.core.HostHomeSearchList
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** B4 内联了 footer；在原生 LazyColumn 接收列表构建回调时保留模块结果快照。 */
internal class B4HomeSearchUiBridge(private val drive: WebDavDriveHook) {
    private val title = ThreadLocal<String?>()

    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val callbackCopy = HostHomeSearchList(drive.cls("ReaMicroHost.HomeSearchList"))
        val function = drive.cls(FUNCTION1_CLASS)
        val invoke = function.hostAbiMethod("invoke", Any::class.java)
        val name = HostNativeUiAbi.method(HostSymbols.profileId, "LazyDsl", "a")
        val lazyColumn = drive.cls("ReaMicroHost.LazyDsl").hostAbiMethodByArity(name, 12).also {
            check(it.parameterTypes[8] == function)
        }
        XposedBridge.hookMethod(lazyColumn, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = drive.homeWebDavSearchRender.get() ?: return
                if (context.wrapped) return
                val original = callbackCopy.nativeOnly(param.args[8] ?: return, setOf(
                    BACKUP_TYPE_WEBDAV, BACKUP_TYPE_LOCAL_LIBRARY, BACKUP_TYPE_ONLINE_COMPLETION,
                ))
                context.wrapped = true
                // 列表可能在外层 composable 返回后重建，不能届时再读取 ThreadLocal。
                param.args[8] = drive.functionProxy("B4HomeSearchList", FUNCTION1_CLASS) { args ->
                    val scope = args?.getOrNull(0) ?: return@functionProxy drive.targetUnit()
                    val previous = drive.homeWebDavSearchRender.get()
                    drive.homeWebDavSearchRender.set(context)
                    try {
                        invoke.invoke(original, scope)
                    } finally {
                        if (previous == null) {
                            drive.homeWebDavSearchRender.remove()
                        } else {
                            drive.homeWebDavSearchRender.set(previous)
                        }
                    }
                    drive.targetUnit()
                }
            }
        })
        XposedBridge.deoptimize(lazyColumn)
        installFooterInjection()
        installTitle()
    }

    private fun installFooterInjection() {
        val name = HostNativeUiAbi.method(HostSymbols.profileId, "HomeSearchFooter", "a")
        val footer = drive.cls("ReaMicroHost.HomeSearchFooter").hostAbiMethodByArity(name, 2).apply { isAccessible = true }
        XposedBridge.hookMethod(footer, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = drive.homeWebDavSearchRender.get() ?: return
                if (context.rendered) return
                val scope = param.args?.getOrNull(0) ?: return
                context.rendered = true
                context.sections.forEach { section ->
                    drive.addHomeWebDavSearchSection(
                        scope, section.type, section.title, section.results,
                        context.intentReceiver, context.generation,
                        context.navGraphScope,
                    )
                }
                logWebDav("B4 home search extension sections injected before native footer")
            }
        })
        XposedBridge.deoptimize(footer)
    }

    private fun installTitle() {
        val result = drive.cls(HOME_SEARCH_BAR_CLASS).hostMethodsNamed(HOME_CLOUD_RESULT_LIST_METHOD)
            .single { it.parameterCount == 6 }
        XposedBridge.hookMethod(result, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.setObjectExtra("reamicro.b4.search.title", title.get())
                val kind = param.args[1] as Int
                title.set(when {
                    kind == BACKUP_TYPE_WEBDAV -> WEBDAV_TITLE
                    kind == BACKUP_TYPE_LOCAL_LIBRARY -> LOCAL_LIBRARY_TITLE
                    drive.isOnlineCompletionRenderType(kind) -> drive.onlineCompletionTitleForType(kind)
                    else -> null
                })
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                title.set(param.getObjectExtra("reamicro.b4.search.title") as? String)
            }
        })
        val textName = HostNativeUiAbi.method(HostSymbols.profileId, "Text", "b")
        val text = drive.cls("ReaMicroHost.Text").declaredMethods
            .single { it.name == textName && it.parameterCount == 20 && it.parameterTypes[0] == String::class.java }
        XposedBridge.hookMethod(text, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val replacement = title.get() ?: return
                val label = param.args[0] as? String ?: return
                if (label.matches(Regex("本地存储\\(\\d+\\)"))) {
                    param.args[0] = replacement + label.removePrefix("本地存储")
                }
            }
        })
        XposedBridge.deoptimize(result)
    }
}
