package com.reamicro.fix.core


import java.lang.reflect.Proxy

/** 原生 PagingData.from 已删除；使用原生 REFRESH Insert，不替换 Paging 协议。 */
class HostPagingData(private val loader: ClassLoader) {
    private val semantic get() = requireNotNull(HostRuntimeBindings.paging) { "Native paging contract unavailable" }
    fun singleFlow(value: Any): Any {
        val contract = semantic
        run {
            return Proxy.newProxyInstance(loader, arrayOf(contract.type("flow"))) { proxy, method, args ->
                when {
                    method == contract.method("collect") -> {
                        try { contract.method("emit").invoke(args!![0], value, args[1]) }
                        catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                    }
                    method.name == "equals" -> proxy === args?.firstOrNull()
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "toString" -> "ReaMicroSingleFlow"
                    else -> error("Unknown Flow contract: $method")
                }
            }
        }
    }
    fun emptyFlow(): Any {
        val contract = semantic
        return Proxy.newProxyInstance(loader, arrayOf(contract.type("flow"))) { proxy, method, args ->
            when {
                method == contract.method("collect") -> contract.unit
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "ReaMicroEmptyFlow"
                else -> error("Unknown Flow contract: $method")
            }
        }
    }

    fun from(items: List<Any>): Any {
        val contract = semantic
        run {
            val page = contract.constructor("page").newInstance(0, items.toList())
            val (loadingMore, complete) = contract.notLoading
            val loadStates = contract.constructor("loadStates").newInstance(loadingMore, complete, complete)
            val event = contract.constructor("insert").newInstance(contract.refresh, listOf(page), 0, 0, loadStates, null)
            fun proxy(role: String, cached: Boolean = false): Any = Proxy.newProxyInstance(loader, arrayOf(contract.type(role))) { self, method, args ->
                when (method.name) {
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "ReaMicroPaging$role"
                    else -> if (cached && method == contract.method("cachedEvent")) event else {
                        check(method.returnType == Void.TYPE) { "Unexpected Paging receiver method: $method" }; null
                    }
                }
            }
            return contract.constructor("paging").newInstance(singleFlow(event), proxy("uiReceiver"), proxy("hintReceiver"), proxy("function0", true))
        }
    }
}
