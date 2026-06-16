package com.reamicro.fix.hook

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch

internal data class BookMetadataPatch(
    val title: String? = null,
    val subtitle: String? = null,
    val author: String? = null,
    val cover: String? = null,
    val size: Long? = null,
    val publisher: String? = null,
)

internal object ReaMicroBookMetadataSync {
    private const val LOG_PREFIX = "ReaMicro LSP"
    private const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
    private const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
    private const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
    private const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
    private const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"

    private var bookshelfRepositoryRef: WeakReference<Any>? = null

    fun rememberBookshelfRepository(repository: Any?) {
        if (repository == null) return
        bookshelfRepositoryRef = WeakReference(repository)
    }

    fun currentBookshelfRepository(): Any? = bookshelfRepositoryRef?.get()

    fun metadataFromOpf(opf: Any?): BookMetadataPatch =
        BookMetadataPatch(
            title = callStringPath(opf, "metadata", "title", "value")?.takeIf { it.isNotBlank() },
            subtitle = callStringPath(opf, "metadata", "subtitle", "value"),
            author = authorsFromOpf(opf),
            cover = coverFromOpf(opf),
            publisher = callStringPath(opf, "metadata", "publisher", "value")
                ?: callStringPath(opf, "metadata", "publisher"),
        )

    fun syncBookMetadataAsync(repository: Any?, book: Any?, patch: BookMetadataPatch, delayMs: Long = 0L) {
        if (book == null) return
        val targetRepository = repository ?: currentBookshelfRepository() ?: return
        Thread {
            if (delayMs > 0L) runCatching { Thread.sleep(delayMs) }
            syncBookMetadata(targetRepository, book, patch)
        }.apply { name = "ReaMicro-BookMetadataSync" }.start()
    }

    fun syncBookMetadata(repository: Any?, book: Any?, patch: BookMetadataPatch): Boolean =
        runCatching {
            val targetRepository = repository ?: currentBookshelfRepository() ?: return false
            val updated = copyBookWithMetadata(book ?: return false, patch) ?: return false
            val updateMethod = method(targetRepository.javaClass, "updateBook", 2)
            invokeSuspendBlocking(targetRepository.javaClass.classLoader, updateMethod, targetRepository, updated)
            XposedBridge.log(
                "$LOG_PREFIX synced book metadata: uuid=${stringValue(updated, "getUuid")}, " +
                    "title=${stringValue(updated, "getTitle")}, author=${stringValue(updated, "getAuthor")}",
            )
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to sync book metadata: ${it.stackTraceToString()}")
        }.getOrDefault(false)

    private fun copyBookWithMetadata(book: Any, patch: BookMetadataPatch): Any? =
        runCatching {
            val copy = method(book.javaClass, "copy", 23)
            copy.invoke(
                book,
                longValue(book, "getId"),
                stringValue(book, "getUuid"),
                longValue(book, "getUid"),
                patch.title?.takeIf { it.isNotBlank() } ?: stringValue(book, "getTitle"),
                patch.subtitle ?: stringValue(book, "getSubtitle"),
                patch.author ?: stringValue(book, "getAuthor"),
                patch.cover?.takeIf { it.isNotBlank() } ?: stringValue(book, "getCover"),
                patch.size?.takeIf { it > 0L } ?: longValue(book, "getSize"),
                stringValue(book, "getUri"),
                stringValue(book, "getGroup"),
                longValue(book, "getCreated"),
                intValue(book, "getCfiVersion"),
                stringValue(book, "getEpubcfi"),
                stringValue(book, "getChapter"),
                floatValue(book, "getProgress"),
                longValue(book, "getTotal"),
                longValue(book, "getFinished"),
                System.currentTimeMillis(),
                longValue(book, "getCloudId"),
                intValue(book, "getBackupType"),
                stringValue(book, "getBackupId"),
                stringValue(book, "getBackupCode"),
                patch.publisher ?: stringValue(book, "getPublisher"),
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to copy book metadata: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun authorsFromOpf(opf: Any?): String? =
        runCatching {
            val metadata = callValue(opf, "metadata") ?: return null
            val authors = callValue(metadata, "authors") ?: callValue(metadata, "getAuthors") ?: return null
            (authors as? Iterable<*>)?.mapNotNull { author ->
                callStringPath(author, "name")?.takeIf { it.isNotBlank() }
            }?.joinToString(", ")?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun coverFromOpf(opf: Any?): String? =
        runCatching {
            opf ?: return null
            opf.javaClass.methods.firstOrNull { it.name == "coverRelativePath" && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(opf)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun callStringPath(target: Any?, vararg path: String): String? {
        var current = target ?: return null
        for (name in path) {
            current = callValue(current, name) ?: return null
        }
        return current.toString()
    }

    private fun callValue(target: Any?, name: String): Any? {
        val current = target ?: return null
        if (current is String) return current
        return runCatching {
            current.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                    (it.name == name || it.name == "get" + name.replaceFirstChar { ch -> ch.uppercaseChar() })
            }?.apply { isAccessible = true }?.invoke(current)
        }.getOrNull()
    }

    private fun method(targetClass: Class<*>, name: String, parameterCount: Int): Method =
        (targetClass.methods.asSequence() + targetClass.declaredMethods.asSequence())
            .firstOrNull { it.name == name && it.parameterTypes.size == parameterCount }
            ?.apply { isAccessible = true }
            ?: error("Method not found: ${targetClass.name}#$name/$parameterCount")

    private fun stringValue(target: Any, getter: String): String =
        runCatching { method(target.javaClass, getter, 0).invoke(target)?.toString().orEmpty() }.getOrDefault("")

    private fun longValue(target: Any, getter: String): Long =
        runCatching { (method(target.javaClass, getter, 0).invoke(target) as? Number)?.toLong() ?: 0L }.getOrDefault(0L)

    private fun intValue(target: Any, getter: String): Int =
        runCatching { (method(target.javaClass, getter, 0).invoke(target) as? Number)?.toInt() ?: 0 }.getOrDefault(0)

    private fun floatValue(target: Any, getter: String): Float =
        runCatching { (method(target.javaClass, getter, 0).invoke(target) as? Number)?.toFloat() ?: 0f }.getOrDefault(0f)

    private fun invokeSuspendBlocking(classLoader: ClassLoader, method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = XposedHelpers.findClass(KOTLIN_CONTINUATION_CLASS, classLoader)
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> emptyCoroutineContext(classLoader)
                "resumeWith" -> {
                    val result = proxyArgs?.getOrNull(0)
                    runCatching {
                        throwOnFailure.invoke(null, result)
                        value = result
                    }.onFailure {
                        error = if (it is InvocationTargetException) it.targetException ?: it else it
                    }
                    latch.countDown()
                    Unit
                }
                "toString" -> "ReaMicroBookMetadataContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val returned = try {
            method.invoke(target, *args.toMutableList().apply { add(continuation) }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        if (returned !== coroutineSuspended(classLoader)) return returned
        latch.await()
        error?.let { throw it }
        return value
    }

    private fun emptyCoroutineContext(classLoader: ClassLoader): Any =
        XposedHelpers.findClass(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null) ?: error("EmptyCoroutineContext unavailable")

    private fun coroutineSuspended(classLoader: ClassLoader): Any =
        runCatching {
            XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader)
                .enumConstants
                ?.firstOrNull { value -> value.toString() == "COROUTINE_SUSPENDED" }
                ?: error("COROUTINE_SUSPENDED unavailable")
        }
}
