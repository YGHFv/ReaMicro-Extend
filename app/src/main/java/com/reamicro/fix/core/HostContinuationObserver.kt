package com.reamicro.fix.core

import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookRuntime
import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** 观察原生完成协议；R8 收窄的参数仍使用由当前宿主父类生成的 carrier。 */
internal object HostContinuationObserver {
    private val carriers = HashMap<Class<*>, Constructor<*>>().also { cache ->
        ModuleResources.cleanup { synchronized(cache) { cache.clear() } }
    }

    fun observe(loader: ClassLoader, completion: Any, successOnly: Boolean = false, completed: () -> Unit): Any {
        val host = requireNotNull(HostRuntimeBindings.coroutines) { "Native coroutine protocol unavailable" }
        check(host.loader === loader) { "Coroutine contract belongs to another host loader" }
        val continuation = host.type("continuation")
        check(continuation.isInstance(completion)) { "Native continuation differs from the queried protocol" }
        val getContext = host.method("continuationContext")
        val resume = host.method("resumeWith")
        val observed = Proxy.newProxyInstance(loader, arrayOf(continuation)) { self, method, args ->
            when {
                method == getContext -> invoke { getContext.invoke(completion) }
                method == resume -> {
                    try {
                        invoke { resume.invoke(completion, args?.getOrNull(0)) }
                    } finally {
                        runCatching {
                            if (!successOnly || args?.getOrNull(0)?.let(host.type("failure")::isInstance) != true) completed()
                        }.onFailure {
                            HookRuntime.logError("ReaMicro native continuation observation failed", it)
                        }
                    }
                }
                method.declaringClass == Any::class.java && method.name == "toString" -> "ReaMicroNativeCompletion"
                method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(self)
                method.declaringClass == Any::class.java && method.name == "equals" -> self === args?.firstOrNull()
                else -> error("Unexpected native continuation callback: $method")
            }
        }
        val parent = host.type("parent")
        val constructor = synchronized(carriers) {
            carriers.getOrPut(parent) {
                val bytes = HostContinuationDex.generate(host.constructor("parent"), continuation, host.method("resume"))
                val type = InMemoryDexClassLoader(ByteBuffer.wrap(bytes), loader).loadClass("ReaMicroContinuationCarrier")
                check(parent.isAssignableFrom(type)) { "Continuation bridge parent differs from queried contract" }
                type.getConstructor(continuation)
            }
        }
        return constructor.newInstance(observed)
    }

    private inline fun <T> invoke(operation: () -> T): T =
        try { operation() } catch (error: InvocationTargetException) { throw error.targetException ?: error }
}
