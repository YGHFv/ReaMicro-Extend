package io.legado.app.utils

import android.content.Context
import android.os.Looper
import io.legado.app.platform.LegadoPlatform
import java.io.File

val isMainThread: Boolean get() = Looper.myLooper() == Looper.getMainLooper()
val Context.externalCache: File get() = LegadoPlatform.cacheDir

val Throwable.stackTraceStr: String get() = stackTraceToString()
fun Throwable.printOnDebug() = DebugLog.e(javaClass.simpleName, this)

object DebugLog {
    fun e(message: String, error: Throwable? = null) = LegadoPlatform.log(message, error)
    fun i(tag: String, message: String) = LegadoPlatform.log("$tag: $message")
}

fun File.createFileReplace(): File {
    parentFile?.mkdirs()
    if (exists() && !delete()) error("Cannot replace file: $this")
    check(createNewFile()) { "Cannot create file: $this" }
    return this
}
