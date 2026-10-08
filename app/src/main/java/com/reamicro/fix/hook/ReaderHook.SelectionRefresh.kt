package com.reamicro.fix.hook

import android.os.SystemClock
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal data class ReaderSelectionRefreshResult(val virtualPage: Int?, val refreshError: String? = null)

internal fun ReaderHook.saveAndReloadReaderSelection(
    viewModel: Any, epub: Any, spinePosition: Int, anchor: Any, restoredAnchor: Any?, write: () -> Unit,
): ReaderSelectionRefreshResult {
    checkSelectionEditSession(viewModel, epub)
    cancelSelectionPagerConfirmation()

    onDemandRefreshCoordinator.cancelActiveIf { true }
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

        val oldPages = XposedHelpers.getObjectField(viewModel, "virtualPages") as? Map<*, *>
        val oldMapping = XposedHelpers.getObjectField(viewModel, "virtualToReal") as? Map<*, *>
        val oldKey = oldPages?.entries?.firstOrNull { (_, value) ->
            val range = value?.let { callNoArg(it, "getRange") }
            range != null && XposedHelpers.callMethod(range, "contains", anchor) == true
        }?.key
        val oldLocalPage = oldMapping?.get(oldKey)?.let { callNoArg(it, "getSecond") as? Number }?.toInt() ?: 0
        write()
        saved = true
        checkSelectionEditSession(viewModel, epub)
        val window = (oldKey as? Number)?.toInt()?.takeIf { selectionWindowHookInstalled }?.let {
            ReaderSelectionWindow(viewModel, it, spinePosition, oldLocalPage, restoredAnchor)
        }
        selectionWindow = window
        val page = (invokeSelectionRecenter(viewModel, method, spinePosition, epub, restoredAnchor) as? Number)?.toInt()
            ?: error("章节重排没有返回有效页码")
        checkSelectionEditSession(viewModel, epub)
        val pages = XposedHelpers.getObjectField(viewModel, "virtualPages") as? Map<*, *>
        val mapping = XposedHelpers.getObjectField(viewModel, "virtualToReal") as? Map<*, *>
        val typedMapping = mapping.orEmpty().entries.mapNotNull { (key, pair) ->
            val virtual = (key as? Number)?.toInt() ?: return@mapNotNull null
            if (pair == null) return@mapNotNull null
            val spine = (callNoArg(pair, "getFirst") as? Number)?.toInt() ?: return@mapNotNull null
            val local = (callNoArg(pair, "getSecond") as? Number)?.toInt() ?: return@mapNotNull null
            virtual to (spine to local)
        }.toMap()
        val available = pages.orEmpty().keys.mapNotNull { (it as? Number)?.toInt() }.toSet()
        val contained = typedMapping.keys.filter { key ->
            if (typedMapping[key]?.first != spinePosition) return@filter false
            val range = pages?.get(key)?.let { callNoArg(it, "getRange") }
            restoredAnchor != null && range != null && XposedHelpers.callMethod(range, "contains", restoredAnchor) == true
        }.toSet()
        val target = com.reamicro.fix.epub.editor.selectionSameChapterPage(
            typedMapping, available, spinePosition, oldLocalPage, contained,
        ) ?: return ReaderSelectionRefreshResult(null, "原章节已无可阅读页面，请从目录选择阅读位置")
        val targetPage = pages?.get(target) ?: error("同章目标页面不存在")
        val freshAnchor = if (target in contained) restoredAnchor!! else
            callNoArg(targetPage, "getStart") ?: error("重排页面缺少有效锚点")

        persistSelectionReadingPosition(viewModel, epub, freshAnchor)
        XposedBridge.log("ReaMicro selection position persisted after file commit and reflow")
        XposedBridge.log("ReaMicro selection window-preserved=${window?.applied == true} oldSlot=$oldKey")
        XposedBridge.log("ReaMicro selection refresh spine=$spinePosition hostPage=$page target=$target local=${typedMapping[target]?.second} anchorFallback=${target !in contained}")
        return ReaderSelectionRefreshResult(target)
    } catch (error: Throwable) {
        if (!saved) throw error
        XposedBridge.logError("ReaMicro LSP selection file saved but refresh failed: ${error.stackTraceToString()}")
        return ReaderSelectionRefreshResult(null, error.message)
    } finally {
        selectionWindow = null
        if (locked) runCatching { XposedHelpers.callMethod(mutex, "unlock", owner) }
            .onFailure { XposedBridge.logError("ReaMicro LSP selection load mutex unlock failed: ${it.message}") }
    }
}

private fun ReaderHook.invokeSelectionRecenter(
    target: Any, method: Method, spine: Int, epub: Any, cfi: Any?,
): Any? = invokeSelectionHostSuspend { continuation ->
    method.invoke(target, spine, epub, cfi, false, continuation)
}

internal fun ReaderHook.invokeSelectionHostSuspend(
    parentContext: Any = emptyCoroutineContext(), operation: (Any?) -> Any?,
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
            invokeUnwrapped { operation(inner[1]) }
        }
        invokeUnwrapped { timeout.invoke(null, 20_000L, recenter, args[1]) }
    }
    val runBlocking = classLoader.loadClass("kotlinx.coroutines.BuildersKt").getMethod(
        "runBlocking", classLoader.loadClass("kotlin.coroutines.CoroutineContext"), f2,
    )
    return invokeUnwrapped { runBlocking.invoke(null, parentContext, timed) }
}
