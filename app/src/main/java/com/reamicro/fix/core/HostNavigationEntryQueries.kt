package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

/** Entry identity survives recomposition; the native back queue determines which page is active. */
internal object HostNavigationEntryQueries {
    fun query(bridge: DexKitBridge, apk: String, scopeDescriptor: String): Map<String, String> {
        val flow = HostDexCalls(apk)
        fun anchor(text: String) = bridge.findMethod(FindMethod.create().matcher(
            MethodMatcher.create().usingEqStrings(text))).distinctBy { it.descriptor }.single()
        val entry = requireNotNull(anchor(
            "You cannot access the NavBackStackEntry's ViewModels after the NavBackStackEntry is destroyed."
        ).declaredClass)
        val identity = entry.fields.filter {
            !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.typeName == "java.lang.String"
        }.single()
        check(entry.methods.filter { it.isConstructor }.any { constructor ->
            flow.writes(constructor.descriptor).any { write ->
                val parameter = write.value as? HostDexCalls.Parameter
                write.field == identity.descriptor && write.receiver == HostDexCalls.Parameter(0) &&
                    parameter != null && constructor.paramTypeNames.getOrNull(parameter.index - 1) == "java.lang.String"
            }
        })
        val lookup = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(
            "No destination with ID ",
            " is on the NavController's back stack. The current destination is ",
        ))).filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int") &&
                it.returnTypeName == entry.name
        }.distinctBy { it.descriptor }.let { candidates ->
            require(candidates.size == 1) {
                "Native navigation entry lookup: expected one, found ${candidates.map { it.descriptor }}"
            }
            candidates.single()
        }
        val state = requireNotNull(lookup.declaredClass)
        // java.util.List belongs to the boot classpath, so DexKit has no MethodData for it.
        val queueCandidates = flow.calls(lookup.descriptor).filter { call ->
            call.descriptor == "Ljava/util/List;->listIterator(I)Ljava/util/ListIterator;"
        }.mapNotNull { (it.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor }.distinct()
        val queue = queueCandidates.mapNotNull(bridge::getFieldData).filter {
            it.declaredClassName == state.name && !Modifier.isStatic(it.modifiers)
        }.single()
        val scope = requireNotNull(bridge.getClassData(scopeDescriptor))
        val controller = requireNotNull(scope.fields.single { !Modifier.isStatic(it.modifiers) }.type)
        val controllerState = controller.fields.single {
            !Modifier.isStatic(it.modifiers) && it.typeName == state.name
        }
        return mapOf(
            "class:navigationEntry" to entry.descriptor,
            "field:navigationEntryId" to identity.descriptor,
            "field:navigationState" to controllerState.descriptor,
            "field:navigationQueue" to queue.descriptor,
            "method:navigationEntryLookup" to lookup.descriptor,
        )
    }
}
