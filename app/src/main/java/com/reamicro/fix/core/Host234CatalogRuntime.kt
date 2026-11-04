package com.reamicro.fix.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** 2.3.4 B1 的 Room/SQLite ABI，REA 与 MT 交叉核对；其他版本不得猜测这些短名。 */
internal class Host234CatalogRuntime(private val loader: ClassLoader) {
    companion object {
        fun supported(): Boolean = HostSymbols.profileId == "2.3.4-b1"
    }

    init { check(supported()) }

    fun transaction(dao: Any, block: (CatalogSql) -> Unit) {
        val databaseType = loader.loadClass("cf9")
        val database = dao.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(dao)
        require(databaseType.isInstance(database))
        val function = loader.hostClass("kotlin.jvm.functions.Function1")
        val callback = Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> { block(connection(requireNotNull(args?.get(0)))); unit() }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "ReaMicroCatalogTransaction"
                else -> error("Unexpected Room callback: $method")
            }
        }
        val perform = loader.loadClass("kt2").getDeclaredMethod(
            "K", loader.hostClass("kotlin.coroutines.Continuation"), databaseType, function,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        // 与原生 DAO 相同：写连接、事务，回调内不嵌套 runBlocking 或另外获取连接。
        HostCoroutines.invoke(loader, perform, null, database, callback, false, true)
    }

    fun unit(): Any = loader.loadClass("e7c").getField("a").get(null)

    fun connection(connection: Any): CatalogSql = Connection(connection)

    private inner class Connection(private val value: Any) : CatalogSql {
        private val prepare = loader.loadClass("mu9").getMethod("g1", String::class.java)
        private val type = loader.loadClass("su9")
        private val bindLong = type.getMethod("g", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val bindText = type.getMethod("V", Int::class.javaPrimitiveType, String::class.java)
        private val step = type.getMethod("c1")
        private val getLong = type.getMethod("getLong", Int::class.javaPrimitiveType)
        private val getText = type.getMethod("w0", Int::class.javaPrimitiveType)
        private val columnCount = type.getMethod("getColumnCount")

        private fun <T> statement(sql: String, args: List<Any>, block: (Any) -> T): T = unwrap {
            val statement = requireNotNull(prepare.invoke(value, sql))
            (statement as AutoCloseable).use {
                args.forEachIndexed { index, argument ->
                    when (argument) {
                        is Long -> bindLong.invoke(statement, argument, index + 1)
                        is String -> bindText.invoke(statement, index + 1, argument)
                        else -> error("Unsupported catalog binding: ${argument.javaClass.name}")
                    }
                }
                block(statement)
            }
        }

        override fun execute(sql: String, args: List<Any>) = statement(sql, args) { step.invoke(it); Unit }

        override fun query(sql: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> = statement(sql, args) { statement ->
            val result = mutableListOf<List<Any>>()
            val count = columnCount.invoke(statement) as Int
            while (step.invoke(statement) as Boolean) {
                result.add((0 until count).map { index ->
                    requireNotNull((if (index in numeric) getLong else getText).invoke(statement, index))
                })
            }
            result
        }
    }

    private inline fun <T> unwrap(block: () -> T): T =
        try { block() } catch (error: InvocationTargetException) { throw error.targetException }
}
