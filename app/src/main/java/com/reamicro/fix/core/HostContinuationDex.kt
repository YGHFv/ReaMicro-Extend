package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.Opcode.*
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.lang.reflect.Modifier

/** A tiny in-memory carrier for the current, validated host superclass. No per-version DEX asset. */
internal object HostContinuationDex {
    fun generate(constructor: java.lang.reflect.Constructor<*>, continuation: Class<*>, resume: java.lang.reflect.Method): ByteArray {
        val parent = constructor.declaringClass
        require(continuation.isInterface && Modifier.isAbstract(parent.modifiers))
        require(resume.parameterTypes.contentEquals(arrayOf(Any::class.java)) && resume.returnType == Any::class.java &&
            !Modifier.isFinal(resume.modifiers) && !Modifier.isStatic(resume.modifiers) && resume.declaringClass.isAssignableFrom(parent))
        require(constructor.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType, continuation))
        require(Modifier.isPublic(constructor.modifiers) || Modifier.isProtected(constructor.modifiers))
        fun descriptor(type: Class<*>) = "L${type.name.replace('.', '/')};"
        val owner = "LReaMicroContinuationCarrier;"
        val base = descriptor(parent)
        val completion = descriptor(continuation)
        val init = ImmutableMethod(owner, "<init>", listOf(ImmutableMethodParameter(completion, emptySet(), null)),
            "V", 0x10001, emptySet(), emptySet(), ImmutableMethodImplementation(3, listOf(
                ImmutableInstruction11n(CONST_4, 0, 2),
                ImmutableInstruction35c(INVOKE_DIRECT, 3, 1, 0, 2, 0, 0,
                    ImmutableMethodReference(base, "<init>", listOf("I", completion), "V")),
                ImmutableInstruction10x(RETURN_VOID)), emptyList(), emptyList()))
        val invoke = ImmutableMethod(owner, resume.name,
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", emptySet(), null)), "Ljava/lang/Object;", 17,
            emptySet(), emptySet(), ImmutableMethodImplementation(2,
                listOf(ImmutableInstruction11x(RETURN_OBJECT, 1)), emptyList(), emptyList()))
        val type = ImmutableClassDef(owner, 17, base, emptyList(), null, emptySet(), emptyList(), listOf(init, invoke))
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(type)))
        return store.data
    }
}
