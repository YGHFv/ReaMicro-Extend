package com.reamicro.fix.core

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Owned by a query session: DEX bytes and indexes are released when that session closes. */
internal class HostDexIndex(apk: String, apks: List<String> = listOf(apk)) {
    val classes: List<ClassDef> = apks.flatMapIndexed { index, path ->
        load(File(path), requireDex = index == 0)
    }.also { classes ->
        // DexKit queries all inputs, whereas ART resolves the first definition.
        // Ambiguous duplicate classes must not silently produce a different binding.
        require(classes.map { it.type }.toSet().size == classes.size) { "Duplicate host DEX class definitions" }
    }
    private val classesByType by lazy { classes.associateBy { it.type } }
    private val ownerMethods = HashMap<String, Map<String, Method>>()
    val methods: Map<String, Method> by lazy {
        classes.asSequence().flatMap { it.methods.asSequence() }.associateBy(::descriptor)
    }
    private val typeReferences: Map<String, List<String>> by lazy {
        val result = HashMap<String, MutableList<String>>()
        methods.forEach { (id, method) ->
            val types = (method.implementation?.instructions ?: emptyList()).mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type
            }.toSet()
            types.forEach { result.getOrPut(it) { ArrayList() }.add(id) }
        }
        result
    }

    fun method(id: String): Method {
        val owner = id.substringBefore("->")
        val entries = ownerMethods.getOrPut(owner) {
            requireNotNull(classesByType[owner]) { "DEX owner unavailable: $owner" }
                .methods.associateBy(::descriptor)
        }
        return requireNotNull(entries[id]) { "DEX method unavailable: $id" }
    }

    fun referencingType(type: String): List<String> = typeReferences[type].orEmpty()

    companion object {
        fun forApk(apk: String): HostDexIndex = HostQuerySession.dexIndex(apk) ?: HostDexIndex(apk)

        private fun descriptor(method: Method) =
            "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

        private fun load(file: File, requireDex: Boolean): List<ClassDef> {
            val zip = try { ZipFile(file) } catch (_: ZipException) {
                return file.inputStream().buffered().use {
                    DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it).classes.toList()
                }
            }
            return zip.use {
                // Do not ask dexlib to probe every image/resource as a DEX and create thousands
                // of NotADexFile exceptions. Read each actual DEX entry exactly once.
                it.hostDexEntries(requireDex).flatMap { entry ->
                    it.getInputStream(entry).buffered().use { input ->
                        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).classes.toList()
                    }
                }
            }
        }
    }
}
