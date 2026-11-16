package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class HostImageModeCompatibilityTest {
    enum class Mode { Normal, FullScreen, SpreadLeft, SpreadRight }
    data class Parsed(val node: Any, val builder: Any, val sheets: Any, val directory: String,
        val fullScreen: Boolean, val mode: Mode?)
    class Document(@JvmField val mode: String)
    class Parser {
        companion object {
            @JvmStatic fun legacy(node: Any, builder: Any, sheets: Any, directory: String, fullScreen: Boolean) =
                Parsed(node, builder, sheets, directory, fullScreen, null)
            @JvmStatic fun modern(node: Any, builder: Any, sheets: Any, directory: String, fullScreen: Boolean, mode: Mode) =
                Parsed(node, builder, sheets, directory, fullScreen, mode)
            @JvmStatic fun mode(value: String) = Mode.valueOf(value)
        }
    }
    class LegacyImage(val id: String, val path: String, val name: String, val width: Float, val height: Float,
        val x: Float, val y: Float, val viewBox: String, val fill: String, val preserveAspectRatio: Boolean,
        val commentAnnotation: String)
    // 故意把新参数置于首位，确保运行时按已查询的字段槽位传参。
    class ModernImage(@JvmField val imagePageMode: Mode, val id: String, val path: String, val name: String,
        val width: Float, val height: Float, val x: Float, val y: Float, val viewBox: String, val fill: String,
        val preserveAspectRatio: Boolean, val commentAnnotation: String)

    private fun descriptor(type: Class<*>): String = when (type) {
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Float.TYPE -> "F"
        else -> "L${type.name.replace('.', '/')};"
    }
    private fun descriptor(method: Method) = descriptor(method.declaringClass) + "->" + method.name +
        method.parameterTypes.joinToString("", "(", ")") { descriptor(it) } + descriptor(method.returnType)
    private fun descriptor(constructor: Constructor<*>) = descriptor(constructor.declaringClass) + "-><init>" +
        constructor.parameterTypes.joinToString("", "(", ")V") { descriptor(it) }
    private val loader get() = javaClass.classLoader!!
    private fun parser(modern: Boolean): HostEpubSourceBindings.Contract {
        val method = Parser::class.java.declaredMethods.single { it.name == if (modern) "modern" else "legacy" }
        val values = mutableMapOf("method:body.parse" to descriptor(method))
        if (modern) {
            values["class:imageMode"] = descriptor(Mode::class.java)
            values["method:imageMode"] = descriptor(Parser::class.java.getDeclaredMethod("mode", String::class.java))
            values["field:document.imageModeSource"] = descriptor(Document::class.java) + "->mode:Ljava/lang/String;"
        }
        return HostEpubSourceBindings.Contract(values, HostRuntimeBindings.Members(values, loader))
    }

    @Test fun legacyParserRetainsItsFiveArguments() {
        val node = Any(); val builder = Any(); val sheets = Any()
        val result = parser(false).parseBody(node, builder, sheets, "/book", true, null) as Parsed
        assertSame(node, result.node)
        assertSame(builder, result.builder)
        assertSame(sheets, result.sheets)
        assertEquals("/book", result.directory)
        assertTrue(result.fullScreen)
        assertNull(result.mode)
    }

    @Test fun modernProjectionPreservesBothSpreadDirections() {
        val host = parser(true)
        for (mode in listOf(Mode.Normal, Mode.FullScreen, Mode.SpreadLeft, Mode.SpreadRight)) {
            val original = host.imageMode(Document(mode.name))
            val parsed = host.parseBody(Any(), Any(), Any(), "/book", mode != Mode.Normal, original) as Parsed
            assertEquals(mode, parsed.mode)
            assertEquals(mode != Mode.Normal, parsed.fullScreen)
        }
    }

    @Test fun modernParserRejectsMissingOrForeignModeBeforeCallingTheHost() {
        val host = parser(true)
        assertThrows(IllegalArgumentException::class.java) { host.parseBody(Any(), Any(), Any(), "/", false, null) }
        assertThrows(IllegalArgumentException::class.java) { host.parseBody(Any(), Any(), Any(), "/", true, "SpreadLeft") }
    }

    private fun image(modern: Boolean): Any {
        val type = if (modern) ModernImage::class.java else LegacyImage::class.java
        val values = mutableMapOf("constructor:image" to descriptor(type.declaredConstructors.single()))
        HostParagraphCommentQueries.imageProperties.forEachIndexed { index, name ->
            values["value:image.$name"] = (index + if (modern) 1 else 0).toString()
        }
        if (modern) {
            values["class:imageMode"] = descriptor(Mode::class.java)
            values["field:image.imagePageMode"] = descriptor(type) + "->imagePageMode:" + descriptor(Mode::class.java)
            values["value:image.imagePageMode"] = "0"
        }
        val host = HostParagraphCommentBindings.Contract(values, HostRuntimeBindings.Members(values, loader))
        return host.image("comment-id", 18f, "批注内容")
    }

    @Test fun legacyCommentIconUsesTheOriginalConstructor() {
        val icon = image(false) as LegacyImage
        assertEquals("comment-id", icon.id)
        assertEquals("#comment", icon.path)
        assertEquals(18f, icon.width)
        assertEquals("批注内容", icon.commentAnnotation)
    }

    @Test fun modernCommentIconStaysInlineAndPreservesItsAnnotation() {
        val icon = image(true) as ModernImage
        assertEquals(Mode.Normal, icon.imagePageMode)
        assertEquals("comment-id", icon.id)
        assertEquals(18f, icon.height)
        assertEquals("批注内容", icon.commentAnnotation)
    }
}
