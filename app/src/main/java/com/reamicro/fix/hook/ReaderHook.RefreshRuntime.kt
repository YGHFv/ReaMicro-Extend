package com.reamicro.fix.hook

import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostStaticMethod

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException

internal data class ReaderRefreshSession(
    val viewModel: Any, val epub: Any, val root: File, val generation: Long,
)

internal fun ReaderHook.captureRefreshSession(viewModel: Any, root: File): ReaderRefreshSession? {
    if (currentViewModelRef?.get() !== viewModel || currentEpubRoot() != root) return null
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: return null
    return ReaderRefreshSession(viewModel, epub, root, onDemandRefreshCoordinator.generation())
}

internal fun ReaderHook.isRefreshSessionCurrent(session: ReaderRefreshSession): Boolean =
    onDemandRefreshCoordinator.isGeneration(session.generation) &&
        currentViewModelRef?.get() === session.viewModel &&
        (currentEpubStrong ?: currentEpubRef?.get()) === session.epub

internal fun ReaderHook.invalidateOnDemandRefreshes() {
    onDemandRefreshCoordinator.reset()
    onDemandSpineTable = null
    onDemandPrefetchInFlight.clear()
    onDemandPrefetchRetryCount.clear()
    onDemandRefreshPending.clear()
    onDemandJumpLease = null
    onDemandVisibleRefreshKey = null
    onDemandVisibleSlot = null
    onDemandVisibleBusyRetries = 0
}

internal fun requireRefreshCurrent(current: () -> Boolean) {
    if (Thread.currentThread().isInterrupted || !current()) throw CancellationException("Reader refresh superseded")
}

internal fun ReaderHook.invokeRefreshHostSuspend(
    session: ReaderRefreshSession, current: () -> Boolean, operation: (Any?) -> Any?,
): Any? {
    requireRefreshCurrent(current)
    val vmType = classLoader.hostClass("androidx.lifecycle.ViewModel")
    val scope = classLoader.hostClass("androidx.lifecycle.ViewModelKt")
        .hostMethod("getViewModelScope", vmType).invoke(null, session.viewModel)
    val context = XposedHelpers.callMethod(scope, "getCoroutineContext")
    val job = requireNotNull(classLoader.hostStaticMethod(
        "kotlinx.coroutines.JobKt",
        "getJob", classLoader.hostClass("kotlin.coroutines.CoroutineContext"),
    ).invoke(null, context)) { "Reader ViewModel job missing" }
    check(XposedHelpers.callMethod(job, "isActive") == true) { "Reader ViewModel job is inactive" }
    return invokeSelectionHostSuspend(parentContext = job) { cont ->
        requireRefreshCurrent(current)
        operation(cont)
    }.also { requireRefreshCurrent(current) }
}

internal fun ReaderHook.refreshOnMain(
    session: ReaderRefreshSession, current: () -> Boolean, action: () -> Any?,
): Any? {
    val f2 = classLoader.hostClass("kotlin.jvm.functions.Function2")
    val block = Proxy.newProxyInstance(classLoader, arrayOf(f2)) { proxy, method, args ->
        when (com.reamicro.fix.core.HostSymbols.logicalMethodName(method)) {
            "invoke" -> { requireRefreshCurrent(current); action() }
            "toString" -> "ReaMicroRefreshMain"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    }
    val main = com.reamicro.fix.core.HostCoroutines.mainDispatcher(classLoader)
    // B4 合并门面的不同静态方法分属不同 owner，必须按已有成员映射分派。
    val withContext = classLoader.hostStaticMethod(
        "kotlinx.coroutines.BuildersKt",
        "withContext", classLoader.hostClass("kotlin.coroutines.CoroutineContext"), f2,
        classLoader.hostClass("kotlin.coroutines.Continuation"),
    )
    return invokeRefreshHostSuspend(session, current) { withContext.invoke(null, main, block, it) }
}

internal class ReaderRefreshLockBusy : IllegalStateException("Reader virtual window is busy")
