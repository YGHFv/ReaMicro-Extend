package com.reamicro.fix.hook

import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostAbiMethodByArity
import com.reamicro.fix.core.hostAbiField
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/** 直接接入原生会员判断；高级偏好在一个带账号门禁的 DataStore 事务里恢复。 */
internal class AccountAdvancedSettings(
    private val loader: ClassLoader,
    private val currentTarget: () -> AccountAdvancedRestoreState.Target?,
    private val memberAtCapture: () -> Boolean,
    private val dataStore: () -> Any?,
    private val preferenceKey: (String) -> Any,
) {
    val operationLock = Any()
    private val state = AccountAdvancedRestoreState()
    private val worker = ModuleResources.pool(1) { task -> Thread(task, "ReaMicroAccountPreferences").apply { isDaemon = true } }
    private val scopes = ThreadLocal.withInitial { ArrayDeque<NativeCheck>() }
    private val continuations = Collections.synchronizedMap(WeakHashMap<Any, NativeCheck>())
    private val pendingEdits = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private var enabled: () -> Boolean = { false }
    @Volatile private var installed = false
    private lateinit var edit: Method
    private lateinit var read: Method
    private lateinit var set: Method
    private lateinit var tokenKey: Any
    private lateinit var keys: List<Any>
    private val function by lazy { loader.hostClass("kotlin.jvm.functions.Function2") }
    private val unit by lazy { loader.hostClass("kotlin.Unit").hostField("INSTANCE").get(null) }
    private val suspended by lazy {
        loader.hostClass("kotlin.coroutines.intrinsics.CoroutineSingletons").enumConstants!!
            .single { (it as Enum<*>).name == "COROUTINE_SUSPENDED" }
    }
    private val baseContinuation by lazy { loader.hostClass("kotlin.coroutines.jvm.internal.BaseContinuationImpl") }
    private val completion by lazy { baseContinuation.hostField("completion") }

    private class NativeCheck(val origin: AccountAdvancedRestoreState.Origin?, val accountId: Long) {
        var allowed: Boolean? = null
    }

    fun install(isEnabled: () -> Boolean) {
        if (HostSymbols.profileId != "2.3.4-b1" || installed) return
        enabled = isEnabled
        val session = loader.hostClass(HostClasses.Host.SESSION)
        val update = session.hostAbiMethodByArity("h", 3)
        val storeType = session.hostAbiField("a").type
        edit = loader.hostStaticMethod(HostClasses.Host.SESSION, "editPreferences", storeType, function,
            loader.hostClass("ReaMicroHost.SuspendContinuation"))
        val mutable = loader.hostClass("ReaMicroHost.MutablePreferences")
        val keyType = loader.hostClass("ReaMicroHost.PreferenceKey")
        read = mutable.hostMethod("get", keyType)
        set = mutable.hostMethod("set", keyType, Any::class.java)
        tokenKey = preferenceKey("getTOKEN")
        keys = listOf("getDYNAMIC_COLOR", "getSMART_RECOGNITION", "getILLUSTRATION_OPTIMIZATION",
            "getRELATE_DOWNLOAD_COVER", "getCALLING_CARD_COLOR").map(preferenceKey)
        installNativeEditGuard()
        installChecks()
        XposedBridge.deoptimize(update)
        // 阻止 ART 将 Session.update/edit 内联进原生 Boolean setter 后绕开事务保护。
        val suspendType = loader.hostClass("ReaMicroHost.SuspendContinuation")
        session.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE, suspendType))
        }.forEach(XposedBridge::deoptimize)
        ModuleResources.blockWhile {
            if (pendingEdits.isEmpty() && continuations.isEmpty()) null else "账号设置事务尚未完成"
        }
        ModuleResources.cleanup { continuations.clear(); pendingEdits.clear(); scopes.remove() }
        installed = true
        XposedBridge.logAlways("ReaMicro account preferences: native membership result + atomic account-bound edit installed")
    }

    private fun installChecks() {
        val info = loader.hostClass("app.zhendong.reamicro.data.res.user.UserInfo")
        val infoId = info.hostField("id")
        val predicate = loader.hostStaticMethod("app.zhendong.reamicro.ui.backup.components.AuthCardKt",
            "isAdvancedMember", info)
        XposedBridge.hookMethod(predicate, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val scope = scopes.get()?.lastOrNull() ?: return
                if (param.throwable != null || infoId.getLong(param.args[0]) != scope.accountId) return
                scope.allowed = param.result as? Boolean
                scope.allowed?.let { state.verdict(scope.origin, it) }
            }
        })
        val methods = listOf(
            loader.hostClass(HostClasses.Host.USER_REPOSITORY).hostAbiMethodByArity("M", 2) to false,
            loader.hostClass("ReaMicroHost.SettingsListViewModel").hostAbiMethodByArity("l", 2) to true,
        )
        methods.forEach { (method, userArgument) ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!installed || (param.args[0] != null && !enabled())) return
                    val caller = param.args.last() ?: return
                    val value = param.args[0]
                    val check = if (value != null) {
                        state.observeCurrent(currentTarget())
                        val userInfo = if (userArgument) value.javaClass.hostMethod("info").invoke(value) else value
                        val id = infoId.getLong(userInfo)
                        val token = if (userArgument) com.reamicro.fix.core.HostRawAbi.field(value.javaClass, "b")
                            .get(value) as? String else null
                        NativeCheck(state.origin(id, token), id).also { continuations[caller] = it }
                    } else findCheck(caller) ?: return
                    requireNotNull(scopes.get()).addLast(check)
                    param.setObjectExtra(CHECK, check)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val check = param.getObjectExtra(CHECK) as? NativeCheck ?: return
                    requireNotNull(scopes.get()).removeLast()
                    if (scopes.get()?.isEmpty() == true) scopes.remove()
                    if (param.throwable != null || param.result !== suspended) {
                        synchronized(continuations) { continuations.entries.removeAll { it.value === check } }
                        if (param.throwable == null && check.allowed != null) {
                            if (check.allowed == true) enqueueRestore()
                        } else state.failed(check.origin)
                    }
                }
            })
            XposedBridge.deoptimize(method)
        }
        // m(User) 的内部调用可能被 ART 内联，校验调用方与谓词都去优化。
        XposedBridge.deoptimize(loader.hostClass("ReaMicroHost.SettingsListViewModel").hostAbiMethodByArity("m", 1))
        XposedBridge.deoptimize(predicate)
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
        XposedBridge.hookMethod(edit, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val scope = scopes.get()?.lastOrNull() ?: return
                val original = param.args[1] ?: return
                val tracked = Any()
                pendingEdits[tracked] = true
                param.setObjectExtra(EDIT_PENDING, tracked)
                param.args[1] = callback("ReaMicroAccountCleanup") { method, args ->
                    try {
                        val preferences = requireNotNull(args?.getOrNull(0))
                        val token = read.invoke(preferences, tokenKey) as? String
                        if (enabled()) state.nativeWrite(scope.origin, token) { invoke(method, original, args) } ?: unit
                        else invoke(method, original, args)
                    } finally { pendingEdits.remove(tracked) }
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null || param.result !== suspended)
                    param.getObjectExtra(EDIT_PENDING)?.let(pendingEdits::remove)
            }
        })
        XposedBridge.deoptimize(edit)
    }

    fun capture(accountId: Long, values: AccountAdvancedPreferences, previous: AccountAdvancedPreferences?): AccountAdvancedPreferences? =
        state.preserved(accountId) ?: if (installed && enabled() && runCatching(memberAtCapture).getOrDefault(false)) values else previous

    fun preserved(accountId: Long): AccountAdvancedPreferences? = state.preserved(accountId)

    fun begin(target: AccountAdvancedRestoreState.Target, values: AccountAdvancedPreferences?): Long {
        state.observeCurrent(currentTarget())
        return state.begin(target, values)
    }
    fun nativeIdentityChange() = state.nativeIdentityChange()
    fun cancel(ticket: Long) = state.cancel(ticket)
    fun ready(ticket: Long) { state.markReady(ticket); enqueueRestore() }

    private fun enqueueRestore() {
        if (!installed || !enabled() || state.candidate() == null || !ModuleResources.accepting) return
        worker.execute {
            val ticket = state.candidate() ?: return@execute
            val store = dataStore() ?: return@execute
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
            runCatching {
                HostCoroutines.invokeTimed(loader, edit, null, 15_000, store, transform)
                if (transformed) {
                    state.committed(ticket)
                    XposedBridge.logAlways("ReaMicro account preferences restored using native membership result")
                }
            }
                .onFailure { XposedBridge.logError("ReaMicro account preferences restore failed", it) }
        }
    }
    private fun callback(name: String, invoke: (Method, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(function)) { self, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> invoke(method, args)
                "toString" -> name
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                else -> null
            }
        }

    private fun invoke(method: Method, target: Any, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException ?: error }

    private companion object {
        const val CHECK = "reamicro.account.native.check"
        const val EDIT_PENDING = "reamicro.account.edit.pending"
    }
}
