package com.reamicro.fix.epub.editor

import java.io.File
import java.lang.reflect.InvocationTargetException

internal object HostOpfIdentifier {
    data class Snapshot(
        val value: String,
        val id: String,
        val type: String,
        val scheme: String,
        val uniqueReference: String,
    ) {
        fun caption(): String = listOfNotNull(
            type.takeIf { it.isNotBlank() },
            id.takeIf { it.isNotBlank() }?.let { "id=$it" },
            uniqueReference.takeIf { it.isNotBlank() }?.let { "unique-identifier=$it" },
        ).joinToString(" · ")
    }

    fun read(loader: ClassLoader, root: File): Snapshot {
        require(root.isDirectory) { "EPUB 解包目录不存在" }
        val pathType = loader.loadClass("okio.Path")
        val pathCompanion = pathType.getField("Companion").get(null)
        val path = pathCompanion.javaClass.getMethod("get", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(pathCompanion, root.canonicalPath, false)
        val opfType = loader.loadClass("org.epub.structure.opf.Opf")
        val companion = opfType.getField("Companion").get(null)
        val opf = try {
            companion.javaClass.getMethod("obtain", pathType).invoke(companion, path)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("宿主 OPF 读取失败：${error.targetException.message.orEmpty()}", error.targetException)
        }
        return fromHostOpf(requireNotNull(opf) { "宿主未返回 OPF" })
    }

    private fun enumName(value: Any?): String =
        value?.toString()?.substringAfterLast('.')?.substringBefore('(').orEmpty()

    internal fun fromHostOpf(opf: Any): Snapshot {
        fun read(target: Any, name: String): Any? =
            target.javaClass.getMethod(name).apply { isAccessible = true }.invoke(target)
        val metadata = requireNotNull(read(opf, "getMetadata")) { "宿主未返回元数据" }
        val identifier = requireNotNull(read(metadata, "getUuid")) { "宿主未返回唯一标识" }
        val id = requireNotNull(read(identifier, "getId")) { "宿主未返回标识类型" }
        val type = read(identifier, "getType")
        return Snapshot(
            value = read(identifier, "getValue") as? String ?: "",
            id = read(id, "getValue") as? String ?: "",
            type = enumName(type),
            scheme = type?.let { runCatching { read(it, "getScheme") as? String }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: enumName(type),
            uniqueReference = read(opf, "getUniqueIdentifier") as? String ?: "",
        )
    }
}
