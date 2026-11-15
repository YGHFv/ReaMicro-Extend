package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

/** Immutable vector contract for the loaded APK. Rendering performs no name discovery. */
internal object HostGraphicsBindings {
    const val KEY = "graphics.vector"
    @Volatile var initializationAttempted = false; private set
    @Volatile var current: Contract? = null; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors(): Map<String, String>? = snapshot?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            var contract: Contract? = null
            val result = session.query(KEY,
                validate = { contract = restore(it, loader) }, find = { HostGraphicsQueries.query(it, apk) })
            current = requireNotNull(contract); snapshot = result.values.toMap()
            HookRuntime.log("ReaMicro $KEY ${if (result.cached) "cached" else "resolved"}")
        } }.onFailure { HookRuntime.logError("ReaMicro $KEY unavailable", it) }
    }

    fun rescan(session: HostQuerySession.Session, base: Map<String, String>): Map<String, String> =
        session.query(KEY, true, validate = { restore(it, session.loader) },
            find = { HostGraphicsQueries.query(it, session.apk) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, val loader: ClassLoader) {
        private val members = HostRuntimeBindings.Members(values, loader)
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)

        private fun primitive(type: Class<*>, raw: Long): Any = when (type) {
            java.lang.Byte.TYPE -> { check(raw in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()); raw.toByte() }
            Integer.TYPE -> { check(raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); raw.toInt() }
            java.lang.Long.TYPE -> raw
            java.lang.Float.TYPE -> { check(raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); Float.fromBits(raw.toInt()) }
            java.lang.Boolean.TYPE -> { check(raw == 0L || raw == 1L); raw != 0L }
            else -> error("Unsupported native vector literal type: $type")
        }

        private fun args(key: String, types: Array<Class<*>>, roles: Map<String, Any>): Array<Any?> =
            Array(types.size) { index -> values.getValue("value:$key.$index").let { value ->
                if (value.startsWith("literal:")) primitive(types[index], value.removePrefix("literal:").toLong())
                else roles.getValue(value)
            } }

        fun build(name: String, layers: List<Pair<Long, String>>, size: Float, viewport: Float, evenOdd: Boolean): Any {
            require(size.isFinite() && size > 0f && viewport.isFinite() && viewport > 0f)
            val builderCtor = constructor("builder")
            val builder = builderCtor.newInstance(*args("builder", builderCtor.parameterTypes, mapOf("name" to name, "size" to size, "viewport" to viewport)))
            val parserCtor = constructor("parser")
            val parser = parserCtor.newInstance(*Array(parserCtor.parameterCount) { index ->
                primitive(parserCtor.parameterTypes[index], values.getValue("value:parser.$index").toLong())
            })
            val add = method("add")
            for ((argb, path) in layers) {
                val nodes = ArrayList<Any>()
                method("parse").invoke(parser, path, nodes)
                val brush = constructor("brush").newInstance(method("color").invoke(null, argb))
                add.invoke(null, *args("add", add.parameterTypes, mapOf("builder" to builder, "nodes" to nodes, "brush" to brush, "fill" to if (evenOdd) 1 else 0)))
            }
            return requireNotNull(method("build").invoke(builder))
        }

        internal fun validate() {
            val required = linkedSetOf("class:builder", "class:vector", "class:brush", "class:parser",
                "method:build", "method:add", "method:color", "method:parse", "constructor:builder", "constructor:brush", "constructor:parser")
            val builder = type("builder"); val brush = type("brush"); val parser = type("parser")
            fun shape(key: String, owner: Class<*>? = null, args: List<Class<*>>, result: Class<*>, static: Boolean) {
                val value = method(key)
                check((owner == null || value.declaringClass == owner) && value.parameterTypes.toList() == args &&
                    value.returnType == result && Modifier.isStatic(value.modifiers) == static)
            }
            shape("build", builder, emptyList(), type("vector"), false)
            shape("color", args = listOf(java.lang.Long.TYPE), result = java.lang.Long.TYPE, static = true)
            shape("parse", parser, listOf(String::class.java, ArrayList::class.java), Void.TYPE, false)
            check(constructor("brush").declaringClass == brush && constructor("brush").parameterTypes.toList() == listOf(java.lang.Long.TYPE))
            val ctor = constructor("builder"); val add = method("add")
            check(ctor.declaringClass == builder && add.declaringClass == builder && Modifier.isStatic(add.modifiers) && add.returnType == Void.TYPE)
            fun template(key: String, types: Array<Class<*>>, roles: Map<String, Pair<Class<*>, Int>>) {
                val seen = hashMapOf<String, Int>()
                types.forEachIndexed { index, type ->
                    val name = "value:$key.$index"; required += name
                    val value = values.getValue(name)
                    if (value.startsWith("literal:")) primitive(type, value.removePrefix("literal:").toLong())
                    else { check(roles.getValue(value).first == type); seen[value] = (seen[value] ?: 0) + 1 }
                }
                check(seen == roles.mapValues { it.value.second })
            }
            template("builder", ctor.parameterTypes, mapOf("name" to (String::class.java to 1), "size" to (java.lang.Float.TYPE to 2), "viewport" to (java.lang.Float.TYPE to 2)))
            template("add", add.parameterTypes, mapOf("builder" to (builder to 1), "nodes" to (ArrayList::class.java to 1), "brush" to (brush to 1), "fill" to (Integer.TYPE to 1)))
            val parserCtor = constructor("parser")
            check(parserCtor.declaringClass == parser && parserCtor.parameterTypes.all { it in listOf(java.lang.Byte.TYPE, java.lang.Boolean.TYPE, Integer.TYPE) })
            parserCtor.parameterTypes.forEachIndexed { index, type ->
                val key = "value:parser.$index"; required += key; primitive(type, values.getValue(key).toLong())
            }
            check(values.keys == required) { "Incomplete native vector contract: missing=${required - values.keys}; extra=${values.keys - required}" }
        }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract = Contract(values.toMap(), loader).apply { validate() }
}
