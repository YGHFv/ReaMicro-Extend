package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

/** Each feature owns an immutable contract; rescans only replace the on-disk cache. */
internal object HostStorageBindings {
    val QUERY_KEYS = HostStorageQueries.GROUPS.map { "storage.$it" }.toSet()
    val OPERATIONS = listOf("createFolder", "delete", "listFolders", "getBook", "move", "openFolder", "rename", "search", "setBackupDir")
    @Volatile var initializationAttempted = false; private set
    @Volatile private var contracts: Map<String, Contract> = emptyMap()
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    val records get() = contracts["records"]
    val operations get() = contracts["operations"]
    val data get() = contracts["data"]
    val folder get() = contracts["folder"]
    val tap get() = contracts["tap"]
    val imports get() = contracts["import"]
    val ui get() = contracts["ui"]
    val routes get() = contracts["routes"]
    val account get() = contracts["account"]
    val presentation get() = contracts["presentation"]
    val library get() = contracts["library"]
    val catalog get() = contracts["catalog"]
    fun boundDescriptors(): Map<String, Map<String, String>> = snapshots.mapValues { it.value.toMap() }

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; contracts = emptyMap(); snapshots = emptyMap()
        val bound = linkedMapOf<String, Contract>(); val values = linkedMapOf<String, Map<String, String>>()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val base = HostAutoScanner.queriedDescriptors().orEmpty()
            var discovery: HostStorageQueries.Discovery? = null
            for (group in HostStorageQueries.GROUPS) runCatching {
                var contract: Contract? = null
                val result = session.query("storage.$group",
                    validate = { contract = restore(group, it, loader) },
                    find = { bridge -> (discovery ?: HostStorageQueries.Discovery(bridge, apk, base).also { discovery = it }).query(group) })
                bound[group] = requireNotNull(contract); values["storage.$group"] = result.values.toMap()
                HookRuntime.log("ReaMicro storage.$group ${if (result.cached) "cached" else "resolved"}")
            }.onFailure { HookRuntime.logError("ReaMicro storage.$group unavailable", it) }
        } }.onFailure { HookRuntime.logError("ReaMicro storage query session unavailable", it) }
        contracts = bound.toMap(); snapshots = values.toMap()
    }

    fun rescan(session: HostQuerySession.Session, base: Map<String, String>): Map<String, Map<String, String>> {
        var discovery: HostStorageQueries.Discovery? = null
        return session.collect(HostStorageQueries.GROUPS.map { "storage.$it" }) { key ->
            val group = key.substringAfter('.')
            session.query(key, true,
                validate = { restore(group, it, session.loader) },
                find = { bridge -> (discovery ?: HostStorageQueries.Discovery(bridge, session.apk, base).also { discovery = it }).query(group) }
            ).values.toMap()
        }
    }

    class Contract internal constructor(values: Map<String, String>, loader: ClassLoader) {
        private val values = values.toMap()
        private val members = HostRuntimeBindings.Members(values, loader)
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)
        fun field(key: String) = members.field(key)
        fun value(key: String) = values.getValue("value:$key").toInt()
        fun hasField(key: String) = "field:$key" in values
        fun matchesRoute(key: String, content: Any): Boolean =
            type("$key.content").isInstance(content) && (0 until value("$key.branches")).all { index ->
                val actual = field("$key.branch.$index").get(content)
                val expected = values.getValue("value:$key.branch.$index").toLong()
                when (actual) {
                    is Number -> actual.toLong() == expected
                    is Boolean -> (if (actual) 1L else 0L) == expected
                    is Char -> actual.code.toLong() == expected
                    else -> false
                }
            }
        private val recordFactories = java.util.concurrent.ConcurrentHashMap<String, HostBoundRecord>()
        fun record(key: String, properties: Map<String, Any?>): Any {
            val expected = HostStorageQueries.RECORDS.getValue(key).second.split(' ')
            check(properties.keys == expected.toSet()) { "Incomplete $key record" }
            return recordFactories.getOrPut(key) {
                HostBoundRecord(constructor(key), expected.associateWith { field("$key.$it") },
                    expected.associateWith { value("$key.$it") },
                    byteFromInt = if (key == "book") setOf("type") else emptySet())
            }.create(properties)
        }
        fun token(key: String): Any = requireNotNull(method("$key.token").invoke(
            if (hasField("$key.tokenReceiver")) field("$key.tokenReceiver").get(null) else null, type(key)))
    }

    internal fun restore(group: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val required = linkedSetOf<String>()
        fun keys(prefix: String, names: String) { names.split(' ').filter(String::isNotEmpty).forEach { required += "$prefix:$it" } }
        when (group) {
            "catalog" -> {
                keys("class", "repository bookDao itemDao chapterDao function1 continuation unit database connection statement queryFlow collector function2 opf path item chapter")
                keys("method", "invoke perform prepare bindLong bindText step getLong getText columnCount flow collect emit items chapters opf path")
                keys("field", "repository.book repository.item repository.chapter database.book database.item database.chapter unit")
                keys("value", "perform.database perform.function perform.continuation perform.flag0 perform.flag1 perform.flagValue0 perform.flagValue1")
                for ((role, schema) in HostEpubRuntimeQueries.records.filterKeys { it in setOf("item", "chapter") })
                    schema.second.forEach { keys("field", "$role.$it") }
            }
            "library" -> {
                keys("class", "repository dao book storage continuation flow path")
                keys("method", "first update findId findUuid list books liveUri")
                keys("field", "repository.dao repository.storage storage.uid")
                keys("value", "findUuid.uid findUuid.uuid findUuid.continuation")
            }
            "presentation" -> {
                keys("class", "composer function0 function1 function2 entity cloud vector rowScope auth detail bookScope label")
                keys("method", "android tree extension cloudRow title authorized unauthorized detail duration bookInfo durationFormat text")
                keys("constructor", "detail label")
                keys("field", "cloud.updatedAt cloud.path cloud.size detail.first detail.second detail.tag")
                keys("value", "tree.provider tree.composer detail.tag label.authorized label.unauthorized")
            }
            "records" -> {
                keys("class", "stateFlow failure"); keys("constructor", "stateFlow failure"); keys("method", "setValue")
                keys("field", "book.empty")
                for ((key, record) in HostStorageQueries.RECORDS) {
                    keys("class", key); keys("constructor", key)
                    record.second.split(' ').forEach { keys("field", "$key.$it"); keys("value", "$key.$it") }
                }
            }
            "operations" -> {
                keys("class", "repository book flow continuation"); keys("method", (OPERATIONS + "auth").joinToString(" "))
            }
            "data" -> {
                keys("class", "vm repository flow continuation cachedFlow collector function2 function1 scope composer info")
                keys("constructor", "vm collector"); keys("method", "cache resume collect screen bar update")
                keys("field", "kind repository owner phase"); keys("value", "authPhase infoPhase")
            }
            "folder" -> {
                keys("class", "vm folder args continuation repository worker"); keys("constructor", "vm worker")
                keys("method", "resume listFolders"); keys("field", "args kind path owner selected phase"); keys("value", "phase")
            }
            "tap" -> {
                keys("class", "vm book tap event intent"); keys("constructor", "vm")
                keys("method", "dispatch entry"); keys("field", "kind book")
            }
            "import" -> {
                keys("class", "repository platform continuation worker path opf function1 pair"); keys("constructor", "worker pair")
                keys("method", "resume normal defaults txt txtDefaults epubDirectory"); keys("field", "originalFile pair.first pair.second")
                keys("value", "file url size mask urlBit sizeBit txtUrl txtSize normalFile normalPath normalOpf normalUrl normalSize normalProgress normalContinuation")
            }
            "ui" -> {
                keys("class", "function0 composer scope receiver hide intent resource resourceBase")
                keys("method", "sheet local authorized unauthorized notAuth dismiss string"); keys("field", "hide resourceKey")
            }
            "routes" -> {
                keys("class", "scope controller storage login account folder home composer")
                keys("method", "navigate pop controllerPop"); keys("constructor", "storage login account folder")
                keys("field", "controller storageKind login.kind account.kind folder.kind folder.path folder.bookId home")
                for (key in listOf("login", "account")) {
                    keys("class", "$key.content $key.lambda")
                    keys("method", "$key.setup $key.register $key.token $key.render $key.decode $key.invoke $key.body")
                    keys("field", "$key.scope $key.callback")
                    keys("value", "$key.branches")
                    val branches = values.getValue("value:$key.branches").toInt()
                    check(branches in 0..16)
                    for (index in 0 until branches) {
                        keys("field", "$key.branch.$index")
                        keys("value", "$key.branch.$index")
                    }
                    if ("field:$key.tokenReceiver" in values) keys("field", "$key.tokenReceiver")
                }
            }
            "account" -> {
                keys("class", "flow scope composer function0 function1 dir")
                for (key in listOf("webdav", "local")) {
                    keys("class", "$key.vm $key.auth $key.repository"); keys("constructor", "$key.vm"); keys("field", "$key.auth")
                    keys("class", "$key.content"); keys("field", "$key.contentVm")
                    keys("method", "$key.screen $key.folder $key.logout $key.order $key.direction $key.collect $key.content $key.acquireVm")
                }
            }
            else -> error("Unknown storage contract $group")
        }
        check(values.keys == required) { "Incomplete storage.$group: missing=${required - values.keys}, extra=${values.keys - required}" }
        val m = Contract(values, loader)
        val int = Int::class.javaPrimitiveType!!; val byte = Byte::class.javaPrimitiveType!!
        val any = Any::class.java; val string = String::class.java; val void = Void.TYPE
        fun field(key: String, owner: Class<*>, type: Class<*>, assignable: Boolean = false, static: Boolean = false) {
            val f = m.field(key)
            check(f.declaringClass == owner && Modifier.isStatic(f.modifiers) == static &&
                (if (assignable) f.type.isAssignableFrom(type) else f.type == type)) { "Invalid storage.$group field $key" }
        }
        fun method(key: String, owner: Class<*>? = null, args: List<Class<*>>, returns: Class<*>, static: Boolean = false) {
            val target = m.method(key)
            check((owner == null || target.declaringClass == owner) && target.parameterTypes.toList() == args &&
                target.returnType == returns && Modifier.isStatic(target.modifiers) == static) { "Invalid storage.$group method $key" }
        }
        fun constructor(key: String, owner: Class<*>, args: List<Class<*>>) {
            val ctor = m.constructor(key); check(ctor.declaringClass == owner && ctor.parameterTypes.toList() == args) { "Invalid $key constructor" }
        }
        fun coroutine(key: String, owner: Class<*>) = method(key, owner, listOf(any), any)
        when (group) {
            "catalog" -> {
                val long = java.lang.Long.TYPE; val bool = java.lang.Boolean.TYPE; val continuation = m.type("continuation")
                field("unit", m.type("unit"), m.type("unit"), static = true)
                for ((role, type) in listOf("book" to "bookDao", "item" to "itemDao", "chapter" to "chapterDao")) {
                    field("repository.$role", m.type("repository"), m.type(type))
                    field("database.$role", m.type(type), m.type("database"))
                }
                val perform = m.method("perform")
                check(Modifier.isStatic(perform.modifiers) && perform.returnType == any && perform.parameterCount == 5)
                val slots = listOf("database", "function", "continuation", "flag0", "flag1").map { m.value("perform.$it") }
                check(slots.toSet() == (0..4).toSet() && slots.map { perform.parameterTypes[it] } ==
                    listOf(m.type("database"), m.type("function1"), continuation, bool, bool))
                check((0..1).all { m.value("perform.flagValue$it") in 0..1 })
                check(m.type("function1").isInterface)
                method("invoke", m.type("function1"), listOf(any), any)
                method("prepare", m.type("connection"), listOf(string), m.type("statement"))
                check(AutoCloseable::class.java.isAssignableFrom(m.type("statement")) && AutoCloseable::class.java.isAssignableFrom(m.type("connection")))
                method("bindLong", m.type("statement"), listOf(long, int), void)
                method("bindText", m.type("statement"), listOf(int, string), void)
                method("step", m.type("statement"), emptyList(), bool)
                method("getLong", m.type("statement"), listOf(int), long)
                method("getText", m.type("statement"), listOf(int), string)
                method("columnCount", m.type("statement"), emptyList(), int)
                method("flow", args = listOf(m.type("database"), Array<String>::class.java, m.type("function1")), returns = m.type("queryFlow"), static = true)
                method("collect", m.type("queryFlow"), listOf(m.type("collector"), continuation), any)
                method("emit", m.type("collector"), listOf(any, continuation), any)
                check(m.type("collector").isInterface)
                val items = m.method("items")
                check(Modifier.isStatic(items.modifiers) && items.parameterTypes.toList() == listOf(long, m.type("opf"), m.type("path")) &&
                    List::class.java.isAssignableFrom(items.returnType))
                method("chapters", args = listOf(List::class.java, m.type("opf"), m.type("path"), m.type("function2")), returns = List::class.java, static = true)
                method("opf", args = listOf(m.type("path")), returns = m.type("opf"), static = true)
                method("path", args = listOf(string, bool), returns = m.type("path"), static = true)
                for ((role, schema) in HostEpubRuntimeQueries.records.filterKeys { it in setOf("item", "chapter") }) {
                    for (property in schema.second) {
                        val expected = when (property) {
                            "id", "bookId", "size" -> long
                            "index", "spineIndex", "playOrder", "parentOrder" -> int
                            else -> string
                        }
                        field("$role.$property", m.type(role), expected)
                    }
                    check(schema.second.map { m.field("$role.$it") }.distinct().size == schema.second.size)
                }
            }
            "library" -> {
                val long = java.lang.Long.TYPE; val continuation = m.type("continuation")
                field("repository.dao", m.type("repository"), m.type("dao"))
                field("repository.storage", m.type("repository"), m.type("storage"))
                field("storage.uid", m.type("storage"), java.lang.Long::class.java)
                method("first", args = listOf(m.type("flow"), continuation), returns = any, static = true)
                method("update", m.type("repository"), listOf(m.type("book"), continuation), any)
                method("findId", m.type("dao"), listOf(long, continuation), any)
                val find = m.method("findUuid")
                check(!Modifier.isStatic(find.modifiers) && find.declaringClass == m.type("dao") && find.returnType == any && find.parameterCount == 3)
                val slots = listOf("uid", "uuid", "continuation").map { m.value("findUuid.$it") }
                check(slots.toSet() == setOf(0, 1, 2) && find.parameterTypes[slots[0]] == long && find.parameterTypes[slots[1]] == string &&
                    continuation.isAssignableFrom(find.parameterTypes[slots[2]]))
                for ((role, owner, args) in listOf(Triple("list", m.type("dao"), listOf(long)),
                    Triple("liveUri", m.type("repository"), listOf(string)))) {
                    val target = m.method(role)
                    check(!Modifier.isStatic(target.modifiers) && target.declaringClass == owner && target.parameterTypes.toList() == args &&
                        m.type("flow").isAssignableFrom(target.returnType))
                }
                method("books", m.type("storage"), listOf(java.lang.Long::class.java), m.type("path"))
            }
            "presentation" -> {
                val composer = m.type("composer")
                method("android", args = emptyList(), returns = m.type("vector"), static = true)
                val tree = m.method("tree")
                check(Modifier.isStatic(tree.modifiers) && tree.returnType == void && tree.parameterCount == 5 &&
                    tree.parameterTypes.toList().groupingBy { it }.eachCount() ==
                    listOf(int, int, composer, List::class.java, m.type("function1")).groupingBy { it }.eachCount())
                check(tree.parameterTypes[m.value("tree.provider")] == int && tree.parameterTypes[m.value("tree.composer")] == composer)
                for (role in listOf("title", "extension")) method(role, m.type("cloud"), emptyList(), string)
                method("cloudRow", args = listOf(m.type("rowScope"), m.type("cloud"), m.type("function2"), composer, int), returns = void, static = true)
                for (role in listOf("updatedAt", "size")) field("cloud.$role", m.type("cloud"), java.lang.Long.TYPE)
                field("cloud.path", m.type("cloud"), string)
                method("authorized", args = listOf(int, m.type("auth"), m.type("function0"), composer, int), returns = void, static = true)
                method("unauthorized", args = listOf(int, m.type("function0"), composer, int), returns = void, static = true)
                constructor("label", m.type("label"), listOf(string, byte))
                check(m.type("function2").isAssignableFrom(m.type("label")))
                for (role in listOf("authorized", "unauthorized")) check(m.value("label.$role") in Byte.MIN_VALUE..Byte.MAX_VALUE)
                constructor("detail", m.type("detail"), listOf(string, string))
                method("detail", m.type("detail"), listOf(any, any), any)
                for (role in listOf("first", "second")) field("detail.$role", m.type("detail"), string)
                field("detail.tag", m.type("detail"), byte)
                check(m.type("detail").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet() ==
                    listOf("first", "second", "tag").map { m.field("detail.$it") }.toSet())
                method("duration", args = listOf(string, java.lang.Long.TYPE, java.lang.Long.TYPE, composer, int), returns = void, static = true)
                method("bookInfo", args = listOf(m.type("bookScope"), m.type("entity"), composer, int), returns = void, static = true)
                method("durationFormat", args = listOf(java.lang.Long.TYPE), returns = string, static = true)
                check(m.method("text").let { Modifier.isStatic(it.modifiers) && it.returnType == void && it.parameterCount == 20 &&
                    it.parameterTypes.first() == string && it.parameterTypes.takeLast(4) == listOf(composer, int, int, int) })
            }
            "records" -> {
                field("book.empty", m.type("book"), m.type("book"), static = true)
                constructor("stateFlow", m.type("stateFlow"), listOf(any))
                method("setValue", m.type("stateFlow"), listOf(any), void)
                constructor("failure", m.type("failure"), listOf(Throwable::class.java))
                for ((key, record) in HostStorageQueries.RECORDS) {
                    val ctor = m.constructor(key); val owner = m.type(key); val properties = record.second.split(' ')
                    check(ctor.declaringClass == owner && ctor.parameterCount == properties.size)
                    check(properties.map { m.value("$key.$it") }.toSet() == properties.indices.toSet())
                    for (property in properties) {
                        val f = m.field("$key.$property"); val parameter = ctor.parameterTypes[m.value("$key.$property")]
                        check(f.declaringClass == owner && !Modifier.isStatic(f.modifiers) &&
                            (f.type == parameter || key == "book" && property == "type" && f.type == byte && parameter == int))
                    }
                }
                field("state.userInfo", m.type("state"), m.type("info")); field("state.treeList", m.type("state"), List::class.java)
                for (key in listOf("auth", "localAuth")) for (property in listOf("token", "orderBy", "orderDirection", "nickname"))
                    field("$key.$property", m.type(key), string)
                for (property in listOf("libraryDir", "backupDir")) field("auth.$property", m.type("auth"), string)
                field("localAuth.libraryDir", m.type("localAuth"), List::class.java)
                field("localAuth.backupDir", m.type("localAuth"), m.type("dir"))
                for (property in listOf("id", "name")) field("dir.$property", m.type("dir"), string)
                for (property in listOf("id", "name", "avatar", "driveId", "identity", "level")) field("info.$property", m.type("info"), string)
                for (property in listOf("id", "name", "url", "path", "hash")) field("book.$property", m.type("book"), string)
                for (property in listOf("size", "updatedAt")) field("book.$property", m.type("book"), Long::class.javaPrimitiveType!!)
                check(m.field("book.type").type in listOf(byte, int))
                for (key in listOf("auth", "localAuth", "info")) for (property in listOf("usedSize", "totalSize"))
                    field("$key.$property", m.type(key), Long::class.javaPrimitiveType!!)
                field("folder.name", m.type("folder"), string); field("folder.path", m.type("folder"), string)
                field("folder.empty", m.type("folder"), Boolean::class.javaPrimitiveType!!)
                field("folder.updatedAt", m.type("folder"), Long::class.javaPrimitiveType!!)
                check(m.field("book.local").type == m.field("book.workers").type && m.field("book.local").type.isInterface)
                check(m.field("book.local").type.isAssignableFrom(m.type("stateFlow")))
            }
            "operations" -> {
                val repository = m.type("repository"); val continuation = m.type("continuation")
                check(continuation.isInterface && m.type("flow").isInterface)
                method("auth", repository, listOf(int), m.type("flow"))
                check(OPERATIONS.map(m::method).distinct().size == OPERATIONS.size)
                for (name in OPERATIONS) {
                    val target = m.method(name); val types = target.parameterTypes.toList()
                    check(target.declaringClass == repository && !Modifier.isStatic(target.modifiers) && target.returnType == any)
                    when (name) {
                        "createFolder", "move", "openFolder" -> check(types.size == 4 && types[0] == int &&
                            continuation.isAssignableFrom(types[1]) && types.drop(2) == listOf(string, string))
                        "rename" -> check(types.size == 4 && types.take(3) == listOf(int, m.type("book"), string) && continuation.isAssignableFrom(types[3]))
                        "setBackupDir" -> check(types.size == 3 && types.take(2) == listOf(int, List::class.java) && continuation.isAssignableFrom(types[2]))
                        else -> check(types.size == 3 && types.take(2) == listOf(int, string) && continuation.isAssignableFrom(types[2]))
                    }
                }
            }
            "data" -> {
                val vm = m.type("vm"); val worker = m.type("collector"); val flow = m.type("flow")
                method("screen", args = listOf(m.type("scope"), int, m.type("composer"), int), returns = void, static = true)
                method("bar", m.method("screen").declaringClass, listOf(m.type("scope"), int, m.type("info"), m.type("composer"), int), void, static = true)
                method("update", args = listOf(m.type("function1")), returns = void)
                val update = m.method("update")
                check(update.declaringClass.isAssignableFrom(vm) && m.type("function1").isInterface)
                val ctor = m.constructor("vm"); check(ctor.declaringClass == vm && ctor.parameterCount == 3 &&
                    ctor.parameterTypes[0] == int && ctor.parameterTypes[2] == m.type("repository"))
                field("kind", vm, int); field("repository", vm, m.type("repository"))
                constructor("collector", worker, listOf(vm, m.type("continuation"), byte))
                field("owner", worker, vm); field("phase", worker, byte); coroutine("resume", worker)
                check(m.value("authPhase") != m.value("infoPhase") && listOf(m.value("authPhase"), m.value("infoPhase")).all { it in Byte.MIN_VALUE..Byte.MAX_VALUE })
                val cache = m.method("cache"); check(Modifier.isStatic(cache.modifiers) && cache.parameterCount == 2 &&
                    cache.parameterTypes[0] == flow && cache.returnType == m.type("cachedFlow") && flow.isAssignableFrom(cache.returnType))
                check(m.type("function2").isInterface)
                method("collect", args = listOf(flow, m.type("function2"), m.type("continuation")), returns = any, static = true)
            }
            "folder" -> {
                val vm = m.type("vm"); val worker = m.type("worker"); val args = m.type("args")
                constructor("vm", vm, listOf(args, m.type("repository")))
                field("args", vm, args); field("kind", args, int); field("path", m.type("folder"), string)
                field("owner", worker, vm, true); field("selected", worker, m.type("folder"), true); field("phase", worker, byte)
                check(m.value("phase") in Byte.MIN_VALUE..Byte.MAX_VALUE)
                check(m.constructor("worker").declaringClass == worker && m.constructor("worker").parameterTypes.count { it == byte } == 1)
                coroutine("resume", worker)
                val list = m.method("listFolders"); check(!Modifier.isStatic(list.modifiers) && list.declaringClass == m.type("repository") && list.returnType == any &&
                    list.parameterCount == 3 && list.parameterTypes.take(2) == listOf(int, string) &&
                    m.type("continuation").isAssignableFrom(list.parameterTypes[2]))
            }
            "tap" -> {
                val vm = m.type("vm"); field("kind", vm, int); field("book", m.type("tap"), m.type("book"))
                check(m.type("tap").superclass == m.type("event") && m.type("intent").isAssignableFrom(m.type("event")))
                check(m.constructor("vm").declaringClass == vm && m.constructor("vm").parameterTypes.first() == int)
                method("dispatch", vm, listOf(m.type("event")), void); method("entry", vm, listOf(m.type("intent")), void)
            }
            "import" -> {
                val repository = m.type("repository"); val platform = m.type("platform"); val worker = m.type("worker")
                val pair = m.type("pair")
                check(m.constructor("pair").let { it.declaringClass == pair && it.parameterTypes.toList() == listOf(any, any) })
                field("pair.first", pair, any); field("pair.second", pair, any)
                check(m.field("pair.first") != m.field("pair.second"))
                check(m.method("epubDirectory").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 3 &&
                    it.parameterTypes.take(2) == listOf(m.type("path"), m.type("path")) && !it.parameterTypes[2].isPrimitive && it.returnType == pair })
                field("originalFile", worker, platform); coroutine("resume", worker)
                check(m.constructor("worker").declaringClass == worker && m.constructor("worker").parameterTypes.first() == platform)
                val normal = m.method("normal"); val defaults = m.method("defaults"); val txt = m.method("txt"); val txtDefaults = m.method("txtDefaults")
                for (target in listOf(normal, defaults, txt, txtDefaults)) check(target.declaringClass == repository && target.returnType == java.io.Serializable::class.java)
                check(!Modifier.isStatic(normal.modifiers) && !Modifier.isStatic(txt.modifiers) && Modifier.isStatic(defaults.modifiers) && Modifier.isStatic(txtDefaults.modifiers))
                check(normal.parameterCount == 7 && normal.parameterTypes[0] == platform && normal.parameterTypes.slice(3..4) == listOf(string, java.lang.Long::class.java))
                val arguments = mapOf("normalFile" to platform, "normalPath" to m.type("path"), "normalOpf" to m.type("opf"),
                    "normalUrl" to string, "normalSize" to java.lang.Long::class.java, "normalProgress" to m.type("function1"),
                    "normalContinuation" to m.type("continuation"))
                check(arguments.keys.map(m::value).toSet() == normal.parameterTypes.indices.toSet())
                arguments.forEach { (role, type) -> check(if (role == "normalContinuation")
                    type.isAssignableFrom(normal.parameterTypes[m.value(role)]) else normal.parameterTypes[m.value(role)] == type) }
                check(m.value("normalContinuation") == normal.parameterCount - 1 && m.type("function1").isInterface)
                check(m.type("continuation").isAssignableFrom(normal.parameterTypes.last()) &&
                    m.type("continuation").isAssignableFrom(txt.parameterTypes.last()))
                check(defaults.parameterCount == 9 && defaults.parameterTypes.take(4) == listOf(repository, platform, normal.parameterTypes[1], normal.parameterTypes[2]))
                check(listOf("file", "url", "size", "mask").map(m::value) == listOf(1, 4, 5, 8))
                check(m.value("urlBit") == (1 shl 3) && m.value("sizeBit") == (1 shl 4))
                check(defaults.parameterTypes[4] == string && defaults.parameterTypes[5] == java.lang.Long::class.java && defaults.parameterTypes[8] == int &&
                    normal.parameterTypes[5].isAssignableFrom(defaults.parameterTypes[6]) &&
                    m.type("continuation").isAssignableFrom(defaults.parameterTypes[7]))
                check(txt.parameterCount == 5 && txt.parameterTypes.slice(2..3) == listOf(string, java.lang.Long::class.java) &&
                    txt.parameterTypes[1] == normal.parameterTypes[1])
                check(m.value("txtUrl") == 2 && m.value("txtSize") == 3)
                check(txtDefaults.parameterCount == 4 && txtDefaults.parameterTypes.toList() ==
                    listOf(repository, txt.parameterTypes[0], txt.parameterTypes[1], worker) &&
                    m.type("continuation").isAssignableFrom(worker))
            }
            "ui" -> {
                val function = m.type("function0"); val composer = m.type("composer"); val receiver = m.type("receiver")
                check(m.type("resourceBase").isAssignableFrom(m.type("resource")))
                field("resourceKey", m.type("resourceBase"), string)
                method("string", args = listOf(m.type("resource"), composer), returns = string, static = true)
                check(function.isInterface && m.type("intent").isAssignableFrom(m.type("hide")))
                field("hide", m.type("hide"), m.type("hide"), static = true)
                method("sheet", args = listOf(m.type("scope"), receiver, Boolean::class.javaPrimitiveType!!, composer, int), returns = void, static = true)
                method("local", args = listOf(function, composer, int), returns = void, static = true)
                method("unauthorized", args = listOf(int, function, composer, int), returns = void, static = true)
                method("notAuth", args = listOf(int, function, composer, int), returns = void, static = true)
                check(m.method("notAuth") != m.method("unauthorized"))
                val authorized = m.method("authorized"); check(Modifier.isStatic(authorized.modifiers) && authorized.returnType == void &&
                    authorized.parameterCount == 5 && authorized.parameterTypes[0] == int && authorized.parameterTypes.drop(2) == listOf(function, composer, int))
                method("dismiss", receiver, listOf(m.type("intent")), void)
            }
            "routes" -> {
                val scope = m.type("scope"); val controller = m.type("controller")
                field("home", m.type("home"), m.type("home"), static = true)
                field("controller", scope, controller); method("navigate", scope, listOf(scope, any), void, true)
                method("pop", scope, emptyList(), void); method("controllerPop", controller, emptyList(), Boolean::class.javaPrimitiveType!!)
                constructor("storage", m.type("storage"), listOf(int)); field("storageKind", m.type("storage"), int)
                for (key in listOf("login", "account")) {
                    constructor(key, m.type(key), listOf(int)); field("$key.kind", m.type(key), int)
                    field("$key.scope", m.type("$key.content"), scope)
                    field("$key.callback", m.type("$key.lambda"), any)
                    method("$key.render", m.type("$key.content"), List(4) { any }, any)
                    for (index in 0 until m.value("$key.branches")) {
                        val branch = m.field("$key.branch.$index")
                        check(branch.declaringClass == m.type("$key.content") &&
                            Modifier.isFinal(branch.modifiers) && !Modifier.isStatic(branch.modifiers) &&
                            branch.type in setOf(byte, java.lang.Short.TYPE, int, java.lang.Long.TYPE,
                                java.lang.Boolean.TYPE, java.lang.Character.TYPE))
                        values.getValue("value:$key.branch.$index").toLong()
                    }
                    method("$key.invoke", m.type("$key.lambda"), List(4) { any }, any)
                    method("$key.body", m.type("$key.lambda"), listOf(any, any, m.type("composer"), int), any)
                    val token = m.method("$key.token"); check(token.parameterTypes.toList() == listOf(Class::class.java) && token.returnType != void)
                    check(Modifier.isStatic(token.modifiers) != m.hasField("$key.tokenReceiver"))
                    if (m.hasField("$key.tokenReceiver")) {
                        val receiver = m.field("$key.tokenReceiver"); check(Modifier.isStatic(receiver.modifiers) && token.declaringClass.isAssignableFrom(receiver.type))
                    }
                    val register = m.method("$key.register"); val decode = m.method("$key.decode")
                    check(Modifier.isStatic(register.modifiers) && register.returnType == void && register.parameterCount == 3 &&
                        register.parameterTypes[1] == token.returnType && register.parameterTypes[2].isAssignableFrom(m.type("$key.lambda")))
                    check(Modifier.isStatic(decode.modifiers) && decode.returnType == any && decode.parameterCount == 2 && decode.parameterTypes[1] == token.returnType)
                    check(!Modifier.isStatic(m.method("$key.setup").modifiers))
                }
                if (m.type("login.content") == m.type("account.content")) {
                    check((0 until m.value("login.branches")).any { login ->
                        (0 until m.value("account.branches")).any { account ->
                            m.field("login.branch.$login") == m.field("account.branch.$account") &&
                                values.getValue("value:login.branch.$login") != values.getValue("value:account.branch.$account")
                        }
                    }) { "Merged storage route callbacks cannot be distinguished" }
                }
                constructor("folder", m.type("folder"), listOf(int, string, java.lang.Long::class.java, int))
                field("folder.kind", m.type("folder"), int); field("folder.path", m.type("folder"), string)
                field("folder.bookId", m.type("folder"), java.lang.Long::class.java)
            }
            "account" -> {
                val composer = m.type("composer"); val flow = m.type("flow")
                check(flow.isInterface && m.type("function0").isInterface && m.type("function1").isInterface)
                for (key in listOf("webdav", "local")) {
                    method("$key.screen", args = listOf(m.type("scope"), composer, int), returns = void, static = true)
                    method("$key.folder", args = listOf(if (key == "webdav") string else m.type("dir"), m.type("function0"), composer, int), returns = void, static = true)
                    method("$key.logout", args = listOf(m.type("function0"), composer, int), returns = void, static = true)
                    for (name in listOf("order", "direction"))
                        method("$key.$name", args = listOf(string, m.type("function1"), composer, int), returns = void, static = true)
                    constructor("$key.vm", m.type("$key.vm"), listOf(m.type("$key.repository")))
                    val auth = m.field("$key.auth"); check(auth.declaringClass == m.type("$key.vm") &&
                        !Modifier.isStatic(auth.modifiers) && flow.isAssignableFrom(auth.type))
                    val collect = m.method("$key.collect"); check(Modifier.isStatic(collect.modifiers) &&
                        collect.parameterTypes.toList() == listOf(flow, any, composer, int, int) && collect.returnType.isInterface)
                    method("$key.content", m.type("$key.content"), List(3) { any }, any)
                    field("$key.contentVm", m.type("$key.content"), any)
                    check(m.method("$key.acquireVm").returnType.isAssignableFrom(m.type("$key.vm")))
                }
            }
        }
        return m
    }
}
