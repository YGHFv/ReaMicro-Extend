package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostHomeBindings {
    val QUERY_KEYS = setOf("home.preferences", "home.bookshelf", "home.header", "home.merchant")
    @Volatile private var contracts: Map<String, Contract> = emptyMap()
    @Volatile private var descriptors: Map<String, Map<String, String>> = emptyMap()
    @Volatile var initializationAttempted = false; private set
    val preferences get() = contracts["preferences"]
    val bookshelf get() = contracts["bookshelf"]
    val header get() = contracts["header"]
    val merchant get() = contracts["merchant"]
    fun boundDescriptors() = descriptors.mapValues { it.value.toMap() }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; contracts = emptyMap(); descriptors = emptyMap()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(),
                HostUiBindings.boundDescriptors().orEmpty(), false)
            contracts = values.mapKeys { it.key.substringAfter('.') }.mapValues { (group, result) -> restore(group, result, loader) }
            descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro home query session unavailable", it) }
        bookshelf?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
        preferences?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) = query(session, base, ui, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>, force: Boolean): Map<String, Map<String, String>> {
        var discovery: HostHomeQueries.Discovery? = null
        return session.collect(QUERY_KEYS) { key ->
            val group = key.substringAfter('.')
            session.query(key, force,
                validate = { restore(group, it, session.loader) },
                find = { bridge -> (discovery ?: HostHomeQueries.Discovery(bridge, session.apk, base, ui).also { discovery = it }).query(group) }
            ).values.toMap()
        }
    }

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members,
        private val loader: ClassLoader) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun callers(prefix: String) = values.keys.filter { it.startsWith("method:$prefix.") }.map { method(it.removePrefix("method:")) }
        fun first(flow: Any, timeout: Long = 15_000L): Any? = HostCoroutines.invokeTimed(loader, method("first"), null, timeout, flow)
        private fun key(name: String) = constructor("key").newInstance(name).also { check(field("key.name").get(it) == name) }
        fun read(session: Any, name: String, timeout: Long = 15_000L): Any? {
            val data = requireNotNull(method("data").invoke(field("store").get(session)))
            val prefs = requireNotNull(first(data, timeout)) { "宿主偏好尚未加载" }
            return method("read").invoke(prefs, key(name))
        }
        fun update(session: Any, name: String, value: Any?) = HostCoroutines.invokeTimed(loader, method("update"), session, 15_000L, key(name), value)
        fun session(home: Any): Any = requireNotNull(field("shelf.session").get(field("home.repository").get(home)))
        fun uid(repository: Any): Long? = (field("storage.uid").get(field("shelf.storage").get(repository)) as? Number)?.toLong()?.takeIf { it > 0L }
        fun logicalDescriptors(): Map<String, String> {
            val p = "app.zhendong.reamicro."
            val fields = if ("class:home" in this@Contract.values) mapOf(
                "${p}ui.home.HomeViewModel#repository" to "home.repository",
                "${p}ui.home.HomeViewModel#homePageInitialized" to "home.initialized",
                "${p}ui.home.HomeViewModel#readingBook" to "home.reading",
                "${p}repository.BookshelfRepository#session" to "shelf.session",
                "${p}repository.BookshelfRepository#bookDao" to "shelf.dao",
                "${p}repository.BookshelfRepository#userStorage" to "shelf.storage",
                "${p}arch.fs.UserStorage#uid" to "storage.uid",
            ) else mapOf("${p}repository.core.Session#dataStore" to "store", "${p}repository.core.Session#token" to "token")
            return buildMap {
                fields.forEach { (logical, role) -> put("field:$logical", this@Contract.values.getValue("field:$role")) }
                if ("method:pagerCurrent" in this@Contract.values) put("method:androidx.compose.foundation.pager.PagerState#getCurrentPage", this@Contract.values.getValue("method:pagerCurrent"))
                if ("method:first" in this@Contract.values) {
                    put("class:kotlinx.coroutines.flow.Flow", this@Contract.values.getValue("class:flow"))
                    put("method:kotlinx.coroutines.flow.FlowKt#first", this@Contract.values.getValue("method:first"))
                }
            }
        }
    }

    internal fun restore(group: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val required = linkedSetOf<String>()
        fun keys(prefix: String, names: String) { names.split(' ').filter(String::isNotEmpty).forEach { required += "$prefix:$it" } }
        when (group) {
            "merchant" -> {
                keys("class", "trip composer"); keys("method", "cards row"); keys("field", "endTime")
                keys("value", "trip duration changed defaults")
            }
            "preferences" -> {
                keys("class", "session key store flow preferences continuation")
                keys("constructor", "key"); keys("field", "store token key.name")
                keys("method", "data first read update")
            }
            "bookshelf" -> {
                keys("class", "home shelf session dao storage state book pager composer mutable")
                keys("field", "home.repository home.initialized home.reading shelf.session shelf.dao shelf.storage storage.uid state.groups book.id book.uid book.group book.title")
                keys("method", "screen page resolve current books pagerFactory pagerCurrent resolveCaller.0 stateGet stateSet")
                keys("value", "screen.home page.groups")
            }
            "header" -> {
                keys("class", "composer modifier vector")
                keys("field", "circle selectionIcon")
                keys("method", "header sync opacity size clip dp materialize color then icon skip")
                keys("value", "header.selected header.visible header.available header.composer sync.composer sync.alpha")
            }
            else -> error("Unknown home contract: $group")
        }
        check(values.keys.containsAll(required)) { "Missing home.$group contracts: ${required - values.keys}" }
        val s = Contract(values, HostRuntimeBindings.Members(values, loader), loader)
        fun field(role: String, owner: String, type: Class<*>) {
            val f = s.field(role); check(f.declaringClass == s.type(owner) && f.type == type && !Modifier.isStatic(f.modifiers))
        }
        fun signature(role: String, args: List<Class<*>>, result: Class<*>, static: Boolean) {
            val m = s.method(role); check(m.parameterTypes.toList() == args && m.returnType == result && Modifier.isStatic(m.modifiers) == static)
        }
        val any = Any::class.java; val int = Int::class.javaPrimitiveType!!; val bool = Boolean::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!; val long = Long::class.javaPrimitiveType!!
        when (group) {
            "merchant" -> {
                field("endTime", "trip", long)
                val cards = s.method("cards"); val row = s.method("row")
                check(Modifier.isStatic(cards.modifiers) && cards.returnType == Void.TYPE && cards.parameterTypes[s.index("trip")] == s.type("trip"))
                check(Modifier.isStatic(row.modifiers) && row.returnType == Void.TYPE && row.parameterTypes[s.index("duration")] == String::class.java)
                val composer = row.parameterTypes.indexOf(s.type("composer"))
                check(s.index("duration") in 0 until composer && s.index("duration") < 10 &&
                    s.index("changed") == composer + 1 && s.index("defaults") == composer + 2 &&
                    row.parameterTypes.drop(composer + 1) == listOf(int, int))
            }
            "preferences" -> {
                field("store", "session", s.type("store")); field("token", "session", String::class.java); field("key.name", "key", String::class.java)
                check(s.constructor("key").declaringClass == s.type("key") && s.constructor("key").parameterTypes.toList() == listOf(String::class.java))
                signature("data", emptyList(), s.type("flow"), false)
                signature("first", listOf(s.type("flow"), s.type("continuation")), any, true)
                signature("read", listOf(s.type("key")), any, false)
                signature("update", listOf(s.type("key"), any, s.type("continuation")), any, false)
                check(s.method("data").declaringClass == s.type("store") && s.method("read").declaringClass == s.type("preferences") &&
                    s.method("update").declaringClass == s.type("session"))
            }
            "bookshelf" -> {
                field("home.repository", "home", s.type("shelf")); field("home.initialized", "home", bool)
                for ((role, type) in listOf("session" to "session", "dao" to "dao", "storage" to "storage")) field("shelf.$role", "shelf", s.type(type))
                field("storage.uid", "storage", java.lang.Long::class.java); field("state.groups", "state", List::class.java)
                for (name in listOf("id", "uid")) field("book.$name", "book", long)
                for (name in listOf("group", "title")) field("book.$name", "book", String::class.java)
                signature("resolve", listOf(List::class.java, bool), int, false)
                check(s.method("resolve").declaringClass == s.type("home"))
                check(s.method("screen").parameterTypes[s.index("screen.home")] == s.type("home"))
                check(s.method("page").parameterTypes[s.index("page.groups")] == List::class.java)
                check(Modifier.isStatic(s.method("pagerFactory").modifiers) && s.type("pager").isAssignableFrom(s.method("pagerFactory").returnType))
                signature("pagerCurrent", emptyList(), int, false)
                check(s.method("pagerCurrent").declaringClass == s.type("pager"))
                check(s.method("books").declaringClass == s.type("dao") && s.method("books").parameterTypes.toList() == listOf(long))
                signature("stateGet", emptyList(), any, false); signature("stateSet", listOf(any), Void.TYPE, false)
                check(listOf("stateGet", "stateSet").all { s.method(it).declaringClass == s.type("mutable") })
            }
            "header" -> {
                val modifier = s.type("modifier"); val composer = s.type("composer")
                for (role in listOf("header", "sync")) {
                    check(Modifier.isStatic(s.method(role).modifiers) && s.method(role).returnType == Void.TYPE &&
                        s.method(role).parameterTypes[s.index("$role.composer")] == composer)
                }
                val roles = listOf("selected", "visible", "available").map { s.index("header.$it") }
                check(roles.distinct().size == 3 && roles.all { s.method("header").parameterTypes[it] == bool })
                check(s.method("sync").parameterTypes[s.index("sync.alpha")] == float)
                for (role in listOf("opacity", "size")) signature(role, listOf(modifier, float), modifier, true)
                signature("dp", listOf(int), float, true)
                signature("materialize", listOf(composer, modifier), modifier, true)
                signature("then", listOf(modifier), modifier, false)
                signature("icon", listOf(s.type("vector"), String::class.java, modifier, long, composer, int, int), Void.TYPE, true)
                signature("skip", emptyList(), Void.TYPE, false); check(s.method("skip").declaringClass == composer)
                check(Modifier.isStatic(s.field("circle").modifiers) && s.method("clip").parameterTypes[1].isAssignableFrom(s.field("circle").type))
                check(Modifier.isStatic(s.field("selectionIcon").modifiers) && s.field("selectionIcon").type == s.type("vector"))
            }
        }
        return s
    }
}
