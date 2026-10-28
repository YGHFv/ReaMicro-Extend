package com.reamicro.fix.core

import java.lang.reflect.Modifier

/** 按核实过的属性顺序复制不可变记录；未知字段、缺失字段和同型位置猜测均拒绝。 */
class HostExactRecordCopy(type: Class<*>, orderedProperties: List<String>) {
    private val fields = orderedProperties.map { type.hostField(it) }
    private val properties = orderedProperties.toList()
    init {
        require(properties.distinct().size == properties.size) { "Duplicate record properties" }
        val instanceFields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        require(instanceFields.toSet() == fields.toSet()) { "Record schema differs from verified constructor" }
        require(fields.all { Modifier.isFinal(it.modifiers) }) { "Record is not immutable" }
    }

    private val constructor = type.getDeclaredConstructor(*fields.map { it.type }.toTypedArray())
        .apply { isAccessible = true }

    fun snapshot(record: Any): Map<String, Any?> =
        properties.zip(fields.map { it.get(record) }).toMap()

    fun copy(record: Any, changes: Map<String, Any?>): Any {
        require(changes.keys.all { it in properties }) { "Unknown record property" }
        val before = snapshot(record)
        val expected = before + changes
        val result = constructor.newInstance(*properties.map(expected::getValue).toTypedArray())
        require(snapshot(record) == before) { "Source record changed during copy" }
        require(snapshot(result) == expected) { "Record constructor did not preserve fields" }
        return result
    }
}
