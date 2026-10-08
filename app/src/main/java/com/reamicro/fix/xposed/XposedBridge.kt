package com.reamicro.fix.xposed

import android.util.Log
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.logging.ModuleLogLevel
import com.reamicro.fix.logging.ModuleLogSink
import com.reamicro.fix.logging.ModuleLogState
import com.reamicro.fix.logging.legacyModuleLogLevel
import com.reamicro.fix.logging.shouldEmitModuleLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicReference

object XposedBridge {
    private const val LOG_TAG = "ReaMicro"
    private val frameworkRef = AtomicReference<XposedInterface?>()

    private val logSinkRef = AtomicReference<ModuleLogSink?>()

    fun attachFramework(framework: XposedInterface) {
        frameworkRef.set(framework)
        logSinkRef.set(FrameworkLogSink(framework))
        log(
            Log.INFO,
            "LibXposed framework attached: api=${framework.apiVersion}, " +
                "framework=${framework.frameworkName} ${framework.frameworkVersion}(${framework.frameworkVersionCode})",
            null,
        )
    }

    fun log(text: String) {
        val priority = if (legacyModuleLogLevel(text) == ModuleLogLevel.ERROR) Log.ERROR else Log.INFO
        log(priority, text, null)
    }

    fun logError(text: String, throwable: Throwable? = null) {
        log(Log.ERROR, text, throwable)
    }

    fun logAlways(text: String) {
        val sink = logSinkRef.get()
        if (sink != null) {
            sink.log(Log.INFO, LOG_TAG, text, null)
        } else {
            Log.println(Log.INFO, LOG_TAG, text)
            ModuleLogBuffer.record(ModuleLogLevel.INFO.name, LOG_TAG, text)
        }
    }

    fun log(throwable: Throwable) {
        log(Log.ERROR, Log.getStackTraceString(throwable), throwable)
    }

    fun hookMethod(method: Executable, callback: XC_MethodHook): XposedInterface.HookHandle {
        method.isAccessible = true
        val framework = frameworkRef.get()
            ?: throw IllegalStateException("LibXposed framework is not attached")
        return framework.hook(method)
            .setPriority(callback.priority)

            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                interceptHook(
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
        return hookBatch(clazz.declaredMethods.asSequence().filter { it.name == methodName }, callback)
    }

    fun hookAllMethodsIncludingInherited(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): List<XposedInterface.HookHandle> {
        return hookBatch(
            (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
                .distinct()
                .filter { it.name == methodName },
            callback,
        )
    }

    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): List<XposedInterface.HookHandle> {
        return hookBatch(clazz.declaredConstructors.asSequence(), callback)
    }

    private fun hookBatch(methods: Sequence<Executable>, callback: XC_MethodHook): List<XposedInterface.HookHandle> {
        val installed = ArrayList<XposedInterface.HookHandle>()
        try {
            for (method in methods) installed += hookMethod(method, callback)
        } catch (error: Throwable) {
            for (handle in installed.asReversed()) {
                try {
                    handle.unhook()
                } catch (cleanupError: Throwable) {
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
        return installed
    }

    internal fun interceptHook(callback: XC_MethodHook, chain: HookChain): Any? {
        val param = XC_MethodHook.MethodHookParam(
            chain.executable,
            chain.thisObject,
            chain.args,
        )

        try {
            callback.callBeforeHookedMethod(param)
        } catch (error: Throwable) {
            param.resetOutcome()
            log(Log.ERROR, "beforeHookedMethod failed", error)
        }

        if (!param.isReturnEarly) {
            try {
                param.setResultFromOriginal(chain.proceed(param.args ?: emptyArray()))
            } catch (error: Throwable) {
                param.setThrowableFromOriginal(error)
            }
        }

        val lastResult = param.result
        val lastThrowable = param.throwable
        try {
            callback.callAfterHookedMethod(param)
        } catch (error: Throwable) {
            if (lastThrowable != null) param.setThrowableFromOriginal(lastThrowable)
            else param.setResultFromOriginal(lastResult)
            log(Log.ERROR, "afterHookedMethod failed", error)
        }

        param.throwable?.let { throw it }
        return param.result
    }

    private fun log(priority: Int, text: String, throwable: Throwable?) {
        val level = when {
            priority >= Log.ERROR -> ModuleLogLevel.ERROR
            priority >= Log.WARN -> ModuleLogLevel.WARN
            else -> ModuleLogLevel.INFO
        }
        val sink = logSinkRef.get()

        if (sink == null) {
            ModuleLogBuffer.record(level.name, LOG_TAG, text)
        }
        if (!shouldEmitModuleLog(ModuleLogState.conciseLogEnabled, level)) return
        if (sink != null) {
            sink.log(priority, LOG_TAG, text, throwable)
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

private class FrameworkLogSink(private val framework: XposedInterface) : ModuleLogSink {
    override fun log(priority: Int, tag: String, text: String, throwable: Throwable?) {
        if (throwable != null) {
            framework.log(priority, tag, text, throwable)
        } else {
            framework.log(priority, tag, text)
        }
    }
}
