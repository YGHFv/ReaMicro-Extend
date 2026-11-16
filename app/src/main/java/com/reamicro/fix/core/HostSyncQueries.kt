package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** 同步契约来自配置属性、请求行为和调用关系；不保存版本短名。 */
internal object HostSyncQueries {
    private const val SESSION = "app.zhendong.reamicro.repository.core.Session"
    private const val PREFERENCE_KEY = "ReaMicroHost.PreferenceKey"

    private fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")

    private fun anchor(bridge: DexKitBridge, vararg strings: String) = bridge.findMethod(
        FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))

    private fun ClassData.constructor(vararg parameters: String) = methods.filter {
        it.isConstructor && it.paramTypeNames == parameters.toList()
    }.one("$name constructor")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun type(key: String, value: ClassData): ClassData = value.also { out["class:$key"] = it.descriptor }
        fun method(key: String, value: MethodData): MethodData = value.also {
            out["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor
        }
        fun known(logical: String): ClassData = requireNotNull(bridge.getClassData(
            base["class:$logical"] ?: HostSymbols.resolveClass(logical))) { "Missing queried class: $logical" }
        val config = type("config", requireNotNull(anchor(bridge, "S3SyncConfig(provider=").one("sync config").declaredClass))
        val revision = type("retentionRevision", requireNotNull(anchor(bridge, "S3SyncRevision(timestamp=")
            .one("sync revision").declaredClass))
        val revisionProperties = HostRecordQueries.query(apk, mapOf("class:retentionRevision" to revision.descriptor))
        for (name in listOf("timestamp", "counter", "deviceId"))
            out["field:retentionRevision.$name"] = revisionProperties.getValue("field:retentionRevision#$name")
        method("retentionEquals", revision.methods.filter { it.name == "equals" &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "boolean" }.one("sync revision equality"))
        val planner = method("retentionPlanner", anchor(bridge, "S3 queue: local=").one("sync planner"))
        method("retentionComparison", planner.invokes.filter { candidate ->
            Modifier.isStatic(candidate.modifiers) && candidate.paramTypeNames == List(2) { "java.lang.Object" } &&
                candidate.returnTypeName == "boolean" && candidate.invokes.any {
                    it.declaredClassName == "java.lang.Object" && it.name == "equals"
                }
        }.one("sync nullable revision comparison"))
        val baselineBook = type("retentionBaselineBook", requireNotNull(anchor(bridge, "S3SyncBaselineBook(uuid=")
            .one("sync baseline book").declaredClass))
        val baselineProperties = HostRecordQueries.query(apk, mapOf("class:retentionBaselineBook" to baselineBook.descriptor))
        for (name in listOf("uuid", "remoteRevision"))
            out["field:retentionBaselineBook.$name"] = baselineProperties.getValue("field:retentionBaselineBook#$name")
        baselineBook.methods.filter { it.isConstructor }.forEachIndexed { index, member ->
            method("retentionBaselineBook$index", member)
        }
        out["value:retentionBaselineConstructors"] = baselineBook.methods.count { it.isConstructor }.toString()
        val calls = HostDexCalls(apk)
        val configConstructor = method("config", config.methods.filter {
            it.isConstructor && it.paramCount == 7 && it.paramTypes[0]?.let { p -> (p.modifiers and 0x4000) != 0 } == true &&
                it.paramTypeNames.drop(1) == List(6) { "java.lang.String" }
        }.one("sync config constructor"))
        type("provider", requireNotNull(configConstructor.paramTypes[0]))
        method("isConfigured", config.methods.filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean"
        }.one("sync configured check"))
        val properties = HostRecordQueries.query(apk, mapOf("class:config" to config.descriptor))
        val configWrites = calls.writes(configConstructor.descriptor)
        for (name in listOf("provider", "endpoint", "bucket", "region", "accessKey", "secretKey", "prefix")) {
            val field = properties.getValue("field:config#$name")
            out["field:config.$name"] = field
            val parameter = configWrites.filter { it.field == field && it.receiver == HostDexCalls.Parameter(0) }
                .map { requireNotNull(it.value as? HostDexCalls.Parameter).index - 1 }.distinct().single()
            out["value:config.$name"] = parameter.toString()
        }

        val transport = type("transport", requireNotNull(anchor(bridge, "S3 request: ", "S3Sync")
            .one("S3 transport request").declaredClass))
        val api = type("transportApi", transport.interfaces.filter { it.methods.count { m -> m.isMethod && !Modifier.isStatic(m.modifiers) } == 8 }
            .single())
        val operations = api.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) }.map { contract ->
            transport.methods.filter { it.name == contract.name && it.paramTypeNames == contract.paramTypeNames &&
                it.returnTypeName == contract.returnTypeName && !Modifier.isStatic(it.modifiers) }.one("sync operation ${contract.descriptor}")
        }
        fun operation(key: String, predicate: (MethodData) -> Boolean) = method(key, operations.filter(predicate).one(key))
        val content = operation("getContent") { it.usingStrings.containsAll(listOf("ETag", "下载 ")) &&
            it.paramCount == 2 && !it.usingStrings.contains("失败：HTTP 404") }
        val head = operation("head") { it.usingStrings.containsAll(listOf("读取 ", "ETag")) && it.paramCount == 2 }
        val bytes = operation("getBytes") { it.usingStrings.contains("失败：HTTP 404") && it.paramCount == 2 }
        operation("delete") { it.usingStrings.contains("删除 ") && it.paramCount == 2 }
        operation("putBytes") { it.paramCount == 5 && it.paramTypeNames.take(4) ==
            listOf("java.lang.String", "byte[]", "java.lang.String", "java.lang.String") }
        operation("putProgress") { it.paramCount == 6 && it.paramTypeNames[1] == "byte[]" && it.usingStrings.contains("If-None-Match: *") }
        val putFile = operation("putFile") { it.paramCount == 6 && it.paramTypeNames[1] != "byte[]" &&
            it.usingStrings.containsAll(listOf("Content-Type", "Content-Length")) }
        operation("getFile") { it.paramCount == 4 && it.usingStrings.contains("GET") }
        val transportConstructor = method("transport", transport.methods.filter { it.isConstructor && it.paramTypeNames.contains(config.name) }
            .one("S3 transport constructor"))
        val configParameter = transportConstructor.paramTypeNames.indexOf(config.name) + 1
        fun configuration(value: HostDexCalls.Value): Boolean = value == HostDexCalls.Parameter(configParameter) ||
            value is HostDexCalls.Call && value.arguments == listOf(HostDexCalls.Parameter(configParameter)) &&
                bridge.getMethodData(value.descriptor)?.let { it.declaredClassName == config.name &&
                    !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == config.name } == true
        out["field:transportConfig"] = calls.writes(transportConstructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && configuration(it.value) &&
                it.field.startsWith(transport.descriptor + "->")
        }.map { it.field }.distinct().single()
        fun response(key: String, source: MethodData, label: String, vararg parameters: String) {
            val owner = type(key, requireNotNull(anchor(bridge, label).one("$key record").declaredClass))
            val constructor = method(key, owner.constructor(*parameters))
            check(source.invokes.any { it.descriptor == constructor.descriptor }) { "$key is not constructed by ${source.descriptor}" }
        }
        response("content", content, "S3ObjectContent(bytes=", "java.lang.String", "byte[]")
        response("head", head, "S3ObjectHead(size=", "long", "java.lang.String")
        method("httpError", bytes.invokes.filter { it.isConstructor && it.paramTypeNames == listOf("int", "java.lang.String") }
            .one("sync HTTP exception"))
        val file = type("file", requireNotNull(putFile.paramTypes[1]))
        method("toFile", file.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == "java.io.File" }.one("sync platform file"))
        val pack = method("onlinePack", planner.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf(file.name, file.name, "java.util.Set") &&
                it.invokes.any { call -> call.returnTypeName == "java.io.File" }
        }.one("sync EPUB packaging"))
        check(pack.usingStrings.contains("/")) { "EPUB packaging path contract differs" }
        val restoreBook = method("onlineRestore", anchor(bridge, "EPUB 校验失败，保留本地书籍")
            .one("sync restore book"))
        val unpack = method("onlineUnpack", anchor(bridge, "Skip unreadable EPUB entry while extracting: ")
            .one("sync EPUB extraction"))
        // 恢复协程分支复杂，调用关系直接检查 DEX 指令，不依赖参数流分析的可达性。
        val dex = HostDexIndex.forApk(apk)
        fun invokedBy(descriptor: String) = (dex.method(descriptor).implementation?.instructions ?: emptyList()).mapNotNull { instruction ->
            val ref = (instruction as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                ?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference
            ref?.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        }
        val restoreInvokes = invokedBy(restoreBook.descriptor)
        check(unpack.descriptor in restoreInvokes || restoreInvokes.filter {
            it.startsWith(unpack.descriptor.substringBefore("->") + "->") &&
                it.contains("(${file.descriptor}${file.descriptor}") && it.endsWith(")V")
        }.any { unpack.descriptor in invokedBy(it) }) {
            "Sync restore does not call the queried EPUB extractor or its default-argument wrapper"
        }
        val syncBook = type("onlineSyncBook", requireNotNull(anchor(bridge, "S3SyncBook(book=")
            .one("sync book record").declaredClass))
        val metadata = type("onlineMetadata", requireNotNull(anchor(bridge, "S3SyncBookMetadata(uuid=")
            .one("sync metadata record").declaredClass))
        val onlineProperties = HostRecordQueries.query(apk, mapOf("class:onlineSyncBook" to syncBook.descriptor,
            "class:onlineMetadata" to metadata.descriptor))
        out["field:onlineSyncBook.metadata"] = onlineProperties.getValue("field:onlineSyncBook#book")
        out["field:onlineMetadata.uuid"] = onlineProperties.getValue("field:onlineMetadata#uuid")
        out["value:onlineRestore.book"] = restoreBook.paramTypeNames.indexOf(syncBook.name).also { check(it >= 0) }.toString()
        out["value:onlineRestore.uid"] = restoreBook.paramTypeNames.indexOf("long").also { check(it >= 0) }.toString()

        val save = method("saveLocal", anchor(bridge, "请先完成 S3 配置").filter {
            it.paramCount == 3 && it.paramTypeNames.take(2) == listOf("long", config.name) && !Modifier.isStatic(it.modifiers)
        }.one("local sync save"))
        val session = type("session", known(SESSION))
        out["field:saveSession"] = requireNotNull(save.declaredClass).fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == session.name
        }.descriptor
        val sessionConstructor = method("session", session.methods.filter { it.isConstructor && it.paramCount == 1 }.one("Session constructor"))
        val datastore = type("datastore", requireNotNull(sessionConstructor.paramTypes.single()))
        out["field:datastore"] = session.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == datastore.name }.descriptor
        val getData = method("getData", datastore.methods.filter { it.name == "getData" && it.paramCount == 0 && !Modifier.isStatic(it.modifiers) }
            .one("DataStore data flow"))
        val flow = type("flow", requireNotNull(getData.returnType))
        val continuation = type("continuation", known("kotlin.coroutines.Continuation"))
        val function2 = type("function2", known("kotlin.jvm.functions.Function2"))
        val function3 = type("function3", known("kotlin.jvm.functions.Function3"))
        fun inherits(candidate: ClassData, expected: ClassData): Boolean {
            val seen = hashSetOf<String>()
            fun visit(type: ClassData): Boolean = if (!seen.add(type.name)) false else type.name == expected.name ||
                type.interfaces.any(::visit) || type.superClass?.let(::visit) == true
            return visit(candidate)
        }
        val sessionCalls = session.methods.flatMap { it.invokes }.distinctBy { it.descriptor }
        method("first", sessionCalls.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 2 &&
            it.paramTypeNames[0] == flow.name && it.returnTypeName == "java.lang.Object" &&
            it.paramTypes[1]?.let { parameter -> inherits(parameter, continuation) } == true }.one("Session first flow value"))
        method("edit", sessionCalls.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 3 &&
            it.paramTypeNames.take(2) == listOf(datastore.name, function2.name) && it.returnTypeName == "java.lang.Object" &&
            it.paramTypes[2]?.let { parameter -> inherits(parameter, continuation) } == true }.one("Session DataStore edit"))
        method("editInvoke", function2.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } &&
            it.returnTypeName == "java.lang.Object" }.one("preferences Function2"))
        method("progressInvoke", function3.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
            it.returnTypeName == "java.lang.Object" }.one("sync Function3"))
        val preferences = type("preferences", requireNotNull(anchor(bridge, "Do mutate preferences once returned to DataStore.")
            .one("mutable preferences").declaredClass))
        val key = type("preferenceKey", known(PREFERENCE_KEY))
        method("preferenceKey", key.constructor("java.lang.String"))
        fun mutation(name: String, parameters: List<String>, mapMethod: String) = method(name, preferences.methods.filter { candidate ->
            !Modifier.isStatic(candidate.modifiers) && candidate.paramTypeNames == parameters && candidate.invokes.any {
                it.className in setOf("java.util.Map", "java.util.LinkedHashMap") && it.name == mapMethod
            }
        }.one("preferences $name"))
        mutation("preferenceGet", listOf(key.name), "get")
        mutation("preferenceRemove", listOf(key.name), "remove")
        mutation("preferenceSet", listOf(key.name, "java.lang.Object"), "put")

        val choices = method("providerChoices", anchor(bridge, "连接R2 Bucket存储同步书架", "连接Bitiful S3服务同步书架")
            .one("sync provider choices"))
        val function0 = type("function0", known("kotlin.jvm.functions.Function0"))
        val composer = type("composer", known("androidx.compose.runtime.Composer"))
        val other = method("otherProvider", choices.invokes.filter { it.paramTypeNames == listOf(function0.name, composer.name, "int") &&
            it.returnTypeName == "void" && Modifier.isStatic(it.modifiers) && it.usingStrings.isEmpty() &&
            // 其他 S3 行使用现场构造的回调，付费提示行直接接收配置页的 Function0 参数。
            calls.calls(choices.descriptor).filter { call -> call.descriptor == it.descriptor }.none { call ->
                (call.arguments.firstOrNull() as? HostDexCalls.Parameter)?.let { argument ->
                    choices.paramTypeNames.getOrNull(argument.index) == function0.name
                } == true
            }
        }.one("other S3 provider row"))
        out.putAll(HostS3ConfigurationQueries.query(bridge, apk, config.name, composer.name, function0.name, choices.descriptor, other.descriptor))
        check(operations.map { it.descriptor }.toSet() == HostSyncBindings.OPERATIONS.map { out.getValue("method:$it") }.toSet())
        return out
    }
}
