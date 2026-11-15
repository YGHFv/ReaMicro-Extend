package com.reamicro.fix.hook

import com.reamicro.fix.core.HostAccountBindings
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.github.libxposed.api.XposedInterface

/** 直接接入原生会员判断；高级偏好在一个带账号门禁的 DataStore 事务里恢复。 */
internal class AccountAdvancedSettings(
    private val loader: ClassLoader,
    private val currentTarget: () -> AccountAdvancedRestoreState.Target?,
    private val memberAtCapture: () -> Boolean,
    private val dataStore: () -> Any?,
    private val preferenceKey: (String) -> Any,
    private val savedPreferences: (Long) -> AccountAdvancedPreferences?,
    private val checkCurrentMember: () -> Unit,
) {
    val operationLock = Any()
    private val state = AccountAdvancedRestoreState()
    private val worker = ModuleResources.pool(1) { task -> Thread(task, "ReaMicroAccountPreferences").apply { isDaemon = true } }
    private val scopes = ThreadLocal.withInitial { ArrayDeque<NativeCheck>() }
    private val continuations = Collections.synchronizedMap(WeakHashMap<Any, NativeCheck>())
    private val pendingEdits = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val restoreQueued = AtomicBoolean()
    private val restoreRequests = AtomicInteger()
    private var enabled: () -> Boolean = { false }
    @Volatile private var installed = false
    @Volatile private var nativeSignOuts = 0
    private var nativeChange: AccountAdvancedRestoreState.NativeChange? = null
    private lateinit var edit: Method
    private lateinit var read: Method
    private lateinit var set: Method
    private lateinit var tokenKey: Any
    private lateinit var keys: List<Any>
    private val host get() = requireNotNull(HostAccountBindings.advanced) { "Account eligibility contract unavailable" }
    private val hooks = arrayListOf<XposedInterface.HookHandle>()
    private val function by lazy { host.type("function") }
    private val unit by lazy { host.field("unit").get(null) }
    private val suspended by lazy {
        host.type("suspended").enumConstants!!
            .single { (it as Enum<*>).name == "COROUTINE_SUSPENDED" }
    }
    private val baseContinuation by lazy { host.type("baseContinuation") }
    private val completion by lazy { host.field("completion") }

    private class NativeCheck(val origin: AccountAdvancedRestoreState.Origin?, val accountId: Long) {
        var allowed: Boolean? = null
    }

    fun install(isEnabled: () -> Boolean) {
        if (installed) return
        enabled = isEnabled
        edit = host.method("edit")
        read = host.method("read")
        set = host.method("set")
        tokenKey = preferenceKey("getTOKEN")
        keys = listOf("getDYNAMIC_COLOR", "getSMART_RECOGNITION", "getILLUSTRATION_OPTIMIZATION",
            "getRELATE_DOWNLOAD_COVER", "getCALLING_CARD_COLOR").map(preferenceKey)
        try {
            installNativeEditGuard()
            installChecks()
            installIdentityObserver()
            HookRuntime.deoptimize(host.method("update"))
            host.methods("setter").forEach(HookRuntime::deoptimize)
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            hooks.clear()
            throw error
        }
        ModuleResources.blockWhile {
            if (pendingEdits.isEmpty() && continuations.isEmpty() && nativeSignOuts == 0) null else "账号设置事务尚未完成"
        }
        ModuleResources.cleanup {
            continuations.clear(); pendingEdits.clear(); scopes.remove()
            nativeSignOuts = 0; nativeChange = null
            restoreRequests.set(0); restoreQueued.set(false); installed = false
        }
        installed = true
        runCatching { observeCurrent() }.onFailure {
            HookRuntime.logError("ReaMicro account identity observation deferred", it)
        }
        HookRuntime.logAlways("ReaMicro account preferences: native membership result + atomic account-bound edit installed")
    }

    private fun installIdentityObserver() {
        val publish = requireNotNull(HostAccountBindings.identity).method("saveCache")
        hooks += HookRuntime.hookMethod(publish, object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable == null && installed && enabled() && observeCurrent())
                    enqueueRestore(recheck = true)
            }
        })
        HookRuntime.deoptimize(publish)
    }

    private fun installChecks() {
        val infoId = host.field("info.id")
        val predicate = host.method("predicate")
        hooks += HookRuntime.hookMethod(predicate, object : HookCallback() {
            override fun after(param: Call) {
                val scope = scopes.get()?.lastOrNull() ?: return
                if (param.throwable != null || infoId.getLong(param.args[0]) != scope.accountId) return
                scope.allowed = param.result as? Boolean
                scope.allowed?.let { state.verdict(scope.origin, it) }
            }
        })
        val methods = listOf(
            host.method("check.repository") to false,
            host.method("check.settings") to true,
        )
        methods.forEach { (method, userArgument) ->
            hooks += HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    if (!installed || (param.args[0] != null && !enabled())) return
                    val caller = param.args.last() ?: return
                    val value = param.args[0]
                    val check = if (value != null) {
                        observeCurrent()
                        val userInfo = if (userArgument) host.method("info").invoke(value) else value
                        val id = infoId.getLong(userInfo)
                        val token = if (userArgument) host.field("user.token").get(value) as? String else null
                        NativeCheck(state.origin(id, token), id).also { continuations[caller] = it }
                    } else findCheck(caller) ?: return
                    requireNotNull(scopes.get()).addLast(check)
                    param.setObjectExtra(CHECK, check)
                }
                override fun after(param: Call) {
                    val check = param.getObjectExtra(CHECK) as? NativeCheck ?: return
                    val stack = scopes.get()
                    if (stack?.lastOrNull() === check) stack.removeLast() else stack?.remove(check)
                    if (stack?.isEmpty() == true) scopes.remove()
                    if (param.throwable != null || param.result !== suspended) {
                        synchronized(continuations) { continuations.entries.removeAll { it.value === check } }
                        if (param.throwable == null && check.allowed != null) {
                            if (check.allowed == true) enqueueRestore(recheck = false)
                        } else state.failed(check.origin)
                    }
                }
            })
            HookRuntime.deoptimize(method)
        }
        HookRuntime.deoptimize(host.method("member"))
        HookRuntime.deoptimize(predicate)
    }

    private fun findCheck(caller: Any): NativeCheck? {
        var node: Any? = caller
        repeat(24) {
            val current = node ?: return null
            continuations[current]?.let { return it }
            if (!baseContinuation.isInstance(current)) return null
            node = completion.get(current)
        }
        return null
    }

    private fun installNativeEditGuard() {
        hooks += HookRuntime.hookMethod(edit, object : HookCallback() {
            override fun before(param: Call) {
                val scope = scopes.get()?.lastOrNull() ?: return
                val original = param.args[1] ?: return
                val tracked = Any()
                pendingEdits[tracked] = true
                param.setObjectExtra(EDIT_PENDING, tracked)
                param.args[1] = callback("ReaMicroAccountCleanup",
                    onRejected = { method, args ->
                        // Native cleanup must still finish if a reload inspection declines module work.
                        try { invoke(method, original, args) } finally { pendingEdits.remove(tracked) }
                    },
                ) { method, args ->
                    try {
                        val preferences = requireNotNull(args?.getOrNull(0))
                        val token = read.invoke(preferences, tokenKey) as? String
                        if (enabled()) state.nativeWrite(scope.origin, token) { invoke(method, original, args) } ?: unit
                        else invoke(method, original, args)
                    } finally { pendingEdits.remove(tracked) }
                }
            }
            override fun after(param: Call) {
                if (param.throwable != null || param.result !== suspended)
                    param.getObjectExtra(EDIT_PENDING)?.let(pendingEdits::remove)
            }
        })
        HookRuntime.deoptimize(edit)
    }

    fun capture(accountId: Long, values: AccountAdvancedPreferences, previous: AccountAdvancedPreferences?): AccountAdvancedPreferences? =
        state.preserved(accountId) ?: if (installed && enabled() && runCatching(memberAtCapture).getOrDefault(false)) values else previous

    fun preserved(accountId: Long): AccountAdvancedPreferences? = state.preserved(accountId)

    fun begin(target: AccountAdvancedRestoreState.Target, values: AccountAdvancedPreferences?): Long {
        requireIdentityMutation()
        observeCurrent()
        return state.begin(target, values)
    }
    fun cancel(ticket: Long) {
        val current = runCatching(currentTarget).getOrNull()
        state.cancel(ticket, current, recoverSavedPreferences(current))
        enqueueRestore(recheck = true)
    }
    fun ready(ticket: Long) { state.markReady(ticket); enqueueRestore(recheck = true) }

    fun requireIdentityMutation() = synchronized(operationLock) {
        check(nativeSignOuts == 0) { "阅微正在退出账号，请完成后重试" }
    }

    fun nativeSignOutStarted() = synchronized(operationLock) {
        if (nativeSignOuts++ == 0) {
            nativeChange = state.nativeIdentityChange(runCatching(currentTarget).getOrNull())
        }
    }

    fun nativeSignOutFinished() = synchronized(operationLock) {
        if (nativeSignOuts == 0 || --nativeSignOuts != 0) return@synchronized
        val change = nativeChange ?: return@synchronized
        nativeChange = null
        val current = runCatching(currentTarget).getOrNull()
        val saved = recoverSavedPreferences(current)
        state.nativeIdentityFinished(change, current, saved)
        observeCurrent()
        enqueueRestore(recheck = true)
    }

    private fun recoverSavedPreferences(current: AccountAdvancedRestoreState.Target?): AccountAdvancedPreferences? =
        runCatching { current?.let { savedPreferences(it.accountId) } }.onFailure {
            HookRuntime.logError("ReaMicro account preferences snapshot unavailable", it)
        }.getOrNull()

    fun updatePreferences(
        store: Any,
        expectedToken: String,
        values: Map<Any, Any?>,
        canWrite: () -> Boolean = { true },
        onApplied: () -> Unit = {},
    ) {
        requireIdentityMutation()
        check(installed) { "账号设置事务尚未就绪" }
        val active = AtomicBoolean(true)
        val applied = AtomicBoolean(false)
        val transform = callback("ReaMicroAccountIdentityPreferences") { _, args ->
            val preferences = requireNotNull(args?.getOrNull(0))
            val actualToken = (read.invoke(preferences, tokenKey) as? String).orEmpty()
            check(active.get() && nativeSignOuts == 0 && canWrite() && actualToken == expectedToken) {
                "阅微账号已经变化，请重试切换"
            }
            values.forEach { (key, value) -> writePreference(preferences, key, value) }
            check(active.get() && nativeSignOuts == 0 && canWrite()) { "阅微账号已经变化，请重试切换" }
            // The native token Flow may publish before edit resumes its caller.
            onApplied()
            applied.set(true)
            unit
        }
        try {
            HostCoroutines.invokeTimed(loader, edit, null, 15_000, store, transform)
            check(applied.get()) { "账号设置事务未执行，请重新打开页面" }
        } finally {
            active.set(false)
        }
    }

    fun reconcilePreferences(
        store: Any,
        beforeToken: String,
        attemptedToken: String,
        beforeValues: Map<Any, Any?>,
        nativeIdentityOwned: () -> Boolean,
        onApplied: () -> Unit = {},
    ): Boolean {
        requireIdentityMutation()
        val active = AtomicBoolean(true)
        var restored = false
        val transform = callback("ReaMicroReconcileAccountIdentity") { _, args ->
            val preferences = requireNotNull(args?.getOrNull(0))
            val token = (read.invoke(preferences, tokenKey) as? String).orEmpty()
            if (active.get() && nativeSignOuts == 0 && nativeIdentityOwned()) {
                when (token) {
                    attemptedToken -> {
                        beforeValues.forEach { (key, value) -> writePreference(preferences, key, value) }
                        onApplied()
                        restored = true
                    }
                    beforeToken -> {
                        onApplied()
                        restored = true
                    }
                }
            }
            unit
        }
        try {
            // 读 token 和条件回滚位于同一原生事务，排在此前可能已提交的失败写入之后。
            HostCoroutines.invokeTimed(loader, edit, null, 15_000, store, transform)
        } finally {
            active.set(false)
        }
        return restored
    }

    private fun writePreference(preferences: Any, key: Any, value: Any?) {
        if (value == null) host.method("remove").invoke(preferences, key)
        else set.invoke(preferences, key, value)
    }

    private fun observeCurrent(): Boolean {
        val current = currentTarget()
        if (!state.needsObservation(current)) return false
        val saved = current?.let { savedPreferences(it.accountId) }
        if (currentTarget() != current) return false
        state.observeCurrent(current, saved)
        return true
    }

    private fun enqueueRestore(recheck: Boolean) {
        if (!installed || !enabled() || !ModuleResources.accepting ||
            (if (recheck) !state.needsCheck() else state.candidate() == null)) return
        val request = if (recheck) RECHECK else RESTORE
        restoreRequests.getAndUpdate { it or request }
        scheduleRestore()
    }

    private fun scheduleRestore() {
        if (!restoreQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    while (true) {
                        val requests = restoreRequests.getAndSet(0)
                        if (requests == 0) break
                        try {
                            synchronized(operationLock) {
                                if (!installed || !enabled() || !ModuleResources.accepting || nativeSignOuts != 0) return@synchronized
                                if (requests and RECHECK != 0 && state.needsCheck()) checkCurrentMember()
                                restorePending()
                            }
                        } catch (error: Throwable) {
                            HookRuntime.logError("ReaMicro account preferences restore failed", error)
                        }
                    }
                } finally {
                    restoreQueued.set(false)
                    if (restoreRequests.get() != 0 && installed && ModuleResources.accepting) scheduleRestore()
                }
            }
        } catch (_: RejectedExecutionException) {
            restoreQueued.set(false)
            if (!ModuleResources.accepting) restoreRequests.set(0)
        }
    }

    private fun restorePending() {
        val ticket = state.candidate() ?: return
        val store = dataStore() ?: return
        var transformed = false
        val transform = callback("ReaMicroRestoreAccountPreferences") { _, args ->
            val preferences = requireNotNull(args?.getOrNull(0))
            val token = read.invoke(preferences, tokenKey) as? String
            if (enabled()) {
                transformed = state.commit(ticket, token) {
                    val p = ticket.preferences
                    listOf(p.dynamicColor, p.smartRecognition, p.illustrationOptimization,
                        p.relateDownloadCover, p.callingCardColor).forEachIndexed { index, value ->
                        set.invoke(preferences, keys[index], value)
                    }
                }
            }
            unit
        }
        HostCoroutines.invokeTimed(loader, edit, null, 15_000, store, transform)
        if (transformed) {
            state.committed(ticket)
            HookRuntime.logAlways("ReaMicro account preferences restored using native membership result")
        }
    }
    private fun callback(
        name: String,
        onRejected: ((Method, Array<out Any?>?) -> Any?)? = null,
        invoke: (Method, Array<out Any?>?) -> Any?,
    ): Any {
        // These protocol objects belong to the host and remain valid after module bindings retire.
        val nativeInvoke = host.method("invoke")
        val nativeUnit = unit
        return Proxy.newProxyInstance(loader, arrayOf(function)) { self, method, args ->
            when {
                method == nativeInvoke -> {
                    val lease = ModuleResources.lease()
                    if (lease == null) {
                        if (onRejected != null) onRejected(method, args) else nativeUnit
                    } else lease.use { invoke(method, args) }
                }
                method.declaringClass == Any::class.java && method.name == "toString" -> name
                method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(self)
                method.declaringClass == Any::class.java && method.name == "equals" -> self === args?.firstOrNull()
                else -> error("Unexpected native preference callback: $method")
            }
        }
    }

    private fun invoke(method: Method, target: Any, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException ?: error }

    private companion object {
        const val CHECK = "reamicro.account.native.check"
        const val EDIT_PENDING = "reamicro.account.edit.pending"
        const val RECHECK = 1
        const val RESTORE = 2
    }
}
