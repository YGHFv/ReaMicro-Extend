package com.script.rhino

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.mozilla.javascript.Context
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

val rhinoContext: RhinoContext
    get() = Context.getCurrentContext() as RhinoContext

val rhinoContextOrNull: RhinoContext?
    get() = Context.getCurrentContext() as? RhinoContext

/** Enters this parser's private factory, without replacing the host's Rhino global factory. */
fun enterRhinoContext(): RhinoContext {
    @Suppress("UNUSED_EXPRESSION")
    RhinoScriptEngine
    val cx = RhinoScriptEngine.contextFactory.enterContext()
    if (cx is RhinoContext) {
        return cx
    }
    // 无论失败原因是什么都要撤销本次 enter，否则会把非 RhinoContext 留在线程上。
    Context.exit()
    error(
        "无法进入 Rhino 上下文: Context.enter() 返回 ${cx.javaClass.name}，" +
            "当前线程已被其他脚本上下文占用"
    )
}

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@OptIn(ExperimentalContracts::class)
inline fun <T> suspendContinuation(crossinline block: suspend CoroutineScope.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val cx = enterRhinoContext()
    try {
        val pending = cx.captureContinuation()
        pending.applicationState = suspend {
            supervisorScope {
                block()
            }
        }
        throw pending
    } catch (e: IllegalStateException) {
        return runBlocking(rhinoContextOrNull?.coroutineContext ?: kotlin.coroutines.EmptyCoroutineContext) { block() }
    } finally {
        Context.exit()
    }
}

inline fun <T> runScriptWithContext(context: CoroutineContext, block: () -> T): T {
    val rhinoContext = enterRhinoContext()
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = context.minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}

suspend inline fun <T> runScriptWithContext(block: () -> T): T {
    val rhinoContext = enterRhinoContext()
    val previousCoroutineContext = rhinoContext.coroutineContext
    rhinoContext.coroutineContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
    try {
        return block()
    } finally {
        rhinoContext.coroutineContext = previousCoroutineContext
        Context.exit()
    }
}
