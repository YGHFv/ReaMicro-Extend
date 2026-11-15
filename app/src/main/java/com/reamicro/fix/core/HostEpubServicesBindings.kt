package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostEpubServicesBindings {
    val QUERY_KEYS = setOf("epub.svg", "epub.css", "epub.metadata")
    @Volatile private var contracts: Map<String, Contract> = emptyMap()
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    @Volatile var initializationAttempted = false; private set
    val svg get() = contracts["svg"]
    val css get() = contracts["css"]
    val metadata get() = contracts["metadata"]
    fun boundDescriptors() = snapshots.mapValues { it.value.toMap() }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; contracts = emptyMap(); snapshots = emptyMap()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val found = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            contracts = found.mapKeys { it.key.substringAfter('.') }.mapValues { (group, values) -> restore(group, values, loader) }
            snapshots = found
        } }.onFailure { HookRuntime.logError("ReaMicro EPUB service queries unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean): Map<String, Map<String, String>> {
        return session.collect(QUERY_KEYS) { key ->
            val group = key.substringAfter('.')
            session.query(key, force, validate = { restore(group, it, session.loader) },
                find = { HostEpubServicesQueries.query(group, it, session.apk, base) }).values.toMap()
        }
    }
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun value(role: String, receiver: Any?) = field(role).get(receiver)
        fun required(role: String, receiver: Any?) = requireNotNull(value(role, receiver)) { "Missing EPUB service $role" }
        fun text(role: String, receiver: Any?) = value(role, receiver) as? String ?: ""
    }
    private fun restore(group: String, v: Map<String, String>, loader: ClassLoader): Contract {
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        val any = Any::class.java; val string = String::class.java; val void = Void.TYPE
        fun method(role: String, owner: String?, args: List<Class<*>>, result: Class<*>, static: Boolean = false) {
            val m = c.method(role)
            check(Modifier.isStatic(m.modifiers) == static && m.parameterTypes.toList() == args && m.returnType == result)
            if (owner != null) check(m.declaringClass == c.type(owner))
        }
        fun field(role: String, owner: String?, result: Class<*>?, static: Boolean = false) {
            val f = c.field(role)
            check(Modifier.isStatic(f.modifiers) == static)
            if (owner != null) check(f.declaringClass == c.type(owner))
            if (result != null) check(f.type == result)
        }
        when (group) {
            "svg" -> {
                check(c.type("source").isInterface)
                method("decode", "decoder", listOf(c.type("source")), c.type("decoded"))
                method("input", "source", emptyList(), java.io.InputStream::class.java)
                method("render", "renderer", listOf(android.graphics.Canvas::class.java), void)
                method("dimensions", "document", emptyList(), c.type("box"))
                field("decoder.instance", "decoder", c.type("decoder"), true)
                field("decoded.document", "decoded", null)
                check(c.field("decoded.document").type.isAssignableFrom(c.type("document")))
                field("document.root", "document", null)
                check(c.field("document.root").type.isAssignableFrom(c.type("root")))
                field("root.viewBox", null, c.type("box"))
                check(c.field("root.viewBox").declaringClass.isAssignableFrom(c.type("root")))
                val dimensions = listOf("left", "top", "width", "height")
                dimensions.forEach { field("box.$it", "box", java.lang.Float.TYPE) }
                check(dimensions.map { c.field("box.$it") }.distinct().size == 4)
                check(c.constructor("renderer").let { it.declaringClass == c.type("renderer") && it.parameterTypes.toList() ==
                    listOf(c.type("document"), c.type("options"), Integer.TYPE, Integer.TYPE) })
            }
            "metadata" -> {
                method("obtain", null, listOf(c.type("path")), c.type("opf"), true)
                method("path", null, listOf(string, java.lang.Boolean.TYPE), c.type("path"), true)
                method("id.value", "id", emptyList(), string)
                method("bannerNames", null, listOf(string), List::class.java, true)
                method("coverNames", null, listOf(string, string), c.type("coverPlan"), true)
                field("cover.primary", "coverPlan", string)
                field("cover.backup", "coverPlan", string)
                field("opf.metadata", "opf", c.type("metadata")); field("opf.reference", "opf", string)
                field("metadata.identifier", "metadata", c.type("identifier"))
                field("identifier.id", "identifier", c.type("id")); field("identifier.type", "identifier", c.type("kind"))
                field("identifier.value", "identifier", string); field("kind.scheme", "kind", string)
                check(c.type("kind").isEnum)
                validateCoverCache(c)
            }
            "css" -> {
                method("attach", "provider", listOf(Array<String>::class.java), c.type("sheets"), true)
                method("merge", null, listOf(ArrayList::class.java, Map::class.java), ArrayList::class.java, true)
                method("cache.get", "cache", listOf(any), any)
                method("cache.expire", "cache", emptyList(), void)
                method("lazy.get", "lazy", emptyList(), any)
                method("trim", null, listOf(string), string, true)
                method("property.name", "property", emptyList(), string)
                method("property.initial", "property", emptyList(), c.type("value"))
                method("property.actual", "property", emptyList(), c.type("value"))
                method("unit.number", "unit", emptyList(), java.lang.Float.TYPE)
                check(c.type("value").isAssignableFrom(c.type("unit")))
                field("provider.cache", "provider", c.type("lazy"), true)
                field("cache.holder", "cache", c.type("holder")); field("cache.entries", "holder", null)
                for (i in 0..1) {
                    field("cache.queue.$i", "cache", null)
                    check(Collection::class.java.isAssignableFrom(c.field("cache.queue.$i").type))
                }
                check(c.field("cache.queue.0") != c.field("cache.queue.1"))
                field("sheets.rules", "sheets", List::class.java)
                field("rule.selector", "rule", c.type("selector")); field("rule.declarations", "rule", List::class.java)
                for (role in listOf("name", "value")) field("declaration.$role", "declaration", string)
                check(c.field("declaration.name") != c.field("declaration.value"))
                field("selector.name", "selector", string); field("selector.level", "selector", Integer.TYPE)
                field("registry", null, java.util.LinkedHashMap::class.java, true)
            }
            else -> error("Unknown EPUB service $group")
        }
        return c
    }

    private fun validateCoverCache(c: Contract) {
        val prefix = "coverCache."
        fun type(role: String) = c.type(prefix + role)
        fun method(role: String, owner: String?, parameters: List<Class<*>>, result: Class<*>, static: Boolean = false) {
            val member = c.method(prefix + role)
            check(Modifier.isStatic(member.modifiers) == static &&
                member.parameterTypes.toList() == parameters && member.returnType == result)
            if (owner != null) check(member.declaringClass == type(owner))
        }
        fun field(role: String, owner: String, target: Class<*>, mayBeErased: Boolean = false) {
            val member = c.field(prefix + role)
            check(!Modifier.isStatic(member.modifiers) && member.declaringClass == type(owner))
            check(if (mayBeErased) member.type.isAssignableFrom(target) else member.type == target)
        }
        fun constructor(role: String, parameters: List<Class<*>>) {
            val member = c.constructor(prefix + role)
            check(member.declaringClass == type(role) && member.parameterTypes.toList() == parameters)
        }
        method("provider", null, listOf(android.content.Context::class.java), type("loader"), true)
        method("memory", "loader", emptyList(), type("memory"))
        method("size", "lru", emptyList(), java.lang.Long.TYPE)
        field("memory.lock", "memory", Any::class.java)
        field("memory.strong", "memory", type("strong"))
        field("memory.weak", "memory", type("weak"))
        field("strong.lru", "strong", type("lru"), true)
        field("lru.entries", "lru", java.util.LinkedHashMap::class.java, true)
        field("lru.size", "lru", java.lang.Long.TYPE)
        field("weak.entries", "weak", java.util.LinkedHashMap::class.java, true)
        field("key.value", "key", String::class.java)
        constructor("memory", listOf(type("strong"), type("weak")))
        constructor("strong", listOf(java.lang.Long.TYPE, type("weak")))
        constructor("weak", listOf(java.lang.Byte.TYPE))
        constructor("key", listOf(String::class.java, Map::class.java))
        constructor("value", listOf(type("image"), Map::class.java, java.lang.Long.TYPE))
        constructor("weakValue", listOf(java.lang.ref.WeakReference::class.java, Map::class.java, java.lang.Long.TYPE))
        check(c.values.getValue("value:coverCache.weakTag").toLong() in Byte.MIN_VALUE..Byte.MAX_VALUE)
    }
}
