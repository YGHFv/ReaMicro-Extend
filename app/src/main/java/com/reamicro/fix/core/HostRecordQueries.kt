package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

/** Links a printed property label to the field value actually appended after it.
 * Unsupported control flow is rejected; declaration/string/field order is never a mapping.
 */
internal object HostRecordQueries {
    private val propertyLabel = Regex("(?:^|\\(|,\\s*)([A-Za-z_][A-Za-z0-9_]*)=$")
    private sealed interface Value
    private data class Text(val value: String) : Value
    private data class Property(val field: String) : Value
    private data class Literal(val value: Long) : Value
    private class Builder(val parts: MutableList<Value> = arrayListOf()) : Value
    private data object Receiver : Value
    private data object Unknown : Value
    private data object Completed : Value

    fun query(apk: String, types: Map<String, String>, scalarFormatters: Set<String> = emptySet(),
        allowPartial: Boolean = false): Map<String, String> {
        val aliases = types.filterKeys { it.startsWith("class:") }.entries.groupBy({ it.value }, { it.key.removePrefix("class:") })
        val index = HostDexIndex.forApk(apk)
        // A value-class formatter changes the printed representation, not the field's origin.
        // Only admit the independently identified Color formatter, never an arbitrary String call.
        val color = types["class:androidx.compose.ui.graphics.Color"]?.let(index::classByType)
        val colorFormatters = color?.methods?.filter { method ->
            method.accessFlags and 8 != 0 &&
                method.parameterTypes.map { it.toString() } == listOf("J") && method.returnType == "Ljava/lang/String;" &&
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "Color("
                } == true
        }?.map(::descriptor).orEmpty().toSet() + scalarFormatters
        check(scalarFormatters.all { descriptor -> index.findMethod(descriptor)?.let {
            it.accessFlags and 8 != 0 && it.parameterTypes.size == 1 && it.returnType == "Ljava/lang/String;"
        } == true }) { "Record value formatters require a static scalar-to-String contract" }
        val output = linkedMapOf<String, String>()
        for ((actual, logicals) in aliases) {
            val type = index.classByType(actual) ?: continue
            val stringMethod = type.methods.singleOrNull {
                it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            } ?: continue
            val parts = evaluate(stringMethod, listOf(Receiver), index, colorFormatters, emptySet()) as? Builder ?: continue
            val properties = linkedMapOf<String, String>()
            var text = ""
            var valid = true
            for (value in parts.parts) when (value) {
                is Text -> text += value.value
                is Property -> {
                    val name = propertyLabel.find(text)?.groupValues?.get(1)
                    if (name == null || !value.field.startsWith(type.type + "->")) {
                        if (!allowPartial) valid = false
                    } else if (properties.put(name, value.field)?.let { it != value.field } == true) valid = false
                    text = ""
                }
                else -> { text = ""; if (!allowPartial) valid = false }
            }
            // R8 may share a field for two equal properties (e.g. onPrimaryFixed).
            // Each association is still proven by the appended register value.
            if (!valid || properties.isEmpty()) continue
            val getters = type.methods.mapNotNull { method -> accessor(method)?.let { it to method } }
                .groupBy({ it.first }, { it.second })
            for (logical in logicals) for ((name, field) in properties) {
                output["field:$logical#$name"] = field
                val getter = getters[field]?.singleOrNull() ?: continue
                output["method:$logical#${if (name.startsWith("is") && name.getOrNull(2)?.isUpperCase() == true) name else "get" + name.replaceFirstChar(Char::uppercaseChar)}"] = descriptor(getter)
            }
        }
        return output
    }

    private fun descriptor(method: MethodReference): String = method.definingClass + "->" + method.name +
        method.parameterTypes.joinToString("", "(", ")") + method.returnType
    private fun field(reference: FieldReference): String = "${reference.definingClass}->${reference.name}:${reference.type}"
    private fun arguments(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        else -> emptyList()
    }

    private fun accessor(method: Method): String? {
        if (method.parameterTypes.isNotEmpty() || method.accessFlags and 8 != 0 || method.returnType == "V" ||
            method.name in setOf("hashCode", "toString", "getClass")) return null
        val body = method.implementation ?: return null
        val instructions = body.instructions.iterator()
        if (!instructions.hasNext()) return null
        val read = instructions.next() as? TwoRegisterInstruction ?: return null
        val reference = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return null
        if (!read.opcode.toString().startsWith("IGET") || reference.definingClass != method.definingClass ||
            read.registerB != body.registerCount - 1 || reference.type != method.returnType) return null
        if (!instructions.hasNext()) return null
        val result = instructions.next() as? OneRegisterInstruction ?: return null
        return field(reference).takeIf {
            !instructions.hasNext() && result.opcode.toString().startsWith("RETURN") && result.registerA == read.registerA
        }
    }

    private fun evaluate(method: Method, args: List<Value>, index: HostDexIndex,
        colorFormatters: Set<String>, stack: Set<String>): Value {
        val id = descriptor(method)
        val body = method.implementation ?: return Unknown
        if (stack.size >= 5 || id in stack) return Unknown
        val instructions = body.instructions.toList()
        if (instructions.size > 500 || body.tryBlocks.isNotEmpty() || instructions.any { it is OffsetInstruction }) return Unknown
        val registers = HashMap<Int, Value>()
        val types = (if (method.accessFlags and 8 == 0) listOf(method.definingClass) else emptyList()) + method.parameterTypes
        var register = body.registerCount - types.sumOf { if (it.toString() in setOf("J", "D")) 2 else 1 }
        types.forEachIndexed { index, type -> registers[register] = args.getOrElse(index) { Unknown }; register += if (type.toString() in setOf("J", "D")) 2 else 1 }
        var result: Value = Unknown
        for (instruction in instructions) {
            val op = instruction.opcode.toString()
            val one = instruction as? OneRegisterInstruction
            val two = instruction as? TwoRegisterInstruction
            val reference = (instruction as? ReferenceInstruction)?.reference
            when {
                op == "CONST_STRING" || op == "CONST_STRING_JUMBO" -> registers[one!!.registerA] = Text((reference as StringReference).string)
                op.startsWith("CONST") && instruction is WideLiteralInstruction -> registers[one!!.registerA] = Literal(instruction.wideLiteral)
                op.startsWith("MOVE_RESULT") -> registers[one!!.registerA] = result
                op.startsWith("MOVE") && two != null -> registers[two.registerA] = registers[two.registerB] ?: Unknown
                op.startsWith("IGET") && two != null && reference is FieldReference -> {
                    registers[two.registerA] = if (registers[two.registerB] == Receiver && reference.definingClass == method.definingClass) Property(field(reference)) else Unknown
                }
                op == "NEW_INSTANCE" -> registers[one!!.registerA] = if ((reference as TypeReference).type == "Ljava/lang/StringBuilder;") Builder() else Unknown
                op == "CHECK_CAST" || op == "NOP" -> Unit
                op.startsWith("INVOKE") && reference is MethodReference -> {
                    val words = arguments(instruction)
                    val callTypes = (if (op.startsWith("INVOKE_STATIC")) emptyList() else listOf(reference.definingClass)) + reference.parameterTypes
                    var word = 0
                    val values = callTypes.map { type ->
                        val value = words.getOrNull(word)?.let { registers[it] } ?: Unknown
                        word += if (type.toString() in setOf("J", "D")) 2 else 1
                        value
                    }
                    val builder = values.firstOrNull() as? Builder
                    result = when {
                        reference.definingClass == "Ljava/lang/StringBuilder;" && builder != null -> when (reference.name) {
                            "<init>" -> { if (reference.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/String;") builder.parts += values[1]; Unknown }
                            "append" -> { builder.parts += values.getOrElse(1) { Unknown }.let { v ->
                                if (v is Literal) Text(if (reference.parameterTypes.firstOrNull()?.toString() == "C") v.value.toInt().toChar().toString() else v.value.toString()) else v
                            }; builder }
                            "toString" -> builder
                            else -> Unknown
                        }
                        reference.definingClass == "Ljava/lang/String;" && reference.name == "valueOf" && values.size == 1 -> values[0]
                        descriptor(reference) in colorFormatters && values.singleOrNull() is Property -> values.single()
                        else -> index.findMethod(descriptor(reference))?.let { evaluate(it, values, index, colorFormatters, stack + id) } ?: Unknown
                    }
                    if (result == Unknown && values.any { it is Builder } &&
                        !(reference.definingClass == "Ljava/lang/StringBuilder;" && reference.name == "<init>")) return Unknown
                }
                op == "RETURN_VOID" -> return Completed
                op.startsWith("RETURN") -> return one?.let { registers[it.registerA] } ?: Unknown
                else -> {
                    if (instruction.opcode.setsRegister() && one != null) registers[one.registerA] = Unknown
                    // Writes, monitor operations and unknown calls cannot prove a record formatter.
                    if (op.startsWith("IPUT") || op.startsWith("SPUT") || op.startsWith("THROW")) return Unknown
                }
            }
        }
        return Unknown
    }
}
