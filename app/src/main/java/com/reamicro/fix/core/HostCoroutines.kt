package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.PendingContinuation
import com.reamicro.fix.xposed.HookCallback
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
    private val carriers = HashMap<Class<*>, Constructor<*>>().also { cache ->
        com.reamicro.fix.reload.ModuleResources.cleanup { synchronized(cache) { cache.clear() } }
    }
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ReaMicroHostCoroutineDeadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }.also { com.reamicro.fix.reload.ModuleResources.executor(it) }
    private fun bindings(loader: ClassLoader) = requireNotNull(HostRuntimeBindings.coroutines) {
        "Native coroutine protocol unavailable"
    }.also { check(it.loader === loader) { "Coroutine contract belongs to another host loader" } }

    fun runBlocking(
        loader: ClassLoader,
        parentContext: Any? = null,
        timeoutMillis: Long? = null,
        operation: (Any?) -> Any?,
    ): Any? {
        val lease = ModuleResources.lease() ?: throw CancellationException("Module generation is retiring")
        return lease.use { runHostBlocking(loader, parentContext, timeoutMillis, operation) }
    }

    private fun runHostBlocking(
        loader: ClassLoader,
        parentContext: Any?,
        timeoutMillis: Long?,
        operation: (Any?) -> Any?,
    ): Any? {
        val host = bindings(loader)
        val context = parentContext ?: host.value("empty")
        check(host.type("context").isInstance(context))
        val function = host.type("function2")
        val job = host.type("job")
        val run = host.method("runBlocking")
        val complete = AtomicBoolean()
        val timer = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val block = Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
            when {
                method == host.method("invoke") -> {
                    if (timeoutMillis != null) {
                        val scope = requireNotNull(args?.getOrNull(0))
                        require(job.isInstance(scope)) { "runBlocking scope does not expose native Job" }
                        val cancel = host.method("cancel")
                        timer.set(deadlines.schedule({
                            if (!complete.get()) {
                                runCatching { unwrap { cancel.invoke(scope, CancellationException("Host operation timed out")) } }
                                    .onFailure { HookRuntime.logError("ReaMicro native coroutine cancellation failed", it) }
                            }
                        }, timeoutMillis, TimeUnit.MILLISECONDS))
                    }
                    val continuation = requireNotNull(args?.getOrNull(1))
                    unwrap { operation(carrier(loader, continuation)) }
                }
                method.name == "toString" -> "ReaMicroHostCoroutine"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected native coroutine callback: $method")
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
            val continuationType = bindings(loader).type("continuation")
            val slots = method.parameterTypes.indices.filter { continuationType.isAssignableFrom(method.parameterTypes[it]) }
            require(slots.size == 1) { "Ambiguous native continuation contract: $method" }
            val values = HostSuspendArguments.bind(method.parameterTypes, slots.single(), requireNotNull(continuation), args)
            unwrap { method.invoke(target, *values) }
        }

    fun emptyContext(loader: ClassLoader): Any = bindings(loader).value("empty")
    fun unit(loader: ClassLoader): Any = bindings(loader).value("unit")
    fun suspended(loader: ClassLoader): Any = bindings(loader).suspended

    fun continuationJob(loader: ClassLoader, continuation: Any): Any? {
        val host = bindings(loader)
        require(host.type("continuation").isInstance(continuation))
        val context = unwrap { host.method("continuationContext").invoke(continuation) }
        return runCatching { unwrap { host.method("getJob").invoke(null, context) } }.getOrNull()
            ?.also { require(host.type("job").isInstance(it)) }
    }

    fun cancelJob(loader: ClassLoader, job: Any, reason: CancellationException) {
        val host = bindings(loader)
        require(host.type("job").isInstance(job))
        unwrap { host.method("cancel").invoke(job, reason) }
    }

    /**
     * Observe a native suspend completion without replacing its dispatcher, Job, or result carrier.
     * Narrowed R8 continuation parameters receive the same validated native carrier as runBlocking.
     */
    fun observeContinuation(
        loader: ClassLoader,
        call: HookCallback.Call,
        index: Int,
        onCompleted: (Any?, Boolean) -> Unit,
        transformSuccess: (Any?) -> Any? = { it },
    ) {
        val host = bindings(loader)
        val completion = requireNotNull(call.args[index])
        require(host.type("continuation").isInstance(completion))
        val pending = PendingContinuation(
            ModuleResources.lease() ?: throw CancellationException("Module generation is retiring"),
        )
        try {
            val observer = Proxy.newProxyInstance(loader, arrayOf(host.type("continuation"))) { proxy, method, args ->
                when {
                    method == host.method("continuationContext") ->
                        unwrap { method.invoke(completion) }
                    method == host.method("resumeWith") -> {
                        pending.resume {
                            val value = args?.singleOrNull()
                            var success = value == null || !host.type("failure").isInstance(value)
                            val forwarded = if (!success) value else try {
                                transformSuccess(value)
                            } catch (failure: Throwable) {
                                success = false
                                host.constructor("failure").newInstance(failure)
                            }
                            runCatching { onCompleted(forwarded, success) }
                                .onFailure { HookRuntime.logError("ReaMicro native completion observer failed", it) }
                            unwrap { method.invoke(completion, forwarded) }
                        }
                        null
                    }
                    method.name == "toString" -> "ReaMicroHostCompletion"
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected native continuation callback: $method")
                }
            }
            val continuation = carrier(loader, observer)
            @Suppress("UNCHECKED_CAST")
            val observations = call.getObjectExtra(OBSERVATIONS) as? MutableList<InvocationObservation>
                ?: arrayListOf<InvocationObservation>().also { call.setObjectExtra(OBSERVATIONS, it) }
            observations += InvocationObservation(index, continuation, pending)
            call.args[index] = continuation
        } catch (error: Throwable) {
            pending.close()
            throw error
        }
    }

    /** Invoke from the hook's after/finally path, including before-hook rollback and host errors. */
    fun finishInvocationContinuations(loader: ClassLoader, call: HookCallback.Call) {
        @Suppress("UNCHECKED_CAST")
        val observations = call.getObjectExtra(OBSERVATIONS) as? List<InvocationObservation> ?: return
        val terminal = call.throwable != null || call.result !== suspended(loader)
        observations.forEach {
            // HookInvocation restores original arguments after a broken before callback. Such an
            // observer was never passed to the host and cannot receive a completion callback.
            if (terminal || call.args[it.index] !== it.continuation) it.pending.close()
        }
        call.setObjectExtra(OBSERVATIONS, null)
    }

    private class InvocationObservation(
        val index: Int, val continuation: Any, val pending: PendingContinuation,
    )

    private const val OBSERVATIONS = "reamicro.host.continuations"

    fun activeViewModelJob(loader: ClassLoader, viewModel: Any): Any {
        val host = bindings(loader)
        check(host.type("viewModel").isInstance(viewModel))
        val scope = unwrap { host.method("viewModelScope").invoke(null, viewModel) }
        val context = unwrap { host.method("scopeContext").invoke(scope) }
        val job = requireNotNull(unwrap { host.method("getJob").invoke(null, context) })
        if (unwrap { host.method("active").invoke(job) } != true) throw CancellationException("Host ViewModel job is inactive")
        return job
    }
    fun withMain(loader: ClassLoader, continuation: Any?, action: () -> Any?): Any? {
        val host = bindings(loader)
        val block = Proxy.newProxyInstance(loader, arrayOf(host.type("function2"))) { proxy, method, args ->
            when {
                method == host.method("invoke") -> action()
                method.name == "toString" -> "ReaMicroHostMain"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected native main callback: $method")
            }
        }
        return unwrap { host.method("withContext").invoke(null, host.value("main"), block, continuation) }
    }

    private fun carrier(loader: ClassLoader, completion: Any): Any {
        val host = bindings(loader)
        val parent = host.type("parent")
        val constructor = synchronized(carriers) {
            carriers.getOrPut(parent) {
                val bytes = HostContinuationDex.generate(host.constructor("parent"), host.type("continuation"), host.method("resume"))
                val dexLoader = InMemoryDexClassLoader(ByteBuffer.wrap(bytes), loader)
                val type = dexLoader.loadClass("ReaMicroContinuationCarrier")
                require(parent.isAssignableFrom(type)) { "Continuation bridge parent differs from queried contract" }
                HookRuntime.logAlways("ReaMicro coroutine carrier parent=${parent.name}")
                type.getConstructor(host.type("continuation"))
            }
        }
        return constructor.newInstance(completion)
    }

    private inline fun <T> unwrap(block: () -> T): T =
        try { block() } catch (error: InvocationTargetException) { throw error.targetException ?: error }
}
