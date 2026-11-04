package com.reamicro.fix.hook

import com.reamicro.fix.core.Host234CatalogRuntime
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
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
        val type = loader.loadClass("pl3")
        val gate = CatalogEmissionGate()
        val unit = Host234CatalogRuntime(loader).unit()
        return Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "ReaMicroDistinctCatalogCollector"
                else -> {
                    require(method.parameterCount == 2 && args?.size == 2)
                    if (!gate.accept(args[0])) unit
                    else try {
                        // 原样传递 continuation、挂起标记及取消异常，不使用模块协程。
                        method.invoke(original, *args)
                    } catch (error: InvocationTargetException) {
                        throw error.targetException
                    }
                }
            }
        }
    }

    fun install(loader: ClassLoader) {
        if (!Host234CatalogRuntime.supported()) return
        val flows = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        val factory = loader.loadClass("t71").getDeclaredMethod(
            "w", loader.loadClass("cf9"), Array<String>::class.java,
            loader.hostClass("kotlin.jvm.functions.Function1"),
        )
        val collect = factory.returnType.getDeclaredMethod(
            "b", loader.loadClass("pl3"), loader.hostClass("kotlin.coroutines.Continuation"),
        )
        XposedBridge.hookMethod(collect, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!flows.containsKey(param.thisObject)) return
                param.args[0] = collector(loader, param.args[0])
            }
        })
        XposedBridge.hookMethod(factory, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val tables = param.args.getOrNull(1) as? Array<*> ?: return
                if (param.throwable == null && relevant(tables)) param.result?.let { flows[it] = true }
            }
        })
        XposedBridge.logAlways("ReaMicro reader catalog invalidation guard installed (2.3.4-b1)")
    }
}
