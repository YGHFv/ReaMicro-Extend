package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal object HostBookBindings {
    const val KEY = "books.actions"
    @Volatile var current: Symbols? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors(): Map<String, String>? = snapshot?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val result = query(session, false)
            current = restore(result, loader)
            snapshot = result
        } }.onFailure { HookRuntime.logError("ReaMicro book actions unavailable", it) }
    }

    fun rescan(session: HostQuerySession.Session, base: Map<String, String>): Map<String, String> = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean,
        base: Map<String, String> = requireNotNull(HostAutoScanner.queriedDescriptors())) =
        session.query(KEY, force, validate = { restore(it, session.loader) }, find = { bridge ->
            HostBookQueries.query(bridge, session.apk, base,
                requireNotNull(session.cached("reader.controls") ?: HostReaderFeatures.boundDescriptors()))
        }).values.toMap()

    class Symbols internal constructor(val members: HostRuntimeBindings.Members, private val values: Map<String, String>) {
        fun method(key: String) = members.method(key)
        fun type(key: String) = members.type(key)
        fun field(key: String) = members.field(key)
        fun constructor(key: String) = members.constructor(key)
        val properties: Map<String, Field> = values.keys.filter { it.startsWith("field:book#") }
            .associate { it.substringAfter('#') to field(it.removePrefix("field:")) }
        val copyFields: List<Field> = constructor("book").parameterTypes.indices.map { field("copy.$it") }
        private val captures = values.keys.filter { it.startsWith("field:topCapture.") }.map { field(it.removePrefix("field:")) }
        private val reads = values.keys.filter { it.startsWith("method:topRead.") }.map { method(it.removePrefix("method:")) }
        fun isTopCallback(value: Any, state: Any): Boolean = method("topRender").declaringClass.isInstance(value) &&
            (values["value:topTag"]?.let { field("topTag").getByte(value).toLong() == it.toLong() } ?: true) &&
            captures.any { capture -> capture.get(value)?.let { receiver ->
                reads.any { read -> read.declaringClass.isInstance(receiver) && read.invoke(receiver) === state }
            } == true }
        fun snapshot(book: Any): Map<String, Any?> {
            require(type("book").isInstance(book))
            return properties.mapValues { it.value.get(book) }
        }
        fun create(properties: Map<String, Any?>): Any {
            require(properties.keys == this.properties.keys) { "Incomplete book record" }
            val byField = properties.mapKeys { this.properties.getValue(it.key) }
            return constructor("book").newInstance(*copyFields.map { byField.getValue(it) }.toTypedArray()).also {
                check(snapshot(it) == properties) { "Book constructor changed record properties" }
            }
        }
        fun withoutAssociation(book: Any): Any {
            return copy(book, mapOf("cloudId" to 0L, "publisher" to ""))
        }
        fun copy(book: Any, changes: Map<String, Any?>): Any {
            val before = snapshot(book)
            require(changes.keys.all { it in properties }) { "Unknown book properties: ${changes.keys - properties.keys}" }
            val fields = changes.mapKeys { properties.getValue(it.key) }
            val result = constructor("book").newInstance(*copyFields.map { if (fields.containsKey(it)) fields[it] else it.get(book) }.toTypedArray())
            check(snapshot(book) == before && snapshot(result) == before + changes) {
                "Book copy failed to preserve record properties"
            }
            return result
        }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Symbols {
        fun keys(prefix: String, names: String) = names.split(' ').map { "$prefix:$it" }
        val required = keys("class", "book vm repository shelf dao route state args scope publish") +
            keys("method", "screen currentState update find sourceClick topBar topRender topRead.0 editVector editIcon") +
            keys("constructor", "vm topCallback publish book") +
            keys("field", "args repository shelf dao sourceBook args#bookId args#compat route#bookId route#compat state#barcode book#id book#cloudId book#publisher topCapture.0")
        check(values.keys.containsAll(required)) { "Missing book contracts ${required.toSet() - values.keys}" }
        val s = Symbols(HostRuntimeBindings.Members(values, loader), values)
        fun field(name: String, owner: String, type: Class<*>) {
            check(s.field(name).declaringClass == s.type(owner) && s.field(name).type == type && !Modifier.isStatic(s.field(name).modifiers))
        }
        for (owner in listOf("args", "route")) {
            field("$owner#bookId", owner, java.lang.Long.TYPE); field("$owner#compat", owner, java.lang.Boolean.TYPE)
        }
        field("state#barcode", "state", String::class.java)
        field("book#id", "book", java.lang.Long.TYPE); field("book#cloudId", "book", java.lang.Long.TYPE)
        field("book#publisher", "book", String::class.java)
        field("args", "vm", s.type("args")); field("repository", "vm", s.type("repository"))
        field("shelf", "repository", s.type("shelf")); field("dao", "shelf", s.type("dao"))
        val screen = s.method("screen")
        check(Modifier.isStatic(screen.modifiers) && screen.returnType == Void.TYPE && screen.parameterCount == 4 &&
            screen.parameterTypes.take(2) == listOf(s.type("scope"), s.type("args")))
        val vmConstructor = s.constructor("vm")
        check(vmConstructor.declaringClass == s.type("vm") && vmConstructor.parameterTypes.take(2) == listOf(s.type("args"), s.type("repository")))
        val current = s.method("currentState")
        check(!Modifier.isStatic(current.modifiers) && current.declaringClass.isAssignableFrom(s.type("vm")) &&
            current.parameterCount == 0 && current.returnType.isAssignableFrom(s.type("state")))
        for ((key, owner, first) in listOf(Triple("update", "shelf", s.type("book")), Triple("find", "dao", java.lang.Long.TYPE))) {
            val m = s.method(key)
            check(!Modifier.isStatic(m.modifiers) && m.declaringClass == s.type(owner) && m.returnType == Any::class.java &&
                m.parameterCount == 2 && m.parameterTypes[0] == first)
            check(loader.hostClass("kotlin.coroutines.Continuation").isAssignableFrom(m.parameterTypes[1]))
        }
        val source = s.method("sourceClick")
        check(!Modifier.isStatic(source.modifiers) && source.parameterCount == 0 && source.returnType == Any::class.java)
        check(s.field("sourceBook").declaringClass == source.declaringClass && s.field("sourceBook").type == s.type("book"))
        val callback = s.method("topRender")
        check(!Modifier.isStatic(callback.modifiers) && callback.declaringClass == s.constructor("topCallback").declaringClass &&
            callback.parameterTypes.toList() == List(2) { Any::class.java } && callback.returnType == Any::class.java)
        for (key in values.keys.filter { it.startsWith("field:topCapture.") }) {
            val f = s.field(key.removePrefix("field:"))
            check(f.declaringClass == callback.declaringClass && !Modifier.isStatic(f.modifiers) && !f.type.isPrimitive)
        }
        for (key in values.keys.filter { it.startsWith("method:topRead.") }) {
            val m = s.method(key.removePrefix("method:"))
            check(m.parameterCount == 0 && m.returnType == Any::class.java && !Modifier.isStatic(m.modifiers))
        }
        values["value:topTag"]?.let {
            check(it.toLong() in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() && s.field("topTag").declaringClass == callback.declaringClass &&
                s.field("topTag").type == java.lang.Byte.TYPE && !Modifier.isStatic(s.field("topTag").modifiers))
        }
        val topBar = s.method("topBar")
        check(Modifier.isStatic(topBar.modifiers) && topBar.parameterCount == 11 && topBar.returnType == Void.TYPE &&
            topBar.parameterTypes[8] == screen.parameterTypes[2])
        val vector = s.method("editVector")
        check(Modifier.isStatic(vector.modifiers) && vector.parameterCount == 0 && !vector.returnType.isPrimitive)
        val icon = s.method("editIcon")
        check(Modifier.isStatic(icon.modifiers) && icon.returnType == Void.TYPE && icon.parameterCount == 7 &&
            icon.parameterTypes.toList() == listOf(vector.returnType, String::class.java, icon.parameterTypes[2],
                java.lang.Long.TYPE, screen.parameterTypes[2], Integer.TYPE, Integer.TYPE))
        val instance = s.type("book").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet()
        check(s.properties.values.toSet() == instance && s.copyFields.toSet() == instance && s.copyFields.size == instance.size &&
            instance.all { Modifier.isFinal(it.modifiers) } && s.constructor("book").declaringClass == s.type("book") &&
            s.constructor("book").parameterTypes.toList() == s.copyFields.map { it.type })
        check(s.constructor("publish").declaringClass == s.type("publish") && s.constructor("publish").parameterTypes.toList() == listOf(java.lang.Long.TYPE))
        return s
    }
}
