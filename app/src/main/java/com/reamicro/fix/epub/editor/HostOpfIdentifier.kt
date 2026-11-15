package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostEpubServicesBindings
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
        val host = requireNotNull(HostEpubServicesBindings.metadata) { "宿主 OPF 接口尚未解析" }
        check(host.type("opf").classLoader === loader)
        val path = requireNotNull(host.method("path").invoke(null, root.canonicalPath, false))
        val opf = try {
            host.method("obtain").invoke(null, path)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("宿主 OPF 读取失败：${error.targetException.message.orEmpty()}", error.targetException)
        }
        return fromHostOpf(requireNotNull(opf) { "宿主未返回 OPF" })
    }

    internal fun fromHostOpf(opf: Any): Snapshot {
        val host = requireNotNull(HostEpubServicesBindings.metadata) { "宿主 OPF 接口尚未解析" }
        check(host.type("opf").isInstance(opf))
        val metadata = host.required("opf.metadata", opf)
        val identifier = host.required("metadata.identifier", metadata)
        val id = host.required("identifier.id", identifier)
        val type = host.value("identifier.type", identifier)
        val typeName = (type as? Enum<*>)?.name.orEmpty()
        return Snapshot(
            value = host.text("identifier.value", identifier),
            id = host.method("id.value").invoke(id) as? String ?: "",
            type = typeName,
            scheme = type?.let { host.text("kind.scheme", it) }?.takeIf { it.isNotBlank() } ?: typeName,
            uniqueReference = host.text("opf.reference", opf),
        )
    }
}
