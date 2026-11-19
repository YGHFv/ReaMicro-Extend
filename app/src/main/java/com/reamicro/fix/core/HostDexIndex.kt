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
    private val classesByType = buildMap<String, ClassDef> {
        apks.forEachIndexed { index, path ->
            for (type in load(File(path), requireDex = index == 0)) {
                // DexKit searches every input, but ART resolves the first definition.
                require(put(type.type, type) == null) { "Duplicate host DEX class: ${type.type}" }
            }
        }
    }
    fun classByType(type: String): ClassDef? = classesByType[type]
    private val ownerMethods = HashMap<String, Map<String, Method>>()
    private val typeReferences: Map<String, List<String>> by lazy {
        val result = HashMap<String, MutableList<String>>()
        for (type in classesByType.values) for (method in type.methods) {
            val instructions = method.implementation?.instructions ?: continue
            var methodId: String? = null
            for (instruction in instructions) {
                val target = ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type ?: continue
                val id = methodId ?: descriptor(method).also { methodId = it }
                val callers = result.getOrPut(target) { ArrayList() }
                // All instructions for this method are visited together.
                if (callers.lastOrNull() != id) callers.add(id)
            }
        }
        result
    }

    fun findMethod(id: String): Method? {
        val owner = id.substringBefore("->")
        val entries = ownerMethods.getOrPut(owner) {
            val type = classesByType[owner] ?: return null
            type.methods.associateBy(::descriptor)
        }
        return entries[id]
    }

    fun method(id: String): Method = requireNotNull(findMethod(id)) { "DEX method unavailable: $id" }

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
