package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import java.util.ArrayDeque

/** Small, conservative reaching-value analysis for native API call arguments.
 * A value survives a control-flow merge only when every incoming path agrees.
 */
internal class HostDexCalls(apk: String) {
    sealed interface Value
    data class Parameter(val index: Int) : Value
    data class Literal(val value: Long) : Value
    data class Text(val value: String) : Value
    data class Type(val descriptor: String) : Value
    data class Field(val descriptor: String) : Value
    data class Call(val descriptor: String, val arguments: List<Value>) : Value
    data class Allocation(val type: String, val offset: Int) : Value
    data class Write(val field: String, val receiver: Value?, val value: Value)
    data class Cast(val type: String, val value: Value)
    data class EqualityText(val value: Value, val literal: Long, val text: String)
    data class BitMask(val parameter: Int, val bit: Int) : Value
    data class NullDefault(val parameter: Int, val maskParameter: Int, val bit: Int)
    data class Arithmetic(val operation: String, val left: Value, val right: Value) : Value
    data class Conversion(val operation: String, val value: Value) : Value
    data class Comparison(val operation: String, val left: Value, val right: Value)
    data object Unknown : Value

    private val index = HostDexIndex.forApk(apk)
    private data class Analysis(val calls: List<Call>, val writes: List<Write>, val casts: List<Cast>,
        val equalityTexts: List<EqualityText>, val nullDefaults: List<NullDefault>, val comparisons: List<Comparison> = emptyList())
    private val cache = HashMap<String, Analysis>()
    fun calls(descriptor: String): List<Call> = analysis(descriptor).calls
    fun calls(descriptor: String, parameters: Map<Int, Long>): List<Call> =
        analyze(index.method(descriptor), parameters).calls
    /** Only immutable fields of this callback may constrain a merged lambda branch. */
    fun callsWithFields(descriptor: String, fields: Map<String, Long>): List<Call> =
        analyze(index.method(descriptor), knownFields = fields).calls
    fun writes(descriptor: String): List<Write> = analysis(descriptor).writes
    fun writes(descriptor: String, parameters: Map<Int, Long>): List<Write> =
        analyze(index.method(descriptor), parameters).writes
    fun casts(descriptor: String): List<Cast> = analysis(descriptor).casts
    fun castsWithFields(descriptor: String, fields: Map<String, Long>): List<Cast> =
        analyze(index.method(descriptor), knownFields = fields).casts
    fun switchKeys(descriptor: String): List<Long> =
        (index.method(descriptor).implementation?.instructions ?: emptyList())
            .filterIsInstance<SwitchPayload>().flatMap { it.switchElements }.map { it.key.toLong() }.distinct()
    fun equalityTexts(descriptor: String): List<EqualityText> = analysis(descriptor).equalityTexts
    fun nullDefaults(descriptor: String): List<NullDefault> = analysis(descriptor).nullDefaults
    fun comparisons(descriptor: String): List<Comparison> = analysis(descriptor).comparisons
    fun literals(descriptor: String): List<Long> = (index.method(descriptor).implementation?.instructions ?: emptyList())
        .filter { it.opcode.toString().startsWith("CONST") }.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }.distinct()
    private fun analysis(descriptor: String): Analysis = cache.getOrPut(descriptor) { analyze(index.method(descriptor)) }
    fun referencingType(type: String): List<String> = index.referencingType(type)
    private fun descriptor(m: MethodReference) = "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"
    private fun descriptor(f: FieldReference) = "${f.definingClass}->${f.name}:${f.type}"
    private fun words(i: Instruction): List<Int> = when (i) {
        is FiveRegisterInstruction -> listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
        is RegisterRangeInstruction -> (i.startRegister until i.startRegister + i.registerCount).toList()
        else -> emptyList()
    }
    private fun bounded(v: Value, depth: Int = 0): Value = when {
        depth >= 5 -> Unknown
        v is Call -> Call(v.descriptor, v.arguments.map { bounded(it, depth + 1) })
        v is Arithmetic -> Arithmetic(v.operation, bounded(v.left, depth + 1), bounded(v.right, depth + 1))
        v is Conversion -> Conversion(v.operation, bounded(v.value, depth + 1))
        else -> v
    }
    private fun merge(left: Value, right: Value): Value = when {
        left == right -> left
        left is Call && right is Call && left.descriptor == right.descriptor && left.arguments.size == right.arguments.size ->
            Call(left.descriptor, left.arguments.zip(right.arguments, ::merge))
        else -> Unknown
    }
    private fun analyze(method: Method, knownParameters: Map<Int, Long> = emptyMap(),
        knownFields: Map<String, Long> = emptyMap()): Analysis {
        val body = method.implementation ?: return Analysis(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val code = body.instructions.toList()
        val offsets = IntArray(code.size)
        var offset = 0
        code.forEachIndexed { i, instruction -> offsets[i] = offset; offset += instruction.codeUnits }
        val at = offsets.withIndex().associate { it.value to it.index }
        val initial = MutableList<Value>(body.registerCount + 1) { Unknown }
        val parameters = (if (method.accessFlags and 8 == 0) listOf(method.definingClass) else emptyList()) + method.parameterTypes
        var register = body.registerCount - parameters.sumOf { if (it.toString() in setOf("J", "D")) 2 else 1 }
        parameters.forEachIndexed { i, type -> initial[register] = knownParameters[i]?.let(::Literal) ?: Parameter(i); register += if (type.toString() in setOf("J", "D")) 2 else 1 }
        val pending = ArrayDeque<Int>()
        val states = arrayOfNulls<List<Value>>(code.size)
        if (code.isEmpty()) return Analysis(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        states[0] = initial; pending.add(0)
        fun invoke(instruction: Instruction, state: List<Value>): Call? {
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
            val regs = words(instruction)
            val types = (if (instruction.opcode.toString().startsWith("INVOKE_STATIC")) emptyList() else listOf(reference.definingClass)) + reference.parameterTypes
            var word = 0
            val args = types.map { type ->
                val value = regs.getOrNull(word)?.let { state[it] } ?: Unknown
                word += if (type.toString() in setOf("J", "D")) 2 else 1
                value
            }
            return Call(descriptor(reference), args)
        }
        fun bitMask(left: Value, right: Value): Value {
            if (left is Literal && right is Literal) return Literal(left.value and right.value)
            val parameter = (left as? Parameter) ?: (right as? Parameter) ?: return Unknown
            val literal = (left as? Literal) ?: (right as? Literal) ?: return Unknown
            val bit = literal.value.toInt()
            return if (bit > 0 && bit.countOneBits() == 1) BitMask(parameter.index, bit) else Unknown
        }
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            val instruction = code[index]
            val state = states[index]!!.toMutableList()
            val op = instruction.opcode.toString()
            val one = instruction as? OneRegisterInstruction
            val two = instruction as? TwoRegisterInstruction
            val ref = (instruction as? ReferenceInstruction)?.reference
            when {
                (op == "CONST_STRING" || op == "CONST_STRING_JUMBO") && ref is StringReference -> state[one!!.registerA] = Text(ref.string)
                op == "CONST_CLASS" && ref is TypeReference -> state[one!!.registerA] = Type(ref.type)
                op.startsWith("CONST") && instruction is WideLiteralInstruction -> state[one!!.registerA] = Literal(instruction.wideLiteral)
                op.startsWith("MOVE_RESULT") -> state[one!!.registerA] = state.last()
                op.startsWith("MOVE") && two != null -> state[two.registerA] = state[two.registerB]
                (op.startsWith("SGET") || op.startsWith("IGET")) && ref is FieldReference -> {
                    val value = if (method.accessFlags and 8 == 0 && op.startsWith("IGET") && two != null && state[two.registerB] == Parameter(0))
                        knownFields[descriptor(ref)] else null
                    state[one!!.registerA] = value?.let(::Literal) ?: Field(descriptor(ref))
                }
                op == "NEW_INSTANCE" -> state[one!!.registerA] = Allocation((ref as TypeReference).type, offsets[index])
                op == "CHECK_CAST" || op == "NOP" -> Unit
                op in setOf("AND_INT_LIT8", "AND_INT_LIT16") && instruction is NarrowLiteralInstruction && two != null ->
                    state[two.registerA] = bitMask(state[two.registerB], Literal(instruction.narrowLiteral.toLong()))
                op == "AND_INT" && instruction is ThreeRegisterInstruction ->
                    state[instruction.registerA] = bitMask(state[instruction.registerB], state[instruction.registerC])
                op == "AND_INT_2ADDR" && two != null -> state[two.registerA] = bitMask(state[two.registerA], state[two.registerB])
                op in setOf("ADD_INT", "SUB_INT") && instruction is ThreeRegisterInstruction ->
                    state[instruction.registerA] = bounded(Arithmetic(op.substringBefore('_'), state[instruction.registerB], state[instruction.registerC]))
                op in setOf("ADD_INT_2ADDR", "SUB_INT_2ADDR") && two != null ->
                    state[two.registerA] = bounded(Arithmetic(op.substringBefore('_'), state[two.registerA], state[two.registerB]))
                op == "INT_TO_FLOAT" && two != null ->
                    state[two.registerA] = bounded(Conversion(op, state[two.registerB]))
                op.startsWith("INVOKE") -> state[state.lastIndex] = invoke(instruction, state)?.let { bounded(it) } ?: Unknown
                instruction.opcode.setsRegister() && one != null -> state[one.registerA] = Unknown
            }
            val next = mutableListOf<Int>()
            val branch = if (op.startsWith("IF_") && one != null) {
                val left = state[one.registerA] as? Literal
                val right = if (two != null) state[two.registerB] as? Literal else Literal(0)
                if (left != null && right != null) when (op.removeSuffix("Z")) {
                    "IF_EQ" -> left.value == right.value; "IF_NE" -> left.value != right.value
                    "IF_LT" -> left.value < right.value; "IF_LE" -> left.value <= right.value
                    "IF_GT" -> left.value > right.value; "IF_GE" -> left.value >= right.value
                    else -> null
                } else null
            } else null
            if (!op.startsWith("RETURN") && op != "THROW" && !op.startsWith("GOTO") && branch != true && index + 1 < code.size) next += index + 1
            if (instruction is OffsetInstruction && (op.startsWith("IF_") || op.startsWith("GOTO"))) {
                if (branch != false) at[offsets[index] + instruction.codeOffset]?.let(next::add)
            } else if (instruction is OffsetInstruction && op in setOf("PACKED_SWITCH", "SPARSE_SWITCH")) {
                val payload = at[offsets[index] + instruction.codeOffset]?.let { code[it] } as? SwitchPayload
                val key = one?.let { state[it.registerA] } as? Literal
                if (payload != null && key != null) {
                    val selected = payload.switchElements.singleOrNull { it.key.toLong() == key.value }
                    if (selected != null) {
                        next.clear()
                        at[offsets[index] + selected.offset]?.let(next::add)
                    }
                } else payload?.switchElements?.forEach { at[offsets[index] + it.offset]?.let(next::add) }
            }
            for (target in next.distinct()) {
                val old = states[target]
                val merged = old?.mapIndexed { i, value -> merge(value, state[i]) } ?: state.toList()
                if (old != merged) { states[target] = merged; pending.add(target) }
            }
            // A failed operation preserves unrelated registers. In particular, graphics setup
            // often computes viewport dimensions before a recoverable tint-resource lookup.
            // Merge the pre-instruction state and invalidate only the result/destination.
            if (instruction.opcode.canThrow()) for (block in body.tryBlocks) if (offsets[index] in block.startCodeAddress until block.startCodeAddress + block.codeUnitCount) {
                val exceptional = states[index]!!.toMutableList()
                exceptional[exceptional.lastIndex] = Unknown
                if (instruction.opcode.setsRegister() && one != null) {
                    exceptional[one.registerA] = Unknown
                    if (instruction.opcode.setsWideRegister()) exceptional[one.registerA + 1] = Unknown
                }
                for (handler in block.exceptionHandlers) {
                    val target = at[handler.handlerCodeAddress] ?: continue
                    val old = states[target]
                    val merged = old?.mapIndexed { i, value -> merge(value, exceptional[i]) } ?: exceptional.toList()
                    if (old != merged) { states[target] = merged; pending.add(target) }
                }
            }
        }
        val calls = code.mapIndexedNotNull { i, instruction ->
            states[i]?.takeIf { instruction.opcode.toString().startsWith("INVOKE") }?.let { invoke(instruction, it) }
        }
        val writes = code.mapIndexedNotNull { i, instruction ->
            val state = states[i] ?: return@mapIndexedNotNull null
            val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: return@mapIndexedNotNull null
            val op = instruction.opcode.toString()
            when {
                op.startsWith("IPUT") && instruction is TwoRegisterInstruction ->
                    Write(descriptor(reference), state[instruction.registerB], state[instruction.registerA])
                op.startsWith("SPUT") && instruction is OneRegisterInstruction ->
                    Write(descriptor(reference), null, state[instruction.registerA])
                else -> null
            }
        }
        val casts = code.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode.toString() != "CHECK_CAST") return@mapIndexedNotNull null
            val type = ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type ?: return@mapIndexedNotNull null
            val register = (instruction as? OneRegisterInstruction)?.registerA ?: return@mapIndexedNotNull null
            states[index]?.let { Cast(type, it[register]) }
        }
        // Recover inlined enum labels only when an equality edge reaches a string directly,
        // before another branch or call. No label is inferred across merged control flow.
        fun edgeText(start: Int): String? {
            var cursor = start
            val seen = hashSetOf<Int>()
            repeat(16) {
                if (cursor !in code.indices || !seen.add(cursor)) return null
                val instruction = code[cursor]
                val op = instruction.opcode.toString()
                val reference = (instruction as? ReferenceInstruction)?.reference
                if ((op == "CONST_STRING" || op == "CONST_STRING_JUMBO") && reference is StringReference) return reference.string
                if (op.startsWith("GOTO") && instruction is OffsetInstruction) {
                    cursor = at[offsets[cursor] + instruction.codeOffset] ?: return null
                } else if (op == "NOP" || op.startsWith("MOVE") || op.startsWith("CONST")) cursor++
                else return null
            }
            return null
        }
        val labels = code.mapIndexedNotNull { index, instruction ->
            val op = instruction.opcode.toString()
            if (op != "IF_EQ" && op != "IF_NE") return@mapIndexedNotNull null
            val state = states[index] ?: return@mapIndexedNotNull null
            val pair = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
            val left = state[pair.registerA]; val right = state[pair.registerB]
            val literal = (left as? Literal) ?: (right as? Literal) ?: return@mapIndexedNotNull null
            val value = if (left is Literal) right else left
            if (value == Unknown || value is Literal) return@mapIndexedNotNull null
            val edge = if (op == "IF_EQ") at[offsets[index] + (instruction as OffsetInstruction).codeOffset] else index + 1
            val label = edge?.let(::edgeText) ?: return@mapIndexedNotNull null
            EqualityText(value, literal.value, label)
        }.distinct()
        val nullDefaults = code.flatMapIndexed { index, instruction ->
            val op = instruction.opcode.toString()
            if (op != "IF_EQZ" && op != "IF_NEZ") return@flatMapIndexed emptyList()
            val state = states[index] ?: return@flatMapIndexed emptyList()
            val mask = state[(instruction as OneRegisterInstruction).registerA] as? BitMask ?: return@flatMapIndexed emptyList()
            var cursor = if (op == "IF_NEZ") at[offsets[index] + (instruction as OffsetInstruction).codeOffset]
                ?: return@flatMapIndexed emptyList() else index + 1
            val found = arrayListOf<NullDefault>()
            val seen = hashSetOf<Int>()
            // Require a direct assignment on the mask's set edge. A call or branch would
            // require conditional analysis beyond this small default-parameter proof.
            for (ignored in 0 until 16) {
                if (cursor !in code.indices || !seen.add(cursor)) break
                val current = code[cursor]; val currentOp = current.opcode.toString()
                val assignsNull = currentOp.startsWith("CONST") && current is WideLiteralInstruction && current.wideLiteral == 0L ||
                    currentOp.startsWith("MOVE_OBJECT") && current is TwoRegisterInstruction && states[cursor]?.get(current.registerB) == Literal(0)
                if (assignsNull) {
                    val register = (current as OneRegisterInstruction).registerA
                    (states[cursor]?.get(register) as? Parameter)?.let { found += NullDefault(it.index, mask.parameter, mask.bit) }
                }
                if (currentOp.startsWith("GOTO") && current is OffsetInstruction) cursor = at[offsets[cursor] + current.codeOffset] ?: break
                else if (currentOp == "NOP" || currentOp.startsWith("MOVE") || currentOp.startsWith("CONST")) cursor++
                else break
            }
            found
        }.distinct()
        val comparisons = code.mapIndexedNotNull { index, instruction ->
            val op = instruction.opcode.toString()
            if (op !in setOf("IF_EQ", "IF_NE", "IF_LT", "IF_LE", "IF_GT", "IF_GE")) return@mapIndexedNotNull null
            val pair = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
            val state = states[index] ?: return@mapIndexedNotNull null
            Comparison(op, state[pair.registerA], state[pair.registerB])
        }.distinct()
        return Analysis(calls, writes, casts, labels, nullDefaults, comparisons)
    }
}
