package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedBridge
import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 保留宿主协程运行时，不把模块协程对象或接口 Proxy 塞进 R8 收窄后的类参数。 */
object HostCoroutines {
    private val carriers = HashMap<Pair<ClassLoader, String>, Constructor<*>>()
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ReaMicroHostCoroutineDeadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun runBlocking(
        loader: ClassLoader,
        parentContext: Any? = null,
        timeoutMillis: Long? = null,
        operation: (Any?) -> Any?,
    ): Any? {
        val context = parentContext ?: loader.hostClass("kotlin.coroutines.EmptyCoroutineContext")
            .hostField("INSTANCE").get(null)
        val function = loader.hostClass("kotlin.jvm.functions.Function2")
        val job = loader.hostClass("kotlinx.coroutines.Job")
        val run = loader.hostStaticMethod(
            "kotlinx.coroutines.BuildersKt", "runBlocking",
            loader.hostClass("kotlin.coroutines.CoroutineContext"), function,
        )
        val complete = AtomicBoolean()
        val timer = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val block = Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> {
                    if (timeoutMillis != null) {
                        val scope = requireNotNull(args?.getOrNull(0))
                        require(job.isInstance(scope)) { "runBlocking scope does not expose native Job" }
                        val cancel = scope.javaClass.hostMethod("cancel", CancellationException::class.java)
                        timer.set(deadlines.schedule({
                            if (!complete.get()) {
                                runCatching { unwrap { cancel.invoke(scope, CancellationException("Host operation timed out")) } }
                                    .onFailure { XposedBridge.logError("ReaMicro native coroutine cancellation failed", it) }
                            }
                        }, timeoutMillis, TimeUnit.MILLISECONDS))
                    }
                    val continuation = requireNotNull(args?.getOrNull(1))
                    unwrap { operation(carrier(loader, continuation)) }
                }
                "toString" -> "ReaMicroHostCoroutine"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        return try {
            unwrap { run.invoke(null, context, block) }
        } finally {
            complete.set(true)
            timer.get()?.cancel(false)
        }
    }

    fun invoke(loader: ClassLoader, method: Method, target: Any?, vararg args: Any?): Any? =
        invokeTimed(loader, method, target, null, *args)

    fun invokeTimed(
        loader: ClassLoader, method: Method, target: Any?, timeoutMillis: Long?, vararg args: Any?,
    ): Any? = runBlocking(loader, timeoutMillis = timeoutMillis) { continuation ->
            val continuationType = loader.hostClass("kotlin.coroutines.Continuation")
            val slots = method.parameterTypes.indices.filter { continuationType.isAssignableFrom(method.parameterTypes[it]) }
            require(slots.size == 1) { "Ambiguous native continuation contract: $method" }
            val values = HostSuspendArguments.bind(method.parameterTypes, slots.single(), requireNotNull(continuation), args)
            unwrap { method.invoke(target, *values) }
        }

    /** 非破坏性实机门禁：验证立即返回、挂起恢复和宿主 Job 取消三条路径。 */
    fun verifyProtocol(loader: ClassLoader) {
        check(runBlocking(loader) { "ReaMicroProtocol" } == "ReaMicroProtocol")
        val delay = loader.hostStaticMethod(
            "kotlinx.coroutines.DelayKt", "delay", Long::class.javaPrimitiveType,
            loader.hostClass("kotlin.coroutines.Continuation"),
        )
        check(invoke(loader, delay, null, 5L)?.toString() == "kotlin.Unit")
        val cancellation = runCatching {
            invokeTimed(loader, delay, null, 40L, 10_000L)
        }.exceptionOrNull()
        check(cancellation is CancellationException) { "Native cancellation did not propagate: $cancellation" }
        XposedBridge.logAlways("ReaMicro native coroutine protocol passed: immediate/resume/cancel")
    }

    fun mainDispatcher(loader: ClassLoader): Any =
        requireNotNull(loader.hostClass("kotlinx.coroutines.internal.MainDispatcherLoader")
            .hostField("dispatcher").get(null))

    private fun carrier(loader: ClassLoader, completion: Any): Any {
        if (HostSymbols.isIdentity) return completion
        val key = loader to HostSymbols.profileId
        val constructor = synchronized(carriers) {
            carriers.getOrPut(key) {
                val bytes = HostSymbols.bundledAsset("assets/host-bridges/${HostSymbols.profileId}.dex")
                val dexLoader = InMemoryDexClassLoader(ByteBuffer.wrap(bytes), loader)
                val type = dexLoader.loadClass("ReaMicroContinuationCarrier")
                val parent = loader.hostClass("kotlin.coroutines.jvm.internal.SuspendLambda")
                require(parent.isAssignableFrom(type)) { "Continuation bridge parent differs from profile" }
                XposedBridge.logAlways("ReaMicro coroutine carrier profile=${HostSymbols.profileId} parent=${parent.name}")
                type.getConstructor(loader.hostClass("kotlin.coroutines.Continuation"))
            }
        }
        return constructor.newInstance(completion)
    }

    private inline fun <T> unwrap(block: () -> T): T =
        try { block() } catch (error: InvocationTargetException) { throw error.targetException ?: error }
}
