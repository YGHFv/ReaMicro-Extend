package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Discover shares the stamps row when present, otherwise the original community row. */
internal object HostDiscoverQueries {
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>, graphics: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun method(descriptor: String) = requireNotNull(bridge.getMethodData(descriptor))
        fun shared(key: String) = method(ui.getValue("method:$key"))
        fun type(key: String) = requireNotNull(bridge.getClassData(base.getValue("class:$key")))
        fun anchor(vararg text: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*text)))
        fun save(key: String, value: MethodData) { put("method:$key", value.descriptor) }
        val composer = type("androidx.compose.runtime.Composer")
        val modifier = type("androidx.compose.ui.Modifier")
        val vector = requireNotNull(bridge.getClassData(graphics.getValue("class:vector")))
        val function = shared("clickable").paramTypes[4]
        put("class:composer", composer.descriptor); put("class:modifier", modifier.descriptor)
        put("class:vector", vector.descriptor); put("class:function0", function.descriptor)
        val community = anchor("Colored.Book", "里程碑", "同好").filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && composer.name in it.paramTypeNames }.one("community content")
        save("community", community)
        val stamps = "錾章" in community.usingStrings
        val routeName = "app.zhendong.reamicro.Route.${if (stamps) "Stamps" else "Community"}"
        val serializer = anchor(routeName).one("Discover anchor route serializer")
        val route = flow.calls(serializer.descriptor).filter { it.arguments.contains(HostDexCalls.Text(routeName)) }
            .map { it.arguments.filterIsInstance<HostDexCalls.Field>().single() }.distinct().single()
        put("field:route", route.descriptor)
        val callback = community.invokes.filter { it.isConstructor }.mapNotNull { it.declaredClass }.distinctBy { it.descriptor }
            .flatMap { it.methods }.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.Object" &&
                it.usingFields.any { used -> used.field.descriptor == route.descriptor } }.one("community row callback")
        save("callback", callback); put("class:callback", callback.declaredClass!!.descriptor)
        val constructor = community.invokes.filter { it.isConstructor && it.className == callback.className }.one("community row callback constructor")
        val discriminatorIndex = constructor.paramTypeNames.indices.filter { constructor.paramTypeNames[it] in setOf("byte", "int") }.single()
        val discriminator = flow.writes(constructor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) &&
            it.value == HostDexCalls.Parameter(discriminatorIndex + 1) }.map { it.field }.distinct().single()
        val tags = flow.calls(community.descriptor).filter { it.descriptor == constructor.descriptor }
            .map { (it.arguments[discriminatorIndex + 1] as HostDexCalls.Literal).value.toInt() }.distinct()
        val branches = Branches(apk, callback.descriptor)
        val tag = tags.single { branches.references(discriminator, it, route.descriptor) }
        put("field:callbackTag", discriminator); put("value:callbackTag", tag.toString())

        for (key in listOf("text", "weight", "clickable")) {
            val target = shared(key)
            check(community.invokes.any { it.descriptor == target.descriptor }) { "Community does not call native $key" }
            save(key, target)
        }
        val icon = community.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf(vector.name, "java.lang.String", modifier.name, "long", composer.name, "int", "int") }.one("community trailing icon")
        val image = community.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf(vector.name, "java.lang.String", modifier.name, composer.name, "int", "int") }.one("community vector image")
        save("icon", icon); save("image", image)
        val rowImage = if (stamps) community.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramCount == 10 &&
            it.paramTypeNames.slice(1..2) == listOf("java.lang.String", modifier.name) &&
            it.paramTypeNames[5] == "float" && it.paramTypeNames.takeLast(3) == listOf(composer.name, "int", "int")
        }.one("stamps painter image") else image
        save("rowImage", rowImage)
        put("value:rowImage.composer", rowImage.paramTypeNames.indices.single { rowImage.paramTypeNames[it] == composer.name }.toString())
        put("value:rowImage.modifier", "2")
        put("value:rowImage.mask", (rowImage.paramCount - 1).toString())
        val spacer = community.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames.toSet() == setOf(composer.name, modifier.name) && it.paramCount == 2 }.one("community weighted spacer")
        save("spacer", spacer)
        val padding = community.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == modifier.name &&
            it.paramTypeNames == listOf(modifier.name, "float") && it.className == shared("padding").className }.one("community all-sides padding")
        save("paddingAll", padding)
        val calls = flow.calls(community.descriptor)
        val dp = shared("dpInt")
        val size = community.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == modifier.name &&
            it.paramTypeNames == listOf(modifier.name, "float") && calls.any { call -> call.descriptor == it.descriptor &&
                call.arguments[1] == HostDexCalls.Call(dp.descriptor, listOf(HostDexCalls.Literal(24))) } }.one("community icon size")
        save("size", size)
        val divider = community.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf(modifier.name, "long", composer.name, "int", "int") }.one("community divider")
        save("divider", divider)
        val aspectCtor = anchor("aspectRatio ", " must be > 0").filter { it.isConstructor && it.paramTypeNames == listOf("float") }.one("aspect ratio element")
        save("aspectRatio", aspectCtor.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == modifier.name &&
            it.paramTypeNames == listOf(modifier.name, "float") }.one("aspect ratio modifier"))
        val vertical = requireNotNull(bridge.getClassData(ui.getValue("class:verticalAlignment")))
        put("class:verticalAlignment", vertical.descriptor)
        put("constructor:verticalAlignment", vertical.methods.filter { it.isConstructor && it.paramTypeNames == listOf("float") }.one("vertical alignment constructor").descriptor)
        val lazyRow = shared("lazyRow")
        put("class:lazyState", lazyRow.paramTypes[1].descriptor)
        save("lazyState", lazyRow.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == lazyRow.paramTypeNames[1] &&
            it.paramTypeNames == listOf("int", composer.name, "int", "int") }.one("remember lazy list state"))
        val weightType = requireNotNull(anchor("FontWeight(weight=").one("font weight identity").declaredClass)
        put("class:fontWeight", weightType.descriptor)
        put("constructor:fontWeight", weightType.methods.filter { it.isConstructor && it.paramTypeNames == listOf("int") }.one("font weight constructor").descriptor)
        for (key in listOf("community", "icon", "image", "spacer", "divider", "text")) {
            val target = method(getValue("method:$key"))
            put("value:$key.composer", target.paramTypeNames.indices.single { target.paramTypeNames[it] == composer.name }.toString())
            if (key != "community") put("value:$key.modifier", target.paramTypeNames.indices.single { target.paramTypeNames[it] == modifier.name }.toString())
        }
        put("value:image.mask", (image.paramCount - 1).toString()); put("value:text.mask", (shared("text").paramCount - 1).toString())
        put("value:icon.tint", icon.paramTypeNames.indices.single { icon.paramTypeNames[it] == "long" }.toString())
        put("value:divider.color", divider.paramTypeNames.indices.single { divider.paramTypeNames[it] == "long" }.toString())
        put("value:clickable.callback", shared("clickable").paramTypeNames.indices.single { shared("clickable").paramTypeNames[it] == function.name }.toString())
        put("value:clickable.modifier", shared("clickable").paramTypeNames.indices.single { shared("clickable").paramTypeNames[it] == modifier.name }.toString())
    }

    /** Follow only the discriminator's selected branch; do not match a sibling route. */
    private class Branches(apk: String, id: String) {
        private val method = HostDexIndex.forApk(apk).method(id)
        fun references(discriminator: String, tag: Int, target: String): Boolean {
            val body = requireNotNull(method.implementation); val code = body.instructions.toList()
            val offsets = IntArray(code.size); var offset = 0
            code.forEachIndexed { index, instruction -> offsets[index] = offset; offset += instruction.codeUnits }
            val at = offsets.withIndex().associate { it.value to it.index }
            fun field(reference: FieldReference) = "${reference.definingClass}->${reference.name}:${reference.type}"
            val switches = code.indices.filter { index ->
                val instruction = code[index]
                if (instruction.opcode.toString() !in setOf("PACKED_SWITCH", "SPARSE_SWITCH")) return@filter false
                val register = (instruction as OneRegisterInstruction).registerA
                val source = (index - 1 downTo 0).firstOrNull { code[it].opcode.setsRegister() &&
                    (code[it] as? OneRegisterInstruction)?.registerA == register } ?: return@filter false
                val reference = ((code[source] as? ReferenceInstruction)?.reference as? FieldReference) ?: return@filter false
                code[source].opcode.toString().startsWith("IGET") && field(reference) == discriminator
            }
            val index = switches.singleOrNull() ?: return false
            val switch = code[index] as OffsetInstruction
            val payload = at[offsets[index] + switch.codeOffset]?.let { code[it] } as? SwitchPayload ?: return false
            val targets = payload.switchElements.associate { it.key to requireNotNull(at[offsets[index] + it.offset]) }
            val start = targets[tag] ?: index + 1
            val boundaries = targets.values.toSet() + (index + 1)
            val pending = java.util.ArrayDeque<Int>(); val seen = hashSetOf<Int>(); pending.add(start)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current !in code.indices || !seen.add(current) || current != start && current in boundaries) continue
                val instruction = code[current]; val op = instruction.opcode.toString()
                val reference = (instruction as? ReferenceInstruction)?.reference
                if (op.startsWith("SGET") && reference is FieldReference && field(reference) == target) return true
                if (op.startsWith("RETURN") || op == "THROW" || instruction is SwitchPayload) continue
                if (!op.startsWith("GOTO")) pending.add(current + 1)
                if (instruction is OffsetInstruction && (op.startsWith("IF_") || op.startsWith("GOTO"))) at[offsets[current] + instruction.codeOffset]?.let(pending::add)
            }
            return false
        }
    }
}
