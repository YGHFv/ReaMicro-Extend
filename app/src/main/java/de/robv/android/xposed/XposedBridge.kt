package de.robv.android.xposed

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

    /**
     * 日志出口。
     *
     * **不能直接用 `frameworkRef` 打日志**：`libxposed` 是 `compileOnly` 依赖，只存在于被
     * 注入的宿主进程里。模块**自己的进程**（接收器、闹钟唤醒、主界面）里没有这个类，一旦
     * 执行到引用 `XposedInterface` 的字节码就会 `NoClassDefFoundError` —— 实测就是打开阅微
     * 时模块进程崩溃（`ApiServerSettingsMirrorReceiver` 里那句 log 触发的）。
     * 所以日志走这个只用自有类型的出口：模块进程里它是 null，永远不会碰到 libxposed。
     */
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

    /**
     * 不受「简洁日志」开关影响的日志。
     *
     * 只给启动自检汇总这一类「必须能看到」的单行输出用。它在 handleLoadedPackage
     * 里打印，那时设置还没 attach，[ModuleLogState.conciseLogEnabled] 仍是默认的
     * true，走普通 log 会被整条吞掉——而这行的用途恰恰是宿主升级后第一眼要看的东西。
     */
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

    /**
     * 同 [hookAllMethods]，但连**继承来的**同名方法一起挂。
     *
     * 只在明确需要的地方用：多数调用点的目标类自己声明了该方法，换成这个只会顺带把父类的同名
     * 方法也挂上，可能影响别处。之所以需要它，是因为调用方的前置校验常常写成
     * `methods + declaredMethods`（能查到继承方法），而 [hookAllMethods] 只扫 `declaredMethods`
     * ——方法若是继承来的，校验通过、却**一个都没挂上**，日志里还打印"安装成功"。
     */
    fun hookAllMethodsIncludingInherited(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): List<XposedInterface.HookHandle> {
        return (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
            .distinct()
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
        val level = when {
            priority >= Log.ERROR -> ModuleLogLevel.ERROR
            priority >= Log.WARN -> ModuleLogLevel.WARN
            else -> ModuleLogLevel.INFO
        }
        val sink = logSinkRef.get()
        // sink 为空说明这是模块**自己的进程**（没有 libxposed 注入）。这里的日志只进
        // logcat，而模块进程的 INFO 日志受「简洁日志」抑制、logcat 里根本看不到——所以同时收进
        // 诊断缓冲，模块主界面能直接翻到。宿主进程里 sink 非空，不会走这条。
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

/**
 * 把日志转给 libxposed 框架。
 *
 * 单独一个类，**只有被注入的进程**才会加载它（仅 [XposedBridge.attachFramework] 里实例化）：
 * 模块自身进程没有 libxposed，凡是引用到 [XposedInterface] 的字节码一执行就
 * `NoClassDefFoundError`，所以这些引用必须隔离在"非注入进程绝不触碰"的类里。
 */
private class FrameworkLogSink(private val framework: XposedInterface) : ModuleLogSink {
    override fun log(priority: Int, tag: String, text: String, throwable: Throwable?) {
        if (throwable != null) {
            framework.log(priority, tag, text, throwable)
        } else {
            framework.log(priority, tag, text)
        }
    }
}
