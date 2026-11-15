package com.reamicro.fix.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Uses the native DAO's write transaction and SQLite statement protocol. */
internal class HostCatalogRuntime(private val loader: ClassLoader) {
    private val host = requireNotNull(HostStorageBindings.catalog) { "Catalog database contract unavailable" }

    fun database(dao: Any): Any {
        val role = listOf("book", "item", "chapter").single { host.type("${it}Dao").isInstance(dao) }
        return requireNotNull(host.field("database.$role").get(dao))
    }

    fun transaction(dao: Any, block: (CatalogSql) -> Unit) {
        val database = database(dao)
        val callback = Proxy.newProxyInstance(loader, arrayOf(host.type("function1"))) { proxy, method, args ->
            when {
                method == host.method("invoke") -> { block(connection(requireNotNull(args?.get(0)))); unit() }
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.get(0)
                method.name == "toString" -> "ReaMicroCatalogTransaction"
                else -> error("Unexpected Room callback: $method")
            }
        }
        val values = mapOf(
            host.value("perform.database") to database,
            host.value("perform.function") to callback,
            host.value("perform.flag0") to (host.value("perform.flagValue0") != 0),
            host.value("perform.flag1") to (host.value("perform.flagValue1") != 0),
        )
        val perform = host.method("perform")
        val args = perform.parameterTypes.indices.filter { it != host.value("perform.continuation") }.map(values::getValue)
        // The callback runs within the native transaction; it never acquires another connection.
        HostCoroutines.invoke(loader, perform, null, *args.toTypedArray())
    }

    fun unit(): Any = requireNotNull(host.field("unit").get(null))
    fun connection(connection: Any): CatalogSql {
        require(host.type("connection").isInstance(connection))
        return Connection(connection)
    }

    private inner class Connection(private val value: Any) : CatalogSql {
        private fun <T> statement(sql: String, args: List<Any>, block: (Any) -> T): T = unwrap {
            val statement = requireNotNull(host.method("prepare").invoke(value, sql))
            (statement as AutoCloseable).use {
                args.forEachIndexed { index, argument ->
                    when (argument) {
                        is Long -> host.method("bindLong").invoke(statement, argument, index + 1)
                        is String -> host.method("bindText").invoke(statement, index + 1, argument)
                        else -> error("Unsupported catalog binding: ${argument.javaClass.name}")
                    }
                }
                block(statement)
            }
        }
        override fun execute(sql: String, args: List<Any>) = statement(sql, args) { host.method("step").invoke(it); Unit }
        override fun query(sql: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> = statement(sql, args) { statement ->
            val result = mutableListOf<List<Any>>()
            val count = host.method("columnCount").invoke(statement) as Int
            while (host.method("step").invoke(statement) as Boolean) {
                result.add((0 until count).map { index ->
                    requireNotNull(host.method(if (index in numeric) "getLong" else "getText").invoke(statement, index))
                })
            }
            result
        }
    }
    private inline fun <T> unwrap(block: () -> T): T =
        try { block() } catch (error: InvocationTargetException) { throw error.targetException }
}
