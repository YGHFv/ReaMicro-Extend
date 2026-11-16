package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Follow the native Saver allocations; merged callback tags come from constructor arguments. */
internal object HostNavigationStateQueries {
    fun query(
        bridge: DexKitBridge,
        apk: String,
        ui: Map<String, String>,
        cacheDescriptor: String,
    ): Map<String, String> {
        val flow = HostDexCalls(apk)
        val cache = requireNotNull(bridge.getFieldData(cacheDescriptor))
        val controller = requireNotNull(cache.type)
        fun method(descriptor: String) = requireNotNull(bridge.getMethodData(descriptor))
        // The native UI contract encodes Composer in its validated title signature.
        val composer = requireNotNull(method(ui.getValue("method:title")).paramTypes.getOrNull(2))
        fun one(values: List<MethodData>, role: String) = values.distinctBy { it.descriptor }.singleOrNull()
            ?: error("Native navigation $role is missing or ambiguous")
        val remember = one(cache.writers.flatMap { writer ->
            flow.writes(writer.descriptor).filter { it.field == cacheDescriptor }.mapNotNull { write ->
                (write.value as? HostDexCalls.Call)?.descriptor?.let(bridge::getMethodData)
            }
        }.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == controller.name &&
                it.paramCount == 2 && it.paramTypeNames.last() == composer.name
        }, "remember controller")
        val anchored = bridge.findMethod(FindMethod.create().matcher(
            MethodMatcher.create().usingEqStrings("android-support-nav:controller:backStack")
        )).toList()
        fun serializesEntry(method: MethodData): Boolean =
            !Modifier.isStatic(method.modifiers) && method.paramCount == 0 &&
                method.returnTypeName == "android.os.Bundle" && "nav-entry-state:id" in method.usingStrings
        val save = one(anchored.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object" &&
                ("nav-entry-state:id" in it.usingStrings || it.invokes.any(::serializesEntry))
        }, "save callback")
        val restore = one(anchored.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Object") &&
                it.returnTypeName == "java.lang.Object" &&
                it.invokes.any { call ->
                    call.descriptor == "Landroid/os/Bundle;->setClassLoader(Ljava/lang/ClassLoader;)V"
                }
        }, "restore callback")
        val calls = flow.calls(remember.descriptor)
        fun construction(callback: MethodData) = calls.filter { call ->
            val target = bridge.getMethodData(call.descriptor) ?: return@filter false
            target.isConstructor && target.declaredClass?.descriptor == callback.declaredClass?.descriptor &&
                call.arguments.firstOrNull() is HostDexCalls.Allocation
        }.distinct().single()
        fun constants(call: HostDexCalls.Call): Map<String, Long> = flow.writes(call.descriptor).mapNotNull { write ->
            val parameter = write.value as? HostDexCalls.Parameter ?: return@mapNotNull null
            val literal = call.arguments.getOrNull(parameter.index) as? HostDexCalls.Literal ?: return@mapNotNull null
            val field = bridge.getFieldData(write.field) ?: return@mapNotNull null
            if (write.receiver != HostDexCalls.Parameter(0) || !Modifier.isFinal(field.modifiers) ||
                field.typeName !in setOf("boolean", "byte", "short", "int", "long", "char")) return@mapNotNull null
            write.field to literal.value
        }.toMap()
        val saveConstruction = construction(save)
        val restoreConstruction = construction(restore)
        val saveCalls = flow.callsWithFields(save.descriptor, constants(saveConstruction))
        val restoreCalls = flow.callsWithFields(restore.descriptor, constants(restoreConstruction))
        fun referencesScope(value: HostDexCalls.Value): Boolean = when (value) {
            is HostDexCalls.Parameter -> value.index == 1
            is HostDexCalls.Call -> value.arguments.any(::referencesScope)
            else -> false
        }
        check(saveCalls.none { it.arguments.any(::referencesScope) }) {
            "Native navigation saver requires an unbound SaverScope"
        }
        check(saveCalls.any { call ->
            call.arguments.any { it == HostDexCalls.Text("nav-entry-state:id") } ||
                bridge.getMethodData(call.descriptor)?.let(::serializesEntry) == true
        })
        check(restoreCalls.any {
            it.descriptor == "Landroid/os/Bundle;->setClassLoader(Ljava/lang/ClassLoader;)V"
        })
        val factory = one(restoreCalls.mapNotNull { bridge.getMethodData(it.descriptor) }.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == controller.name &&
                it.paramTypeNames == listOf("android.content.Context")
        }, "controller factory")
        val state = requireNotNull(bridge.getFieldData(ui.getValue("field:navigationState"))).type
        val pending = flow.writes(restore.descriptor).mapNotNull { bridge.getFieldData(it.field) }
            .filter { it.declaredClass?.descriptor == state.descriptor &&
                it.type.descriptor == "[Landroid/os/Bundle;" }.distinctBy { it.descriptor }.single()
        val graph = one(pending.writers.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramTypeNames.firstOrNull() == controller.name &&
            it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") }, "pending stack consumption")
        fun arguments(call: HostDexCalls.Call): String {
            val constructor = method(call.descriptor)
            check(call.arguments.size == constructor.paramCount + 1)
            return constructor.paramTypeNames.mapIndexed { index, type ->
                val value = call.arguments[index + 1]
                if (type == "android.content.Context") {
                    check(value !is HostDexCalls.Literal)
                    "@context"
                } else {
                    check(type in setOf("boolean", "byte", "short", "int", "long", "char"))
                    (value as? HostDexCalls.Literal)?.value?.toString()
                        ?: error("Unproven native navigation constructor argument: $value")
                }
            }.joinToString(",")
        }
        return linkedMapOf(
            "method:rememberNavigation" to remember.descriptor,
            "method:navigationFactory" to factory.descriptor,
            "method:navigationSave" to save.descriptor,
            "method:navigationRestore" to restore.descriptor,
            "method:navigationGraph" to graph.descriptor,
            "field:navigationPending" to pending.descriptor,
            "constructor:navigationSave" to saveConstruction.descriptor,
            "constructor:navigationRestore" to restoreConstruction.descriptor,
            "value:navigationSaveArguments" to arguments(saveConstruction),
            "value:navigationRestoreArguments" to arguments(restoreConstruction),
        )
    }
}
