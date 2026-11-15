package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostAccountBindings {
    val QUERY_KEYS = setOf("account.identity", "account.preferences", "account.advanced", "account.ui")
    @Volatile private var contracts: Map<String, Contract> = emptyMap()
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    @Volatile var initializationAttempted = false; private set
    val identity get() = contracts["identity"]
    val preferences get() = contracts["preferences"]
    val advanced get() = contracts["advanced"]
    val ui get() = contracts["ui"]
    fun boundDescriptors() = snapshots.mapValues { it.value.toMap() }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; contracts = emptyMap(); snapshots = emptyMap()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val result = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            contracts = result.mapKeys { it.key.substringAfter('.') }.mapValues { (group, values) -> restore(group, values, loader) }
            snapshots = result
        } }.onFailure { HookRuntime.logError("ReaMicro account query session unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean): Map<String, Map<String, String>> {
        var discovery: HostAccountQueries.Discovery? = null
        return session.collect(QUERY_KEYS) { key ->
            val group = key.substringAfter('.')
            session.query(key, force, validate = { restore(group, it, session.loader) },
                find = { bridge -> (discovery ?: HostAccountQueries.Discovery(bridge, session.apk, base).also { discovery = it }).query(group) }).values.toMap()
        }
    }
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members,
        private val loader: ClassLoader) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun methods(prefix: String) = values.keys.filter { it.startsWith("method:$prefix.") }.sorted().map { method(it.removePrefix("method:")) }
        fun constructors(prefix: String) = values.keys.filter { it.startsWith("constructor:$prefix.") }.sorted().map { constructor(it.removePrefix("constructor:")) }
        fun key(name: String): Any = requireNotNull(field("key.$name").get(null)).also {
            check(field("key.name").get(it) == name) { "Unexpected account preference key: $name" }
        }
        fun first(flow: Any) = HostCoroutines.invokeTimed(loader, method("first"), null, 15_000L, flow)
        fun record(role: String, values: Map<String, Any?>): Any {
            val args = arrayOfNulls<Any?>(constructor(role).parameterCount)
            values.forEach { (name, value) -> args[index("$role.$name")] = value }
            require(values.size == args.size && values.keys.all { "value:$role.$it" in this.values })
            return constructor(role).newInstance(*args).also { result ->
                values.forEach { (name, value) -> check(field("$role.$name").get(result) == value) { "Account $role.$name constructor mismatch" } }
            }
        }
    }
    internal fun restore(group: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val required = linkedSetOf<String>()
        fun keys(prefix: String, names: String) { names.split(' ').filter(String::isNotBlank).forEach { required += "$prefix:$it" } }
        fun record(role: String, names: List<String>) {
            required += "constructor:$role"
            names.forEach { required += "field:$role.$it"; required += "value:$role.$it" }
        }
        when (group) {
            "identity" -> {
                keys("class", "user info repository session storage shelf cachedData dao continuation activity repositoryProperty")
                keys("constructor", "cachedData repository.0 session.0")
                keys("field", "cachedUser cachedData.user repository.dao shelf.storage shelf.session storage.repository repository.session activity.repository")
                keys("method", "info saveCache upsert signOut repositoryValue")
                HostAccountQueries.INFO_FIELDS.forEach { required += "field:info.$it" }
                record("user", HostAccountQueries.USER_FIELDS)
            }
            "preferences" -> {
                keys("class", "session key store flow preferences continuation margins")
                keys("field", "store token key.name"); keys("constructor", "key")
                keys("method", "data first read update margins")
                HostAccountQueries.PREFERENCES.forEach { required += "field:key.$it" }
                record("margins", HostAccountQueries.MARGINS)
            }
            "advanced" -> {
                keys("class", "user info session repository settings continuation baseContinuation function store key mutable unit suspended")
                keys("field", "info.id user.token completion unit")
                keys("method", "predicate member edit read set remove invoke update storeUpdate info check.repository check.settings")
                keys("constructor", "mutable")
            }
            "ui" -> {
                keys("class", "info viewModel builder content composer scope listScope function")
                keys("field", "builder.info builder.viewModel")
                keys("method", "root content builder item")
            }
            else -> error("Unknown account contract: $group")
        }
        check(values.keys.containsAll(required)) { "Incomplete account.$group: ${required - values.keys}" }
        val s = Contract(values, HostRuntimeBindings.Members(values, loader), loader)
        // Eagerly resolve every published member so a broken warm cache cannot reach a live hook.
        values.keys.forEach { key -> when {
            key.startsWith("class:") -> s.type(key.removePrefix("class:"))
            key.startsWith("method:") -> s.method(key.removePrefix("method:"))
            key.startsWith("field:") -> s.field(key.removePrefix("field:"))
            key.startsWith("constructor:") -> s.constructor(key.removePrefix("constructor:"))
        } }
        val any = Any::class.java; val long = java.lang.Long.TYPE; val int = Integer.TYPE; val bool = java.lang.Boolean.TYPE
        fun field(role: String, owner: String, type: Class<*>) {
            val f = s.field(role); check(!Modifier.isStatic(f.modifiers) && f.declaringClass == s.type(owner) && f.type == type)
        }
        fun signature(role: String, args: List<Class<*>>, result: Class<*>, static: Boolean, owner: String? = null) {
            val m = s.method(role); check(m.parameterTypes.toList() == args && m.returnType == result && Modifier.isStatic(m.modifiers) == static)
            if (owner != null) check(m.declaringClass == s.type(owner))
        }
        fun suspendMethod(role: String, owner: String, args: List<Class<*>>) {
            val m = s.method(role)
            check(!Modifier.isStatic(m.modifiers) && m.declaringClass == s.type(owner) && m.returnType == any &&
                m.parameterCount == args.size + 1 && m.parameterTypes.dropLast(1) == args && s.type("continuation").isAssignableFrom(m.parameterTypes.last()))
        }
        fun recordShape(role: String, names: List<String>) {
            val ctor = s.constructor(role)
            check(ctor.declaringClass == s.type(role) && ctor.parameterCount == names.size)
            check(names.map { s.index("$role.$it") }.toSet() == names.indices.toSet())
            val fields = names.map { s.field("$role.$it") }
            check(fields.toSet() == s.type(role).declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet())
            names.forEach { check(ctor.parameterTypes[s.index("$role.$it")] == s.field("$role.$it").type) }
        }
        when (group) {
            "identity" -> {
                check(android.app.Activity::class.java.isAssignableFrom(s.type("activity")) && s.type("repositoryProperty").isInterface)
                field("activity.repository", "activity", s.type("repositoryProperty"))
                check(Modifier.isFinal(s.field("activity.repository").modifiers))
                signature("repositoryValue", emptyList(), any, false, "repositoryProperty")
                recordShape("user", HostAccountQueries.USER_FIELDS)
                for (name in listOf("id", "updated")) field("user.$name", "user", long)
                for (name in listOf("token", "data")) field("user.$name", "user", String::class.java)
                field("info.id", "info", long); field("info.userType", "info", int)
                for (name in listOf("nickName", "email", "vipExpireTime")) field("info.$name", "info", String::class.java)
                field("info.thirdBinds", "info", List::class.java)
                for ((role, owner, type) in listOf(Triple("shelf.storage", "shelf", "storage"), Triple("shelf.session", "shelf", "session"),
                    Triple("storage.repository", "storage", "repository"), Triple("repository.session", "repository", "session"),
                    Triple("repository.dao", "repository", "dao"), Triple("cachedData.user", "cachedData", "user"))) field(role, owner, s.type(type))
                check(Modifier.isStatic(s.field("cachedUser").modifiers) && s.field("cachedUser").type == s.type("user"))
                check(s.constructor("cachedData").declaringClass == s.type("cachedData") && s.constructor("cachedData").parameterTypes.toList() == listOf(s.type("user")))
                signature("info", emptyList(), s.type("info"), false, "user")
                val save = s.method("saveCache")
                check(Modifier.isStatic(save.modifiers) && save.returnType == Void.TYPE && save.parameterCount == 1 &&
                    save.parameterTypes.single().isAssignableFrom(s.type("cachedData")) && save.declaringClass == s.field("cachedUser").declaringClass)
                suspendMethod("upsert", "dao", listOf(s.type("user"))); suspendMethod("signOut", "repository", emptyList())
                for (role in listOf("repository", "session")) check(s.constructors(role).isNotEmpty() && s.constructors(role).all { it.declaringClass == s.type(role) })
            }
            "preferences" -> {
                field("store", "session", s.type("store")); field("token", "session", String::class.java); field("key.name", "key", String::class.java)
                check(s.constructor("key").declaringClass == s.type("key") && s.constructor("key").parameterTypes.toList() == listOf(String::class.java))
                signature("data", emptyList(), s.type("flow"), false, "store")
                signature("first", listOf(s.type("flow"), s.type("continuation")), any, true)
                signature("read", listOf(s.type("key")), any, false, "preferences")
                suspendMethod("update", "session", listOf(s.type("key"), any))
                HostAccountQueries.PREFERENCES.forEach { name ->
                    check(Modifier.isStatic(s.field("key.$name").modifiers) && s.field("key.$name").type == s.type("key"))
                }
                recordShape("margins", HostAccountQueries.MARGINS)
                HostAccountQueries.MARGINS.forEach { field("margins.$it", "margins", int) }
                suspendMethod("margins", "session", listOf(s.type("margins")))
            }
            "advanced" -> {
                field("info.id", "info", long); field("user.token", "user", String::class.java)
                field("completion", "baseContinuation", s.type("continuation"))
                check(Modifier.isStatic(s.field("unit").modifiers) && s.field("unit").type == s.type("unit"))
                check(s.type("suspended").isEnum && s.type("function").isInterface)
                signature("predicate", listOf(s.type("info")), bool, true)
                signature("member", listOf(s.type("user")), bool, true, "settings")
                signature("info", emptyList(), s.type("info"), false, "user")
                signature("invoke", listOf(any, any), any, false, "function")
                signature("read", listOf(s.type("key")), any, false, "mutable")
                signature("set", listOf(s.type("key"), any), Void.TYPE, false, "mutable")
                signature("remove", listOf(s.type("key")), Void.TYPE, false, "mutable")
                check(s.constructor("mutable").let { it.declaringClass == s.type("mutable") &&
                    it.parameterTypes.toList() == listOf(LinkedHashMap::class.java, bool) })
                suspendMethod("storeUpdate", "store", listOf(s.type("function")))
                val edit = s.method("edit")
                check(Modifier.isStatic(edit.modifiers) && edit.returnType == any && edit.parameterCount == 3 &&
                    edit.parameterTypes.take(2) == listOf(s.type("store"), s.type("function")) && s.type("continuation").isAssignableFrom(edit.parameterTypes.last()))
                suspendMethod("update", "session", listOf(s.type("key"), any))
                suspendMethod("check.repository", "repository", listOf(s.type("info")))
                suspendMethod("check.settings", "settings", listOf(s.type("user")))
                s.methods("setter").forEach { check(it.declaringClass == s.type("session") && !Modifier.isStatic(it.modifiers) && it.returnType == any &&
                    it.parameterCount == 2 && it.parameterTypes.first() == bool && s.type("continuation").isAssignableFrom(it.parameterTypes.last())) }
            }
            "ui" -> {
                signature("root", listOf(s.type("scope"), s.type("composer"), int), Void.TYPE, true)
                signature("content", List(3) { any }, any, false, "content")
                signature("builder", listOf(any), any, false, "builder")
                val item = s.method("item")
                check(item.declaringClass == s.type("listScope") && !Modifier.isStatic(item.modifiers) &&
                    item.returnType == Void.TYPE && item.parameterTypes.take(2) == listOf(any, any) && item.parameterCount == 3)
                check(s.type("function").isInterface && s.type("function").isAssignableFrom(s.type("builder")))
                field("builder.info", "builder", any); field("builder.viewModel", "builder", any)
                check(s.field("builder.info") != s.field("builder.viewModel"))
            }
        }
        return s
    }
}
