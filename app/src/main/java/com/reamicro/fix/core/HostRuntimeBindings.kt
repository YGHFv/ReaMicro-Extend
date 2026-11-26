package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** All discovery runs during initialization. Runtime consumers use these already validated members. */
internal object HostRuntimeBindings {
    @Volatile var navigation: Navigation? = null; private set
    @Volatile var http: Http? = null; private set
    @Volatile var paging: Paging? = null; private set
    @Volatile var files: Files? = null; private set
    @Volatile var coroutines: Coroutines? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshots: Map<String, Map<String, String>> = emptyMap()
    fun boundDescriptors(): Map<String, Map<String, String>> = snapshots.mapValues { it.value.toMap() }
    fun rescan(session: HostQuerySession.Session, force: Boolean = true): Map<String, Map<String, String>> {
        return session.collect(listOf("navigation", "http", "paging", "files", "coroutines").map { "runtime.$it" }) { key ->
            val group = key.substringAfter('.')
            session.query(key, force,
                validate = { when (group) {
                    "navigation" -> restoreNavigation(it, session.loader)
                    "http" -> restoreHttp(it, session.loader)
                    "paging" -> restorePaging(it, session.loader)
                    "coroutines" -> restoreCoroutines(it, session.loader)
                    else -> restoreFiles(it, session.loader)
                } }, find = { when (group) {
                    "navigation" -> HostRuntimeQueries.navigation(it)
                    "http" -> HostRuntimeQueries.http(it, session.apk)
                    "paging" -> HostRuntimeQueries.paging(it)
                    "coroutines" -> HostCoroutineQueries.runtime(it)
                    else -> HostRuntimeQueries.files(it)
                } }).values.toMap()
        }
    }

    fun initialize(loader: ClassLoader, apkPath: String, dataDir: String) {
        initializationAttempted = true
        val bound = linkedMapOf<String, Map<String, String>>()
        navigation = null; http = null; paging = null; files = null; coroutines = null; snapshots = emptyMap()
        runCatching { HostQuerySession.use(loader, apkPath, dataDir) { session ->
            fun <T> resolve(key: String, restore: (Map<String, String>, ClassLoader) -> T,
                find: (org.luckypray.dexkit.DexKitBridge) -> Map<String, String>): T? = runCatching {
                var value: T? = null
                val result = session.query(key,
                    validate = { value = restore(it, loader) }, find = find)
                bound[key] = result.values.toMap()
                HookRuntime.log("ReaMicro $key ${if (result.cached) "cached" else "resolved"}")
                requireNotNull(value)
            }.onFailure { HookRuntime.logError("ReaMicro $key unavailable", it) }.getOrNull()
            navigation = resolve("runtime.navigation", ::restoreNavigation, HostRuntimeQueries::navigation)
            http = resolve("runtime.http", ::restoreHttp) { HostRuntimeQueries.http(it, apkPath) }
            paging = resolve("runtime.paging", ::restorePaging, HostRuntimeQueries::paging)
            files = resolve("runtime.files", ::restoreFiles, HostRuntimeQueries::files)
            coroutines = resolve("runtime.coroutines", ::restoreCoroutines, HostCoroutineQueries::runtime)
        } }.onFailure { HookRuntime.logError("ReaMicro runtime query session unavailable", it) }
        snapshots = bound
    }

    class Navigation internal constructor(val scope: Class<*>, val controller: Field,
        val navigate: Method, val pop: Method, val controllerPop: Method, val constructor: Constructor<*>)
    class Coroutines internal constructor(val loader: ClassLoader, private val members: Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun constructor(role: String) = members.constructor(role)
        fun value(role: String): Any = requireNotNull(members.field(role).get(null))
        val suspended: Any by lazy { requireNotNull(type("states").enumConstants).single { (it as Enum<*>).name == "COROUTINE_SUSPENDED" } }
    }

    class Http internal constructor(private val members: Members, val requestBuilderTag: Byte,
        val cleartextPolicies: List<Method>) {
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)
        fun field(key: String) = members.field(key)
    }

    class Paging internal constructor(private val members: Members) {
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)
        fun field(key: String) = members.field(key)
        val unit: Any get() = requireNotNull(field("unit").get(null))
        val notLoading: Pair<Any, Any> by lazy {
            val reached = field("endReached")
            val values = (0..1).map { requireNotNull(field("notLoading.$it").get(null)) }
            values.single { !reached.getBoolean(it) } to values.single { reached.getBoolean(it) }
        }
        val refresh: Any by lazy { requireNotNull(type("loadType").enumConstants).single { (it as Enum<*>).name == "REFRESH" } }
    }

    class Files internal constructor(val platform: Class<*>, val wrapper: Class<*>,
        private val createPlatform: Constructor<*>, private val createWrapper: Constructor<*>,
        private val wrapperField: Field, private val fileField: Field) {
        fun from(file: java.io.File): Any = createPlatform.newInstance(createWrapper.newInstance(file))
        fun file(value: Any): java.io.File? {
            check(platform.isInstance(value)) { "Unexpected platform file type" }
            val inner = wrapperField.get(value)
            return if (wrapper.isInstance(inner)) fileField.get(inner) as java.io.File else null
        }
    }

    private fun keys(values: Map<String, String>, vararg groups: Pair<String, String>) {
        val required = groups.flatMap { (prefix, names) -> names.split(' ').filter { it.isNotEmpty() }.map { "$prefix:$it" } }.toSet()
        check(values.keys == required) { "Incomplete native contract: missing=${required - values.keys}, extra=${values.keys - required}" }
    }
    internal fun restoreCoroutines(values: Map<String, String>, loader: ClassLoader): Coroutines {
        keys(values,
            "class" to "continuation context function2 job viewModel scope empty unit states main parent base failure",
            "method" to "runBlocking delay withContext getJob active cancel viewModelScope scopeContext continuationContext invoke resume resumeWith",
            "constructor" to "parent failure", "field" to "empty unit main")
        val m = Members(values, loader)
        val any = Any::class.java; val void = Void.TYPE
        fun signature(role: String, params: List<Class<*>>, result: Class<*>, static: Boolean) {
            val method = m.method(role)
            check(method.parameterTypes.toList() == params && method.returnType == result && Modifier.isStatic(method.modifiers) == static)
        }
        val continuation = m.type("continuation"); val context = m.type("context"); val function = m.type("function2")
        check(continuation.isInterface && context.isInterface && function.isInterface && m.type("job").isInterface)
        check(context.isAssignableFrom(m.type("job")))
        signature("runBlocking", listOf(context, function), any, true)
        signature("delay", listOf(java.lang.Long.TYPE, continuation), any, true)
        signature("withContext", listOf(context, function, continuation), any, true)
        signature("getJob", listOf(context), m.type("job"), true)
        signature("active", emptyList(), java.lang.Boolean.TYPE, false)
        signature("cancel", listOf(java.util.concurrent.CancellationException::class.java), void, false)
        for (role in listOf("active", "cancel")) check(m.method(role).declaringClass == m.type("job"))
        signature("viewModelScope", listOf(m.type("viewModel")), m.type("scope"), true)
        signature("scopeContext", emptyList(), context, false)
        check(m.method("scopeContext").declaringClass == m.type("scope"))
        signature("continuationContext", emptyList(), context, false)
        check(m.method("continuationContext").declaringClass == continuation)
        signature("resumeWith", listOf(any), void, false)
        check(m.method("resumeWith").declaringClass == continuation)
        check(m.constructor("failure").let { it.declaringClass == m.type("failure") &&
            it.parameterTypes.toList() == listOf(Throwable::class.java) })
        signature("invoke", listOf(any, any), any, false)
        check(m.method("invoke").declaringClass == function)
        signature("resume", listOf(any), any, false)
        check(m.method("resume").declaringClass == m.type("base") && !Modifier.isFinal(m.method("resume").modifiers))
        check(continuation.isAssignableFrom(m.type("base")) && m.type("base").isAssignableFrom(m.type("parent")))
        check(Modifier.isAbstract(m.type("parent").modifiers))
        check(m.constructor("parent").let { it.declaringClass == m.type("parent") &&
            it.parameterTypes.toList() == listOf(Integer.TYPE, continuation) && (Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)) })
        for (role in listOf("empty", "unit", "main")) check(m.field(role).let { Modifier.isStatic(it.modifiers) && it.type == m.type(role) })
        check(context.isAssignableFrom(m.type("empty")) && context.isAssignableFrom(m.type("main")))
        check(m.type("states").isEnum && m.type("states").enumConstants.map { (it as Enum<*>).name }.toSet() ==
            setOf("COROUTINE_SUSPENDED", "UNDECIDED", "RESUMED"))
        return Coroutines(loader, m)
    }

    internal class Members(values: Map<String, String>, loader: ClassLoader) {
        private val resolver = HostMembers()
        private val bound = values.mapValues { (key, value) -> when {
            key.startsWith("class:") -> resolver.describedClass(value, loader)
            key.startsWith("method:") -> resolver.describedMethod(value, loader)
            key.startsWith("constructor:") -> resolver.describedConstructor(value, loader)
            key.startsWith("field:") -> resolver.describedField(value, loader)
            else -> value
        } }
        fun type(key: String) = bound.getValue("class:$key") as Class<*>
        fun method(key: String) = bound.getValue("method:$key") as Method
        fun constructor(key: String) = bound.getValue("constructor:$key") as Constructor<*>
        fun field(key: String) = bound.getValue("field:$key") as Field
        fun value(key: String) = (bound.getValue("value:$key") as String).toInt()
    }

    fun restoreNavigation(values: Map<String, String>, loader: ClassLoader): Navigation {
        keys(values, "class" to "scope controller", "method" to "navigate pop controllerPop", "field" to "controller",
            "constructor" to "scope")
        val m = Members(values, loader)
        val scope = m.type("scope")
        val controller = m.field("controller")
        val navigate = m.method("navigate")
        val pop = m.method("pop")
        val controllerPop = m.method("controllerPop")
        check(controller.declaringClass == scope && controller.type == m.type("controller") && !Modifier.isStatic(controller.modifiers))
        check(navigate.declaringClass == scope && Modifier.isStatic(navigate.modifiers) && navigate.parameterTypes.toList() == listOf(scope, Any::class.java) && navigate.returnType == Void.TYPE)
        check(pop.declaringClass == scope && !Modifier.isStatic(pop.modifiers) && pop.parameterCount == 0 && pop.returnType == Void.TYPE)
        check(controllerPop.declaringClass == controller.type && !Modifier.isStatic(controllerPop.modifiers) && controllerPop.parameterCount == 0 && controllerPop.returnType == Boolean::class.javaPrimitiveType)
        val constructor = m.constructor("scope")
        check(constructor.declaringClass == scope && constructor.parameterTypes.toList() == listOf(controller.type))
        return Navigation(scope, controller, navigate, pop, controllerPop, constructor)
    }

    fun restoreHttp(values: Map<String, String>, loader: ClassLoader): Http {
        val cleartextKeys = values.keys.filter { it.startsWith("method:cleartext.") }.toSet()
        check(cleartextKeys.isNotEmpty() && cleartextKeys == cleartextKeys.indices.map { "method:cleartext.$it" }.toSet()) {
            "Incomplete host cleartext policy contract"
        }
        keys(values.filterKeys { it !in cleartextKeys }, "class" to "requestBuilder requestBody request client clientBuilder call response responseBody source media sink",
            "method" to "setMethod url header execute cancel source input responseLength headerValue bodyMedia media bodyLength bodyWrite sinkWrite",
            "constructor" to "requestBuilder request client clientBuilder call byteBody", "field" to "code responseBody headers request.method request.body request.headers canceled", "value" to "requestBuilderTag")
        val m = Members(values, loader)
        val cleartextPolicies = cleartextKeys.indices.map { m.method("cleartext.$it") }
        check(cleartextPolicies.distinct().size == cleartextPolicies.size && cleartextPolicies.all {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.toList() == listOf(String::class.java) && it.returnType == Boolean::class.javaPrimitiveType
        }) { "Invalid host cleartext policy methods" }
        val tag = values.getValue("value:requestBuilderTag").toByte()
        fun constructor(key: String, owner: String, vararg params: Class<*>) {
            check(m.constructor(key).declaringClass == m.type(owner) && m.constructor(key).parameterTypes.toList() == params.toList()) { "Invalid $key constructor" }
        }
        fun method(key: String, owner: Class<*>, result: Class<*>, static: Boolean = false, vararg params: Class<*>) {
            val method = m.method(key)
            check(method.declaringClass == owner && Modifier.isStatic(method.modifiers) == static &&
                method.parameterTypes.toList() == params.toList() && method.returnType == result) { "Invalid $key method" }
        }
        val int = Int::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        constructor("requestBuilder", "requestBuilder", Byte::class.javaPrimitiveType!!)
        constructor("request", "request", m.type("requestBuilder"))
        constructor("clientBuilder", "clientBuilder")
        constructor("client", "client", m.type("clientBuilder"))
        constructor("call", "call", m.type("client"), m.type("request"), Boolean::class.javaPrimitiveType!!)
        method("url", m.type("requestBuilder"), Void.TYPE, false, String::class.java)
        method("header", m.type("requestBuilder"), Void.TYPE, false, String::class.java, String::class.java)
        method("setMethod", m.type("requestBuilder"), Void.TYPE, false, String::class.java, m.type("requestBody"))
        method("execute", m.type("call"), m.type("response"))
        method("cancel", m.type("call"), Void.TYPE)
        val canceled = m.field("canceled")
        check(canceled.declaringClass == m.type("call") && canceled.type == Boolean::class.javaPrimitiveType &&
            !Modifier.isStatic(canceled.modifiers) && Modifier.isVolatile(canceled.modifiers))
        check(Modifier.isAbstract(m.type("requestBody").modifiers) && m.type("sink").isInterface)
        method("bodyWrite", m.type("requestBody"), Void.TYPE, false, m.type("sink"))
        method("bodyLength", m.type("requestBody"), long)
        method("bodyMedia", m.type("requestBody"), m.type("media"))
        check(listOf("bodyWrite", "bodyLength", "bodyMedia").none { Modifier.isFinal(m.method(it).modifiers) })
        method("sinkWrite", m.type("sink"), m.type("sink"), false, ByteArray::class.java, int, int)
        val byteBody = m.constructor("byteBody")
        check(byteBody.declaringClass.superclass == m.type("requestBody") && byteBody.parameterTypes.toList() == listOf(int, m.type("media"), ByteArray::class.java))
        check(Modifier.isStatic(m.method("media").modifiers) && m.method("media").returnType == m.type("media") &&
            m.method("media").parameterTypes.toList() == listOf(String::class.java))
        for ((key, type) in listOf("code" to int, "responseBody" to m.type("responseBody"))) {
            val field = m.field(key)
            check(field.declaringClass == m.type("response") && field.type == type && !Modifier.isStatic(field.modifiers))
        }
        val headers = m.field("headers")
        check(headers.declaringClass == m.type("response") && !Modifier.isStatic(headers.modifiers))
        method("headerValue", headers.type, String::class.java, false, String::class.java)
        for ((role, type) in listOf("method" to String::class.java, "body" to m.type("requestBody"), "headers" to headers.type)) {
            val field = m.field("request.$role")
            check(field.declaringClass == m.type("request") && !Modifier.isStatic(field.modifiers) && field.type.isAssignableFrom(type))
        }
        method("source", m.type("responseBody"), m.type("source"))
        method("responseLength", m.type("responseBody"), long)
        method("input", m.type("source"), java.io.InputStream::class.java)
        check(m.type("source").isInterface && java.io.Closeable::class.java.isAssignableFrom(m.type("response")) &&
            java.io.Closeable::class.java.isAssignableFrom(m.type("responseBody")))
        // A merged constructor can accept any byte yet initialize an unrelated sibling branch.
        // Exercise a local request before accepting cached tags; this performs no network I/O.
        val builder = m.constructor("requestBuilder").newInstance(tag)
        m.method("url").invoke(builder, "https://reamicro.invalid/runtime-contract")
        val initial = m.constructor("request").newInstance(builder)
        check(m.field("request.method").get(initial) == "GET" && m.field("request.body").get(initial) == null)
        m.method("header").invoke(builder, "X-ReaMicro-Contract", "validated")
        m.method("setMethod").invoke(builder, "GET", null)
        val request = m.constructor("request").newInstance(builder)
        check(m.type("request").isInstance(request) &&
            m.method("headerValue").invoke(m.field("request.headers").get(request), "X-ReaMicro-Contract") == "validated")
        val client = m.constructor("client").newInstance(m.constructor("clientBuilder").newInstance())
        val call = m.constructor("call").newInstance(client, request, false)
        check(!canceled.getBoolean(call))
        repeat(2) { m.method("cancel").invoke(call) }
        check(canceled.getBoolean(call)) { "Host HTTP cancellation did not update the call" }
        return Http(m, tag, cleartextPolicies)
    }

    fun restorePaging(values: Map<String, String>, loader: ClassLoader): Paging {
        keys(values, "class" to "continuation unit insert loadType loadStates notLoading page paging flow uiReceiver hintReceiver function0 collector",
            "method" to "collect emit cachedEvent", "constructor" to "insert loadStates page paging", "field" to "unit endReached flow pages items notLoading.0 notLoading.1")
        val m = Members(values, loader)
        check(m.type("loadType").isEnum)
        check(m.constructor("paging").parameterTypes.toList() == listOf(m.type("flow"), m.type("uiReceiver"), m.type("hintReceiver"), m.type("function0")))
        for (role in listOf("paging", "insert", "page", "loadStates")) check(m.constructor(role).declaringClass == m.type(role))
        check(m.constructor("page").parameterTypes.toList() == listOf(Int::class.javaPrimitiveType, List::class.java))
        val state = m.type("notLoading").superclass
        check(m.constructor("loadStates").parameterTypes.toList() == List(3) { state })
        check(m.constructor("insert").parameterTypes.toList() == listOf(m.type("loadType"), List::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, m.type("loadStates"), m.type("loadStates")))
        check(m.method("collect").parameterTypes.toList() == listOf(m.type("collector"), m.type("continuation")))
        check(m.method("emit").parameterTypes.toList() == listOf(Any::class.java, m.type("continuation")))
        for ((method, owner) in listOf("collect" to "flow", "emit" to "collector", "cachedEvent" to "function0")) {
            check(m.method(method).declaringClass == m.type(owner) && !Modifier.isStatic(m.method(method).modifiers) && m.method(method).returnType == Any::class.java)
        }
        check(m.method("cachedEvent").parameterCount == 0)
        check(listOf("flow", "collector", "uiReceiver", "hintReceiver", "function0", "continuation").all { m.type(it).isInterface })
        check(m.type("uiReceiver").declaredMethods.all { it.parameterCount == 0 && it.returnType == Void.TYPE })
        check(m.type("hintReceiver").declaredMethods.all { it.parameterCount == 1 && it.returnType == Void.TYPE })
        check(m.field("flow").declaringClass == m.type("paging") && m.field("flow").type == m.type("flow"))
        check(m.field("pages").declaringClass == m.type("insert") && m.field("items").declaringClass == m.type("page"))
        check(listOf("pages", "items").all { m.field(it).type == List::class.java })
        check(listOf("flow", "pages", "items", "endReached").none { Modifier.isStatic(m.field(it).modifiers) })
        check(m.field("endReached").declaringClass == state && m.field("endReached").type == Boolean::class.javaPrimitiveType)
        val constants = (0..1).map { m.field("notLoading.$it") }
        check(constants.toSet().size == 2 && constants.all {
            Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.declaringClass == m.type("notLoading") && it.type == m.type("notLoading")
        })
        check(m.field("unit").type == m.type("unit") && Modifier.isStatic(m.field("unit").modifiers))
        return Paging(m).apply { notLoading; refresh; unit }
    }

    fun restoreFiles(values: Map<String, String>, loader: ClassLoader): Files {
        keys(values, "class" to "wrapper platform", "constructor" to "wrapper platform", "field" to "file wrapper")
        val m = Members(values, loader)
        check(m.constructor("wrapper").declaringClass == m.type("wrapper") &&
            m.constructor("wrapper").parameterTypes.toList() == listOf(java.io.File::class.java))
        val wrapper = m.field("wrapper")
        check(wrapper.declaringClass == m.type("platform") && !Modifier.isStatic(wrapper.modifiers))
        check(wrapper.type == m.type("wrapper").superclass)
        check(m.constructor("platform").declaringClass == m.type("platform") && m.constructor("platform").parameterTypes.toList() == listOf(wrapper.type))
        val file = m.field("file")
        check(file.declaringClass == m.type("wrapper") && file.type == java.io.File::class.java && !Modifier.isStatic(file.modifiers))
        return Files(m.type("platform"), m.type("wrapper"), m.constructor("platform"), m.constructor("wrapper"), wrapper, file)
    }
}
