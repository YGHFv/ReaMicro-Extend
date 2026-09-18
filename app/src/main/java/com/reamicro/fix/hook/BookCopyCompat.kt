package com.reamicro.fix.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class BookCopyPatch(
    val title: String? = null,
    val subtitle: String? = null,
    val author: String? = null,
    val cover: String? = null,
    val size: Long? = null,
    val updated: Long? = null,
    val pinnedAt: Long? = null,
    val cloudId: Long? = null,
    val backupType: Int? = null,
    val backupId: String? = null,
    val backupCode: String? = null,
    val publisher: String? = null,
)

internal object BookCopyCompat {
    fun copy(book: Any, patch: BookCopyPatch): Any {
        val copyMethod = findCopyMethod(book)
        val parameterTypes = copyMethod.parameterTypes
        val args = Array<Any?>(parameterTypes.size) { index ->
            componentMethod(book, index + 1).invoke(book)
        }
        val embeddedFontsPresent = parameterTypes.getOrNull(12) == Integer.TYPE
        val epubcfiIndex = if (embeddedFontsPresent) 13 else 12
        val updatedIndex = epubcfiIndex + 5
        val contentDownloadedPresent = parameterTypes.lastOrNull() == java.lang.Boolean.TYPE
        val publisherIndex = parameterTypes.lastIndex - if (contentDownloadedPresent) 1 else 0
        val backupCodeIndex = publisherIndex - 1
        val backupIdIndex = publisherIndex - 2
        val backupTypeIndex = publisherIndex - 3
        val cloudIdIndex = publisherIndex - 4
        val pinnedAtIndex = (updatedIndex + 1).takeIf { it < cloudIdIndex }

        check(parameterTypes.size >= 23 && publisherIndex > updatedIndex) {
            "Unsupported Book.copy signature: ${copyMethod.toGenericString()}"
        }
        check(parameterTypes.getOrNull(6) == String::class.java && parameterTypes.getOrNull(7) == java.lang.Long.TYPE) {
            "Unsupported Book.copy core fields: ${copyMethod.toGenericString()}"
        }
        check(
            parameterTypes.getOrNull(updatedIndex) == java.lang.Long.TYPE &&
                parameterTypes.getOrNull(cloudIdIndex) == java.lang.Long.TYPE &&
                parameterTypes.getOrNull(backupTypeIndex) == Integer.TYPE &&
                parameterTypes.getOrNull(backupIdIndex) == String::class.java &&
                parameterTypes.getOrNull(backupCodeIndex) == String::class.java &&
                parameterTypes.getOrNull(publisherIndex) == String::class.java
        ) {
            "Unsupported Book.copy tail fields: ${copyMethod.toGenericString()}"
        }

        patch.title?.let { args[3] = it }
        patch.subtitle?.let { args[4] = it }
        patch.author?.let { args[5] = it }
        patch.cover?.takeIf { it.isNotBlank() }?.let { args[6] = it }
        patch.size?.takeIf { it > 0L }?.let { args[7] = it }
        patch.updated?.takeIf { it > 0L }?.let { args[updatedIndex] = it }
        patch.pinnedAt?.let { value -> pinnedAtIndex?.let { args[it] = value } }
        patch.cloudId?.let { args[cloudIdIndex] = it }
        patch.backupType?.let { args[backupTypeIndex] = it }
        patch.backupId?.let { args[backupIdIndex] = it }
        patch.backupCode?.let { args[backupCodeIndex] = it }
        patch.publisher?.let { args[publisherIndex] = it }
        return copyMethod.invoke(book, *args)
    }

    private fun findCopyMethod(book: Any): Method {
        val bookClass = book.javaClass
        val candidates = (bookClass.methods.asSequence() + bookClass.declaredMethods.asSequence())
            .filter {
                it.name == "copy" &&
                    it.returnType == bookClass &&
                    !Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size >= 23
            }
            .distinctBy { it.toGenericString() }
            .sortedByDescending { it.parameterTypes.size }
            .toList()
        return candidates.firstOrNull()?.apply { isAccessible = true }
            ?: error(
                "Unsupported Book.copy signature: ${bookClass.name}; methods=" +
                    (bookClass.methods.asSequence() + bookClass.declaredMethods.asSequence())
                        .filter { it.name.startsWith("copy") }
                        .distinctBy { it.toGenericString() }
                        .joinToString { it.toGenericString() },
            )
    }

    private fun componentMethod(book: Any, number: Int): Method {
        val name = "component$number"
        return (book.javaClass.methods.asSequence() + book.javaClass.declaredMethods.asSequence())
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }
            ?: error("Book.$name not found: ${book.javaClass.name}")
    }
}
