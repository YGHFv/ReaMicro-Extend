package com.reamicro.fix.core

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Creates a record using the constructor slots proven by the host query. */
internal class HostBoundRecord(
    private val constructor: Constructor<*>,
    private val fields: Map<String, Field>,
    private val slots: Map<String, Int>,
    private val byteFromInt: Set<String> = emptySet(),
) {
    init {
        require(fields.keys == slots.keys && slots.values.toSet() == constructor.parameterTypes.indices.toSet())
        require(fields.size == constructor.parameterCount)
        require(fields.values.toSet() == constructor.declaringClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet())
        require(fields.keys.containsAll(byteFromInt))
        fields.forEach { (name, field) ->
            val parameter = constructor.parameterTypes[slots.getValue(name)]
            require(field.declaringClass == constructor.declaringClass && !Modifier.isStatic(field.modifiers) &&
                (field.type == parameter || name in byteFromInt && field.type == java.lang.Byte.TYPE &&
                    parameter == java.lang.Integer.TYPE)) { "Native record slot type mismatch: $name" }
            field.isAccessible = true
        }
        constructor.isAccessible = true
    }

    fun create(properties: Map<String, Any?>): Any {
        require(properties.keys == fields.keys) { "Incomplete native record" }
        val arguments = arrayOfNulls<Any>(constructor.parameterCount)
        val expected = properties.mapValues { (name, value) ->
            arguments[slots.getValue(name)] = value
            if (name in byteFromInt && fields.getValue(name).type == java.lang.Byte.TYPE &&
                constructor.parameterTypes[slots.getValue(name)] == java.lang.Integer.TYPE
            ) {
                require(value is Int && value in Byte.MIN_VALUE..Byte.MAX_VALUE) { "Native byte value out of range: $name" }
                value.toByte()
            } else value
        }
        return constructor.newInstance(*arguments).also { result ->
            check(fields.all { (name, field) -> field.get(result) == expected[name] }) {
                "Native record constructor did not preserve its properties"
            }
        }
    }
}
