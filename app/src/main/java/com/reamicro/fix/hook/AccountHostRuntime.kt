package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.core.HostAccountBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Retains native instances weakly and recovers an existing repository after deferred loading. */
internal object AccountHostRuntime {
    @Volatile private var repositoryRef: WeakReference<Any>? = null
    @Volatile private var sessionRef: WeakReference<Any>? = null
    @Volatile private var activityProvider: (() -> Activity?)? = null
    @Volatile private var installed = false
    private val host get() = requireNotNull(HostAccountBindings.identity) { "Account identity contract unavailable" }
    private val identityLock = ReentrantLock()
    private var identityGeneration = 0L
    private var observedIdentity: Identity? = null
    private var identityMutations = 0

    internal data class Identity(
        val accountId: Long,
        val token: String,
        internal val generation: Long,
        internal val session: Any,
        internal val user: Any?,
    ) {
        override fun toString(): String = "AccountIdentity(accountId=$accountId, generation=$generation)"
    }

    @Synchronized fun install(provider: () -> Activity?) {
        activityProvider = provider
        if (installed) return
        val contract = host
        val handles = arrayListOf<XposedInterface.HookHandle>()
        try {
            for (role in listOf("repository", "session")) for (constructor in contract.constructors(role)) {
                handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val value = param.thisObject ?: return
                        if (role == "repository") {
                            rememberRepository(value)
                        } else if (contract.type("session").isInstance(value)) sessionRef = WeakReference(value)
                    }
                })
            }
            val publish = contract.method("saveCache")
            handles += HookRuntime.hookMethod(publish, object : HookCallback() {
                override fun before(param: Call) {
                    if (!contract.type("cachedData").isInstance(param.args.getOrNull(0))) return
                    val lease = ModuleResources.lease() ?: return
                    // Both hosts publish the cached User in this synchronous native function.
                    // Holding the lock through publication also protects short account-bound file commits.
                    identityLock.lock()
                    param.setObjectExtra("reamicro.account.identity.publish.lease", lease)
                }

                override fun after(param: Call) {
                    val lease = param.getObjectExtra("reamicro.account.identity.publish.lease") as? AutoCloseable ?: return
                    try {
                        storageSnapshot()
                    } finally {
                        identityLock.unlock()
                        lease.close()
                    }
                }
            })
            HookRuntime.deoptimize(publish)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        installed = true
        ModuleResources.cleanup {
            repositoryRef = null; sessionRef = null; activityProvider = null; installed = false
            identityLock.withLock { observedIdentity = null; identityMutations = 0; identityGeneration++ }
        }
    }

    private fun rememberRepository(value: Any): Any {
        check(host.type("repository").isInstance(value)) { "Native account repository type mismatch" }
        val session = requireNotNull(host.field("repository.session").get(value))
        check(host.type("session").isInstance(session)) { "Native account session type mismatch" }
        repositoryRef = WeakReference(value)
        sessionRef = WeakReference(session)
        return value
    }

    fun repository(activity: Activity? = activityProvider?.invoke()): Any? {
        repositoryRef?.get()?.let { return it }
        if (activity != null && !activity.isDestroyed && host.type("activity").isInstance(activity)) {
            val property = host.field("activity.repository").get(activity)
            if (property != null) return rememberRepository(requireNotNull(host.method("repositoryValue").invoke(property)))
        }
        val shelf = ReaMicroBookMetadataSync.currentBookshelfRepository() ?: return null
        if (!host.type("shelf").isInstance(shelf)) return null
        val storage = host.field("shelf.storage").get(shelf) ?: return null
        return host.field("storage.repository").get(storage)?.let(::rememberRepository)
    }

    fun session(activity: Activity? = activityProvider?.invoke()): Any? =
        repository(activity)?.let { host.field("repository.session").get(it) } ?: sessionRef?.get()

    fun user(): Any? = host.field("cachedUser").get(null)

    /** Account-only callers require a logged-in user; local storage also supports native uid 0 guests. */
    fun snapshot(activity: Activity? = activityProvider?.invoke()): Identity? =
        storageSnapshot(activity)?.takeIf { it.accountId > 0L && it.token.isNotBlank() }

    /** Session is reused by native login/signout in both hosts; its object identity is not an account. */
    fun storageSnapshot(activity: Activity? = activityProvider?.invoke()): Identity? = identityLock.withLock {
        if (identityMutations != 0) return@withLock null
        val bindings = HostAccountBindings.preferences ?: return@withLock observeIdentity(null)
        val current = user()
        val currentSession = session(activity) ?: return@withLock observeIdentity(null)
        val id = if (current == null) 0L else host.field("user.id").getLong(current)
        val token = if (current == null) "" else (host.field("user.token").get(current) as? String)
            ?: return@withLock observeIdentity(null)
        if (id < 0L || (id == 0L) != token.isBlank() || bindings.field("token").get(currentSession) != token ||
            user() !== current || session(activity) !== currentSession || bindings.field("token").get(currentSession) != token) {
            return@withLock observeIdentity(null)
        }
        observeIdentity(Identity(id, token, identityGeneration, currentSession, current))
    }

    /** Callers that edit native DataStore must also compare its token inside that same transaction. */
    fun isCurrent(identity: Identity, activity: Activity? = activityProvider?.invoke()): Boolean =
        storageSnapshot(activity)?.let { current -> current.generation == identity.generation &&
            current.accountId == identity.accountId && current.token == identity.token && current.session === identity.session } == true

    fun isCurrentStorage(identity: Identity, activity: Activity? = activityProvider?.invoke()): Boolean = isCurrent(identity, activity)

    /** Use only for the final short move/delete, never for network or suspended work. */
    fun <T> withCurrentStorage(identity: Identity, activity: Activity? = activityProvider?.invoke(), action: () -> T): T =
        identityLock.withLock {
            check(isCurrentStorage(identity, activity)) { "账号已切换，请重新操作" }
            action()
        }

    fun beginIdentityMutation() = identityLock.withLock {
        identityMutations++
        observedIdentity = null
        identityGeneration++
    }

    fun endIdentityMutation() = identityLock.withLock {
        check(identityMutations > 0) { "Unbalanced account identity mutation" }
        identityMutations--
        observedIdentity = null
        identityGeneration++
    }

    private fun observeIdentity(value: Identity?): Identity? {
        val previous = observedIdentity
        if (previous?.accountId != value?.accountId || previous?.token != value?.token || previous?.session !== value?.session) {
            identityGeneration++
        }
        return value?.copy(generation = identityGeneration).also { observedIdentity = it }
    }
}
