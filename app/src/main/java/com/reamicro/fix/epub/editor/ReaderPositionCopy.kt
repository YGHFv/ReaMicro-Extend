package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.B2BookAssociation
import com.reamicro.fix.core.HostExactRecordCopy
import com.reamicro.fix.core.HostSymbols

internal object ReaderPositionCopy {
    fun copy(book: Any, cfi: String, chapter: String, progress: Float, updated: Long): Any {
        require(cfi.isNotBlank())
        if (HostSymbols.abiProfileId == "2.3.3-b4") {
            return HostExactRecordCopy(book.javaClass, B2BookAssociation.properties).copy(book, mapOf(
                "epubcfi" to cfi, "chapter" to chapter,
                "progress" to progress.coerceIn(0f, 1f), "updated" to updated,
            ))
        }
        val method = book.javaClass.methods.single {
            it.name == "copy" && it.returnType == book.javaClass && it.parameterTypes.size == 26
        }
        val types = method.parameterTypes
        check(types[13] == String::class.java && types[14] == String::class.java &&
            types[15] == Float::class.javaPrimitiveType && types[18] == Long::class.javaPrimitiveType &&
            types[25] == Boolean::class.javaPrimitiveType) { "宿主 Book 阅读进度字段不匹配" }
        val args = Array<Any?>(26) { index ->
            book.javaClass.getMethod("component${index + 1}").invoke(book)
        }
        args[13] = cfi
        args[14] = chapter
        args[15] = progress.coerceIn(0f, 1f)
        args[18] = updated
        return method.invoke(book, *args)
    }
}
