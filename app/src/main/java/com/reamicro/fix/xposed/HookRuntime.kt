package com.reamicro.fix.xposed

import android.util.Log
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.logging.ModuleLogLevel
import com.reamicro.fix.logging.ModuleLogSink
import com.reamicro.fix.logging.ModuleLogState
import com.reamicro.fix.logging.legacyModuleLogLevel
import com.reamicro.fix.logging.shouldEmitModuleLog
import com.reamicro.fix.reload.ModuleResources
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicReference

object HookRuntime {
    private const val LOG_TAG = "ReaMicro"
    private val frameworkRef = AtomicReference<XposedInterface?>()
    @Volatile private var featuresEnabled = true
    private val managementRegistration = ThreadLocal.withInitial { false }
    private val featureRegistration = ThreadLocal<(() -> Boolean)?>()

    private val logSinkRef = AtomicReference<ModuleLogSink?>()

    fun setFeaturesEnabled(enabled: Boolean) {
        featuresEnabled = enabled
    }

    /** Only the activation/settings entry and its host controls remain available while disabled. */
    internal fun <T> withManagementHooks(block: () -> T): T {
        val previous = managementRegistration.get()
        managementRegistration.set(true)
        return try {
            block()
        } finally {
            if (previous == true) managementRegistration.set(true) else managementRegistration.remove()
        }
    }

    /** Capture a feature's live setting for every hook installed in this scope. */
    internal fun <T> withFeatureGate(enabled: () -> Boolean, block: () -> T): T {
        val previous = featureRegistration.get()
        featureRegistration.set(if (previous == null) enabled else { { previous() && enabled() } })
        return try {
            block()
        } finally {
            if (previous == null) featureRegistration.remove() else featureRegistration.set(previous)
        }
    }

    fun attachFramework(framework: XposedInterface) {
        require(framework.apiVersion >= XposedInterface.API_102) { "ReaMicro requires libxposed API 102" }
        frameworkRef.set(framework)
        logSinkRef.set(FrameworkLogSink(framework))
        HookInvocation.setFailureReporter { phase, executable, error ->
            logError("Hook $phase callback failed: $executable", error)
        }
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

    /** 解除核实过的调用方内联，保证其内部 hook 能被实际调用。 */
    fun deoptimize(method: Executable): Boolean =
        ModuleResources.withRegistration {
            requireNotNull(frameworkRef.get()) { "LibXposed framework is not attached" }.deoptimize(method)
        }

    fun hookMethod(method: Executable, callback: HookCallback): XposedInterface.HookHandle =
        ModuleResources.withRegistration {
            method.isAccessible = true
            val framework = frameworkRef.get()
                ?: throw IllegalStateException("LibXposed framework is not attached")
            val handle = framework.hook(method)
                .setPriority(callback.priority)
                // Callback errors are caught inside HookInvocation. Deliberate cancellation and
                // failed-write outcomes must still reach the host caller.
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(interceptor(callback, generationBound = true,
                    featureBound = managementRegistration.get() != true, featureGate = featureRegistration.get()))
            object : XposedInterface.HookHandle by handle {
                override fun unhook() = ModuleResources.releaseHook { handle.unhook() }
            }
        }

    /** 崩溃上报保护在代际切换和功能开关关闭期间始终生效。 */
    internal fun hookProcessGuardMethod(
        method: Executable,
        callback: HookCallback,
        id: String,
        previous: XposedInterface.HookHandle? = null,
    ): XposedInterface.HookHandle = ModuleResources.withRegistration {
        method.isAccessible = true
        check(id.isNotBlank()) { "A process guard requires a stable hook id" }
        val interceptor = interceptor(callback, generationBound = false, featureBound = false)
        if (previous != null) {
            // Old module versions did not assign ids; the caller validates their exact guard method.
            check(previous.executable == method && (previous.id == null || previous.id == id)) {
                "Process guard replacement mismatch"
            }
            return@withRegistration previous.replaceHook(interceptor)
        }
        val framework = requireNotNull(frameworkRef.get()) { "LibXposed framework is not attached" }
        framework.hook(method)
            .setId(id)
            .setPriority(callback.priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(interceptor)
    }

    private fun interceptor(callback: HookCallback, generationBound: Boolean, featureBound: Boolean,
        featureGate: (() -> Boolean)? = null): XposedInterface.Hooker =
        XposedInterface.Hooker { chain ->
            if (featureBound && !featuresEnabled) return@Hooker chain.proceed()
            // Settings providers belong to this generation too. Admit the invocation before
            // reading them, so cleanup cannot clear their state while a gate is being evaluated.
            if (generationBound && !ModuleResources.enterHook()) {
                return@Hooker chain.proceed()
            }
            try {
                if (featureBound && (!featuresEnabled ||
                        featureGate?.let { runCatching(it).getOrDefault(false) } == false)) {
                    chain.proceed()
                } else {
                    callback.intercept(chain)
                }
            } finally {
                if (generationBound) ModuleResources.leaveHook()
            }
        }

    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: HookCallback): List<XposedInterface.HookHandle> {
        // 所有宿主方法均先解析当前 DexKit 动态映射。
        return hookBatch(clazz.hostMethodsNamed(methodName).asSequence(), callback)
    }

    fun hookAllConstructors(clazz: Class<*>, callback: HookCallback): List<XposedInterface.HookHandle> {
        return hookBatch(clazz.declaredConstructors.asSequence(), callback)
    }

    private fun hookBatch(methods: Sequence<Executable>, callback: HookCallback): List<XposedInterface.HookHandle> {
        val installed = ArrayList<XposedInterface.HookHandle>()
        try {
            for (method in methods.distinct()) installed += hookMethod(method, callback)
            check(installed.isNotEmpty()) { "No executable matched the hook request" }
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
