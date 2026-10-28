package com.reamicro.fix.epub.editor

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RectF
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets

/** 经宿主保留的 SVG decoder 分配 parser，再复用原生 renderDocument 的 Canvas 入口。 */
internal class B4HostSvgPreview(private val loader: ClassLoader) {
    data class Document(val native: Any, val width: Float, val height: Float, val viewBox: RectF?)

    fun parse(text: String): Document {
        val source = loader.hostClass("ReaMicroHost.SvgBufferedSource")
        val input = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
        val proxy = Proxy.newProxyInstance(loader, arrayOf(source)) { instance, method, args ->
            when (method.name) {
                "i1" -> input
                "toString" -> "ReaMicroSvgPreviewSource"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> error("Unexpected native SVG source operation: ${method.name}")
            }
        }
        val decoder = loader.hostClass("ReaMicroHost.SvgDecoder")
        val decoded = decoder.hostMethod("decode", source).invoke(decoder.hostField("INSTANCE").get(null), proxy)
        val document = requireNotNull(loader.hostClass("ReaMicroHost.SvgDecoded")
            .hostField("document").get(decoded)) { "SVG 文档为空" }
        val root = requireNotNull(loader.hostClass("ReaMicroHost.SvgDocument")
            .hostField("root").get(document)) { "SVG 文档没有根元素" }
        val bounds = loader.hostClass("ReaMicroHost.SvgBox")
        val box = loader.hostClass("ReaMicroHost.SvgRoot").hostField("viewBox").get(root)
        val viewBox = box?.let {
            val left = bounds.hostField("minX").getFloat(it)
            val top = bounds.hostField("minY").getFloat(it)
            RectF(left, top, left + bounds.hostField("width").getFloat(it),
                top + bounds.hostField("height").getFloat(it))
        }
        val size = requireNotNull(loader.hostClass("ReaMicroHost.SvgDocument")
            .hostMethod("dimensions").invoke(document))
        return Document(document, bounds.hostField("width").getFloat(size),
            bounds.hostField("height").getFloat(size), viewBox)
    }

    fun picture(document: Any, width: Int, height: Int): Picture {
        val renderer = loader.hostClass("ReaMicroHost.SvgRenderer")
        val native = renderer.getDeclaredConstructor(
            loader.hostClass("ReaMicroHost.SvgDocument"), loader.hostClass("ReaMicroHost.SvgRenderOptions"),
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).newInstance(document, null, width, height)
        return Picture().also { picture ->
            val canvas = picture.beginRecording(width, height)
            try {
                renderer.hostMethod("render", Canvas::class.java).invoke(native, canvas)
            } finally {
                picture.endRecording()
            }
        }
    }
}
