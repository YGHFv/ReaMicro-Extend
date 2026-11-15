package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostBookCoverBindings {
    const val KEY = "books.cover"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, requireNotNull(HostAutoScanner.queriedDescriptors()))
            current = restore(values, loader)
            descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro cover bindings unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>) =
        session.query(KEY, force,
            validate = { restore(it, session.loader) }, find = { HostBookCoverQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(private val members: HostRuntimeBindings.Members, private val values: Map<String, String>) {
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)
        fun field(key: String) = members.field(key)
        fun property(receiver: Any, name: String): Any? {
            val candidates = values.keys.filter { it.startsWith("field:") && it.endsWith("#$name") }
                .map { field(it.removePrefix("field:")) }.filter { it.declaringClass.isInstance(receiver) }
            return candidates.single().get(receiver)
        }
        fun unwrap(value: Any?): Any? {
            if (value != null && type("failure").isInstance(value)) throw (field("failure").get(value) as Throwable)
            return value
        }
        fun configurationTag(): Long = values.getValue("value:configuration.tag").toLong()
        fun defaultRequestBuilder(callback: Any): Any? {
            if (!type("defaultRequestCallback").isInstance(callback)) return null
            if ((field("defaultRequest.tag").get(callback) as Number).toLong() !=
                values.getValue("value:defaultRequest.tag").toLong()) return null
            val context = field("defaultRequest.context").get(callback)
                ?.takeIf { type("defaultRequestContext").isInstance(it) } ?: return null
            val builder = if (values.getValue("value:defaultRequest.access") == "field") {
                field("defaultRequest.request").get(context)
            } else method("defaultRequest.request").invoke(context)
            return builder?.takeIf { type("nativeRequestBuilder").isInstance(it) }
        }
        /** Set one captured token after the native DefaultRequest plugin has merged its headers. */
        fun bindAuthorization(builder: Any, token: String) {
            check(type("nativeRequestBuilder").isInstance(builder))
            val headers = requireNotNull(field("nativeRequest.headers").get(builder))
            val bearer = "Bearer $token"
            @Suppress("UNCHECKED_CAST")
            val values = field("nativeHeaders.values").get(headers) as MutableMap<String, Any?>
            values.remove("Authorization")
            method("appendHeader").invoke(headers, "Authorization", bearer)
            check(method("headerValues").invoke(headers, "Authorization") == listOf(bearer)) {
                "Native request authorization was not replaced"
            }
        }
        fun request(name: String, author: String, md5: String, cover: String): Any {
            val inputs = mapOf("name" to name, "author" to author, "md5" to md5, "cover" to cover, "coverMd5" to "")
            val arguments = arrayOfNulls<Any>(inputs.size)
            inputs.forEach { (key, value) -> arguments[values.getValue("value:request.$key").toInt()] = value }
            val request = constructor("request").newInstance(*arguments)
            check(inputs.all { (key, value) -> property(request, key) == value }) { "Cover request does not preserve properties" }
            return request
        }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        fun keys(prefix: String, names: String) = names.split(' ').map { "$prefix:$it" }
        val required = keys("class", "book details state overview overviewState repository shelf dao route overviewRoute publisher cloud request response envelope failure args api") +
            keys("method", "applyBook currentState cleared cloudInfo find sourceClick search post data") +
            keys("constructor", "details overview request") +
            keys("field", "args detailsRepository overviewRepository shelf dao overviewId sourceBook api failure") +
            keys("field", "args#bookId args#compat book#id book#cloudId book#publisher book#cover book#uuid book#title book#author state#title state#author state#publishers overviewState#book overviewState#relationPublisher cloud#publishers publisher#cover publisher#publisher response#id") +
            keys("field", "request#name request#author request#md5 request#cover request#coverMd5") +
            keys("value", "request.name request.author request.md5 request.cover request.coverMd5") +
            keys("class", "requestConfiguration nativeRequestBuilder nativeHeaders defaultRequestCallback defaultRequestContext") +
            keys("constructor", "requestConfiguration nativeRequestBuilder") +
            keys("method", "configureRequest appendHeader headerValues defaultRequest") +
            keys("field", "configuration.request configuration.tag nativeRequest.body nativeRequest.headers nativeHeaders.values defaultRequest.context defaultRequest.tag book#uid") +
            keys("value", "configuration.tag defaultRequest.access defaultRequest.tag")
        check(values.keys.containsAll(required)) { "Missing cover contracts: ${required.toSet() - values.keys}" }
        val c = Contract(HostRuntimeBindings.Members(values, loader), values)
        val long = java.lang.Long.TYPE
        val boolean = java.lang.Boolean.TYPE
        val string = String::class.java
        val any = Any::class.java
        val continuation = loader.hostClass("kotlin.coroutines.Continuation")
        fun field(name: String, owner: String, type: Class<*>) {
            val f = c.field(name)
            check(f.declaringClass == c.type(owner) && f.type == type && !Modifier.isStatic(f.modifiers)) { "Invalid cover field $name" }
        }
        for ((key, owner, target) in listOf(Triple("args", "details", "args"), Triple("detailsRepository", "details", "repository"),
            Triple("overviewRepository", "overview", "repository"), Triple("shelf", "repository", "shelf"), Triple("dao", "shelf", "dao"),
            Triple("api", "repository", "api"))) field(key, owner, c.type(target))
        field("overviewId", "overview", long); field("failure", "failure", Throwable::class.java)
        field("args#bookId", "args", long); field("args#compat", "args", boolean)
        for (key in listOf("id", "cloudId", "uid")) field("book#$key", "book", long)
        for (key in listOf("publisher", "cover", "uuid", "title", "author")) field("book#$key", "book", string)
        for (key in listOf("title", "author")) field("state#$key", "state", string)
        field("state#publishers", "state", List::class.java); field("cloud#publishers", "cloud", List::class.java)
        field("overviewState#book", "overviewState", c.type("book"))
        field("overviewState#relationPublisher", "overviewState", c.type("publisher"))
        field("publisher#cover", "publisher", string); field("publisher#publisher", "publisher", string)
        field("response#id", "response", long)
        for (key in listOf("name", "author", "md5", "cover", "coverMd5")) field("request#$key", "request", string)
        val request = c.constructor("request")
        check(request.declaringClass == c.type("request") && request.parameterTypes.toList() == List(5) { string })
        check(listOf("name", "author", "md5", "cover", "coverMd5").map { values.getValue("value:request.$it").toInt() }.toSet() == (0..4).toSet())
        val details = c.constructor("details")
        check(details.declaringClass == c.type("details") && details.parameterTypes.take(2) == listOf(c.type("args"), c.type("repository")))
        val overview = c.constructor("overview")
        check(overview.declaringClass == c.type("overview") && overview.parameterTypes.take(2) == listOf(c.type("shelf"), c.type("repository")) && overview.parameterTypes.last() == long)
        fun suspendMethod(key: String, owner: String, params: List<Class<*>>) {
            val m = c.method(key)
            check(m.declaringClass == c.type(owner) && !Modifier.isStatic(m.modifiers) && m.returnType == any &&
                m.parameterCount == params.size + 1 && m.parameterTypes.dropLast(1) == params && continuation.isAssignableFrom(m.parameterTypes.last())) { "Invalid cover method $key" }
        }
        suspendMethod("cloudInfo", "repository", listOf(long, boolean))
        suspendMethod("find", "dao", listOf(long)); suspendMethod("search", "shelf", listOf(string))
        suspendMethod("post", "api", listOf(c.type("request")))
        val data = c.method("data")
        check(Modifier.isStatic(data.modifiers) && data.parameterTypes.toList() == listOf(c.type("envelope")) && data.returnType == any)
        val apply = c.method("applyBook")
        check(apply.declaringClass == c.type("overview") && !Modifier.isStatic(apply.modifiers) && apply.parameterTypes.toList() == listOf(c.type("book")) && apply.returnType == Void.TYPE)
        for (key in listOf("currentState", "cleared")) {
            val m = c.method(key)
            check(!Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.declaringClass.isAssignableFrom(c.type("details")) &&
                m.declaringClass.isAssignableFrom(c.type("overview")))
            check(if (key == "cleared") m.returnType == Void.TYPE else m.returnType.isAssignableFrom(c.type("state")) && m.returnType.isAssignableFrom(c.type("overviewState")))
        }
        val click = c.method("sourceClick")
        check(!Modifier.isStatic(click.modifiers) && click.parameterCount == 0 && click.returnType == any &&
            c.field("sourceBook").declaringClass == click.declaringClass && c.field("sourceBook").type == c.type("book") &&
            !Modifier.isStatic(c.field("sourceBook").modifiers))
        field("configuration.request", "requestConfiguration", any)
        val tag = c.field("configuration.tag")
        check(tag.declaringClass == c.type("requestConfiguration") && !Modifier.isStatic(tag.modifiers) &&
            tag.type in setOf(java.lang.Byte.TYPE, java.lang.Integer.TYPE))
        check(values.getValue("value:configuration.tag").toLong() in 0L..255L)
        val configure = c.method("configureRequest")
        check(configure.declaringClass == c.type("requestConfiguration") && !Modifier.isStatic(configure.modifiers) &&
            configure.parameterTypes.toList() == listOf(any) && configure.returnType == any)
        val configureConstructor = c.constructor("requestConfiguration")
        check(configureConstructor.declaringClass == c.type("requestConfiguration") &&
            configureConstructor.parameterTypes.toList().let {
                it.size == 3 && it[0] == any && it[1] == c.type("api") && it[2] == tag.type
            })
        val builder = c.constructor("nativeRequestBuilder")
        check(builder.declaringClass == c.type("nativeRequestBuilder") && builder.parameterCount == 0)
        field("nativeRequest.body", "nativeRequestBuilder", any)
        field("nativeRequest.headers", "nativeRequestBuilder", c.type("nativeHeaders"))
        for ((role, params, result) in listOf(
            Triple("appendHeader", listOf(string, string), Void.TYPE),
            Triple("headerValues", listOf(string), List::class.java),
        )) {
            val method = c.method(role)
            check(!Modifier.isStatic(method.modifiers) && method.declaringClass.isAssignableFrom(c.type("nativeHeaders")) &&
                method.parameterTypes.toList() == params && method.returnType == result) { "Invalid native cover header $role" }
        }
        val headerValues = c.field("nativeHeaders.values")
        check(!Modifier.isStatic(headerValues.modifiers) &&
            headerValues.declaringClass.isAssignableFrom(c.type("nativeHeaders")) &&
            (headerValues.type == any || Map::class.java.isAssignableFrom(headerValues.type)))
        field("defaultRequest.context", "defaultRequestCallback", any)
        val defaultTag = c.field("defaultRequest.tag")
        check(defaultTag.declaringClass == c.type("defaultRequestCallback") && !Modifier.isStatic(defaultTag.modifiers) &&
            defaultTag.type in setOf(java.lang.Byte.TYPE, java.lang.Integer.TYPE) && Modifier.isFinal(defaultTag.modifiers))
        check(values.getValue("value:defaultRequest.tag").toLong() in 0L..255L)
        val stage = c.method("defaultRequest")
        check(stage.declaringClass == c.type("defaultRequestCallback") && !Modifier.isStatic(stage.modifiers) &&
            stage.parameterTypes.toList() == listOf(any) && stage.returnType == any)
        when (values.getValue("value:defaultRequest.access")) {
            "field" -> field("defaultRequest.request", "defaultRequestContext", any)
            "method" -> {
                val access = c.method("defaultRequest.request")
                check(!Modifier.isStatic(access.modifiers) &&
                    access.declaringClass.isAssignableFrom(c.type("defaultRequestContext")) &&
                    access.parameterCount == 0 && access.returnType == any)
            }
            else -> error("Unsupported native default request access")
        }
        return c
    }
}
