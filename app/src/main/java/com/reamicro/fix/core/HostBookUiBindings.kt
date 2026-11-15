package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostBookUiBindings {
    val QUERY_KEYS = setOf("books.export", "books.imageSheet", "books.metadata", "books.backup", "books.syncUi", "books.fade", "books.localSheet")
    @Volatile private var contracts: Map<String, Contract> = emptyMap()
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    @Volatile var initializationAttempted = false; private set
    val export get() = contracts["export"]
    val imageSheet get() = contracts["imageSheet"]
    val metadata get() = contracts["metadata"]
    val backup get() = contracts["backup"]
    val syncUi get() = contracts["syncUi"]
    val fade get() = contracts["fade"]
    val localSheet get() = contracts["localSheet"]
    fun boundDescriptors() = snapshots.mapValues { it.value.toMap() }
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; contracts = emptyMap(); snapshots = emptyMap()
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val result = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            contracts = result.mapKeys { it.key.substringAfter('.') }.mapValues { (group, v) -> restore(group, v, loader) }
            snapshots = result
        } }.onFailure { HookRuntime.logError("ReaMicro book UI queries unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean): Map<String, Map<String, String>> {
        var discovery: HostBookUiQueries.Discovery? = null
        return session.collect(QUERY_KEYS) { key ->
            val group = key.substringAfter('.')
            session.query(key, force, validate = { restore(group, it, session.loader) },
                find = { bridge -> (discovery ?: HostBookUiQueries.Discovery(bridge, session.apk, base).also { discovery = it }).query(group) }).values.toMap()
        }
    }
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun hasMethod(role: String) = "method:$role" in values
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun number(role: String) = values.getValue("value:$role").toInt()
        fun constructor(role: String) = members.constructor(role)
        fun constructors(prefix: String) = values.keys.filter { it.startsWith("constructor:$prefix") }.map { constructor(it.removePrefix("constructor:")) }
        val endAlign: Any by lazy {
            val candidates = values.getValue("value:align.candidates").split(',').map(String::toInt).distinct()
            val end = candidates.single { method("alignName").invoke(null, it) == "End" }
            constructor("align").newInstance(end)
        }
        fun call(role: String, vararg args: Any?) = method(role).invoke(null, *args)
        fun isSyncContent(value: Any): Boolean = type("content").isInstance(value) &&
            (field("tag").get(value) as Number).toInt() == number("tag")
        fun runningSyncContent(original: Any, authState: Any): Any {
            check(isSyncContent(original) && type("state").isInstance(authState))
            val args = Array<Any?>(constructor("content").parameterCount) { slot ->
                val capturedField = field("argument.$slot")
                when (capturedField) {
                    field("provider") -> number("provider.yun115")
                    field("yun115State") -> authState
                    else -> capturedField.get(original)
                }
            }
            return constructor("content").newInstance(*args).also { copy ->
                check(isSyncContent(copy))
                args.indices.forEach { check(field("argument.$it").get(copy) == args[it]) }
            }
        }
        fun backupState(book: Any, backup: Any, deleting: Boolean): Any {
            val args = arrayOfNulls<Any>(3)
            mapOf("book" to book, "backup" to backup, "deleting" to deleting).forEach { (role, value) -> args[number("state.$role")] = value }
            return constructor("state").newInstance(*args).also { state ->
                listOf("book", "backup", "deleting").forEach { check(field("state.$it").get(state) == args[number("state.$it")]) }
            }
        }
    }
    internal fun restore(group: String, values: Map<String, String>, loader: ClassLoader): Contract {
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val int = Integer.TYPE; val any = Any::class.java; val void = Void.TYPE
        fun signature(role: String, args: List<Class<*>>, result: Class<*>, static: Boolean = true) {
            val m = s.method(role); check(m.parameterTypes.toList() == args && m.returnType == result && Modifier.isStatic(m.modifiers) == static)
        }
        when (group) {
            "localSheet" -> {
                val clicks = s.number("clicks"); val constructors = s.number("clickConstructors")
                check(clicks > 0 && constructors >= clicks)
                val required = listOf("book", "composer", "function0", "vector", "modifier", "content", "sheet").map { "class:$it" } +
                    listOf("root", "title", "cell", "group", "info", "content").map { "method:$it" } +
                    listOf("clicks", "clickConstructors", "subtitle", "changed").map { "value:$it" } + "field:contentBook" +
                    (0 until clicks).flatMap { listOf("class:click.$it", "field:clickBook.$it") } +
                    (0 until constructors).map { "constructor:click.$it" }
                check(values.keys == required.toSet())
                check(s.type("function0").isInterface)
                signature("cell", listOf(s.type("modifier"), s.type("vector"), String::class.java, s.type("function0"), s.type("composer"), int), void)
                signature("group", listOf(s.type("vector"), String::class.java, s.type("function0"), s.type("composer"), int), void)
                signature("info", listOf(s.type("book"), s.type("composer"), int), void)
                signature("title", List(3) { String::class.java } + listOf(s.type("composer"), int), void)
                signature("content", List(3) { any }, any, false)
                check(s.method("content").declaringClass == s.type("content"))
                check(s.field("contentBook").let { it.declaringClass == s.type("content") && !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(s.type("book")) })
                check(s.number("subtitle") in 0..2 && s.number("changed") == 4)
                for (role in listOf("root", "info", "title", "cell", "group")) check(s.method(role).declaringClass == s.type("sheet"))
                check(s.method("root").let { Modifier.isStatic(it.modifiers) && it.returnType == void &&
                    it.parameterCount == 5 && it.parameterTypes.take(2) == listOf(s.type("book"), s.type("function0")) &&
                    it.parameterTypes[2].isInterface && it.parameterTypes.takeLast(2) == listOf(s.type("composer"), int) })
                val owners = (0 until clicks).map { s.type("click.$it").also { type ->
                    check(s.type("function0").isAssignableFrom(type))
                    check(s.field("clickBook.$it").let { field -> field.declaringClass == type && !Modifier.isStatic(field.modifiers) && field.type == s.type("book") })
                } }.toSet()
                check(owners.size == clicks)
                check(s.constructors("click.").let { ctors -> ctors.size == constructors && ctors.map { it.declaringClass }.toSet() == owners &&
                    ctors.all { it.parameterTypes.count { type -> type == s.type("book") } == 1 } })
            }
            "fade" -> {
                val create = s.method("create"); val immediate = s.method("immediate")
                check(s.type("protocol").isInterface && s.type("protocol").isAssignableFrom(s.type("factory")))
                check(create.declaringClass == s.type("factory") && !Modifier.isStatic(create.modifiers) &&
                    create.parameterCount == 2 && create.parameterTypes[1] == s.type("result"))
                check(immediate.declaringClass == s.type("protocol") && !Modifier.isStatic(immediate.modifiers) &&
                    immediate.parameterTypes.contentEquals(create.parameterTypes) && immediate.returnType == create.returnType && immediate.name == create.name)
                signature("request", emptyList(), s.type("request"), false)
                check(s.method("request").declaringClass == s.type("result"))
                check(s.field("noFade").let { Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("protocol") &&
                    s.type("protocol").isAssignableFrom(it.type) && !s.type("factory").isAssignableFrom(it.type) })
                check(s.field("data").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("request") && it.type == any })
                for (index in 0..1) check(s.field("key.$index").let {
                    !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("request") && it.type == String::class.java
                })
                check(s.field("key.0") != s.field("key.1"))
                check(s.method("update").let { it.declaringClass == s.type("painter") && !Modifier.isStatic(it.modifiers) &&
                    it.returnType == void && it.parameterCount == 1 })
                check(s.constructors("publisher.").toSet() == s.type("publisher").declaredConstructors.toSet())
                check(s.field("publisherCover").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("publisher") && it.type == String::class.java })
                check(s.method("oldCover").let { Modifier.isStatic(it.modifiers) && it.returnType == void &&
                    it.parameterTypes[s.number("coverSource")] == String::class.java })
            }
            "syncUi" -> {
                signature("root", listOf(java.lang.Long.TYPE, s.type("function1"), s.type("function1"),
                    s.type("function2"), s.type("composer"), int), void)
                signature("card", listOf(s.type("vector"), String::class.java, String::class.java, java.lang.Boolean.TYPE,
                    s.type("function0"), s.type("function0"), s.type("composer"), int), void)
                signature("available", listOf(List::class.java, s.type("function1"), s.type("composer"), int), void)
                signature("content", List(4) { any }, any, false)
                check(s.method("content").declaringClass == s.type("content"))
                val ctor = s.constructor("content")
                check(ctor.declaringClass == s.type("content") && ctor.parameterCount == 7)
                val fields = (0 until ctor.parameterCount).map { slot -> s.field("argument.$slot").also {
                    check(it.declaringClass == s.type("content") && !Modifier.isStatic(it.modifiers) &&
                        it.type.isAssignableFrom(ctor.parameterTypes[slot]))
                } }
                val tag = s.field("tag")
                check(tag.declaringClass == s.type("content") && !Modifier.isStatic(tag.modifiers) && tag.type in setOf(Byte::class.javaPrimitiveType, int))
                check((fields + tag).toSet() == s.type("content").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet())
                check(fields.distinct().size == 7)
                for ((role, type) in listOf("provider" to java.lang.Integer::class.java, "yun115State" to s.type("state"),
                    "authorize" to s.type("function1"), "setup" to s.type("function1"), "pick" to s.type("function2"))) {
                    val slot = fields.indexOf(s.field(role)); check(slot >= 0 && ctor.parameterTypes[slot] == type)
                }
                for ((role, type) in listOf("bookId" to java.lang.Long.TYPE, "authorize" to s.type("function1"),
                    "setup" to s.type("function1"), "pick" to s.type("function2")))
                    check(s.method("root").parameterTypes[s.number("root.$role")] == type)
                check(listOf("running", "composer", "flags").map { s.number("content.$it") }.let { it.distinct().size == 3 && it.all { slot -> slot in 0..3 } })
                check(s.number("provider.yun115") >= 0)
                val rowCtor = s.constructor("row")
                check(rowCtor.declaringClass == s.type("row") && s.type("function0").isAssignableFrom(s.type("row")) &&
                    rowCtor.parameterTypes.toList() == listOf(s.type("function1"), int, Byte::class.javaPrimitiveType))
                for ((role, type) in listOf("callback" to s.type("function1"), "provider" to int, "tag" to Byte::class.javaPrimitiveType))
                    check(s.field("row.$role").let { it.declaringClass == s.type("row") && !Modifier.isStatic(it.modifiers) && it.type == type })
                check(s.type("row").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet() ==
                    listOf("callback", "provider", "tag").map { s.field("row.$it") }.toSet())
                val click = s.method("rowClickable")
                check(Modifier.isStatic(click.modifiers) && click.returnType == s.type("modifier") && click.parameterCount == 6 &&
                    click.parameterTypes.let { it[0] == s.type("modifier") && it[1] == java.lang.Boolean.TYPE &&
                        it[2] == String::class.java && it[4] == s.type("function0") && it[5] == int })
                signature("image", listOf(s.type("vector"), String::class.java, s.type("modifier"), s.type("composer"), int, int), void)
                for (arity in 0..2) {
                    signature("invoke$arity", List(arity) { any }, any, false)
                    check(s.type("function$arity").isInterface && s.method("invoke$arity").declaringClass == s.type("function$arity"))
                }
            }
            "backup" -> {
                signature("materialize", listOf(s.type("composer"), s.type("modifier")), s.type("modifier"))
                signature("rememberScroll", listOf(s.type("composer")), s.type("scrollState"))
                signature("verticalScroll", listOf(s.type("modifier"), s.type("scrollState"), int), s.type("modifier"))
                check(s.field("rootModifier").let { Modifier.isStatic(it.modifiers) && s.type("modifier").isAssignableFrom(it.type) })
                check(s.number("scrollMask").let { it > 0 && (it and (it - 1)) == 0 })
                check(s.type("vm").superclass == s.type("receiver"))
                val ctor = s.constructor("vm")
                check(ctor.declaringClass == s.type("vm") && ctor.parameterCount == 3)
                for ((role, type) in listOf("bookId" to java.lang.Long.TYPE, "storage" to s.type("storage"))) {
                    val f = s.field("vm.$role")
                    check(!Modifier.isStatic(f.modifiers) && f.declaringClass == s.type("vm") && f.type == type &&
                        ctor.parameterTypes[s.number("vm.$role")] == type)
                }
                val props = mapOf("book" to s.type("book"), "backup" to s.type("cloud"), "deleting" to java.lang.Boolean.TYPE)
                val stateCtor = s.constructor("state")
                check(stateCtor.declaringClass == s.type("state") && stateCtor.parameterCount == props.size &&
                    props.keys.map { s.number("state.$it") }.toSet() == (0..2).toSet())
                for ((role, type) in props) {
                    val f = s.field("state.$role")
                    check(f.declaringClass == s.type("state") && !Modifier.isStatic(f.modifiers) && f.type == type &&
                        stateCtor.parameterTypes[s.number("state.$role")] == type)
                }
                check(s.type("state").declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet() == props.keys.map { s.field("state.$it") }.toSet())
                check(s.method("stateFactory").let { it.declaringClass == s.type("vm") && !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 && it.returnType.isAssignableFrom(s.type("state")) })
                signature("content", listOf(s.type("book"), s.type("cloud"), java.lang.Boolean.TYPE) +
                    List(4) { s.type("function") } + listOf(s.type("composer"), int), void)
                signature("screen", List(3) { any }, any, false)
                signature("loading", listOf(int, s.type("composer")), void)
                check(s.method("screen").declaringClass == s.type("screen"))
                check(s.field("screen.vm").let { it.declaringClass == s.type("screen") && !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(s.type("vm")) })
                signature("card", listOf(int, s.type("vector"), s.type("cloud"), java.lang.Boolean.TYPE, s.type("function"), s.type("composer"), int), void)
                signature("yun115", listOf(s.type("cloud"), java.lang.Boolean.TYPE, s.type("function"), s.type("composer"), int), void)
                if (s.hasMethod("typeName")) signature("typeName", listOf(java.lang.Integer::class.java), String::class.java)
                signature("update", listOf(s.type("function")), void, false)
                check(s.method("update").declaringClass == s.type("receiver"))
                check(listOf("baidu", "yun115", "aliyun").map { s.number("provider.$it") }.distinct().size == 3)
                for ((role, owner, type) in listOf(Triple("book.id", "book", java.lang.Long.TYPE), Triple("book.backupType", "book", int),
                    Triple("book.backupId", "book", String::class.java), Triple("cloud.name", "cloud", String::class.java), Triple("cloud.path", "cloud", String::class.java))) {
                    check(s.field(role).let { it.declaringClass == s.type(owner) && !Modifier.isStatic(it.modifiers) && it.type == type })
                }
                check(s.field("cloud.type").let { it.declaringClass == s.type("cloud") && !Modifier.isStatic(it.modifiers) && it.type in setOf(Byte::class.javaPrimitiveType, int) })
            }
            "metadata" -> {
                val required = listOf("book", "composer", "function", "vector", "scope", "repository", "storage", "path", "align", "modifier", "activity", "navigationController").map { "class:$it" } +
                    listOf("title", "sync", "icon", "image", "edit", "copy", "next", "local", "baidu", "yun115", "aliyun", "books", "toFile", "alignName", "activityResult", "invoke").map { "method:$it" } +
                    listOf("uid", "uuid", "title", "author", "size", "backupType").map { "field:book.$it" } +
                    listOf("constructor:align", "field:repository.storage", "field:navigation.activity", "value:align.candidates", "value:backup.baidu", "value:backup.yun115", "value:backup.aliyun")
                check(values.keys.containsAll(required))
                val navigationActivity = s.field("navigation.activity")
                check(navigationActivity.declaringClass == s.type("navigationController") &&
                    navigationActivity.type == android.app.Activity::class.java &&
                    !Modifier.isStatic(navigationActivity.modifiers) && Modifier.isFinal(navigationActivity.modifiers))
                signature("title", listOf(s.type("scope"), s.type("book"), s.type("function"), s.type("function"), s.type("composer"), int), void)
                signature("sync", listOf(s.type("scope"), s.type("book"), s.type("function"), s.type("composer"), int), void)
                signature("icon", listOf(s.type("vector"), String::class.java, s.type("modifier"), java.lang.Long.TYPE, s.type("composer"), int, int), void)
                signature("image", listOf(s.type("vector"), String::class.java, s.type("modifier"), s.type("composer"), int, int), void)
                for (role in listOf("edit", "copy", "next", "local", "baidu", "yun115", "aliyun")) signature(role, emptyList(), s.type("vector"))
                check(listOf("baidu", "yun115", "aliyun").map { s.number("backup.$it") }.distinct().size == 3)
                val buttonRoles = listOf("tag", "color", "vector", "click", "extra")
                val buttonKeys = listOf("class:button", "class:button.function2", "class:button.extra",
                    "constructor:button", "method:button.body", "method:button.extraInvoke",
                    "field:button.foreground", "field:button.empty", "value:button.tag") +
                    buttonRoles.flatMap { listOf("field:button.$it", "value:button.slot.$it") }
                check(values.keys.containsAll(buttonKeys))
                // The native extra slot is Function3 in stable and a concrete ComposableLambda in beta.
                check(s.type("button.function2").isInterface &&
                    s.type("button.function2").isAssignableFrom(s.type("button")))
                signature("button.body", List(2) { any }, any, false)
                signature("button.extraInvoke", List(3) { any }, any, false)
                check(s.method("button.body").declaringClass == s.type("button") &&
                    s.method("button.extraInvoke").declaringClass == s.type("button.extra"))
                val buttonCtor = s.constructor("button")
                check(buttonCtor.declaringClass == s.type("button") &&
                    buttonCtor.parameterTypes.toList() == listOf(java.lang.Byte.TYPE, java.lang.Long.TYPE) + List(3) { any })
                check(buttonRoles.map { s.number("button.slot.$it") } == (0..4).toList() &&
                    s.number("button.tag") in Byte.MIN_VALUE..Byte.MAX_VALUE)
                for ((role, type) in buttonRoles.zip(listOf(java.lang.Byte.TYPE, java.lang.Long.TYPE, any, any, any))) {
                    val field = s.field("button.$role")
                    check(field.declaringClass == s.type("button") && !Modifier.isStatic(field.modifiers) && field.type == type)
                }
                check(buttonRoles.map { s.field("button.$it") }.distinct().size == 5)
                check(s.field("button.foreground").let { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) })
                check(s.field("button.empty").let { Modifier.isStatic(it.modifiers) && s.type("button.extra").isAssignableFrom(it.type) &&
                    s.type("button.extra").isInstance(it.get(null)) })
                signature("books", listOf(java.lang.Long::class.java), s.type("path"), false)
                signature("toFile", emptyList(), java.io.File::class.java, false)
                check(s.method("books").declaringClass == s.type("storage") && s.method("toFile").declaringClass == s.type("path"))
                val storage = s.field("repository.storage")
                check(storage.declaringClass == s.type("repository") && storage.type == s.type("storage") && !Modifier.isStatic(storage.modifiers))
                check(s.constructors("repository.").isNotEmpty() && s.constructors("repository.").toSet() == s.type("repository").declaredConstructors.toSet())
                signature("alignName", listOf(int), String::class.java)
                check(s.method("alignName").declaringClass == s.type("align") && s.constructor("align").declaringClass == s.type("align") &&
                    s.constructor("align").parameterTypes.toList() == listOf(int))
                check(values.getValue("value:align.candidates").split(',').map(String::toInt).isNotEmpty())
                signature("activityResult", listOf(int, int, android.content.Intent::class.java), void, false)
                check(s.method("activityResult").declaringClass == s.type("activity") && android.app.Activity::class.java.isAssignableFrom(s.type("activity")))
                signature("invoke", emptyList(), any, false)
                check(s.type("function").isInterface && s.method("invoke").declaringClass == s.type("function"))
                for (role in listOf("uid", "uuid", "title", "author", "size", "backupType")) {
                    val field = s.field("book.$role")
                    val type = when (role) { "uid", "size" -> java.lang.Long.TYPE; "backupType" -> int; else -> String::class.java }
                    check(field.declaringClass == s.type("book") && field.type == type && !Modifier.isStatic(field.modifiers))
                }
            }
            "imageSheet" -> {
                val required = listOf("book", "composer", "function", "function1", "sheet", "continuation", "context").map { "class:$it" } +
                    listOf("overview", "hide", "visible", "invoke", "update").map { "method:$it" } +
                    listOf("uid", "uuid", "title", "author", "publisher", "cover", "uri").map { "field:book.$it" }
                check(values.keys.containsAll(required))
                val overview = s.method("overview")
                check(Modifier.isStatic(overview.modifiers) && overview.returnType == void && overview.parameterCount == 5 &&
                    overview.parameterTypes.drop(1) == listOf(s.type("book"), s.type("function1"), s.type("composer"), int))
                signature("invoke", emptyList(), any, false)
                signature("update", listOf(any), any, false)
                check(s.type("function").isInterface && s.method("invoke").declaringClass == s.type("function"))
                check(s.type("function1").isInterface && s.method("update").declaringClass == s.type("function1"))
                val menuMethods = listOf("icon", "text", "clickable", "endNode", "size", "paddingDefault", "divider", "next",
                    "icon.Link", "icon.AutoAwesome", "icon.Download")
                val menuNumbers = listOf("start", "top", "end", "bottom", "clickMask", "textFlags", "textFlags2", "textMask",
                    "iconSize", "iconGap", "iconMask", "arrowTop", "arrowMask", "dividerInset", "dividerMask")
                check(values.keys.containsAll(menuMethods.map { "method:menu.$it" } +
                    listOf("vector", "modifier", "style").map { "class:menu.$it" } +
                    listOf("style", "foreground", "trailing").map { "field:menu.$it" } +
                    menuNumbers.map { "value:menu.$it" }))
                val modifier = s.type("menu.modifier")
                signature("menu.icon", listOf(s.type("menu.vector"), String::class.java, modifier, java.lang.Long.TYPE,
                    s.type("composer"), int, int), void)
                signature("menu.size", listOf(modifier, java.lang.Float.TYPE), modifier)
                signature("menu.paddingDefault", listOf(modifier) + List(4) { java.lang.Float.TYPE } + int, modifier)
                signature("menu.divider", listOf(modifier, java.lang.Long.TYPE, s.type("composer"), int, int), void)
                signature("menu.endNode", listOf(java.lang.Boolean.TYPE), void, false)
                check(s.method("menu.endNode").declaringClass == s.type("composer"))
                for (role in listOf("next", "icon.Link", "icon.AutoAwesome", "icon.Download"))
                    signature("menu.$role", emptyList(), s.type("menu.vector"))
                val menuText = s.method("menu.text")
                check(Modifier.isStatic(menuText.modifiers) && menuText.returnType == void && menuText.parameterCount == 20 &&
                    menuText.parameterTypes.take(2) == listOf(String::class.java, modifier) &&
                    menuText.parameterTypes[15] == s.type("menu.style") &&
                    menuText.parameterTypes.takeLast(4) == listOf(s.type("composer"), int, int, int))
                val click = s.method("menu.clickable")
                check(Modifier.isStatic(click.modifiers) && click.returnType == modifier &&
                    click.parameterTypes.take(3) == listOf(modifier, java.lang.Boolean.TYPE, String::class.java) &&
                    click.parameterTypes.takeLast(2) == listOf(s.type("function"), int))
                check(s.field("menu.style").type == s.type("menu.style") && !Modifier.isStatic(s.field("menu.style").modifiers))
                for (role in listOf("foreground", "trailing")) {
                    val field = s.field("menu.$role")
                    check(field.type == java.lang.Long.TYPE && !Modifier.isStatic(field.modifiers))
                }
                for (role in listOf("start", "top", "end", "bottom", "iconSize", "iconGap", "arrowTop", "dividerInset"))
                    check(s.number("menu.$role") in 0..128)
                check((s.number("menu.clickMask") and 1) != 0 &&
                    (s.number("menu.textMask") and (2 or 4 or 131072)) == 0)
                signature("hide", listOf(s.type("continuation")), any, false)
                signature("visible", emptyList(), java.lang.Boolean.TYPE, false)
                check(s.method("hide").declaringClass == s.type("sheet") && s.method("visible").declaringClass == s.type("sheet"))
                for (role in listOf("cover", "banner")) {
                    val count = s.number("$role.callbacks")
                    check(count == if (role == "cover") 4 else 3)
                    signature("$role.entry", listOf(java.lang.Boolean.TYPE) + List(count) { s.type("function") } +
                        listOf(s.type("composer"), int), void)
                    signature("$role.content", List(3) { any }, any, false)
                    signature("$role.context", emptyList(), s.type("context"), false)
                    check(s.method("$role.content").declaringClass == s.type(role) && s.type("$role.scope").isInterface &&
                        s.method("$role.context").declaringClass == s.type("$role.scope"))
                    val constructor = s.constructor(role)
                    check(constructor.declaringClass == s.type(role) && constructor.parameterCount == count + 3 &&
                        constructor.parameterTypes.count { it == s.type("function") } == count &&
                        constructor.parameterTypes.count { it == s.type("sheet") } == 1 &&
                        constructor.parameterTypes.count { it == s.type("$role.scope") } == 1 &&
                        constructor.parameterTypes.count { it == java.lang.Boolean.TYPE } == 1)
                    for ((capture, type) in listOf("pick" to s.type("function"), "state" to s.type("sheet"), "scope" to s.type("$role.scope"))) {
                        val field = s.field("$role.$capture")
                        check(field.declaringClass == s.type(role) && !Modifier.isStatic(field.modifiers) && field.type.isAssignableFrom(type))
                    }
                    check(listOf("pick", "state", "scope").map { s.field("$role.$it") }.distinct().size == 3)
                }
                for (role in listOf("uid", "uuid", "title", "author", "publisher", "cover", "uri")) {
                    val field = s.field("book.$role")
                    check(field.declaringClass == s.type("book") && !Modifier.isStatic(field.modifiers) &&
                        field.type == if (role == "uid") java.lang.Long.TYPE else String::class.java)
                }
            }
            "export" -> {
                val required = listOf("book", "composer", "function", "activity", "vector").map { "class:$it" } +
                    listOf("content", "header", "drive", "icon", "activityResult", "invoke").map { "method:$it" } +
                    listOf("uid", "uuid", "title").map { "field:book.$it" }
                check(values.keys.containsAll(required))
                signature("header", listOf(s.type("book"), s.type("composer"), int), void)
                signature("drive", listOf(s.type("vector"), String::class.java, String::class.java, java.lang.Boolean.TYPE,
                    s.type("function"), s.type("function"), s.type("composer"), int), void)
                signature("icon", emptyList(), s.type("vector"))
                signature("activityResult", listOf(int, int, android.content.Intent::class.java), void, false)
                check(s.method("activityResult").declaringClass == s.type("activity") && android.app.Activity::class.java.isAssignableFrom(s.type("activity")))
                signature("invoke", emptyList(), any, false)
                check(s.type("function").isInterface && s.method("invoke").declaringClass == s.type("function"))
                val content = s.method("content")
                check(Modifier.isStatic(content.modifiers) && content.returnType == void && content.parameterCount == 9 &&
                    content.parameterTypes.first() == s.type("book") && content.parameterTypes.takeLast(2) == listOf(s.type("composer"), int) &&
                    content.parameterTypes.count { it.name == "kotlin.jvm.functions.Function1" } == 4)
                for ((name, type) in listOf("uid" to java.lang.Long.TYPE, "uuid" to String::class.java, "title" to String::class.java)) {
                    val f = s.field("book.$name"); check(f.declaringClass == s.type("book") && !Modifier.isStatic(f.modifiers) && f.type == type)
                }
            }
            else -> error("Unknown book UI contract: $group")
        }
        return s
    }
}
