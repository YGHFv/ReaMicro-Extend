package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethodByArity

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.cloud.webdav.WebDavImportSource
import com.reamicro.fix.core.HostImportArguments
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.hook.webdav.BOOKSHELF_REPOSITORY_CLASS
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

/** 来源跟随单个原生导入协程，TXT 转换后的新路径不能作为匹配依据。 */
internal class B4ImportSourceBridge(private val drive: WebDavDriveHook) {
    private val activeSource = ThreadLocal<WebDavImportSource?>()
    private val workerSources = Collections.synchronizedMap(WeakHashMap<Any, WebDavImportSource>())

    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val repository = drive.cls(BOOKSHELF_REPOSITORY_CLASS)
        val worker = drive.cls("ReaMicroHost.NativeImportWorker")
        val originalFile = worker.hostAbiField("N").apply { isAccessible = true }
        val invoke = worker.hostAbiMethod("v", Any::class.java)
        val import = repository.hostAbiMethodByArity("l", 7)
        val defaults = repository.hostAbiMethodByArity("m", 9)
        val txt = repository.hostAbiMethodByArity("n", 5)
        val txtDefaults = repository.hostAbiMethodByArity("o", 4)
        check(originalFile.type == import.parameterTypes[0])
        check(defaults.parameterTypes[0] == repository && defaults.parameterTypes[1] == originalFile.type)
        check(defaults.parameterTypes[4] == String::class.java &&
            defaults.parameterTypes[5] == java.lang.Long::class.java &&
            defaults.parameterTypes[8] == Int::class.javaPrimitiveType)
        check(txt.parameterTypes[2] == String::class.java &&
            txt.parameterTypes[3] == java.lang.Long::class.java)

        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val file = originalFile.get(owner) ?: return
                val source = workerSources[owner] ?: drive.platformFileKeys(file)
                    .firstNotNullOfOrNull { drive.pendingWebDavImports[it] } ?: return
                workerSources[owner] = source
                param.setObjectExtra("reamicro.b4.import.bound", true)
                param.setObjectExtra("reamicro.b4.import.previous", activeSource.get())
                activeSource.set(source)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra("reamicro.b4.import.bound") != true) return
                activeSource.set(param.getObjectExtra("reamicro.b4.import.previous") as? WebDavImportSource)
                // 挂起不清理，恢复执行仍须使用同一来源；只在协程完成或抛错时释放。
                if (param.throwable != null || param.result === drive.targetUnit()) {
                    val owner = param.thisObject ?: return
                    val source = workerSources.remove(owner) ?: return
                    val file = originalFile.get(owner) ?: return
                    drive.platformFileKeys(file).forEach { drive.pendingWebDavImports.remove(it, source) }
                }
            }
        })
        XposedBridge.hookMethod(defaults, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.args[1] ?: return
                val source = drive.platformFileKeys(file)
                    .firstNotNullOfOrNull { drive.pendingWebDavImports[it] } ?: activeSource.get() ?: return
                HostImportArguments.bindDefault(param.args, source.sourceUrl, source.sourceSize)
                drive.rememberBookshelfRepository(param.args[0])
                XposedBridge.logAlways("ReaMicro B4 import source bound size=${source.sourceSize ?: 0}")
            }
        })
        XposedBridge.hookMethod(txt, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val source = activeSource.get() ?: return
                param.args[2] = source.sourceUrl
                source.sourceSize?.takeIf { it > 0 }?.let { param.args[3] = it }
                XposedBridge.logAlways("ReaMicro B4 TXT reimport source bound size=${source.sourceSize ?: 0}")
            }
        })
        listOf(invoke, import, defaults, txt, txtDefaults).forEach(XposedBridge::deoptimize)
    }
}
