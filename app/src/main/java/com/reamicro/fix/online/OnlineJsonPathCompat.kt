package com.reamicro.fix.online

import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线书源使用的轻量 JSONPath 兼容器。
 *
 * 只实现当前书源规则所需的字段、数组下标、通配符和递归下降语义，
 * 但递归下降后必须继续执行剩余路径，不能把 `$..name[*].*` 截断成 `$..name`。
 */
internal object OnlineJsonPathCompat {
    fun values(node: Any?, rawRule: String): List<Any?> {
        val rule = rawRule.trim()
        if (node == null || rule.isBlank()) return emptyList()
        return rule.split("||")
            .asSequence()
            .map { valuesSingle(node, it.trim()) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun valuesSingle(node: Any?, rawRule: String): List<Any?> {
        if (node == null || rawRule.isBlank()) return emptyList()
        if (rawRule.startsWith("$..")) {
            return recursivePathValues(node, rawRule.removePrefix("$.."))
        }
        var path = rawRule
        if (path.startsWith("$.")) path = path.drop(2)
        else if (path.startsWith(".")) path = path.drop(1)
        else if (path == "$") return listOf(node)
        return followPath(listOf(node), path)
    }

    private fun recursivePathValues(node: Any?, descendantPath: String): List<Any?> {
        val name = descendantPath.substringBefore('.').substringBefore('[')
        if (name.isBlank()) return emptyList()
        var current = recursiveValues(node, name)
        var remaining = descendantPath.removePrefix(name)

        val selector = Regex("""^\[(\d+|\*)]""").find(remaining)
        if (selector != null) {
            current = applyArraySelector(current, selector.groupValues[1])
            remaining = remaining.removeRange(selector.range)
        }
        remaining = remaining.removePrefix(".")
        return followPath(current, remaining)
    }

    private fun followPath(initial: List<Any?>, rawPath: String): List<Any?> {
        if (rawPath.isBlank()) return initial.filterValid()
        var current = initial
        rawPath.split('.').filter { it.isNotBlank() }.forEach { token ->
            current = current.flatMap { value -> step(value, token) }
            if (current.isEmpty()) return emptyList()
        }
        return current.filterValid()
    }

    private fun step(value: Any?, token: String): List<Any?> {
        if (value == null || value == JSONObject.NULL) return emptyList()
        if (token == "*" || token == "*[*]") {
            val children = when (value) {
                is JSONObject -> value.keys().asSequence().map { value.opt(it) }.toList()
                is JSONArray -> (0 until value.length()).map { value.opt(it) }
                else -> emptyList()
            }
            return if (token.endsWith("[*]")) children.flatMap(::arrayItems) else children
        }
        val match = Regex("""^([^\[]+)(?:\[(\d+|\*)])?$""").matchEntire(token)
            ?: return emptyList()
        val name = match.groupValues[1]
        val index = match.groupValues.getOrNull(2).orEmpty()
        val values = when (value) {
            is JSONObject -> listOf(value.opt(name))
            is JSONArray -> (0 until value.length()).mapNotNull { itemIndex ->
                (value.opt(itemIndex) as? JSONObject)?.opt(name)
            }
            else -> emptyList()
        }
        return when (index) {
            "" -> values
            "*" -> values.flatMap(::arrayItems)
            else -> values.mapNotNull { arrayValue ->
                (arrayValue as? JSONArray)?.opt(index.toIntOrNull() ?: return@mapNotNull null)
            }
        }
    }

    private fun applyArraySelector(values: List<Any?>, selector: String): List<Any?> =
        when (selector) {
            "*" -> values.flatMap(::arrayItems)
            else -> values.mapNotNull { value ->
                (value as? JSONArray)?.opt(selector.toIntOrNull() ?: return@mapNotNull null)
            }
        }

    private fun arrayItems(value: Any?): List<Any?> =
        when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is JSONObject -> value.keys().asSequence().map { value.opt(it) }.toList()
            null, JSONObject.NULL -> emptyList()
            else -> listOf(value)
        }

    private fun recursiveValues(value: Any?, name: String): List<Any?> {
        val results = mutableListOf<Any?>()
        fun visit(item: Any?) {
            when (item) {
                is JSONObject -> {
                    if (item.has(name)) results.add(item.opt(name))
                    item.keys().asSequence().forEach { visit(item.opt(it)) }
                }
                is JSONArray -> for (index in 0 until item.length()) visit(item.opt(index))
            }
        }
        visit(value)
        return results.filterValid()
    }

    private fun List<Any?>.filterValid(): List<Any?> =
        filter { it != null && it != JSONObject.NULL }
}
