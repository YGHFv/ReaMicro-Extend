package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Bind the current APK's vector factories and SVG parser by their native call chains. */
internal object HostGraphicsQueries {
    private fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun query(bridge: DexKitBridge, apk: String): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun method(descriptor: String) = requireNotNull(bridge.getMethodData(descriptor))
        fun anchor(vararg text: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*text)))
        fun HostDexCalls.Value.contains(text: String): Boolean = this == HostDexCalls.Text(text) ||
            this is HostDexCalls.Call && arguments.any { it.contains(text) }
        fun literal(value: HostDexCalls.Value) = (value as? HostDexCalls.Literal)?.value
            ?: error("Native vector factory argument is not a constant: $value")
        fun save(key: String, value: MethodData) { put("${if (value.isConstructor) "constructor" else "method"}:$key", value.descriptor) }
        fun template(key: String, args: List<HostDexCalls.Value>) {
            args.forEachIndexed { index, value -> put("value:$key.$index", literal(value).toString()) }
        }

        val build = anchor("ImageVector.Builder is single use, create a new instance to create a new ImageVector")
            .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName != "void" }.one("vector build")
        val builder = requireNotNull(build.declaredClass)
        val vector = requireNotNull(build.returnType)
        put("class:builder", builder.descriptor); put("class:vector", vector.descriptor); save("build", build)
        val colored = anchor("Colored.Yun115").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == vector.name }.one("colored vector factory")
        val filled = anchor("Filled.PlayArrow").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == vector.name }.one("filled vector factory")
        val inflate = anchor("No path data available", "viewportWidth", "viewportHeight").one("XML vector inflation")
        val native = flow.calls(colored.descriptor)
        val xml = flow.calls(inflate.descriptor)
        val constructor = colored.invokes.filter { it.isConstructor && it.className == builder.name }.one("vector builder constructor")
        save("builder", constructor)
        val ctorCall = native.filter { it.descriptor == constructor.descriptor }.single()
        val xmlCtor = xml.filter { it.descriptor == constructor.descriptor }.single()
        check(ctorCall.arguments.size == constructor.paramCount + 1 && xmlCtor.arguments.size == ctorCall.arguments.size)
        val viewport = constructor.paramTypeNames.indices.filter { index -> constructor.paramTypeNames[index] == "float" &&
            (xmlCtor.arguments[index + 1].contains("viewportWidth") || xmlCtor.arguments[index + 1].contains("viewportHeight")) }
        check(viewport.size == 2)
        val size = constructor.paramTypeNames.indices.filter { constructor.paramTypeNames[it] == "float" && it !in viewport }
        check(size.size == 2)
        constructor.paramTypeNames.forEachIndexed { index, type ->
            val value = when {
                index in size -> "size"
                index in viewport -> "viewport"
                type == "java.lang.String" && ctorCall.arguments[index + 1] == HostDexCalls.Text("Colored.Yun115") -> "name"
                else -> "literal:${literal(ctorCall.arguments[index + 1])}"
            }
            put("value:builder.$index", value)
        }

        val brush = requireNotNull(anchor("SolidColor(value=").one("solid color identity").declaredClass)
        put("class:brush", brush.descriptor)
        val brushCtor = colored.invokes.filter { it.isConstructor && it.className == brush.name && it.paramTypeNames == listOf("long") }.one("solid color constructor")
        save("brush", brushCtor)
        val colorCalls = native.filter { it.descriptor == brushCtor.descriptor }.map { it.arguments[1] }.distinct()
        val conversion = colorCalls.single() as HostDexCalls.Call
        val color = method(conversion.descriptor)
        check(Modifier.isStatic(color.modifiers) && color.paramTypeNames == listOf("long") && color.returnTypeName == "long")
        save("color", color)

        val add = colored.invokes.filter { it.className == builder.name && Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramTypeNames.containsAll(listOf(builder.name, "java.util.ArrayList", brush.name)) }.one("colored vector add path")
        save("add", add)
        val addCall = native.filter { it.descriptor == add.descriptor }.single()
        val filledCall = flow.calls(filled.descriptor).filter { it.descriptor == add.descriptor }.single()
        check(addCall.arguments.size == add.paramCount && filledCall.arguments.size == add.paramCount)
        val fill = add.paramTypeNames.indices.filter { add.paramTypeNames[it] == "int" && filledCall.arguments[it] == HostDexCalls.Literal(0) }.single()
        check(add.paramTypeNames.indices.filter { add.paramTypeNames[it] == "int" && it != fill }
            .single().let { filledCall.arguments[it] == HostDexCalls.Literal(2) })
        add.paramTypeNames.forEachIndexed { index, type ->
            put("value:add.$index", when (type) {
                builder.name -> "builder"
                brush.name -> "brush"
                "java.util.ArrayList" -> "nodes"
                else -> if (index == fill) "fill" else "literal:${literal(addCall.arguments[index])}"
            })
        }

        val parse = anchor("Unknown command for: ").filter { !Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf("java.lang.String", "java.util.ArrayList") && inflate.invokes.any { called -> called.descriptor == it.descriptor } }.one("native SVG parser")
        val parser = requireNotNull(parse.declaredClass)
        put("class:parser", parser.descriptor); save("parse", parse)
        val receiver = xml.filter { it.descriptor == parse.descriptor }.map { it.arguments.first() }.distinct().single()
        val allocations = when (receiver) {
            is HostDexCalls.Allocation -> xml.filter { it.arguments.firstOrNull() == receiver && it.descriptor.startsWith("${parser.descriptor}-><init>(") }
            is HostDexCalls.Field -> {
                val field = requireNotNull(bridge.getFieldData(receiver.descriptor))
                check(field.typeName in setOf(parser.name, "java.lang.Object") && !Modifier.isStatic(field.modifiers))
                (listOf(inflate) + inflate.invokes.filter { it.isConstructor && it.className == field.className }).flatMap { owner ->
                    val writes = flow.writes(owner.descriptor).filter { it.field == receiver.descriptor }
                    flow.calls(owner.descriptor).filter { call -> call.descriptor.startsWith("${parser.descriptor}-><init>(") && writes.any { it.value == call.arguments.firstOrNull() } }
                }
            }
            else -> error("SVG parser receiver has no constructor provenance: $receiver")
        }.distinct()
        val parserCall = allocations.single()
        val parserCtor = method(parserCall.descriptor)
        check(parserCtor.isConstructor && parserCtor.paramTypeNames.all { it in setOf("byte", "boolean", "int") })
        save("parser", parserCtor); template("parser", parserCall.arguments.drop(1))
    }
}
