package com.reamicro.fix.core

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 既有 B4 私有桥使用的短名独立重定位；不污染业务逻辑名和真实反射方法。 */
object HostRawAbi {
    private val mappings = HashMap<String, JSONObject>()
    private fun mapping(): JSONObject = synchronized(mappings) {
        mappings.getOrPut(HostSymbols.profileId) {
            JSONObject(HostSymbols.bundledAsset("assets/host-profiles/${HostSymbols.profileId}.abi.json")
                .toString(Charsets.UTF_8))
        }
    }

    fun method(type: Class<*>, name: String, types: Array<out Class<*>?>): Method {
        if (!HostSymbols.usesRc3Abi) return type.getDeclaredMethod(name, *types)
        val descriptor = types.joinToString("") { descriptor(requireNotNull(it)) }
        val entry = mapping().getJSONObject("methods").optString("${type.name}#$name($descriptor)")
        if (entry.isEmpty()) return type.getDeclaredMethod(name, *types)
        val owner = Class.forName(entry.substringBefore('#'), false, type.classLoader)
        return owner.getDeclaredMethod(entry.substringAfter('#'), *types)
    }

    fun methodName(type: Class<*>, name: String, arity: Int): Pair<Class<*>, String> {
        if (!HostSymbols.usesRc3Abi) return type to name
        val entry = mapping().getJSONObject("arity").optString("${type.name}#$name/$arity")
        if (entry.isEmpty()) return type to name
        return Class.forName(entry.substringBefore('#'), false, type.classLoader) to entry.substringAfter('#')
    }

    fun field(type: Class<*>, name: String): Field {
        if (!HostSymbols.usesRc3Abi) return type.getDeclaredField(name)
        val entry = mapping().getJSONObject("fields").optString("${type.name}#$name")
        if (entry.isEmpty()) return type.getDeclaredField(name)
        return Class.forName(entry.substringBefore('#'), false, type.classLoader)
            .getDeclaredField(entry.substringAfter('#'))
    }

    private fun descriptor(type: Class<*>): String = when (type) {
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Short.TYPE -> "S"
        java.lang.Character.TYPE -> "C"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        java.lang.Void.TYPE -> "V"
        else -> if (type.isArray) type.name.replace('.', '/') else "L${type.name.replace('.', '/')};"
    }
}

fun Class<*>.hostAbiMethod(name: String, vararg types: Class<*>?): Method =
    HostRawAbi.method(this, name, types)

fun Class<*>.hostAbiField(name: String): Field = HostRawAbi.field(this, name)

fun Class<*>.hostAbiMethodByArity(name: String, arity: Int): Method {
    val (owner, actual) = HostRawAbi.methodName(this, name, arity)
    return owner.declaredMethods.single { it.name == actual && it.parameterCount == arity }
}
