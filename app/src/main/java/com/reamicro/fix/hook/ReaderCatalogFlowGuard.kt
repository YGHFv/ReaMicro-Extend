package com.reamicro.fix.hook

import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

internal class CatalogEmissionGate {
    private var previous: List<*>? = null

    fun accept(value: Any?): Boolean {
        if (value !is List<*>) return true
        if (previous == value) return false
        previous = value
        return true
    }
}

/** Room 按整表通知；每个订阅独立去重，避免其他书更新把当前书重新送去排版。 */
internal object ReaderCatalogFlowGuard {
    fun relevant(tables: Array<*>): Boolean = tables.size == 1 &&
        (tables[0] == "book_item_ref" || tables[0] == "book_chapter")

    fun collector(loader: ClassLoader, original: Any): Any {
        val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
        val type = host.type("collector")
        require(type.isInstance(original))
        val gate = CatalogEmissionGate()
        return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when {
                method == host.method("emit") -> {
                    require(args?.size == 2)
                    if (!gate.accept(args[0])) requireNotNull(host.field("unit").get(null))
                    else try {
                        method.invoke(original, *args)
                    } catch (error: InvocationTargetException) {
                        throw error.targetException
                    }
                }
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.get(0)
                method.name == "toString" -> "ReaMicroDistinctCatalogCollector"
                else -> error("Unknown catalog collector method: $method")
            }
        }
    }

    fun install(loader: ClassLoader) {
        val host = requireNotNull(com.reamicro.fix.core.HostStorageBindings.catalog)
        val flows = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(host.method("collect"), object : HookCallback() {
                override fun before(param: Call) {
                    if (!flows.containsKey(param.thisObject)) return
                    param.args[0] = collector(loader, requireNotNull(param.args[0]))
                }
            })
            handles += HookRuntime.hookMethod(host.method("flow"), object : HookCallback() {
                override fun after(param: Call) {
                    val tables = param.args[1] as? Array<*> ?: return
                    if (param.throwable == null && relevant(tables)) param.result?.let { flows[it] = true }
                }
            })
            HookRuntime.deoptimize(host.method("flow"))
            com.reamicro.fix.reload.ModuleResources.cleanup { flows.clear() }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        HookRuntime.logAlways("ReaMicro reader catalog invalidation guard installed")
    }
}
