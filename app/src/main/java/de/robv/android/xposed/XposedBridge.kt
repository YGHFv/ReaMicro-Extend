package de.robv.android.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicReference

object XposedBridge {
    private const val LOG_TAG = "ReaMicro"
    private val frameworkRef = AtomicReference<XposedInterface?>()

    fun attachFramework(framework: XposedInterface) {
        frameworkRef.set(framework)
        framework.log(
            Log.INFO,
            LOG_TAG,
            "LibXposed framework attached: api=${framework.apiVersion}, " +
                "framework=${framework.frameworkName} ${framework.frameworkVersion}(${framework.frameworkVersionCode})",
        )
    }

    fun log(text: String) {
        log(Log.INFO, text, null)
    }

    fun log(throwable: Throwable) {
        log(Log.ERROR, Log.getStackTraceString(throwable), throwable)
    }

    fun hookMethod(method: Executable, callback: XC_MethodHook): XposedInterface.HookHandle? {
        method.isAccessible = true
        val framework = frameworkRef.get()
            ?: throw IllegalStateException("LibXposed framework is not attached")
        return framework.hook(method)
            .setPriority(callback.priority)
            .intercept { chain ->
                interceptForTest(
                    callback,
                    HookChain(
                        executable = chain.executable,
                        thisObject = chain.thisObject,
                        args = chain.args.toTypedArray(),
                        proceed = { args -> chain.proceed(args) },
                    ),
                )
            }
    }

    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook): List<XposedInterface.HookHandle> {
        return clazz.declaredMethods
            .asSequence()
            .filter { it.name == methodName }
            .mapNotNull { hookMethod(it, callback) }
            .toList()
    }

    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): List<XposedInterface.HookHandle> {
        return clazz.declaredConstructors
            .asSequence()
            .mapNotNull { hookMethod(it, callback) }
            .toList()
    }

    internal fun interceptForTest(callback: XC_MethodHook, chain: HookChain): Any? {
        val param = XC_MethodHook.MethodHookParam(
            chain.executable,
            chain.thisObject,
            chain.args,
        )

        runCatching {
            callback.callBeforeHookedMethod(param)
        }.onFailure {
            log(Log.ERROR, "beforeHookedMethod failed: ${it.stackTraceToString()}", it)
        }

        if (!param.isReturnEarly) {
            runCatching {
                chain.proceed(param.args ?: emptyArray())
            }.onSuccess {
                param.setResultFromOriginal(it)
            }.onFailure {
                param.setThrowableFromOriginal(it)
            }
        }

        runCatching {
            callback.callAfterHookedMethod(param)
        }.onFailure {
            log(Log.ERROR, "afterHookedMethod failed: ${it.stackTraceToString()}", it)
        }

        param.throwable?.let { throw it }
        return param.result
    }

    private fun log(priority: Int, text: String, throwable: Throwable?) {
        val framework = frameworkRef.get()
        if (framework != null) {
            if (throwable != null) {
                framework.log(priority, LOG_TAG, text, throwable)
            } else {
                framework.log(priority, LOG_TAG, text)
            }
        } else {
            Log.println(priority, LOG_TAG, text)
        }
    }

    internal class HookChain(
        val executable: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
        private val proceed: (Array<Any?>) -> Any?,
    ) {
        fun proceed(args: Array<Any?>): Any? = proceed.invoke(args)
    }
}
