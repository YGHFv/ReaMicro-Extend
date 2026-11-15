package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Highlight settings and native family sheet closures, including their R8 captures. */
internal object HostHighlightUiQueries {
    private const val P = "app.zhendong.reamicro."
    val FAMILIES = listOf("EpubFamily", "BuildInFamily", "UserFamily")
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Highlight UI $role is missing or ambiguous")
    private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
        interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun anchor(vararg strings: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        fun method(role: String, value: MethodData) = value.also { put("method:$role", it.descriptor) }
        fun index(role: String, value: Int) { put("value:$role", value.toString()) }
        val receiver = type("receiver", P + "arch.MVIViewModel")
        val book = type("book", P + "data.db.entity.Book")
        val composer = type("composer", "androidx.compose.runtime.Composer")
        val function0 = type("function0", "kotlin.jvm.functions.Function0")
        val function3 = type("function3", "kotlin.jvm.functions.Function3")
        val statusIntent = type("intent", P + "ui.reader.ReaderUiIntent\$UpdateUISheetStatus")
        val statusField = requireNotNull(bridge.getFieldData(base.getValue("field:${P}ui.reader.ReaderUiIntent\$UpdateUISheetStatus#status")))
        val status = statusField.type
        put("class:status", status.descriptor)
        put("field:intent.status", statusField.descriptor)
        put("constructor:intent", statusIntent.methods.filter { it.isConstructor && it.paramTypeNames == listOf(status.name) }
            .one("sheet intent constructor").descriptor)
        method("intent", known(P + "arch.MVIViewModel", "intent"))
        val page = method("page", known(P + "ui.reader.compose.ReaderHighlightScreenKt", "HighlightPageContent"))
        val preset = method("preset", known(P + "ui.reader.compose.ReaderHighlightScreenKt", "PresetDialogueRow"))
        check(Modifier.isStatic(page.modifiers) && page.returnTypeName == "void" && page.paramTypeNames.first() == "java.util.List")
        check(Modifier.isStatic(preset.modifiers) && preset.returnTypeName == "void" && preset.paramCount == 7 &&
            preset.paramTypeNames.count { it == function0.name } == 2)
        index("preset.composer", preset.paramTypeNames.indices.single { preset.paramTypeNames[it] == composer.name })
        val presetCallers = preset.callers.filter { !Modifier.isStatic(it.modifiers) && it.returnTypeName == "java.lang.Object" &&
            it.paramCount in 2..4 && it.paramTypeNames.all { p -> p == "java.lang.Object" } }.distinctBy { it.descriptor }
        check(presetCallers.any { it.paramCount == 4 })
        index("preset.callers", presetCallers.size)
        presetCallers.forEachIndexed { i, value -> method("preset.caller.$i", value) }
        val bodies = arrayListOf<MethodData>()
        for (role in FAMILIES + "Hidden") {
            val singleton = requireNotNull(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(role)))
                .filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" && it.declaredClass?.isA(status) == true }
                .one("$role identity").declaredClass)
            put("class:status.$role", singleton.descriptor)
            val instance = singleton.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == singleton.name }
            put("field:status.$role", instance.descriptor)
            if (role == "Hidden") continue
            val root = method("root.$role", instance.readers.filter { candidate ->
                Modifier.isStatic(candidate.modifiers) && candidate.returnTypeName == "void" &&
                    candidate.paramTypeNames == (listOf(receiver.name, status.name) +
                    (if (role == "EpubFamily") listOf(book.name) else emptyList()) + listOf(composer.name, "int"))
            }.one("$role root"))
            index("root.$role.status", root.paramTypeNames.indices.single { root.paramTypeNames[it] == status.name })
            val ctor = root.invokes.filter { it.isConstructor && it.declaredClass?.isA(function3) == true }.one("$role content constructor")
            val closure = requireNotNull(ctor.declaredClass)
            put("class:body.$role", closure.descriptor)
            put("constructor:body.$role", ctor.descriptor)
            val body = method("body.$role", closure.methods.filter { candidate -> candidate.isMethod &&
                !Modifier.isStatic(candidate.modifiers) && candidate.paramTypeNames == List(3) { "java.lang.Object" } &&
                candidate.returnTypeName == "java.lang.Object" && candidate.usingFields.any { it.field.descriptor == instance.descriptor }
            }.one("$role body"))
            bodies += body
            val callers = body.callers.filter { it.isMethod }.distinctBy { it.descriptor }
            index("body.$role.callers", callers.size)
            callers.forEachIndexed { i, value -> method("body.$role.caller.$i", value) }
            val casts = flow.casts(body.descriptor)
            fun capture(name: String, kind: ClassData) {
                val fields = closure.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == kind.name }.map { it.descriptor } +
                    casts.filter { it.type == kind.descriptor }.mapNotNull { (it.value as? HostDexCalls.Field)?.descriptor }
                put("field:body.$role.$name", fields.distinct().single())
            }
            capture("status", status); capture("owner", receiver)
            index("body.$role.composer", casts.filter { it.type == composer.descriptor }.mapNotNull {
                (it.value as? HostDexCalls.Parameter)?.index?.minus(1)
            }.distinct().single())
            val heights = flow.calls(body.descriptor).filter { it.descriptor == ui.getValue("method:height") }.map { call ->
                val density = call.arguments.last() as? HostDexCalls.Call ?: error("$role height has no density conversion")
                check(density.descriptor == ui.getValue("method:dpInt"))
                (density.arguments.single() as? HostDexCalls.Literal)?.value ?: error("$role height has no literal")
            }.distinct()
            index("body.$role.height", heights.single().toInt().also { check(it in 1..1000) })
        }
        put("method:height", ui.getValue("method:height"))
        put("method:dpInt", ui.getValue("method:dpInt"))
        val topBar = requireNotNull(bridge.getMethodData(ui.getValue("method:topBar")))
        val close = method("sheet.close", anchor("Close").filter { Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName in topBar.paramTypeNames }.one("sheet close vector"))
        put("class:sheet.vector", requireNotNull(close.returnType).descriptor)
        val holderCtor = anchor("statusBarsIgnoringVisibility").filter { it.isConstructor &&
            it.paramTypeNames == listOf("android.view.View") }.one("status inset holder")
        val holder = requireNotNull(holderCtor.declaredClass)
        put("class:sheet.insetHolder", holder.descriptor)
        fun contains(value: HostDexCalls.Value, text: String): Boolean = value == HostDexCalls.Text(text) ||
            value is HostDexCalls.Call && value.arguments.any { contains(it, text) }
        val statusInsetsField = requireNotNull(bridge.getFieldData(flow.writes(holderCtor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && contains(it.value, "statusBarsIgnoringVisibility")
        }.map { it.field }.distinct().single()))
        put("field:sheet.statusInsets", statusInsetsField.descriptor)
        val insets = topBar.paramTypes.filter { statusInsetsField.type.isA(it) }.single()
        put("class:sheet.insets", insets.descriptor)
        val topInsets = method("sheet.topInsets", topBar.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(composer.name) && it.returnType?.isA(insets) == true }.one("top bar insets"))
        method("sheet.insetHolder", bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes(composer.name).returnType(holder.name))).filter { Modifier.isStatic(it.modifiers) }.one("remember inset holder"))
        val exclude = bodies.flatMap { it.invokes }.filter { it.isConstructor &&
            it.paramTypeNames == listOf(insets.name, insets.name) && it.declaredClass?.isA(insets) == true }.one("sheet inset exclusion")
        put("constructor:sheet.excludeInsets", exclude.descriptor)
        val exclusionCalls = bodies.distinctBy { it.descriptor }.flatMap { flow.calls(it.descriptor) }
            .filter { it.descriptor == exclude.descriptor }
        check(exclusionCalls.isNotEmpty() && exclusionCalls.all {
            (it.arguments[1] as? HostDexCalls.Call)?.descriptor == topInsets.descriptor &&
                it.arguments[2] == HostDexCalls.Field(statusInsetsField.descriptor)
        })
        val handler = anchor("No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner")
            .filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" }.one("navigation event handler")
        val eventState = handler.paramTypes.filter { it.name !in setOf("boolean", "int", function0.name, composer.name) }.single()
        put("class:sheet.navigationState", eventState.descriptor)
        val back = method("sheet.navigationBack", handler.callers.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramTypeNames == listOf(eventState.name, "boolean", function0.name,
                function0.name, composer.name, "int", "int") }.one("navigation back wrapper"))
        val remember = method("sheet.rememberNavigation", bodies.flatMap { it.invokes }.filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(composer.name) && it.returnTypeName == eventState.name
        }.one("remember navigation state"))
        val backCalls = bodies.distinctBy { it.descriptor }.flatMap { flow.calls(it.descriptor) }
            .filter { it.descriptor == back.descriptor }
        check(backCalls.isNotEmpty() && backCalls.all {
            (it.arguments[0] as? HostDexCalls.Call)?.descriptor == remember.descriptor &&
                it.arguments[2] == HostDexCalls.Literal(0)
        })
        index("sheet.navigationDefaults", backCalls.map { (it.arguments.last() as? HostDexCalls.Literal)?.value }.distinct().single()
            ?.toInt() ?: error("Navigation back default mask is not proven"))
    }
}
