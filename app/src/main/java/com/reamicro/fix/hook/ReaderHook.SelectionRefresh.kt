package com.reamicro.fix.hook

import android.os.SystemClock
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal data class ReaderSelectionRefreshResult(val virtualPage: Int?, val refreshError: String? = null)

/** Lock before commit, rebuild with Epub.read, await Main publication, then validate the new maps. */
internal fun ReaderHook.saveAndReloadReaderSelection(
    viewModel: Any, epub: Any, spinePosition: Int, anchor: Any, write: () -> Unit,
): ReaderSelectionRefreshResult {
    checkSelectionEditSession(viewModel, epub)
    val mutex = XposedHelpers.getObjectField(viewModel, "virtualPageLoadMutex")
        ?: error("宿主缺少章节加载锁，未写入")
    val owner = Any()
    var locked = false
    var saved = false
    try {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (!locked && SystemClock.elapsedRealtime() < deadline) {
            checkSelectionEditSession(viewModel, epub)
            locked = XposedHelpers.callMethod(mutex, "tryLock", owner) as? Boolean == true
            if (!locked) Thread.sleep(25)
        }
        check(locked) { "章节仍在加载，请稍后保存；未修改文件" }
        checkSelectionEditSession(viewModel, epub)
        val method = findOnDemandRecenterMethod(viewModel, epub, anchor)
            ?.takeIf { it.name == "recenterVirtualWindow" } ?: error("宿主重排接口不匹配，未写入")
        write()
        saved = true
        checkSelectionEditSession(viewModel, epub)
        val page = (invokeSelectionRecenter(viewModel, method, spinePosition, epub, anchor) as? Number)?.toInt()
            ?: error("章节重排没有返回有效页码")
        checkSelectionEditSession(viewModel, epub)
        val pages = XposedHelpers.getObjectField(viewModel, "virtualPages") as? Map<*, *>
        val mapping = XposedHelpers.getObjectField(viewModel, "virtualToReal") as? Map<*, *>
        val pair = mapping?.get(page) ?: error("重排后的页码映射不存在")
        val mappedSpine = (callNoArg(pair, "getFirst") as? Number)?.toInt()
        val refCount = (callNoArg(epub, "getItemRefs") as? List<*>)?.size ?: 0
        // Host 2310 deliberately falls back to a neighbouring readable spine after an
        // entire chapter is deleted. Its freshly published mapping is authoritative.
        check(com.reamicro.fix.epub.editor.validSelectionRefreshPage(pages?.containsKey(page) == true, mappedSpine, refCount)) {
            "重排后的页码映射无效"
        }
        return ReaderSelectionRefreshResult(page)
    } catch (error: Throwable) {
        if (!saved) throw error
        XposedBridge.logError("ReaMicro LSP selection file saved but refresh failed: ${error.stackTraceToString()}")
        return ReaderSelectionRefreshResult(null, error.message)
    } finally {
        if (locked) runCatching { XposedHelpers.callMethod(mutex, "unlock", owner) }
            .onFailure { XposedBridge.logError("ReaMicro LSP selection load mutex unlock failed: ${it.message}") }
    }
}

/** Structured host coroutine timeout: do not abandon a running operation while unlocking its mutex. */
private fun ReaderHook.invokeSelectionRecenter(
    target: Any, method: Method, spine: Int, epub: Any, cfi: Any,
): Any? {
    val f2 = classLoader.loadClass("kotlin.jvm.functions.Function2")
    val continuationType = classLoader.loadClass("kotlin.coroutines.Continuation")
    val timeout = classLoader.loadClass("kotlinx.coroutines.TimeoutKt")
        .getMethod("withTimeout", Long::class.javaPrimitiveType, f2, continuationType)
    fun block(fn: (Array<out Any?>) -> Any?): Any =
        Proxy.newProxyInstance(classLoader, arrayOf(f2)) { proxy, called, args ->
            when (called.name) {
                "invoke" -> fn(args ?: emptyArray())
                "toString" -> "ReaMicroSelectionRecenter"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    fun invokeUnwrapped(block: () -> Any?): Any? =
        try { block() } catch (e: InvocationTargetException) { throw e.targetException ?: e }
    val timed = block { args ->
        val recenter = block { inner ->
            invokeUnwrapped { method.invoke(target, spine, epub, cfi, false, inner[1]) }
        }
        invokeUnwrapped { timeout.invoke(null, 20_000L, recenter, args[1]) }
    }
    val runBlocking = classLoader.loadClass("kotlinx.coroutines.BuildersKt").getMethod(
        "runBlocking", classLoader.loadClass("kotlin.coroutines.CoroutineContext"), f2,
    )
    return invokeUnwrapped { runBlocking.invoke(null, emptyCoroutineContext(), timed) }
}