package com.reamicro.fix.epub.editor

internal object ReaderPositionCopy {
    fun copy(book: Any, cfi: String, chapter: String, progress: Float, updated: Long): Any {
        require(cfi.isNotBlank())
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
