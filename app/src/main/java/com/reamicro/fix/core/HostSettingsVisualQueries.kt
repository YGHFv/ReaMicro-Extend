package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Reuses the actual settings row, including its shape, arrow and dimension scaling. */
internal object HostSettingsVisualQueries {
    fun query(
        bridge: DexKitBridge,
        apk: String,
        settings: Map<String, String>,
        ui: Map<String, String>,
    ): Map<String, String> {
        val flow = HostDexCalls(apk)
        fun method(role: String) = requireNotNull(bridge.getMethodData(ui.getValue("method:$role")))
        val list = method("listItem")
        val clickable = method("clickable")
        val colors = method("listColors")
        val background = method("backgroundAuto")
        method("clip")
        val border = method("border")
        val height = method("height")
        val fill = method("fillWidth")
        val function0 = clickable.paramTypeNames[4]
        val composer = list.paramTypeNames[7]
        val candidates = list.callers.filter { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.paramTypeNames == listOf(function0, composer, "int") &&
                candidate.returnTypeName == "void" &&
                flow.calls(candidate.descriptor).any { call ->
                    call.descriptor == colors.descriptor &&
                        (call.arguments.firstOrNull() as? HostDexCalls.Call)?.descriptor == background.descriptor
                } &&
                flow.calls(candidate.descriptor).any { call ->
                    call.descriptor == clickable.descriptor && call.arguments[4] == HostDexCalls.Parameter(0)
                }
        }.distinctBy { it.descriptor }
        val builder = settings.getValue("method:builder")
        val builderCalls = flow.calls(builder)
        fun reachedFromSettings(candidate: MethodData): Boolean = builderCalls.any { construction ->
            val constructor = bridge.getMethodData(construction.descriptor) ?: return@any false
            if (!constructor.isConstructor || construction.arguments.firstOrNull() !is HostDexCalls.Allocation) return@any false
            val callback = constructor.declaredClass ?: return@any false
            val constants = flow.writes(constructor.descriptor).mapNotNull { write ->
                val parameter = write.value as? HostDexCalls.Parameter ?: return@mapNotNull null
                val literal = construction.arguments.getOrNull(parameter.index) as? HostDexCalls.Literal
                    ?: return@mapNotNull null
                val field = bridge.getFieldData(write.field) ?: return@mapNotNull null
                if (write.receiver != HostDexCalls.Parameter(0) || !Modifier.isFinal(field.modifiers) ||
                    field.typeName !in setOf("int", "byte", "short", "boolean")) return@mapNotNull null
                write.field to literal.value
            }.toMap()
            callback.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(3) { "java.lang.Object" } &&
                    it.returnTypeName == "java.lang.Object"
            }.any { body ->
                flow.callsWithFields(body.descriptor, constants).any { it.descriptor == candidate.descriptor }
            }
        }
        val entry = candidates.filter(::reachedFromSettings).singleOrNull()
            ?: error("Native settings entry is missing or ambiguous: ${candidates.map { it.descriptor }}")
        val calls = flow.calls(entry.descriptor)
        fun call(role: String) = calls.filter { it.descriptor == method(role).descriptor }.distinct().single()
        fun literal(value: HostDexCalls.Value): Long =
            (value as? HostDexCalls.Literal)?.value ?: error("Unproven native settings constant: $value")
        fun dimension(value: HostDexCalls.Value, role: String): Long {
            val scaled = value as? HostDexCalls.Call ?: error("Native settings dimension is not scaled: $value")
            check(scaled.descriptor == method(role).descriptor)
            return literal(scaled.arguments.single())
        }
        val listCall = call("listItem")
        val shape = call("clip").arguments[1] as? HostDexCalls.Field
            ?: error("Native settings shape is not a host field")
        check(call("border").arguments[3] == shape)
        check(call("border").descriptor == border.descriptor && call("height").descriptor == height.descriptor &&
            call("fillWidth").descriptor == fill.descriptor)
        val arrow = listCall.arguments[4] as? HostDexCalls.Field
            ?: error("Native settings arrow is not a host slot")
        check(listCall.arguments[2] == HostDexCalls.Literal(0) && listCall.arguments[3] == HostDexCalls.Literal(0))
        val rowHeight = dimension(call("height").arguments[1], "dpInt")
        val borderWidth = Double.fromBits(dimension(call("border").arguments[1], "dpDouble"))
        val elevation = dimension(listCall.arguments[6], "dpInt")
        check(rowHeight in 1..200 && borderWidth.isFinite() && borderWidth in 0.0..8.0 && elevation in 0..32)
        check(Float.fromBits(literal(call("fillWidth").arguments[1]).toInt()) == 1f)
        return linkedMapOf(
            "method:settingsEntry" to entry.descriptor,
            "field:settingsShape" to shape.descriptor,
            "field:settingsArrow" to arrow.descriptor,
            "value:settingsHeight" to rowHeight.toString(),
            "value:settingsBorderWidth" to borderWidth.toString(),
            "value:settingsElevation" to elevation.toString(),
            "value:settingsClickMask" to literal(call("clickable").arguments[5]).toString(),
            "value:settingsListFlags" to literal(listCall.arguments[8]).toString(),
            "value:settingsListMask" to literal(listCall.arguments[9]).toString(),
        )
    }
}
