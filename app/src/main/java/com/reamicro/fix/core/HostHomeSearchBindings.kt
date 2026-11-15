package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostHomeSearchBindings {
    const val KEY = "home.search"
    @Volatile var initializationAttempted = false; private set
    @Volatile var current: Contract? = null; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun requireCurrent() = requireNotNull(current) { "Home search contract unavailable" }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            var bound: Contract? = null
            val result = session.query(KEY,
                validate = { bound = restore(it, loader) },
                find = { HostHomeSearchQueries.query(it, apk, HostAutoScanner.queriedDescriptors().orEmpty(),
                    requireNotNull(HostUiBindings.boundDescriptors())) })
            current = requireNotNull(bound); snapshot = result.values.toMap()
            HookRuntime.log("ReaMicro $KEY ${if (result.cached) "cached" else "resolved"}")
        } }.onFailure { HookRuntime.logError("ReaMicro home search unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) =
        session.query(KEY, true, validate = { restore(it, session.loader) },
            find = { HostHomeSearchQueries.query(it, session.apk, base, ui) }).values.toMap()

    class Contract internal constructor(private val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun field(role: String) = members.field(role)
        fun method(role: String) = members.method(role)
        fun constructor(role: String) = members.constructor(role)
        fun slot(role: String) = values.getValue("value:$role").toInt()
        val properties = values.keys.filter { it.startsWith("field:state.") }.map { it.removePrefix("field:state.") }
        fun query(event: Any?): String? = when {
            type("search").isInstance(event) -> field("search.query").get(event) as String
            type("close").isInstance(event) -> ""
            else -> null
        }
        fun cloudResults(state: Any): Map<*, *>? = field("state.cloudSearchResults").get(state) as Map<*, *>?
        fun copyCloudResults(state: Any, results: Map<*, *>?): Any {
            check(type("state").isInstance(state))
            val args = arrayOfNulls<Any>(constructor("state").parameterCount)
            val copiedResults = results?.let(::LinkedHashMap)
            for (name in properties) args[slot("state.$name")] =
                if (name == "cloudSearchResults") copiedResults else field("state.$name").get(state)
            return constructor("state").newInstance(*args).also { copied ->
                for (name in properties) check(field("state.$name").get(copied) == args[slot("state.$name")])
            }
        }
        fun open(receiver: Any, book: Any) {
            check(type("home").isInstance(receiver) && type("cloud").isInstance(book))
            val event = constructor("tap").newInstance(book)
            check(field("tap.book").get(event) === book)
            method("dispatch").invoke(receiver, event)
        }
        fun update(receiver: Any, action: (Any) -> Any) {
            check(type("receiver").isInstance(receiver))
            val ui = requireNotNull(HostUiBindings.current)
            method("update").invoke(receiver, ui.function(type("function1"), fallback = { args -> args?.getOrNull(0) }) { args -> action(requireNotNull(args?.getOrNull(0))) })
        }
        private fun builderSlot(rootSlot: Int) = constructor("builder").parameterTypes.indices.single { slot ->
            values["value:builder.root.$slot"]?.toInt() == rootSlot
        }
        fun builderMatches(callback: Any, receiver: Any): Boolean = type("builder").isInstance(callback) &&
            (field("builder.${slot("builder.tag")}").get(callback) as? Number)?.toInt() == slot("builder.tagValue") &&
            field("builder.${builderSlot(slot("root.receiver"))}").get(callback) === receiver

        /** Copy only the native builder; Compose retains the original result map and restart arguments. */
        fun nativeOnly(callback: Any, receiver: Any, extensionTypes: Set<Int>): Any {
            check(builderMatches(callback, receiver))
            val args = constructor("builder").parameterTypes.indices.map { field("builder.$it").get(callback) }.toMutableList()
            val mapSlot = builderSlot(slot("root.results"))
            val results = args[mapSlot] as Map<*, *>
            args[mapSlot] = LinkedHashMap<Any?, Any?>().also { copied ->
                results.forEach { (key, value) -> if (key !in extensionTypes) copied[key] = value }
            }
            return constructor("builder").newInstance(*args.toTypedArray()).also { copy ->
                args.indices.forEach { check(field("builder.$it").get(copy) == args[it]) }
            }
        }
    }

    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val c = Contract(values.toMap(), HostRuntimeBindings.Members(values, loader))
        val any = Any::class.java; val int = Integer.TYPE; val void = Void.TYPE
        fun method(role: String, owner: String?, args: List<Class<*>>, result: Class<*>, static: Boolean = false) {
            val m = c.method(role)
            check((owner == null || m.declaringClass == c.type(owner)) && m.parameterTypes.toList() == args &&
                m.returnType == result && Modifier.isStatic(m.modifiers) == static)
        }
        fun field(role: String, owner: String, type: Class<*>) {
            val f = c.field(role)
            check(f.declaringClass == c.type(owner) && f.type == type && !Modifier.isStatic(f.modifiers))
        }
        check(listOf("search", "close", "tap").all { c.type("event").isAssignableFrom(c.type(it)) })
        check(c.type("receiver").isAssignableFrom(c.type("home")))
        method("dispatch", "home", listOf(c.type("event")), void)
        val entry = c.method("entry")
        check(entry.declaringClass == c.type("home") && !Modifier.isStatic(entry.modifiers) && entry.returnType == void &&
            entry.parameterCount == 1 && entry.parameterTypes[0].isAssignableFrom(c.type("event")))
        field("search.query", "search", String::class.java); field("tap.book", "tap", c.type("cloud"))
        check(c.constructor("tap").let { it.declaringClass == c.type("tap") && it.parameterTypes.toList() == listOf(c.type("cloud")) })
        val stateCtor = c.constructor("state")
        check(stateCtor.declaringClass == c.type("state") && stateCtor.parameterCount == c.properties.size)
        check(c.properties.map { c.slot("state.$it") }.toSet() == stateCtor.parameterTypes.indices.toSet())
        check(c.properties.map { c.field("state.$it") }.toSet() == c.type("state").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet())
        for (name in c.properties) field("state.$name", "state", stateCtor.parameterTypes[c.slot("state.$name")])
        check(c.field("state.cloudSearchResults").type in setOf(Map::class.java, LinkedHashMap::class.java))
        check(c.field("state.localSearchResults").type == List::class.java && c.field("state.searching").type == java.lang.Boolean.TYPE)
        val root = c.method("root")
        check(Modifier.isStatic(root.modifiers) && root.returnType == void && root.parameterCount == 6 &&
            root.parameterTypes.takeLast(2) == listOf(c.type("composer"), int))
        check(listOf("scope", "receiver", "results", "local").map { c.slot("root.$it") }.toSet() == (0..3).toSet())
        check(root.parameterTypes[c.slot("root.scope")] == c.type("scope") && root.parameterTypes[c.slot("root.receiver")] == c.type("receiver") &&
            root.parameterTypes[c.slot("root.local")] == List::class.java && root.parameterTypes[c.slot("root.results")] == c.field("state.cloudSearchResults").type)
        val lazy = c.method("lazy")
        check(Modifier.isStatic(lazy.modifiers) && lazy.returnType == void && lazy.parameterCount == 12 &&
            lazy.parameterTypes[c.slot("lazy.content")] == c.type("function1") && lazy.parameterTypes.count { it == c.type("function1") } == 1)
        method("invoke", "function1", listOf(any), any)
        check(c.type("function1").isInterface && c.type("function1").isAssignableFrom(c.type("builder")))
        method("builder", "builder", listOf(any), any)
        check(c.method("builder").name == c.method("invoke").name)
        val builderCtor = c.constructor("builder")
        check(builderCtor.declaringClass == c.type("builder") && builderCtor.parameterCount == 5)
        check(builderCtor.parameterTypes.indices.map { c.field("builder.$it") }.toSet() ==
            c.type("builder").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet())
        val tag = c.slot("builder.tag")
        check(tag in builderCtor.parameterTypes.indices && builderCtor.parameterTypes[tag] in setOf(Byte::class.javaPrimitiveType, int))
        check(builderCtor.parameterTypes.indices.filter { it != tag }.map { c.slot("builder.root.$it") }.toSet() == (0..3).toSet())
        builderCtor.parameterTypes.indices.forEach { slot ->
            field("builder.$slot", "builder", builderCtor.parameterTypes[slot])
            if (slot != tag) check(builderCtor.parameterTypes[slot].isAssignableFrom(root.parameterTypes[c.slot("builder.root.$slot")]))
        }
        method("item", "lazyScope", listOf(any, any, c.type("function3")), void)
        method("footer", null, listOf(c.type("lazyScope"), c.type("lambda")), void, true)
        val results = c.method("results")
        check(Modifier.isStatic(results.modifiers) && results.returnType == void && results.parameterCount == 6 &&
            results.parameterTypes.takeLast(2) == listOf(c.type("composer"), int))
        check(listOf("scope", "type", "list", "callback").map { c.slot("results.$it") }.toSet() == (0..3).toSet())
        for ((role, type) in mapOf("scope" to c.type("rowScope"), "type" to int, "list" to List::class.java, "callback" to c.type("function1")))
            check(results.parameterTypes[c.slot("results.$role")] == type)
        val row = c.method("row")
        check(Modifier.isStatic(row.modifiers) && row.returnType == void &&
            row.parameterTypes[c.slot("row.book")] == c.type("cloud") &&
            row.parameterTypes[c.slot("row.composer")] == c.type("composer") &&
            c.slot("row.composer") == row.parameterCount - 2 && row.parameterTypes.last() == int &&
            row.parameterTypes.count { it == c.type("function1") } == 1)
        val text = c.method("text")
        check(Modifier.isStatic(text.modifiers) && text.returnType == void && text.parameterCount == 20 && text.parameterTypes[0] == String::class.java)
        method("update", null, listOf(c.type("function1")), void)
        check(c.method("update").declaringClass.isAssignableFrom(c.type("receiver")))
        return c
    }
}
