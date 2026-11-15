package com.reamicro.fix.epub.editor

import android.graphics.Picture
import android.graphics.RectF
import com.reamicro.fix.core.HostEpubServicesBindings
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets

/** 经宿主保留的 SVG decoder 分配 parser，再复用原生 renderDocument 的 Canvas 入口。 */
internal class NativeSvgPreview(private val loader: ClassLoader) {
    private val host = requireNotNull(HostEpubServicesBindings.svg) { "宿主 SVG 预览接口尚未解析" }
    init { check(host.type("source").classLoader === loader) { "宿主 SVG ClassLoader 已改变" } }
    data class Document(val native: Any, val width: Float, val height: Float, val viewBox: RectF?)

    fun parse(text: String): Document {
        val source = host.type("source")
        val input = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
        val proxy = Proxy.newProxyInstance(loader, arrayOf(source)) { instance, method, args ->
            when {
                method == host.method("input") -> input
                method.name == "toString" && method.parameterCount == 0 -> "ReaMicroSvgPreviewSource"
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(instance)
                method.name == "equals" && method.parameterTypes.toList() == listOf(Any::class.java) -> instance === args?.firstOrNull()
                else -> error("Unexpected native SVG source operation: ${method.name}")
            }
        }
        val decoded = requireNotNull(host.method("decode").invoke(host.required("decoder.instance", null), proxy))
        val document = host.required("decoded.document", decoded)
        check(host.type("document").isInstance(document))
        val root = host.required("document.root", document)
        check(host.type("root").isInstance(root))
        val viewBox = host.value("root.viewBox", root)?.let { box ->
            val left = host.field("box.left").getFloat(box)
            val top = host.field("box.top").getFloat(box)
            RectF(left, top, left + host.field("box.width").getFloat(box),
                top + host.field("box.height").getFloat(box))
        }
        val size = requireNotNull(host.method("dimensions").invoke(document))
        return Document(document, host.field("box.width").getFloat(size), host.field("box.height").getFloat(size), viewBox)
    }

    fun picture(document: Any, width: Int, height: Int): Picture {
        val native = host.constructor("renderer").newInstance(document, null, width, height)
        return Picture().also { picture ->
            val canvas = picture.beginRecording(width, height)
            try {
                host.method("render").invoke(native, canvas)
            } finally {
                picture.endRecording()
            }
        }
    }
}
