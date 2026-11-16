package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

/** Installed contracts stay immutable. A rescan validates and writes only the next process's cache. */
internal object HostReaderExtrasBindings {
    val QUERY_KEYS = HostReaderExtrasQueries.GROUPS.map { "reader.$it" }.toSet()
    @Volatile var initializationAttempted = false; private set
    @Volatile private var contracts: Map<String, Contract> = emptyMap()
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    val autoPage get() = contracts["autoPage"]
    val fontFamilies get() = contracts["fontFamilies"]
    val fontCompletion get() = contracts["fontCompletion"]
    val importOverwrite get() = contracts["importOverwrite"]
    val importMetadata get() = contracts["importMetadata"]
    val virtualPageGuard get() = contracts["virtualPageGuard"]
    fun boundDescriptors() = snapshots.mapValues { it.value.toMap() }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; contracts = emptyMap(); snapshots = emptyMap()
        val bound = linkedMapOf<String, Contract>(); val snapshots = linkedMapOf<String, Map<String, String>>()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val base = HostAutoScanner.queriedDescriptors().orEmpty()
            var discovery: HostReaderExtrasQueries.Discovery? = null
            for (group in HostReaderExtrasQueries.GROUPS) runCatching {
                var contract: Contract? = null
                val result = session.query("reader.$group",
                    validate = { contract = restore(group, it, loader) },
                    find = { bridge -> (discovery ?: HostReaderExtrasQueries.Discovery(bridge, apk, base).also { discovery = it }).query(group) })
                bound[group] = requireNotNull(contract); snapshots["reader.$group"] = result.values.toMap()
                HookRuntime.log("ReaMicro reader.$group ${if (result.cached) "cached" else "resolved"}")
            }.onFailure { HookRuntime.logError("ReaMicro reader.$group unavailable", it) }
        } }.onFailure { HookRuntime.logError("ReaMicro reader extras session unavailable", it) }
        contracts = bound.toMap(); this.snapshots = snapshots.toMap()
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>): Map<String, Map<String, String>> {
        var discovery: HostReaderExtrasQueries.Discovery? = null
        return session.collect(HostReaderExtrasQueries.GROUPS.map { "reader.$it" }) { key ->
            val group = key.substringAfter('.')
            session.query(key, true,
                validate = { restore(group, it, session.loader) },
                find = { bridge -> (discovery ?: HostReaderExtrasQueries.Discovery(bridge, session.apk, base).also { discovery = it }).query(group) }).values.toMap()
        }
    }
    class Contract internal constructor(values: Map<String, String>, loader: ClassLoader) {
        val values: Map<String, String> = values.toMap()
        private val members = HostRuntimeBindings.Members(values, loader)
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)
        fun field(key: String) = members.field(key)
        fun index(key: String) = values.getValue("value:$key").toInt()
        fun methods(prefix: String) = values.keys.filter { it.startsWith("method:$prefix") }.map { method(it.removePrefix("method:")) }
        fun constructors(prefix: String) = values.keys.filter { it.startsWith("constructor:$prefix") }.map { constructor(it.removePrefix("constructor:")) }
        fun read(key: String, receiver: Any?): Any? = receiver?.let { field(key).get(it) }

    }
    internal fun restore(group: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val required = linkedSetOf<String>()
        fun keys(prefix: String, names: String) { names.split(' ').filter(String::isNotEmpty).forEach { required += "$prefix:$it" } }
        fun dynamic(prefix: String, minimum: Int = 0) {
            val matching = values.keys.filter { it.startsWith(prefix) }
            check(matching.size >= minimum && matching.map { it.removePrefix(prefix).toInt() }.sorted() == matching.indices.toList()) { "Invalid $prefix members" }
            required += matching
        }
        when (group) {
            "autoPage" -> {
                keys("class", "vm tap close mutableState"); keys("method", "dispatch send clear close play pause stateGet stateSet"); keys("constructor", "tap")
                dynamic("constructor:vm.", 1)
            }
            "fontFamilies" -> {
                keys("class", "provider family weight list"); keys("method", "fromPath song"); keys("constructor", "weight list"); keys("field", "system")
            }
            "fontCompletion" -> {
                keys("class", "provider family name pair composer"); keys("method", "withName fontKey row"); keys("constructor", "pair")
                keys("field", "name embedded builtIn attached first second"); keys("value", "row.pair row.composer row.flags row.mask")
                dynamic("method:rowCaller.", 1)
            }
            "virtualPageGuard" -> {
                keys("class", "vm epub page continuation loadContinuation unit"); keys("method", "window mapped load")
                keys("field", "pages spines unit"); keys("value", "window.epub window.continuation window.page mapped.epub mapped.continuation mapped.page")
                dynamic("method:caller.", 1)
            }
            "importMetadata" -> {
                keys("class", "repository dao book opf path continuation metadata identifier title creator publisher meta")
                keys("method", "update find cover")
                keys("field", "repository.dao opf.metadata metadata.title metadata.subtitle metadata.uuid metadata.authors metadata.publisher metadata.metas identifier.value title.value creator.name publisher.name meta.property meta.id")
                keys("field", "book.id book.uid book.uuid book.title book.subtitle book.author book.cover book.publisher book.size book.cloudId book.updated")
            }
            "importOverwrite" -> {
                keys("class", "repository dao book worker opf metadata identifier title meta path platform user continuation flow function1 pair importCallback importContinuation importWorker txtConverter txtContinuation")
                keys("method", "import first list find exists delete obtain overwrite epubImport path parent resolve daoUpdate daoInsert daoDelete bookCopy importWorkerResume txt")
                keys("constructor", "importContinuation pair importWorker txtConverter txtContinuation")
                keys("field", "worker.repository repository.dao repository.userFlow user.id book.id book.uuid book.uid book.title book.uri book.size book.created book.cloudId book.publisher book.backupType book.backupId book.backupCode opf.metadata metadata.title metadata.uuid metadata.metas identifier.value title.value meta.property meta.value pair.first pair.second importWorker.manager txtConverter.directory")
                keys("value", "import.source import.directory import.opf import.uri import.size import.progress import.continuation first.continuation find.uid find.uuid find.continuation exists.value exists.continuation delete.value delete.continuation epubImport.source epubImport.directory epubImport.callback txt.book txt.directory txt.uri txt.size txt.continuation")
                dynamic("constructor:repository.", 1); dynamic("constructor:worker.", 1)
            }
            else -> error("Unknown reader extras contract: $group")
        }
        check(values.keys == required) { "Incomplete reader.$group: missing=${required - values.keys}; extra=${values.keys - required}" }
        val s = Contract(values, loader)
        val int = Integer.TYPE; val long = java.lang.Long.TYPE; val bool = java.lang.Boolean.TYPE; val any = Any::class.java
        fun method(key: String, owner: Class<*>? = null, args: List<Class<*>>, result: Class<*>, static: Boolean = false) {
            val m = s.method(key)
            check((owner == null || owner == m.declaringClass) && m.parameterTypes.toList() == args && m.returnType == result && Modifier.isStatic(m.modifiers) == static) { "Invalid reader.$group method $key" }
        }
        fun field(key: String, owner: Class<*>, type: Class<*>, static: Boolean = false, subtype: Boolean = false) {
            val f = s.field(key)
            check(f.declaringClass == owner && (if (subtype) type.isAssignableFrom(f.type) else f.type == type) && Modifier.isStatic(f.modifiers) == static) { "Invalid reader.$group field $key" }
        }
        fun constructor(key: String, owner: Class<*>, args: List<Class<*>>) {
            val c = s.constructor(key); check(c.declaringClass == owner && c.parameterTypes.toList() == args)
        }
        fun slot(key: String, name: String, type: Class<*>, subtype: Boolean = false) {
            val m = s.method(key); val index = s.index("$key.$name")
            check(index in m.parameterTypes.indices && (if (subtype) type.isAssignableFrom(m.parameterTypes[index]) else m.parameterTypes[index] == type))
        }
        when (group) {
            "autoPage" -> {
                val vm = s.type("vm"); val dispatch = s.method("dispatch")
                check(dispatch.declaringClass == vm && dispatch.parameterCount == 1 && dispatch.returnType == Void.TYPE && !Modifier.isStatic(dispatch.modifiers))
                check(dispatch.parameterTypes.single().isAssignableFrom(s.type("tap")))
                val send = s.method("send"); check(send.declaringClass.isAssignableFrom(vm) && send.parameterTypes.toList() == dispatch.parameterTypes.toList() && send.returnType == Void.TYPE && !Modifier.isStatic(send.modifiers))
                val clear = s.method("clear"); check(clear.declaringClass.isAssignableFrom(vm) && clear.parameterCount == 0 && clear.returnType == Void.TYPE && !Modifier.isStatic(clear.modifiers))
                constructor("tap", s.type("tap"), listOf(long)); method("close", s.type("close"), emptyList(), any)
                check(s.type("close").isInterface)
                check(s.constructors("vm.").toSet() == vm.declaredConstructors.toSet())
                for (key in listOf("play", "pause")) { val icon = s.method(key); check(icon.parameterCount == 0 && Modifier.isStatic(icon.modifiers) && !icon.returnType.isPrimitive) }
                check(s.method("play").returnType == s.method("pause").returnType)
                method("stateGet", s.type("mutableState"), emptyList(), any); method("stateSet", s.type("mutableState"), listOf(any), Void.TYPE)
            }
            "fontFamilies" -> {
                val family = s.type("family"); val list = s.type("list")
                check(family.isAssignableFrom(list)); constructor("weight", s.type("weight"), listOf(int)); constructor("list", list, listOf(List::class.java))
                val from = s.method("fromPath")
                check(from.declaringClass == s.type("provider") && Modifier.isStatic(from.modifiers) && from.parameterTypes.toList() == listOf(String::class.java, s.type("weight")) && !from.returnType.isPrimitive)
                field("system", family, family, true, true); method("song", s.type("provider"), emptyList(), family, true)
            }
            "fontCompletion" -> {
                val provider = s.type("provider"); val pair = s.type("pair")
                val names = java.lang.reflect.Array.newInstance(s.type("name"), 0).javaClass
                method("withName", provider, listOf(names), s.type("family"), true)
                method("fontKey", provider, listOf(String::class.java), String::class.java, true)
                field("name", s.type("name"), String::class.java)
                for (key in listOf("embedded", "builtIn")) field(key, provider, bool, true)
                check(s.field("embedded") != s.field("builtIn")); field("attached", provider, Map::class.java, true, true)
                constructor("pair", pair, listOf(any, any)); field("first", pair, any); field("second", pair, any)
                check(s.field("first") != s.field("second"))
                val row = s.method("row"); check(Modifier.isStatic(row.modifiers) && row.returnType == Void.TYPE && row.parameterCount == 4)
                slot("row", "pair", pair); slot("row", "composer", s.type("composer")); slot("row", "flags", int)
                check(setOf(s.index("row.pair"), s.index("row.composer"), s.index("row.flags")).size == 3 && s.index("row.mask") == (7 shl (s.index("row.pair") * 3 + 1)))
            }
            "virtualPageGuard" -> {
                val vm = s.type("vm"); val continuation = s.type("continuation")
                check(continuation.isAssignableFrom(s.type("loadContinuation")))
                field("pages", vm, Map::class.java, subtype = true); field("spines", vm, Map::class.java, subtype = true)
                check(s.field("pages") != s.field("spines")); field("unit", s.type("unit"), s.type("unit"), true)
                for (key in listOf("window", "mapped")) {
                    val m = s.method(key); check(m.declaringClass == vm && !Modifier.isStatic(m.modifiers) && m.returnType == any && m.parameterCount == (if (key == "window") 4 else 3))
                    slot(key, "epub", s.type("epub")); slot(key, "continuation", continuation, true); slot(key, "page", int)
                    check(setOf(s.index("$key.epub"), s.index("$key.continuation"), s.index("$key.page")).size == 3)
                }
                val load = s.method("load"); check(load.declaringClass == vm &&
                    (load.parameterCount == 2 || load.parameterTypes.take(3) == listOf(int, int, s.type("page"))) &&
                    load.parameterTypes.first() == int && continuation.isAssignableFrom(load.parameterTypes.last()) &&
                    load.returnType == any && !Modifier.isStatic(load.modifiers))
            }
            "importMetadata" -> {
                val book = s.type("book"); val repository = s.type("repository"); val dao = s.type("dao"); val metadata = s.type("metadata")
                field("repository.dao", repository, dao); field("opf.metadata", s.type("opf"), metadata)
                for (name in listOf("title", "subtitle")) field("metadata.$name", metadata, s.type("title"))
                field("metadata.uuid", metadata, s.type("identifier")); field("metadata.publisher", metadata, s.type("publisher"))
                for (name in listOf("authors", "metas")) field("metadata.$name", metadata, List::class.java, subtype = true)
                for ((owner, name) in listOf("identifier" to "value", "title" to "value", "creator" to "name", "publisher" to "name", "meta" to "property", "meta" to "id")) field("$owner.$name", s.type(owner), String::class.java)
                for (name in listOf("id", "uid", "size", "cloudId", "updated")) field("book.$name", book, long)
                for (name in listOf("uuid", "title", "subtitle", "author", "cover", "publisher")) field("book.$name", book, String::class.java)
                for ((key, owner, first) in listOf(Triple("update", repository, book), Triple("find", dao, long))) {
                    val m = s.method(key)
                    check(!Modifier.isStatic(m.modifiers) && m.declaringClass == owner && m.returnType == any && m.parameterCount == 2 && m.parameterTypes.first() == first && s.type("continuation").isAssignableFrom(m.parameterTypes.last()))
                }
                method("cover", s.type("opf"), listOf(s.type("path")), String::class.java)
            }
            "importOverwrite" -> {
                val repository = s.type("repository"); val dao = s.type("dao"); val book = s.type("book"); val path = s.type("path"); val opf = s.type("opf")
                check(s.constructors("repository.").toSet() == repository.declaredConstructors.toSet() && s.constructors("worker.").toSet() == s.type("worker").declaredConstructors.toSet())
                field("worker.repository", s.type("worker"), repository); field("repository.dao", repository, dao)
                field("repository.userFlow", repository, s.type("flow"), subtype = true); field("user.id", s.type("user"), long)
                for (name in "uuid title uri publisher backupId backupCode".split(' ')) field("book.$name", book, String::class.java)
                for (name in listOf("id", "uid", "size", "created", "cloudId")) field("book.$name", book, long)
                field("book.backupType", book, int)
                field("opf.metadata", opf, s.type("metadata")); field("metadata.title", s.type("metadata"), s.type("title"))
                field("metadata.uuid", s.type("metadata"), s.type("identifier")); field("metadata.metas", s.type("metadata"), List::class.java, subtype = true)
                for (owner in listOf("identifier", "title", "meta")) field("$owner.value", s.type(owner), String::class.java)
                field("meta.property", s.type("meta"), String::class.java)
                val imported = s.method("import"); check(imported.declaringClass == repository && !Modifier.isStatic(imported.modifiers) && imported.parameterCount == 7 && imported.returnType == java.io.Serializable::class.java)
                val importContinuation = s.type("importContinuation")
                check(s.type("continuation").isAssignableFrom(importContinuation))
                val bookCopy = s.method("bookCopy")
                check(bookCopy.declaringClass == book && Modifier.isStatic(bookCopy.modifiers) &&
                    bookCopy.returnType == book && bookCopy.parameterCount > 1 && bookCopy.parameterTypes.first() == book)
                val importConstructor = s.constructor("importContinuation")
                check(importConstructor.declaringClass == importContinuation && importConstructor.parameterCount == 2 &&
                    importConstructor.parameterTypes.first() == repository && s.type("continuation").isAssignableFrom(importConstructor.parameterTypes.last()))
                constructor("pair", s.type("pair"), listOf(any, any))
                field("pair.first", s.type("pair"), any); field("pair.second", s.type("pair"), any)
                check(s.field("pair.first") != s.field("pair.second"))
                for ((key, first) in listOf("daoUpdate" to book, "daoInsert" to book, "daoDelete" to long)) {
                    val commit = s.method(key)
                    check(commit.declaringClass == dao && !Modifier.isStatic(commit.modifiers) && commit.returnType == any &&
                        commit.parameterCount == 2 && commit.parameterTypes.first() == first &&
                        s.type("continuation").isAssignableFrom(commit.parameterTypes.last()))
                }
                check(s.method("daoUpdate") != s.method("daoInsert"))
                for ((name, type) in mapOf("source" to s.type("platform"), "directory" to path, "opf" to opf, "uri" to String::class.java, "size" to java.lang.Long::class.java, "progress" to s.type("function1"))) slot("import", name, type)
                check(listOf("source", "directory", "opf", "uri", "size", "progress", "continuation").map { s.index("import.$it") }.distinct().size == 7)
                for (key in listOf("import", "first", "find", "exists", "delete")) slot(key, "continuation", s.type("continuation"), true)
                method("first", args = listOf(s.type("flow"), s.type("continuation")), result = any, static = true)
                val list = s.method("list"); check(list.declaringClass == dao && list.parameterTypes.toList() == listOf(long) && s.type("flow").isAssignableFrom(list.returnType) && !Modifier.isStatic(list.modifiers))
                slot("find", "uid", long); slot("find", "uuid", String::class.java)
                val find = s.method("find"); check(find.declaringClass == dao && find.parameterCount == 3 && find.returnType == any && !Modifier.isStatic(find.modifiers))
                for ((key, type) in listOf("exists" to String::class.java, "delete" to book)) {
                    val m = s.method(key); slot(key, "value", type); check(m.declaringClass == repository && m.parameterCount == 2 && m.returnType == any && !Modifier.isStatic(m.modifiers))
                }
                method("obtain", args = listOf(path), result = opf, static = true); method("overwrite", opf, listOf(path), Void.TYPE)
                method("path", args = listOf(String::class.java, bool), result = path, static = true)
                method("parent", path, emptyList(), path); method("resolve", path, listOf(String::class.java, bool), path)
                val epubImport = s.method("epubImport"); check(Modifier.isStatic(epubImport.modifiers) && epubImport.parameterCount == 3 && epubImport.returnType == s.type("pair"))
                slot("epubImport", "source", path); slot("epubImport", "directory", path); slot("epubImport", "callback", s.type("importCallback"))
                check(s.type("function1").isInterface && s.type("function1").isAssignableFrom(s.type("importCallback")))
                check(listOf("source", "directory", "callback").map { s.index("epubImport.$it") }.distinct().size == 3)
                val worker = s.type("importWorker")
                check(s.type("continuation").isAssignableFrom(worker))
                val workerCtor = s.constructor("importWorker")
                check(workerCtor.declaringClass == worker && workerCtor.parameterTypes.count { it == s.type("worker") } == 1)
                field("importWorker.manager", worker, s.type("worker"))
                method("importWorkerResume", worker, listOf(any), any)
                constructor("txtConverter", s.type("txtConverter"), listOf(path, path, long, s.type("continuation")))
                check(s.type("continuation").isAssignableFrom(s.type("txtConverter")))
                field("txtConverter.directory", s.type("txtConverter"), path)
                val txt = s.method("txt")
                check(txt.declaringClass == repository && !Modifier.isStatic(txt.modifiers) &&
                    txt.parameterCount == 5 && txt.returnType == java.io.Serializable::class.java)
                for ((name, type) in mapOf("book" to book, "directory" to path,
                    "uri" to String::class.java, "size" to java.lang.Long::class.java)) slot("txt", name, type)
                slot("txt", "continuation", s.type("continuation"), true)
                check(listOf("book", "directory", "uri", "size", "continuation").map { s.index("txt.$it") }.toSet().size == 5)
                val txtState = s.type("txtContinuation")
                val txtCtor = s.constructor("txtContinuation")
                check(s.type("continuation").isAssignableFrom(txtState) && txtCtor.declaringClass == txtState &&
                    txtCtor.parameterTypes.first() == repository && txtCtor.parameterCount == 2 &&
                    s.type("continuation").isAssignableFrom(txtCtor.parameterTypes.last()))
            }
        }
        return s
    }
}
