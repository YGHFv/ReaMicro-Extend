package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

/** 安装时冻结成员；重新扫描只更新下一次加载使用的磁盘缓存。 */
internal object HostSyncBindings {
    const val QUERY_KEY = "integration.webDavSync"
    val OPERATIONS = listOf("getContent", "head", "getBytes", "delete", "putBytes", "putProgress", "putFile", "getFile")
    private val CONFIG_PROPERTIES = listOf("provider", "endpoint", "bucket", "region", "accessKey", "secretKey", "prefix")
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null

    fun boundDescriptors(): Map<String, String>? = descriptors?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null
        descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false)
            current = restore(values, loader)
            descriptors = values.toMap()
        } }.onFailure { HookRuntime.logError("ReaMicro WebDAV sync contract unavailable", it) }
    }

    fun rescan(session: HostQuerySession.Session, base: Map<String, String> = HostAutoScanner.queriedDescriptors().orEmpty()): Map<String, String> =
        query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean,
        base: Map<String, String> = HostAutoScanner.queriedDescriptors().orEmpty()): Map<String, String> =
        session.query(QUERY_KEY, force,
            validate = { restore(it, session.loader) },
            find = { HostSyncQueries.query(it, session.apk, base) },
        ).values.toMap()
    class Contract internal constructor(private val members: HostRuntimeBindings.Members, private val values: Map<String, String>) {
        internal val configPositions = CONFIG_PROPERTIES.associateWith { values.getValue("value:config.$it").toInt() }
        fun type(key: String) = members.type(key)
        fun method(key: String) = members.method(key)
        fun constructor(key: String) = members.constructor(key)
        fun field(key: String) = members.field(key)
        val provider: Any = requireNotNull(type("provider").enumConstants).single { (it as Enum<*>).name == "S3" }
        fun config(config: Any, property: String): Any? = field("config.$property").get(config)
        fun preferenceKey(name: String): Any = constructor("preferenceKey").newInstance(name)
        fun number(name: String): Int = values.getValue("value:$name").toInt()
        fun text(name: String): String = values.getValue("value:$name")
        val configurationPreset: Any = requireNotNull(type("configurationPreset").enumConstants).single { (it as Enum<*>).name == "Other" }

        fun newConfig(endpoint: String, bucket: String, region: String, accessKey: String, secretKey: String, prefix: String): Any {
            val properties = mapOf("provider" to provider, "endpoint" to endpoint, "bucket" to bucket, "region" to region,
                "accessKey" to accessKey, "secretKey" to secretKey, "prefix" to prefix)
            val arguments = arrayOfNulls<Any>(CONFIG_PROPERTIES.size)
            for ((key, value) in properties) arguments[configPositions.getValue(key)] = value
            return constructor("config").newInstance(*arguments)
        }
    }

    internal fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val required = buildSet {
            "config provider transport transportApi content head file session datastore flow continuation function0 function2 function3 preferences preferenceKey composer"
                .split(' ').forEach { add("class:$it") }
            "config transport content head httpError session preferenceKey".split(' ').forEach { add("constructor:$it") }
            (OPERATIONS + "isConfigured toFile saveLocal getData first edit editInvoke progressInvoke preferenceGet preferenceRemove preferenceSet providerChoices otherProvider".split(' '))
                .forEach { add("method:$it") }
            "transportConfig saveSession datastore".split(' ').forEach { add("field:$it") }
            CONFIG_PROPERTIES.forEach { add("field:config.$it"); add("value:config.$it") }
            addAll(HostS3ConfigurationQueries.REQUIRED)
            add("class:retentionRevision")
            add("method:retentionEquals")
            add("method:retentionPlanner")
            add("method:retentionComparison")
            for (name in listOf("timestamp", "counter", "deviceId")) add("field:retentionRevision.$name")
            add("class:retentionBaselineBook")
            add("field:retentionBaselineBook.uuid")
            add("field:retentionBaselineBook.remoteRevision")
            add("value:retentionBaselineConstructors")
            val count = values["value:retentionBaselineConstructors"]?.toIntOrNull() ?: 0
            check(count in 1..4)
            repeat(count) { add("constructor:retentionBaselineBook$it") }
        }
        check(values.keys == required) { "Incomplete sync contract: missing=${required - values.keys}, extra=${values.keys - required}" }
        val m = Contract(HostRuntimeBindings.Members(values, loader), values)
        val string = String::class.java
        val any = Any::class.java
        val int = java.lang.Integer.TYPE
        val long = java.lang.Long.TYPE
        val config = m.type("config")
        val revision = m.type("retentionRevision")
        check(m.method("retentionEquals").let { it.declaringClass == revision &&
            it.parameterTypes.toList() == listOf(any) && it.returnType == java.lang.Boolean.TYPE })
        for ((name, expected) in mapOf("timestamp" to long, "counter" to long, "deviceId" to string))
            check(m.field("retentionRevision.$name").let { it.declaringClass == revision && it.type == expected &&
                !Modifier.isStatic(it.modifiers) })
        check(m.method("retentionPlanner").let { it.returnType == any && it.parameterTypes.toList() == listOf(any) })
        check(m.method("retentionComparison").let { Modifier.isStatic(it.modifiers) &&
            it.parameterTypes.toList() == listOf(any, any) && it.returnType == java.lang.Boolean.TYPE })
        check(m.field("retentionBaselineBook.uuid").type == string &&
            m.field("retentionBaselineBook.remoteRevision").type == revision)
        repeat(m.number("retentionBaselineConstructors")) {
            check(m.constructor("retentionBaselineBook$it").declaringClass == m.type("retentionBaselineBook"))
        }
        val provider = m.type("provider")
        val continuation = m.type("continuation")
        val transport = m.type("transport")
        val api = m.type("transportApi")
        fun instanceField(key: String, owner: Class<*>, value: Class<*>, assignable: Boolean = false) {
            val field = m.field(key)
            check(field.declaringClass == owner && !Modifier.isStatic(field.modifiers) &&
                (if (assignable) field.type.isAssignableFrom(value) else field.type == value)) { "Invalid sync field: $key" }
        }
        fun constructor(key: String, owner: Class<*>, parameters: List<Class<*>>) {
            val member = m.constructor(key)
            check(member.declaringClass == owner && member.parameterTypes.toList() == parameters) { "Invalid sync constructor: $key" }
        }
        fun suspendMethod(key: String, owner: Class<*>? = null, prefix: List<Class<*>>, static: Boolean) {
            val method = m.method(key)
            check(method.returnType == any && method.parameterCount == prefix.size + 1 &&
                method.parameterTypes.dropLast(1) == prefix && continuation.isAssignableFrom(method.parameterTypes.last()) &&
                Modifier.isStatic(method.modifiers) == static && (owner == null || method.declaringClass == owner)) { "Invalid sync suspend method: $key" }
        }
        check(provider.isEnum && api.isInterface && api.isAssignableFrom(transport))
        constructor("config", config, listOf(provider) + List(6) { string })
        check(m.configPositions.values.toSet() == (0..6).toSet() && m.configPositions.getValue("provider") == 0)
        instanceField("config.provider", config, provider)
        for (name in listOf("endpoint", "bucket", "region", "accessKey", "secretKey", "prefix")) instanceField("config.$name", config, string)
        val sample = m.newConfig("https://reamicro.invalid", "bucket", "region", "access", "secret", "prefix")
        for ((property, expected) in mapOf("provider" to m.provider, "endpoint" to "https://reamicro.invalid", "bucket" to "bucket",
            "region" to "region", "accessKey" to "access", "secretKey" to "secret", "prefix" to "prefix")) {
            check(m.config(sample, property) == expected) { "Sync config constructor does not populate $property" }
        }
        check(m.method("isConfigured").let { it.declaringClass == config && it.parameterCount == 0 &&
            it.returnType == java.lang.Boolean.TYPE && !Modifier.isStatic(it.modifiers) })
        check(m.constructor("transport").declaringClass == transport && m.constructor("transport").parameterTypes.count { it == config } == 1)
        instanceField("transportConfig", transport, config, assignable = true)
        val operations = OPERATIONS.map(m::method)
        val contracts = api.declaredMethods.filterNot { Modifier.isStatic(it.modifiers) }
        check(contracts.size == 8 && operations.distinct().size == 8)
        for (method in operations) {
            check(method.declaringClass == transport && !Modifier.isStatic(method.modifiers) && method.returnType == any &&
                method.parameterTypes.first() == string && continuation.isAssignableFrom(method.parameterTypes.last()))
            check(contracts.count { it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.contentEquals(method.parameterTypes) } == 1)
        }
        for (name in listOf("getContent", "head", "getBytes", "delete")) suspendMethod(name, transport, listOf(string), false)
        suspendMethod("putBytes", transport, listOf(string, ByteArray::class.java, string, string), false)
        val function3 = m.type("function3")
        val file = m.type("file")
        for (name in listOf("putProgress", "putFile")) {
            val method = m.method(name)
            check(method.parameterCount == 6 && method.parameterTypes.take(4) ==
                listOf(string, if (name == "putProgress") ByteArray::class.java else file, string, string) &&
                function3.isAssignableFrom(method.parameterTypes[4]))
        }
        check(m.method("getFile").let { it.parameterCount == 4 && it.parameterTypes[1] == file && function3.isAssignableFrom(it.parameterTypes[2]) })
        check(m.method("toFile").let { it.declaringClass == file && it.parameterCount == 0 &&
            it.returnType == java.io.File::class.java && !Modifier.isStatic(it.modifiers) })
        constructor("content", m.type("content"), listOf(string, ByteArray::class.java))
        constructor("head", m.type("head"), listOf(long, string))
        check(Throwable::class.java.isAssignableFrom(m.constructor("httpError").declaringClass) &&
            m.constructor("httpError").parameterTypes.toList() == listOf(int, string))
        val session = m.type("session")
        val datastore = m.type("datastore")
        constructor("session", session, listOf(datastore))
        instanceField("datastore", session, datastore)
        val save = m.method("saveLocal")
        suspendMethod("saveLocal", prefix = listOf(long, config), static = false)
        instanceField("saveSession", save.declaringClass, session)
        check(m.method("getData").let { it.declaringClass == datastore && it.parameterCount == 0 &&
            it.returnType == m.type("flow") && !Modifier.isStatic(it.modifiers) })
        suspendMethod("first", prefix = listOf(m.type("flow")), static = true)
        suspendMethod("edit", prefix = listOf(datastore, m.type("function2")), static = true)
        for ((key, owner, size) in listOf(Triple("editInvoke", "function2", 2), Triple("progressInvoke", "function3", 3))) {
            check(m.type(owner).isInterface)
            check(m.method(key).let { it.declaringClass == m.type(owner) && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.toList() == List(size) { any } && it.returnType == any })
        }
        val key = m.type("preferenceKey")
        constructor("preferenceKey", key, listOf(string))
        for ((name, parameters) in listOf("preferenceGet" to listOf(key), "preferenceRemove" to listOf(key), "preferenceSet" to listOf(key, any))) {
            check(m.method(name).let { it.declaringClass == m.type("preferences") && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.toList() == parameters && it.returnType == (if (name == "preferenceGet") any else Void.TYPE) })
        }
        check(m.method("otherProvider").let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.toList() == listOf(m.type("function0"), m.type("composer"), int) })
        val function0 = m.type("function0")
        val composer = m.type("composer")
        check(function0.isInterface && function0.declaredMethods.count {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == any
        } == 1)
        check(m.method("providerChoices").let {
            Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.count { parameter -> parameter == function0 } in 1..2 &&
                it.parameterTypes.count { parameter -> parameter == composer } == 1
        })
        check(m.type("configurationPreset").isEnum)
        check(m.constructor("configurationBody").parameterTypes.toList() == listOf(java.lang.Boolean.TYPE,
            m.type("configurationPreset"), m.type("configurationState"), m.type("configurationState"), m.type("configurationState")))
        check(m.constructor("configurationSaveBar").parameterTypes.let { p -> p.size in 4..5 &&
            p.take(3) == listOf(m.type("function0"), java.lang.Boolean.TYPE, java.lang.Boolean.TYPE) &&
            (p.size == 4 || p[4] == java.lang.Byte.TYPE) })
        for ((role, count) in listOf("configurationBody" to 3, "configurationSaveBar" to 2)) {
            val method = m.method(role)
            check(method.declaringClass == m.constructor(role).declaringClass && !Modifier.isStatic(method.modifiers) &&
                method.returnType == any && method.parameterTypes.toList() == List(count) { any })
        }
        check(m.constructor("configurationHeaderRestart").parameterTypes.toList() ==
            listOf(any, any, int, java.lang.Byte.TYPE))
        check(m.method("configurationHeaderRestart").let { method ->
            method.declaringClass == m.constructor("configurationHeaderRestart").declaringClass &&
                !Modifier.isStatic(method.modifiers) && method.returnType == any &&
                method.parameterTypes.toList() == List(2) { any }
        })
        check(m.method("configurationField").parameterTypes.toList() == listOf(string, string,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
            m.type("configurationFunction1"), composer, int, int))
        check(m.method("configurationImePadding").let { method ->
            Modifier.isStatic(method.modifiers) && method.parameterTypes.toList() == listOf(m.type("configurationModifier")) &&
                method.returnType == m.type("configurationModifier")
        })
        check(m.type("configurationFunction1").isInterface && m.type("configurationFunction1").declaredMethods.count {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(any) && it.returnType == any
        } == 1)
        check(m.method("configurationTopBar").let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterCount == 11 && it.parameterTypes[0] == string && it.parameterTypes[8] == composer &&
            it.parameterTypes.takeLast(2) == listOf(int, int) })
        check(m.method("configurationHeader").parameterTypes.toList() == listOf(m.type("configurationPreset"), m.type("configurationModifier"), composer, int))
        check(m.method("configurationCard").let { it.parameterCount == 4 && it.parameterTypes[0] == m.type("configurationPreset") && it.parameterTypes[2] == composer })
        check(m.method("configurationBackground").parameterTypes.toList() == listOf(m.type("configurationScheme")) && m.method("configurationBackground").returnType == java.lang.Long.TYPE)
        check(m.method("configurationProviderGap").let { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && method.parameterCount == 3 &&
                method.parameterTypes[0] == int && method.parameterTypes[2] == composer &&
                m.type("configurationModifier").isAssignableFrom(method.parameterTypes[1])
        })
        check(m.number("configurationProviderGap") in 1..48)
        return m
    }
}
