package com.reamicro.fix.core

/** 只复制 B4 搜索列表闭包；原始 Map 必须留给 Compose 的变化检测和重启回调。 */
class HostHomeSearchList @JvmOverloads constructor(private val type: Class<*>, private val branch: Byte = 10) {
    private val properties = listOf("b", "c", "d", "e", "a")
    private val fields = properties.map { type.getDeclaredField(it).apply { isAccessible = true } }
    private val constructor = type.getDeclaredConstructor(
        Any::class.java, Any::class.java, Any::class.java, Any::class.java, Byte::class.javaPrimitiveType,
    ).apply { isAccessible = true }

    fun nativeOnly(callback: Any, extensionTypes: Set<Int>): Any {
        require(callback.javaClass == type)
        val values = fields.map { it.get(callback) }.toMutableList()
        require(values[4] == branch) { "Unverified search list branch: ${values[4]}, expected $branch" }
        val results = values[2] as? Map<*, *> ?: error("Missing search results")
        values[2] = results.filterKeys { it !in extensionTypes }
        return constructor.newInstance(*values.toTypedArray())
    }
}
