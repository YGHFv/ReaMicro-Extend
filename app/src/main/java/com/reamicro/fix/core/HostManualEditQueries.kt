package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** The editor and its hidden entry follow this APK's publisher and About composition graphs. */
internal object HostManualEditQueries {
    fun query(group: String, bridge: DexKitBridge, apk: String,
        ui: Map<String, String>, association: Map<String, String>, navigation: Map<String, String>): Map<String, String> {
        val flow = HostDexCalls(apk)
        fun method(descriptor: String) = requireNotNull(bridge.getMethodData(descriptor))
        fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
            ?: error("Manual editor $role is missing or ambiguous")
        val composer = method(ui.getValue("method:title")).paramTypes[2]
        val modifier = method(ui.getValue("method:title")).paramTypes[1]
        val scaffold = method(ui.getValue("method:scaffold"))
        val out = linkedMapOf<String, String>("class:composer" to composer.descriptor)
        fun save(role: String, m: MethodData) { out["method:$role"] = m.descriptor }

        data class Callback(val method: MethodData, val fields: Map<String, Long>)
        fun callback(value: HostDexCalls.Value, arity: Int): Callback {
            val field = value as? HostDexCalls.Field ?: error("Expected a static Compose callback")
            val data = requireNotNull(bridge.getFieldData(field.descriptor))
            check(Modifier.isStatic(data.modifiers) && Modifier.isFinal(data.modifiers))
            val init = requireNotNull(data.declaredClass).methods.filter { it.name == "<clinit>" }.one("callback initializer")
            val allocation = flow.writes(init.descriptor).single { it.field == field.descriptor }.value as HostDexCalls.Allocation
            val construction = flow.calls(init.descriptor).single { call ->
                call.arguments.firstOrNull() == allocation && method(call.descriptor).isConstructor
            }
            val wrapper = method(construction.descriptor)
            val callbackSlot = wrapper.paramTypeNames.indices.single { wrapper.paramTypeNames[it] == "java.lang.Object" } + 1
            val target = construction.arguments[callbackSlot] as HostDexCalls.Allocation
            val ctor = flow.calls(init.descriptor).single { it.arguments.firstOrNull() == target && method(it.descriptor).isConstructor }
            val targetClass = requireNotNull(bridge.getClassData(target.type))
            val constants = flow.writes(ctor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
                .mapNotNull { write ->
                    val parameter = write.value as? HostDexCalls.Parameter ?: return@mapNotNull null
                    val literal = ctor.arguments.getOrNull(parameter.index) as? HostDexCalls.Literal ?: return@mapNotNull null
                    val f = requireNotNull(bridge.getFieldData(write.field))
                    check(f.declaredClassName == targetClass.name && !Modifier.isStatic(f.modifiers) && Modifier.isFinal(f.modifiers))
                    write.field to literal.value
                }.toMap()
            val invocation = if (arity == 4) method(ui.getValue("method:destinationInvoke")) else method(ui.getValue("method:invoke$arity"))
            val body = targetClass.methods.filter { !Modifier.isStatic(it.modifiers) &&
                it.name == invocation.name && it.paramTypeNames == invocation.paramTypeNames &&
                it.returnTypeName == invocation.returnTypeName }.one("callback body")
            return Callback(body, constants)
        }
        fun calls(callback: Callback) = flow.callsWithFields(callback.method.descriptor, callback.fields)
        fun publishCallback(role: String, value: Callback) {
            save(role, value.method)
            value.fields.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                out["field:$role.branch.$index"] = entry.key
                out["value:$role.branch.$index"] = entry.value.toString()
            }
            out["value:$role.branches"] = value.fields.size.toString()
        }
        when (group) {
            "publisher" -> {
                val vm = requireNotNull(bridge.getClassData(association.getValue("class:vm")))
                val scope = requireNotNull(bridge.getClassData(navigation.getValue("class:scope")))
                val root = flow.referencingType(vm.descriptor).map(::method).filter {
                    Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(scope.name, "long", composer.name, "int") &&
                        it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == scaffold.descriptor }
                }.one("publisher page")
                save("root", root); out["class:scope"] = scope.descriptor; out["class:vm"] = vm.descriptor
                val constructors = vm.methods.filter { it.isConstructor }
                val ids = constructors.flatMap { ctor ->
                    val slot = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == "long" }
                    flow.writes(ctor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) &&
                        it.value == HostDexCalls.Parameter(slot + 1) }.map { it.field }
                }.distinct()
                val id = requireNotNull(bridge.getFieldData(ids.single()))
                check(id.declaredClassName == vm.name && id.typeName == "long" && Modifier.isFinal(id.modifiers))
                out["field:vm.bookId"] = id.descriptor
                val content = root.invokes.filter { it.isConstructor }.mapNotNull { it.declaredClass }
                    .distinctBy { it.descriptor }.flatMap { it.methods }.filter {
                        !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
                            it.returnTypeName == "java.lang.Object" && flow.casts(it.descriptor).any { cast -> cast.type == vm.descriptor }
                    }.one("publisher retained content")
                val capture = flow.casts(content.descriptor).filter { it.type == vm.descriptor }
                    .map { (it.value as HostDexCalls.Field).descriptor }.distinct().single()
                check(bridge.getFieldData(capture)?.let { !Modifier.isStatic(it.modifiers) &&
                    it.declaredClassName == content.declaredClassName && it.typeName == "java.lang.Object" } == true)
                save("content", content); out["field:content.vm"] = capture
                val acquire = flow.casts(root.descriptor).filter { it.type == vm.descriptor }
                    .map { method((it.value as HostDexCalls.Call).descriptor) }.one("existing publisher VM acquisition")
                save("acquireVm", acquire)
                val top = method(ui.getValue("method:topBar"))
                val scaffoldCall = flow.calls(root.descriptor).single { it.descriptor == scaffold.descriptor }
                val (topBarSlot, topBar) = scaffold.paramTypes.indices.filter {
                    scaffold.paramTypes[it].descriptor in setOf(ui.getValue("class:function2"), ui.getValue("class:lambda"))
                }.mapNotNull { slot -> runCatching { callback(scaffoldCall.arguments[slot], 2) }.getOrNull()?.let { slot to it } }
                    .filter { (_, value) -> calls(value).count { it.descriptor == top.descriptor } == 1 }.single()
                publishCallback("toolbar", topBar)
                save("scaffold", scaffold); save("topBar", top)
                val action = top.paramTypes.indices.filter {
                    top.paramTypes[it].descriptor in setOf(ui.getValue("class:function3"), ui.getValue("class:lambda"))
                }.last()
                check(action < top.paramTypeNames.indexOf(composer.name))
                out["value:scaffold.toolbar"] = topBarSlot.toString()
                out["value:scaffold.changed"] = (scaffold.paramTypeNames.indexOf(composer.name) + 1).toString()
                out["value:scaffold.defaults"] = scaffold.paramTypeNames.lastIndex.toString()
                out["value:topBar.actions"] = action.toString()
                out["value:topBar.changed"] = (top.paramTypeNames.indexOf(composer.name) + 1).toString()
                out["value:topBar.defaults"] = top.paramTypeNames.lastIndex.toString()
                val cover = requireNotNull(bridge.getFieldData(association.getValue("field:candidate#cover")))
                val image = cover.readers.filter {
                    Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                        it.paramTypeNames.contains(composer.name)
                }.flatMap { it.invokes }.filter {
                    Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 7 &&
                        it.paramTypeNames.take(2) == listOf("java.lang.Object", "java.lang.String") &&
                        it.paramTypes[2].descriptor == modifier.descriptor &&
                        it.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int")
                }.one("candidate cover image")
                save("candidateImage", image)
                out["class:modifier"] = modifier.descriptor
                out["value:candidateImage.data"] = "0"
                out["value:candidateImage.changed"] = "5"
                out["value:candidateImage.defaults"] = "6"
                val book = requireNotNull(bridge.getClassData(association.getValue("class:book")))
                val candidate = requireNotNull(bridge.getClassData(association.getValue("class:candidate")))
                val repository = requireNotNull(bridge.getClassData(association.getValue("class:repository")))
                out["class:book"] = book.descriptor
                out["class:candidate"] = candidate.descriptor
                out["class:repository"] = repository.descriptor
                val relate = repository.methods.filter {
                    it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 3 &&
                        it.paramTypeNames.take(2) == listOf(book.name, candidate.name) &&
                        it.returnTypeName == "java.lang.Object"
                }.one("confirmed candidate persistence")
                save("relate", relate)
                val relatedCover = relate.invokes.filter {
                    it.declaredClassName == repository.name && !Modifier.isStatic(it.modifiers) &&
                        it.paramCount == 3 && it.paramTypeNames.take(2) == listOf(book.name, "java.lang.String") &&
                        it.paramTypeNames.last() == relate.paramTypeNames.last() &&
                        it.returnTypeName == "java.lang.Object"
                }.one("cover after confirmed association")
                save("relatedCover", relatedCover)
                val renderers = cover.readers.filter {
                    Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                        it.paramTypeNames.count { name -> name == candidate.name } == 1 &&
                        composer.name in it.paramTypeNames && it.invokes.any { call -> call.descriptor == image.descriptor }
                }.distinctBy { it.descriptor }
                check(renderers.isNotEmpty()) { "Manual candidate image has no owned render frame" }
                out["value:candidateRender.count"] = renderers.size.toString()
                renderers.sortedBy { it.descriptor }.forEachIndexed { index, renderer ->
                    save("candidateRender.$index", renderer)
                    out["value:candidateRender.$index.candidate"] = renderer.paramTypeNames.indexOf(candidate.name).toString()
                }
            }
            "unlock" -> {
                val destination = callback(HostDexCalls.Field(ui.getValue("field:aboutContent")), 4)
                val root = calls(destination).mapNotNull { bridge.getMethodData(it.descriptor) }.filter {
                    Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                        it.paramTypeNames.toSet() == setOf("int", composer.name) && it.paramCount == 2 &&
                        it.invokes.any { m -> m.descriptor == scaffold.descriptor }
                }.one("About screen")
                save("root", root)
                val contentSlot = scaffold.paramTypes.indices.filter { scaffold.paramTypes[it].descriptor == ui.getValue("class:lambda") }.last()
                val content = callback(flow.calls(root.descriptor).single { it.descriptor == scaffold.descriptor }.arguments[contentSlot], 3)
                publishCallback("content", content)
                check(content.method.usingStrings.contains("Ver "))
                val logo = calls(content).mapNotNull { bridge.getMethodData(it.descriptor) }.filter {
                    Modifier.isStatic(it.modifiers) && it.paramCount == 10 && it.returnTypeName == "void" &&
                        it.paramTypeNames[1] == "java.lang.String" && it.paramTypes[2].descriptor == modifier.descriptor &&
                        it.paramTypeNames[5] == "float" && it.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int")
                }.one("About logo image")
                check(calls(content).count { it.descriptor == logo.descriptor } == 1)
                save("image", logo)
                out["class:modifier"] = logo.paramTypes[2].descriptor
                out["value:image.modifier"] = "2"
                out["value:image.changed"] = "8"
                out["value:image.defaults"] = "9"
            }
            else -> error("Unknown manual editor group: $group")
        }
        return out
    }
}
