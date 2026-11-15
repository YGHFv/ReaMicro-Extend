package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Native contracts are derived from behavior and relationships; merged branch tags come from actual call sites. */
internal object HostRuntimeQueries {
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")
    private fun anchor(bridge: DexKitBridge, vararg strings: String) = bridge.findMethod(
        FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))
    private fun ClassData.constructor(vararg parameters: String) = methods.filter {
        it.isConstructor && it.paramTypeNames == parameters.toList()
    }.one("$name constructor")

    fun navigation(bridge: DexKitBridge): Map<String, String> {
        val controller = requireNotNull(anchor(bridge, "No destination with route ").one("navigation controller").declaredClass)
        val controllerPop = controller.methods.filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean"
        }.one("navigation controller pop")
        val navigate = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramCount(2).returnType("void"))).filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames[0] == it.className && it.paramTypeNames[1] == "java.lang.Object" &&
                it.declaredClass?.fields?.filter { f -> !Modifier.isStatic(f.modifiers) }?.let { fields ->
                    fields.size == 1 && fields.single().typeName == controller.name
                } == true
        }.one("navigation scope navigate")
        val scope = requireNotNull(navigate.declaredClass)
        val pop = scope.methods.filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "void" &&
                it.invokes.any { m -> m.descriptor == controllerPop.descriptor } &&
                it.invokes.any { m -> m.className == controller.name && m.paramTypeNames ==
                    listOf("java.lang.Object", "kotlin.jvm.functions.Function1") }
        }.one("navigation scope pop with start-route fallback")
        return linkedMapOf(
            "class:scope" to scope.descriptor, "class:controller" to controller.descriptor,
            "method:navigate" to navigate.descriptor, "method:pop" to pop.descriptor,
            "method:controllerPop" to controllerPop.descriptor,
            "constructor:scope" to scope.constructor(controller.name).descriptor,
            "field:controller" to scope.fields.single { !Modifier.isStatic(it.modifiers) }.descriptor,
        )
    }

    fun http(bridge: DexKitBridge, apk: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun cls(key: String, type: ClassData): ClassData = type.also { out["class:$key"] = it.descriptor }
        fun method(key: String, value: MethodData): MethodData = value.also {
            out["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor
        }
        val setMethod = method("setMethod", anchor(bridge, "method.isEmpty() == true", " must have a request body.")
            .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 2 && it.paramTypeNames[0] == "java.lang.String" }
            .one("request method setter"))
        val builder = cls("requestBuilder", requireNotNull(setMethod.declaredClass))
        val body = cls("requestBody", requireNotNull(setMethod.paramTypes[1]))
        val url = method("url", builder.methods.filter { it.paramTypeNames == listOf("java.lang.String") &&
            it.usingStrings.containsAll(listOf("ws:", "http:", "wss:", "https:")) }.one("request URL setter"))
        method("header", builder.methods.filter { !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(2) { "java.lang.String" } && it.returnTypeName == "void" }.one("request header setter"))
        val builderConstructor = method("requestBuilder", builder.constructor("byte"))
        out["value:requestBuilderTag"] = constructorTag(apk, builderConstructor, url).toString()
        val requestConstructor = method("request", anchor(bridge, "url == null")
            .filter { it.isConstructor && it.paramTypeNames == listOf(builder.name) }.one("request construction"))
        val request = cls("request", requireNotNull(requestConstructor.declaredClass))
        val client = cls("client", requireNotNull(anchor(bridge, "No System TLS: ")
            .filter { it.isConstructor && it.paramCount == 1 }.one("HTTP client constructor").declaredClass))
        val clientConstructor = method("client", client.methods.filter { it.isConstructor && it.paramCount == 1 }.one("client builder constructor"))
        val clientBuilder = cls("clientBuilder", requireNotNull(clientConstructor.paramTypes.single()))
        method("clientBuilder", clientBuilder.constructor())
        val execute = method("execute", anchor(bridge, "Already Executed").filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName != "void" &&
                it.declaredClass?.methods?.any { c -> c.isConstructor && c.paramTypeNames == listOf(client.name, request.name, "boolean") } == true
        }.one("HTTP call execute"))
        val call = cls("call", requireNotNull(execute.declaredClass))
        method("call", call.constructor(client.name, request.name, "boolean"))
        val response = cls("response", requireNotNull(execute.returnType))
        check(response.methods.any { it.usingStrings.contains("Response{protocol=") })
        out["field:code"] = response.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "int" }.descriptor
        val responseBodyField = response.fields.single { f -> !Modifier.isStatic(f.modifiers) && f.type?.let { t ->
            Modifier.isAbstract(t.modifiers) && t.interfaces.any { it.name == "java.io.Closeable" }
        } == true }
        out["field:responseBody"] = responseBodyField.descriptor
        val responseBody = cls("responseBody", requireNotNull(responseBodyField.type))
        val source = method("source", responseBody.methods.filter { it.isMethod && Modifier.isAbstract(it.modifiers) &&
            it.paramCount == 0 && it.returnType?.let { t -> Modifier.isInterface(t.modifiers) &&
                t.methods.any { m -> m.paramCount == 0 && m.returnTypeName == "java.io.InputStream" } } == true
        }.one("response source"))
        val sourceType = cls("source", requireNotNull(source.returnType))
        method("input", sourceType.methods.filter { it.paramCount == 0 && it.returnTypeName == "java.io.InputStream" }.one("source input stream"))
        method("responseLength", responseBody.methods.filter { it.isMethod && it.paramCount == 0 && it.returnTypeName == "long" }.one("response length"))
        val headersField = response.fields.single { f -> !Modifier.isStatic(f.modifiers) && f.type?.methods?.any {
            it.isConstructor && it.paramTypeNames == listOf("java.lang.String[]")
        } == true }
        out["field:headers"] = headersField.descriptor
        val flow = HostDexCalls(apk)
        val canceled = call.fields.single { field ->
            !Modifier.isStatic(field.modifiers) && Modifier.isVolatile(field.modifiers) &&
                field.typeName == "boolean" && field.readers.any { reader ->
                    reader.className == call.name && reader.usingStrings.contains("Canceled")
                }
        }
        val cancel = canceled.writers.filter { target ->
            target.className == call.name && target.isMethod && !Modifier.isStatic(target.modifiers) &&
                target.paramCount == 0 && target.returnTypeName == "void" &&
                flow.writes(target.descriptor).any { write ->
                    write.field == canceled.descriptor && write.receiver == HostDexCalls.Parameter(0) &&
                        write.value == HostDexCalls.Literal(1)
                }
        }.one("HTTP call cancellation")
        check(canceled.writers.map { it.descriptor }.distinct() == listOf(cancel.descriptor)) {
            "HTTP cancellation flag has unrelated writers"
        }
        method("cancel", cancel)
        out["field:canceled"] = canceled.descriptor
        val requestWrites = flow.writes(requestConstructor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
        for ((role, parameter) in listOf("method" to 1, "body" to 2)) {
            val source = flow.writes(setMethod.descriptor).single { it.receiver == HostDexCalls.Parameter(0) &&
                it.value == HostDexCalls.Parameter(parameter) }.field
            out["field:request.$role"] = requestWrites.single { it.value == HostDexCalls.Field(source) }.field
        }
        out["field:request.headers"] = requestWrites.single { write ->
            val call = write.value as? HostDexCalls.Call
            call != null && bridge.getMethodData(call.descriptor)?.returnTypeName == headersField.typeName
        }.field
        method("headerValue", requireNotNull(headersField.type).methods.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == "java.lang.String"
        }.one("response header value"))
        val mediaMethod = method("bodyMedia", body.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName !in setOf("void", "boolean", "int", "long")
        }.one("request content type"))
        val media = cls("media", requireNotNull(mediaMethod.returnType))
        method("media", anchor(bridge, "No subtype found for: \"").filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == media.name
        }.one("media type parse"))
        method("bodyLength", body.methods.filter { it.isMethod && it.paramCount == 0 && it.returnTypeName == "long" }.one("request body length"))
        val writer = method("bodyWrite", body.methods.filter { it.isMethod && Modifier.isAbstract(it.modifiers) &&
            it.paramCount == 1 && it.returnTypeName == "void" && it.paramTypes.single().let { Modifier.isInterface(it.modifiers) }
        }.one("request body write"))
        val sink = cls("sink", writer.paramTypes.single())
        method("sinkWrite", sink.methods.filter { it.paramTypeNames == listOf("byte[]", "int", "int") && it.returnTypeName == sink.name }.one("buffered sink write"))
        method("byteBody", bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .name("<init>").paramTypes("int", media.name, "byte[]"))).filter { it.declaredClass?.superClass?.name == body.name }.one("byte request body"))
        val cleartext = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes("java.lang.String").returnType("boolean")
            .addInvoke("Landroid/security/NetworkSecurityPolicy;->isCleartextTrafficPermitted(Ljava/lang/String;)Z")))
            .filter { it.isMethod && !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) }
            .distinctBy { it.descriptor }.sortedBy { it.descriptor }
        check(cleartext.isNotEmpty()) { "Host cleartext policy delegates unavailable" }
        cleartext.forEachIndexed { index, target -> method("cleartext.$index", target) }
        return out
    }

    fun paging(bridge: DexKitBridge): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun cls(key: String, type: ClassData): ClassData = type.also { out["class:$key"] = it.descriptor }
        fun method(key: String, value: MethodData): MethodData = value.also { out["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor }
        val continuation = anchor(bridge, "create(Continuation) has not been overridden").one("continuation").paramTypes.single()
        cls("continuation", continuation)
        val unit = cls("unit", requireNotNull(anchor(bridge, "kotlin.Unit").filter {
            it.name == "toString" && it.paramCount == 0 && it.returnTypeName == "java.lang.String"
        }.one("Kotlin Unit").declaredClass))
        out["field:unit"] = unit.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == unit.name }.descriptor
        val insertCtor = method("insert", anchor(bridge, "Cannot create a REFRESH Insert event with no TransformablePages as this could permanently stall pagination. Note that this check does not prevent empty LoadResults and is instead usually an indication of an internal error in Paging itself.")
            .filter { it.isConstructor && it.paramCount == 6 }.one("paging refresh insert"))
        cls("insert", requireNotNull(insertCtor.declaredClass))
        val loadType = cls("loadType", insertCtor.paramTypes[0])
        check(loadType.superClass?.name == "java.lang.Enum")
        val states = cls("loadStates", insertCtor.paramTypes[4])
        val notLoading = cls("notLoading", requireNotNull(anchor(bridge, "NotLoading(endOfPaginationReached=").one("not loading").declaredClass))
        val statesByEnd = notLoading.fields.filter { Modifier.isStatic(it.modifiers) && it.typeName == notLoading.name }
        check(statesByEnd.size == 2 && statesByEnd.all { Modifier.isFinal(it.modifiers) })
        statesByEnd.forEachIndexed { index, field -> out["field:notLoading.$index"] = field.descriptor }
        val state = requireNotNull(notLoading.superClass)
        method("loadStates", states.constructor(state.name, state.name, state.name))
        out["field:endReached"] = state.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "boolean" }.descriptor
        val page = cls("page", requireNotNull(anchor(bridge, "TransformablePage(originalPageOffsets=").one("transformable page").declaredClass))
        method("page", page.constructor("int", "java.util.List"))
        val candidates = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().name("<init>").paramCount(4))).filter { ctor ->
            val p = ctor.paramTypes
            p.all { Modifier.isInterface(it.modifiers) } &&
                p[0].methods.any { it.paramCount == 2 && it.paramTypeNames[1] == continuation.name && it.returnTypeName == "java.lang.Object" } &&
                p[1].methods.size == 2 && p[1].methods.all { it.paramCount == 0 && it.returnTypeName == "void" } &&
                p[2].methods.size == 1 && p[2].methods.single().paramCount == 1 && p[2].methods.single().returnTypeName == "void" &&
                p[3].methods.size == 1 && p[3].methods.single().paramCount == 0 && p[3].methods.single().returnTypeName == "java.lang.Object"
        }
        val pagingCtor = method("paging", candidates.one("paging data"))
        cls("paging", requireNotNull(pagingCtor.declaredClass))
        val flow = cls("flow", pagingCtor.paramTypes[0])
        cls("uiReceiver", pagingCtor.paramTypes[1]); cls("hintReceiver", pagingCtor.paramTypes[2]); cls("function0", pagingCtor.paramTypes[3])
        val collect = method("collect", flow.methods.filter { it.paramCount == 2 && it.paramTypeNames[1] == continuation.name }.one("flow collect"))
        val collector = cls("collector", collect.paramTypes[0])
        method("emit", collector.methods.filter { it.paramTypeNames == listOf("java.lang.Object", continuation.name) && it.returnTypeName == "java.lang.Object" }.one("collector emit"))
        method("cachedEvent", pagingCtor.paramTypes[3].methods.single())
        out["field:flow"] = requireNotNull(pagingCtor.declaredClass).fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == flow.name }.descriptor
        out["field:pages"] = requireNotNull(insertCtor.declaredClass).fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.util.List" }.descriptor
        out["field:items"] = page.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.util.List" }.descriptor
        return out
    }

    fun files(bridge: DexKitBridge): Map<String, String> {
        val wrapper = requireNotNull(anchor(bridge, "FileWrapper(file=").one("file wrapper").declaredClass)
        val importFile = anchor(bridge, "pending-imports").filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 1 && it.returnTypeName == "void" &&
                it.declaredClass?.methods?.any { m -> m.usingStrings.containsAll(listOf(";UPDATED_BOOK", "BOOK_ID=")) } == true
        }.one("pending import file")
        val platform = importFile.paramTypes.single()
        val base = requireNotNull(wrapper.superClass)
        val constructor = platform.constructor(base.name)
        return linkedMapOf(
            "class:wrapper" to wrapper.descriptor,
            "class:platform" to platform.descriptor,
            "constructor:wrapper" to wrapper.constructor("java.io.File").descriptor,
            "constructor:platform" to constructor.descriptor,
            "field:file" to wrapper.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.io.File" }.descriptor,
            "field:wrapper" to platform.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == base.name }.descriptor,
        )
    }

    /** Read constants passed by real URL-building call sites; never guess a merged constructor branch. */
    private fun constructorTag(apk: String, constructor: MethodData, urlSetter: MethodData): Byte {
        val dataflow = HostDexCalls(apk)
        val tags = urlSetter.callers.flatMap { dataflow.calls(it.descriptor) }.filter { it.descriptor == constructor.descriptor }
            .mapNotNull { (it.arguments.getOrNull(1) as? HostDexCalls.Literal)?.value }
            .filter { it in Byte.MIN_VALUE..Byte.MAX_VALUE }.map { it.toByte() }.toSet()
        return tags.singleOrNull() ?: error("Request constructor has no unique proven tag: $tags")
    }
}
