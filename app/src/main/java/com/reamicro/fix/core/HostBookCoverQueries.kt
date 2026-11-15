package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Cover upload follows the host's own request, transport and envelope call chain. */
internal object HostBookCoverQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: missing/ambiguous ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun type(key: String, descriptor: String): ClassData = requireNotNull(bridge.getClassData(descriptor)).also {
            out["class:$key"] = it.descriptor
        }
        fun logical(key: String, name: String) = type(key, base.getValue("class:$name"))
        fun method(key: String, m: MethodData) = m.also {
            out["${if (m.isConstructor) "constructor" else "method"}:$key"] = m.descriptor
        }
        fun anchor(text: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(text)))
            .one(text)
        fun anchoredType(key: String, text: String) = type(key, requireNotNull(anchor(text).declaredClass).descriptor)
        fun field(key: String, owner: ClassData, target: ClassData) {
            out["field:$key"] = owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == target.name }.descriptor
        }
        val book = logical("book", P + "data.db.entity.Book")
        val details = logical("details", P + "ui.book.BookDetailsViewModel")
        val state = logical("state", P + "ui.book.BookDetailsUiState")
        val overview = logical("overview", P + "ui.home.BookOverviewViewModel")
        val overviewState = logical("overviewState", P + "ui.home.BookOverviewUiState")
        val repository = logical("repository", P + "repository.BookRepository")
        val shelf = logical("shelf", P + "repository.BookshelfRepository")
        val dao = logical("dao", P + "data.db.dao.BookDao")
        logical("route", P + "Route\$BookDetail")
        val publisher = logical("publisher", "ReaMicroHost.BookPublisherRecord")
        val cloud = logical("cloud", "ReaMicroHost.CloudBookInfo")
        val request = logical("request", P + "data.res.book.PostUserBookReq")
        val response = anchoredType("response", "PostUserBookRes(id=")
        val envelope = anchoredType("envelope", "Envelope(code=")
        val failure = anchoredType("failure", "Failure(")
        out["field:failure"] = failure.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Throwable"
        }.descriptor
        val screen = requireNotNull(bridge.getMethodData(base.getValue("method:${P}ui.book.BookDetailsScreenKt#BookDetailsScreen")))
        val args = type("args", screen.paramTypes[1].descriptor)
        method("details", details.methods.filter { it.isConstructor && it.paramTypeNames.take(2) == listOf(args.name, repository.name) }.one("details constructor"))
        method("overview", overview.methods.filter { it.isConstructor && it.paramTypeNames.take(2) == listOf(shelf.name, repository.name) &&
            it.paramTypeNames.last() == "long" }.one("overview constructor"))
        field("args", details, args); field("detailsRepository", details, repository)
        field("overviewRepository", overview, repository); field("shelf", repository, shelf); field("dao", shelf, dao)
        val flow = HostDexCalls(apk)
        val overviewConstructor = requireNotNull(bridge.getMethodData(out.getValue("constructor:overview")))
        out["field:overviewId"] = flow.writes(overviewConstructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(overviewConstructor.paramCount) &&
                it.field.endsWith(":J")
        }.single().field
        val applyMethods = overview.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(book.name) && it.returnTypeName == "void" }
        method("applyBook", applyMethods.filter { m -> m.invokes.any { i -> i.descriptor != m.descriptor && applyMethods.any { it.descriptor == i.descriptor } } }
            .one("apply overview book"))
        method("currentState", requireNotNull(bridge.getMethodData(base.getValue("method:${P}arch.MVIViewModel#getCurrentState"))))
        method("cleared", requireNotNull(bridge.getMethodData(base.getValue("method:androidx.lifecycle.ViewModel#onCleared"))))
        method("cloudInfo", requireNotNull(bridge.getMethodData(base.getValue("method:${P}repository.BookRepository#getBookCloudInfo-0E7RQCE"))))
        val overviewSerializer = requireNotNull(anchor("app.zhendong.reamicro.Route.BookOverview").declaredClass)
        val overviewRoute = overviewSerializer.methods.flatMap { it.invokes }.filter {
            it.isConstructor && it.paramTypeNames == listOf("long", "int") &&
                it.declaredClass?.methods.orEmpty().any { m -> "BookOverview(bookId=" in m.usingStrings }
        }.one("overview serializer route")
        type("overviewRoute", requireNotNull(overviewRoute.declaredClass).descriptor)
        val update = shelf.methods.filter { m -> !Modifier.isStatic(m.modifiers) && m.paramCount == 2 &&
            m.paramTypeNames[0] == book.name && m.invokes.any { i -> i.className == dao.name && i.paramCount == 2 && i.paramTypeNames[0] == book.name }
        }.one("book update DAO relationship")
        method("find", update.invokes.filter { it.className == dao.name && it.paramCount == 2 && it.paramTypeNames[0] == "long" &&
            it.returnTypeName == "java.lang.Object" }.one("book by local ID"))
        val detailRoute = requireNotNull(bridge.getClassData(out.getValue("class:route")))
        val routeConstructor = detailRoute.methods.filter { it.isConstructor && it.paramTypeNames == listOf("long", "boolean") }.one("details route constructor")
        val click = method("sourceClick", routeConstructor.callers.filter { it.paramCount == 0 && it.returnTypeName == "java.lang.Object" &&
            !Modifier.isStatic(it.modifiers) && it.declaredClass?.fields?.count { f -> !Modifier.isStatic(f.modifiers) && f.typeName == book.name } == 1
        }.one("book source callback"))
        field("sourceBook", requireNotNull(click.declaredClass), book)
        val sql = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("AND title LIKE", "SELECT * FROM books")))
            .filter { it.usingStrings.any { s -> s.contains("AND title LIKE") && s.contains("SELECT * FROM books") } }.one("local title search SQL")
        method("search", shelf.methods.filter { m -> !Modifier.isStatic(m.modifiers) && m.paramCount == 2 &&
            m.paramTypeNames[0] == "java.lang.String" && m.returnTypeName == "java.lang.Object" &&
            m.invokes.any { it.isConstructor && it.className == sql.className } &&
            m.invokes.any { it.isConstructor && it.className == failure.name }
        }.one("local books Result search"))
        val requestConstructor = method("request", request.methods.filter { it.isConstructor && it.paramTypeNames == List(5) { "java.lang.String" } }.one("cover request"))
        val upload = repository.methods.filter { m -> m.invokes.any { it.descriptor == requestConstructor.descriptor } }.one("host book upload")
        val post = method("post", upload.invokes.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 2 &&
            it.paramTypeNames[0] == request.name && it.returnTypeName == "java.lang.Object" }.one("post user book"))
        val api = type("api", requireNotNull(post.declaredClass).descriptor)
        field("api", repository, api)
        method("data", upload.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(envelope.name) &&
            it.returnTypeName == "java.lang.Object" }.one("upload envelope data or throw"))
        out.putAll(HostRecordQueries.query(apk, mapOf("class:book" to book.descriptor, "class:state" to state.descriptor,
            "class:overviewState" to overviewState.descriptor, "class:args" to args.descriptor, "class:cloud" to cloud.descriptor,
            "class:publisher" to publisher.descriptor, "class:request" to request.descriptor, "class:response" to response.descriptor))
            .filterKeys { it.startsWith("field:") })
        val writes = flow.writes(requestConstructor.descriptor)
        for (property in listOf("name", "author", "md5", "cover", "coverMd5")) {
            val write = writes.filter { it.field == out.getValue("field:request#$property") && it.receiver == HostDexCalls.Parameter(0) }.single()
            val parameter = write.value as? HostDexCalls.Parameter ?: error("Request property has no constructor input: $property")
            out["value:request.$property"] = (parameter.index - 1).toString()
        }
        queryAccountBoundRequest(bridge, flow, out, post)
        return out
    }
    /** DefaultRequest reads the mutable Session token after the native request was configured. */
    private fun queryAccountBoundRequest(
        bridge: DexKitBridge,
        flow: HostDexCalls,
        out: MutableMap<String, String>,
        post: MethodData,
    ) {
        fun type(key: String, descriptor: String): ClassData =
            requireNotNull(bridge.getClassData(descriptor)).also { out["class:$key"] = it.descriptor }
        fun method(key: String, value: MethodData) =
            value.also { out["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor }
        fun anchor(vararg strings: String) =
            bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))
                .one(strings.joinToString())
        val configure = method("configureRequest", anchor("rest/book/post-user-book"))
        check(!Modifier.isStatic(configure.modifiers) && configure.paramTypeNames == listOf("java.lang.Object"))
        val configuration = type("requestConfiguration", requireNotNull(configure.declaredClass).descriptor)
        val create = method("requestConfiguration", post.invokes.filter {
            it.isConstructor && it.className == configuration.name
        }.one("native post request configuration"))
        val arguments = flow.calls(post.descriptor).filter { it.descriptor == create.descriptor }
            .distinct().single().arguments
        val writes = flow.writes(create.descriptor)
        val requestCapture = configuration.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object"
        }
        out["field:configuration.request"] = requestCapture.descriptor
        val tag = configuration.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName in setOf("byte", "int")
        }
        out["field:configuration.tag"] = tag.descriptor
        val tagWrite = writes.single { it.field == tag.descriptor && it.receiver == HostDexCalls.Parameter(0) }
        val tagParameter = tagWrite.value as? HostDexCalls.Parameter ?: error("Native post tag has no constructor input")
        val tagValue = arguments[tagParameter.index] as? HostDexCalls.Literal ?: error("Native post tag is not literal")
        out["value:configuration.tag"] = tagValue.value.toString()
        val builderCasts = flow.casts(configure.descriptor).filter { it.value == HostDexCalls.Parameter(1) }
            .map { it.type }.distinct().single()
        val builder = type("nativeRequestBuilder", builderCasts)
        method("nativeRequestBuilder", builder.methods.filter {
            it.isConstructor && it.paramCount == 0
        }.one("native Ktor request builder"))
        out["field:nativeRequest.body"] = builder.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Object"
        }.descriptor
        val auth = anchor("Authorization", "platform", "version")
        val append = method("appendHeader", flow.calls(auth.descriptor).filter {
            it.arguments.contains(HostDexCalls.Text("Authorization"))
        }.mapNotNull { bridge.getMethodData(it.descriptor) }.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.String" } &&
                it.returnTypeName == "void"
        }.one("native Authorization header append"))
        val headers = type("nativeHeaders", builder.fields.mapNotNull { field ->
            if (Modifier.isStatic(field.modifiers) || field.typeName.startsWith("java.")) null
            else bridge.getClassData("L" + field.typeName.replace('.', '/') + ";")
        }.filter {
            generateSequence(it) { parent -> parent.superClass }.any { parent ->
                parent.descriptor == append.declaredClass?.descriptor
            }
        }.distinctBy { it.descriptor }.single().descriptor)
        out["field:nativeRequest.headers"] = builder.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == headers.name
        }.descriptor
        val defaults = method("defaultRequest", anchor("Applied DefaultRequest to ", ". New url: "))
        val defaultCallback = type("defaultRequestCallback", requireNotNull(defaults.declaredClass).descriptor)
        val defaultTag = defaultCallback.fields.single {
            !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                it.typeName in setOf("int", "byte")
        }
        val defaultTags = flow.switchKeys(defaults.descriptor).filter { value ->
            flow.callsWithFields(defaults.descriptor, mapOf(defaultTag.descriptor to value)).any {
                it.arguments.contains(HostDexCalls.Text("Applied DefaultRequest to "))
            }
        }
        out["field:defaultRequest.tag"] = defaultTag.descriptor
        out["value:defaultRequest.tag"] = defaultTags.single().toString()
        val defaultCasts = flow.castsWithFields(defaults.descriptor,
            mapOf(defaultTag.descriptor to defaultTags.single()))
        val headerHierarchy = generateSequence(headers) { it.superClass }.map { it.descriptor }.toSet()
        val headerValues = method("headerValues",
            flow.callsWithFields(defaults.descriptor, mapOf(defaultTag.descriptor to defaultTags.single()))
                .mapNotNull { bridge.getMethodData(it.descriptor) }.filter {
                    !Modifier.isStatic(it.modifiers) && it.declaredClass?.descriptor in headerHierarchy &&
                        it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == "java.util.List"
                }.one("native default request header values"))
        val headerMap = flow.calls(headerValues.descriptor).filter {
            it.descriptor.startsWith("Ljava/util/Map;->get(")
        }.map { it.arguments.first() }.filterIsInstance<HostDexCalls.Field>().distinct().single()
        out["field:nativeHeaders.values"] = headerMap.descriptor
        val origins = defaultCasts.filter { it.type == builder.descriptor }
            .map { it.value }.distinct().single()
        val contextDescriptor: String
        val contextCapture: HostDexCalls.Field
        when (origins) {
            is HostDexCalls.Field -> {
                val field = requireNotNull(bridge.getFieldData(origins.descriptor))
                contextDescriptor = requireNotNull(field.declaredClass).descriptor
                out["field:defaultRequest.request"] = field.descriptor
                out["value:defaultRequest.access"] = "field"
                contextCapture = defaultCasts.filter { it.type == contextDescriptor }
                    .map { it.value }.filterIsInstance<HostDexCalls.Field>().distinct().single()
            }
            is HostDexCalls.Call -> {
                val access = method("defaultRequest.request", requireNotNull(bridge.getMethodData(origins.descriptor)))
                contextDescriptor = requireNotNull(access.declaredClass).descriptor
                out["value:defaultRequest.access"] = "method"
                contextCapture = origins.arguments.single() as? HostDexCalls.Field
                    ?: error("Native default request context is not captured")
            }
            else -> error("Unsupported native default request origin")
        }
        type("defaultRequestContext", contextDescriptor)
        out["field:defaultRequest.context"] = contextCapture.descriptor
    }
}
