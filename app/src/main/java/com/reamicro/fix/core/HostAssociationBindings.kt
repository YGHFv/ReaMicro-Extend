package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostAssociationBindings {
    const val KEY = "books.association"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro association query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostAssociationQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun vmConstructors() = values.keys.filter { it.startsWith("constructor:vm.") }.map { constructor(it.removePrefix("constructor:")) }
        fun bookshelfRepository(vm: Any): Any {
            require(type("vm").isInstance(vm))
            val repository = requireNotNull(field("vm.repository").get(vm))
            check(type("repository").isInstance(repository))
            return requireNotNull(field("repository.shelf").get(repository)).also {
                check(type("shelf").isInstance(it))
            }
        }
        fun property(role: String, name: String, record: Any): Any? {
            require(type(role).isInstance(record))
            return field("$role#$name").get(record)
        }
        private fun properties(role: String) = values.keys.filter { it.startsWith("field:$role#") }
            .associate { key -> key.substringAfter('#') to field(key.removePrefix("field:")) }
        fun create(role: String, values: Map<String, Any?>): Any {
            val schema = properties(role)
            require(values.keys == schema.keys) { "Incomplete association $role values" }
            fun coerce(value: Any?, type: Class<*>): Any? = when {
                value is List<*> && type == java.util.ArrayList::class.java -> ArrayList(value)
                else -> value
            }
            val expected = values.mapValues { (name, value) -> coerce(value, schema.getValue(name).type) }
            val ctor = constructor(role)
            val args = ctor.parameterTypes.indices.map { index ->
                val literal = this.values["value:$role.argument.$index"]
                if (literal != null) literal.toInt() else {
                    val target = field("$role.argument.$index")
                    expected.getValue(schema.entries.single { it.value == target }.key)
                }
            }.toTypedArray()
            val result = ctor.newInstance(*args)
            check(schema.all { (name, field) -> field.get(result) == expected[name] }) { "Association $role constructor changed values" }
            return result
        }
        fun copyState(state: Any, changes: Map<String, Any?>): Any {
            val schema = properties("state")
            require(type("state").isInstance(state) && changes.keys.all { it in schema })
            val before = schema.mapValues { it.value.get(state) }
            return create("state", before + changes).also {
                check(schema.all { (name, field) -> field.get(state) == before[name] })
            }
        }
    }

    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val required = listOf("class:vm", "class:state", "class:book", "class:candidate", "class:group", "class:flow",
            "class:repository", "class:shelf", "field:vm.repository", "field:repository.shelf",
            "method:current", "method:update", "method:get", "method:cas", "method:set", "method:cleared", "field:vm.flow", "constructor:vm.0")
        check(values.keys.containsAll(required))
        for ((role, owner, target) in listOf(Triple("vm.repository", "vm", "repository"),
            Triple("repository.shelf", "repository", "shelf"))) {
            val field = s.field(role)
            check(field.declaringClass == s.type(owner) && field.type == s.type(target) &&
                Modifier.isFinal(field.modifiers) && !Modifier.isStatic(field.modifiers))
        }
        val schemas = mapOf("state" to "book recommend thirdPartyBooks searching relating isRelateSuccess",
            "candidate" to "title author alias subtitle original intro cover translator publisher publishDate price isbn words detail rating status",
            "group" to "publisher books")
        for ((role, names) in schemas) {
            val schema = names.split(' ').map { s.field("$role#$it") }
            check(schema.toSet() == s.type(role).declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet())
            check(schema.all { Modifier.isFinal(it.modifiers) && it.declaringClass == s.type(role) })
            val ctor = s.constructor(role)
            check(ctor.declaringClass == s.type(role))
            val mapped = ctor.parameterTypes.mapIndexedNotNull { index, type ->
                val constant = values["value:$role.argument.$index"]
                if (constant != null) { check(type == Integer.TYPE && constant.toInt() > 0); null }
                else s.field("$role.argument.$index").also { check(it.type == type && it in schema) }
            }
            check(mapped.size == schema.size && mapped.toSet() == schema.toSet() && ctor.parameterCount - mapped.size in 0..1)
        }
        check(s.field("state#book").type == s.type("book"))
        check(List::class.java.isAssignableFrom(s.field("state#thirdPartyBooks").type))
        for (name in listOf("searching", "relating", "isRelateSuccess")) check(s.field("state#$name").type == java.lang.Boolean.TYPE)
        for (name in listOf("title", "author")) check(s.field("book#$name").declaringClass == s.type("book") && s.field("book#$name").type == String::class.java)
        check(s.field("group#publisher").type == String::class.java && s.field("group#books").type == List::class.java)
        for (name in schemas.getValue("candidate").split(' ')) check(s.field("candidate#$name").type == if (name == "rating") java.lang.Double.TYPE else String::class.java)
        val vmFlow = s.field("vm.flow")
        check(vmFlow.declaringClass.isAssignableFrom(s.type("vm")) && vmFlow.type == s.type("flow") && !Modifier.isStatic(vmFlow.modifiers))
        for ((role, parameters, result) in listOf(Triple("get", emptyList(), Any::class.java),
            Triple("cas", List(2) { Any::class.java }, java.lang.Boolean.TYPE), Triple("set", listOf(Any::class.java), Void.TYPE))) {
            val method = s.method(role)
            check(method.declaringClass.isAssignableFrom(s.type("flow")) && !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.toList() == parameters && method.returnType == result)
        }
        for (role in listOf("current", "cleared")) {
            val method = s.method(role)
            check(method.declaringClass.isAssignableFrom(s.type("vm")) && !Modifier.isStatic(method.modifiers) && method.parameterCount == 0)
            check(if (role == "current") method.returnType.isAssignableFrom(s.type("state")) else method.returnType == Void.TYPE)
        }
        check(s.vmConstructors().all { it.declaringClass == s.type("vm") })
        val update = s.method("update")
        check(!Modifier.isStatic(update.modifiers) && update.declaringClass.isAssignableFrom(s.type("vm")) &&
            update.parameterTypes.toList() == listOf(s.type("reducer")) && update.returnType == Void.TYPE && s.type("reducer").isInterface)
        return s
    }
}
