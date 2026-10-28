package com.reamicro.fix.core

/** 只插入已确认的协程槽位，不重排业务参数或猜测被 R8 删除的参数。 */
internal object HostSuspendArguments {
    fun bind(types: Array<Class<*>>, continuationIndex: Int, continuation: Any, arguments: Array<out Any?>): Array<Any?> {
        require(continuationIndex in types.indices && arguments.size + 1 == types.size) {
            "Suspend argument count differs from host contract"
        }
        var argumentIndex = 0
        val result = Array<Any?>(types.size) { index ->
            if (index == continuationIndex) continuation else arguments[argumentIndex++]
        }
        types.indices.forEach { index ->
            val value = result[index]
            val type = types[index]
            val compatible = if (value == null) !type.isPrimitive else when (type) {
                java.lang.Boolean.TYPE -> value is Boolean
                java.lang.Byte.TYPE -> value is Byte
                java.lang.Short.TYPE -> value is Short
                java.lang.Integer.TYPE -> value is Int
                java.lang.Long.TYPE -> value is Long
                java.lang.Float.TYPE -> value is Float
                java.lang.Double.TYPE -> value is Double
                java.lang.Character.TYPE -> value is Char
                else -> type.isInstance(value)
            }
            require(compatible) { "Suspend argument $index does not match ${type.name}" }
        }
        return result
    }
}
