package com.reamicro.fix.hook

import com.reamicro.fix.core.HostAccountBindings
import com.reamicro.fix.core.HostContinuationObserver
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 退出的门禁跟随原生 continuation 完成；同一退出的挂起重入不重新保存账号。 */
internal class AccountNativeSignOut(
    private val loader: ClassLoader,
    private val started: () -> Unit,
    private val finished: () -> Unit,
) {
    private class Ticket(val lease: AutoCloseable) {
        val complete = AtomicBoolean()
        var started = false
    }

    private val calls = Collections.synchronizedMap(WeakHashMap<Any, Ticket>())
    private val host get() = requireNotNull(HostAccountBindings.advanced)

    fun install() {
        val method = requireNotNull(HostAccountBindings.identity).method("signOut")
        val completion = host.field("completion")
        val base = host.type("baseContinuation")
        val hook = HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val args = param.args ?: return
                val caller = args.lastOrNull() ?: return
                var node: Any? = caller
                repeat(24) {
                    val current = node ?: return@repeat
                    calls[current]?.let {
                        param.setObjectExtra(CALL, it)
                        return
                    }
                    node = if (base.isInstance(current)) completion.get(current) else null
                }
                val ticket = Ticket(ModuleResources.lease() ?: return)
                try {
                    val observed = HostContinuationObserver.observe(loader, caller) { finish(ticket) }
                    check(method.parameterTypes.last().isInstance(observed)) {
                        "Native signout continuation differs from the queried coroutine carrier"
                    }
                    started()
                    ticket.started = true
                    calls[observed] = ticket
                    param.setObjectExtra(CALL, ticket)
                    param.setObjectExtra(INITIAL, true)
                    args[args.lastIndex] = observed
                } catch (error: Throwable) {
                    finish(ticket)
                    HookRuntime.logError("ReaMicro native signout tracking unavailable", error)
                }
            }

            override fun after(param: Call) {
                val ticket = param.getObjectExtra(CALL) as? Ticket ?: return
                // 挂起重入最终由包装的原生 continuation 通知完成，立即返回则没有 resumeWith。
                if (param.getObjectExtra(INITIAL) == true &&
                    (param.throwable != null || param.result !== HostCoroutines.suspended(loader))) finish(ticket)
            }
        })
        try {
            HookRuntime.deoptimize(method)
            ModuleResources.cleanup {
                hook.unhook()
                calls.clear()
            }
        } catch (error: Throwable) {
            runCatching { hook.unhook() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun finish(ticket: Ticket) {
        if (!ticket.complete.compareAndSet(false, true)) return
        try {
            synchronized(calls) { calls.entries.removeAll { it.value === ticket } }
            if (ticket.started) finished()
        } catch (error: Throwable) {
            HookRuntime.logError("ReaMicro native signout completion observation failed", error)
        } finally {
            ticket.lease.close()
        }
    }

    private companion object {
        const val CALL = "reamicro.account.native.signout"
        const val INITIAL = "reamicro.account.native.signout.initial"
    }
}
