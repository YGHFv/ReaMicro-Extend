package com.reamicro.fix.core

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import java.lang.reflect.Proxy

/** 原生 PagingData.from 已删除；使用原生 REFRESH Insert，不替换 Paging 协议。 */
class B2PagingData(private val loader: ClassLoader) {
    private fun type(role: String): Class<*> {
        check(HostNativeAbi.hasNativePaging(HostSymbols.profileId))
        return loader.hostClass("ReaMicroHost.$role")
    }

    fun singleFlow(value: Any): Any =
        type("SingleFlow").getDeclaredConstructor(Any::class.java, Byte::class.javaPrimitiveType)
            .newInstance(value, (if (HostSymbols.profileId == "2.3.3-rc3") 6 else 5).toByte())

    fun from(items: List<Any>): Any {
        val snapshot = items.toList()
        val page = type("TransformablePage").getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java)
            .newInstance(0, snapshot)
        val notLoading = type("NotLoading")
        val loadStates = type("LoadStates").declaredConstructors.single { it.parameterCount == 3 }
            .newInstance(notLoading.hostAbiField("c").get(null),
                notLoading.hostAbiField("b").get(null), notLoading.hostAbiField("b").get(null))
        val event = type("PagingInsert").declaredConstructors.single { it.parameterCount == 6 }
            .newInstance(type("LoadType").hostAbiField("a").get(null), listOf(page), 0, 0, loadStates, null)
        val function0 = loader.hostClass("kotlin.jvm.functions.Function0")
        val cached = Proxy.newProxyInstance(loader, arrayOf(function0)) { proxy, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> event
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ReaMicroPagingRefresh"
                else -> null
            }
        }
        val paging = type("PagingData")
        val result = paging.declaredConstructors.single { it.parameterCount == 4 }
            .newInstance(singleFlow(event), paging.hostAbiField("e").get(null),
                paging.hostAbiField("f").get(null), cached)
        check(type("PagingInsert").hostAbiField("b").get(event) == listOf(page))
        return result
    }
}
