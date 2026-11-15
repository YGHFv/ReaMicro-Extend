package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.Opcode.*
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.lang.reflect.Modifier

/** One tiny subclass of the discovered RequestBody. JDK Consumer keeps module classes out of the host loader. */
internal object HostRuntimeBridge {
    fun httpBody(contract: HostRuntimeBindings.Http): ByteArray {
        fun descriptor(type: Class<*>) = "L${type.name.replace('.', '/')};"
        val owner = "LReaMicroDynamicRequestBody;"
        val parent = contract.type("requestBody")
        val base = descriptor(parent)
        val media = descriptor(contract.type("media"))
        val sink = descriptor(contract.type("sink"))
        val file = "Ljava/io/File;"
        val consumer = "Ljava/util/function/Consumer;"
        val parentCtor = parent.declaredConstructors.singleOrNull { it.parameterCount == 0 }
        check(parentCtor == null || Modifier.isPublic(parentCtor.modifiers) || Modifier.isProtected(parentCtor.modifiers))
        // R8 removes RequestBody's empty constructor; native concrete bodies then invoke Object directly.
        val constructorOwner = if (parentCtor == null) "Ljava/lang/Object;" else base
        val fields = listOf("file" to file, "media" to media, "writer" to consumer).map { (name, type) ->
            ImmutableField(owner, name, type, 18, null, emptySet(), emptySet())
        }
        fun params(vararg types: String) = types.map { ImmutableMethodParameter(it, emptySet(), null) }
        fun field(name: String, type: String) = ImmutableFieldReference(owner, name, type)
        val init = ImmutableMethod(owner, "<init>", params(file, media, consumer), "V", 0x10001, emptySet(), emptySet(),
            ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction35c(INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(constructorOwner, "<init>", emptyList(), "V")),
                ImmutableInstruction22c(IPUT_OBJECT, 1, 0, field("file", file)),
                ImmutableInstruction22c(IPUT_OBJECT, 2, 0, field("media", media)),
                ImmutableInstruction22c(IPUT_OBJECT, 3, 0, field("writer", consumer)),
                ImmutableInstruction10x(RETURN_VOID)), emptyList(), emptyList()))
        val length = ImmutableMethod(owner, contract.method("bodyLength").name, emptyList(), "J", 17, emptySet(), emptySet(),
            ImmutableMethodImplementation(3, listOf(
                ImmutableInstruction22c(IGET_OBJECT, 0, 2, field("file", file)),
                ImmutableInstruction35c(INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(file, "length", emptyList(), "J")),
                ImmutableInstruction11x(MOVE_RESULT_WIDE, 0), ImmutableInstruction11x(RETURN_WIDE, 0)), emptyList(), emptyList()))
        val contentType = ImmutableMethod(owner, contract.method("bodyMedia").name, emptyList(), media, 17, emptySet(), emptySet(),
            ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction22c(IGET_OBJECT, 0, 1, field("media", media)), ImmutableInstruction11x(RETURN_OBJECT, 0)), emptyList(), emptyList()))
        val write = ImmutableMethod(owner, contract.method("bodyWrite").name, params(sink), "V", 17, emptySet(), emptySet(),
            ImmutableMethodImplementation(3, listOf(
                ImmutableInstruction22c(IGET_OBJECT, 0, 1, field("writer", consumer)),
                ImmutableInstruction35c(INVOKE_INTERFACE, 2, 0, 2, 0, 0, 0, ImmutableMethodReference(consumer, "accept", listOf("Ljava/lang/Object;"), "V")),
                ImmutableInstruction10x(RETURN_VOID)), emptyList(), emptyList()))
        val type = ImmutableClassDef(owner, 17, base, emptyList(), null, emptySet(), fields, listOf(init, length, contentType, write))
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(type)))
        return store.data
    }
}
