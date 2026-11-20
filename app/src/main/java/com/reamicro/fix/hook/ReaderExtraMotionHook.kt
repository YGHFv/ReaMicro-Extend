package com.reamicro.fix.hook

import android.view.View
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookCallback.Call
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.ArrayDeque

/** Motion hooks are gated by actual reader state/view identity, including shared Compose classes. */
internal class ReaderExtraMotionHook(
    private val symbols: HostReaderFeatures.Symbols,
    private val speed: (Int) -> Float,
    private val nativeMode: () -> Int = { 4 },
) {
    private val curlOwners = WeakIdentityMap<CurlState>()
    private val curlChildren = WeakIdentityMap<CurlState>()
    private val curlFrames = ThreadLocal.withInitial { ArrayDeque<CurlFrame>() }

    private class CurlState(owner: Any, child: Any) {
        val owner = WeakReference(owner)
        val child = WeakReference(child)
        var factor = 1f
        var active = false
        var hide: WeakReference<Any>? = null
        var restore: WeakReference<Any>? = null
    }
    private data class CurlFrame(var owner: Any?, var kind: Int)

    fun install(): Set<Int> {
        val installed = linkedSetOf<Int>()
        if (installFeature("curl", "extraCurlDuration", ::installCurl)) installed += 4
        return installed
    }

    private fun installFeature(label: String, required: String, block: (Hooks) -> Unit): Boolean {
        val hooks = Hooks()
        val result = runCatching {
            check(symbols.optional(required) != null) { "Missing verified reader $label symbol: $required" }
            block(hooks)
        }.onSuccess {
            HookRuntime.log("ReaMicro reader $label speed installed")
        }.onFailure {
            hooks.rollback()
            HookRuntime.logError("ReaMicro reader $label speed installation failed", it)
        }
        HookInstallReport.record("ReaderExtraMotionHook", label, result.isSuccess, result.exceptionOrNull())
        return result.isSuccess
    }

    private fun installCurl(hooks: Hooks) {
        val animationSetter = symbols["extraCurlDuration"]
        val dispatch = symbols["extraCurlDispatch"]
        val cleanup = symbols["extraCurlCleanup"]
        val first = symbols["extraCurlFirst"]
        val finish = symbols["extraCurlFinish"]
        val maintenance = symbols["extraCurlMaintenance"]
        val detach = symbols["extraCurlDetach"]
        val ownerConstructor = symbols.constructor("extraCurlOwner")
        val childField = symbols.field("extraCurlChild")
        val callbackOwner = listOf("First", "Finish", "Maintenance").associate { role ->
            symbols["extraCurl$role"].declaringClass to symbols.field("extraCurl${role}Owner")
        }
        check(Runnable::class.java.isAssignableFrom(first.declaringClass) &&
            Runnable::class.java.isAssignableFrom(finish.declaringClass) &&
            Runnable::class.java.isAssignableFrom(maintenance.declaringClass))

        fun state(owner: Any): CurlState = curlOwners[owner] ?: run {
            val child = requireNotNull(childField.get(owner))
            CurlState(owner, child).also { curlOwners.put(owner, it); curlChildren.put(child, it) }
        }
        fun callbackBelongs(callback: Any, owner: Any): Boolean =
            callbackOwner[callback.javaClass]?.get(callback) === owner
        fun frameHook(kind: Int, owner: (Call) -> Any?) = object : HookCallback() {
            override fun before(param: Call) {
                val frame = CurlFrame(null, kind)
                curlFrames.get().push(frame)
                frame.owner = owner(param)
            }
            override fun after(param: Call) { curlFrames.get().pop() }
        }
        hooks.method(ownerConstructor, object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable == null) param.thisObject?.let { state(it) }
            }
        })
        hooks.method(dispatch, frameHook(DISPATCH) { param ->
            val owner = param.thisObject ?: return@frameHook null
            // Also discover a controller that existed before hooks were installed/reloaded.
            state(owner)
            owner
        })
        hooks.method(cleanup, frameHook(CLEANUP) { it.thisObject })
        hooks.method(first, frameHook(SYNTHETIC) { param -> callbackOwner[first.declaringClass]?.get(param.thisObject) })
        hooks.method(finish, frameHook(SYNTHETIC) { param -> callbackOwner[finish.declaringClass]?.get(param.thisObject) })
        hooks.method(maintenance, object : HookCallback() {
            override fun before(param: Call) {
                val frame = CurlFrame(null, OTHER)
                curlFrames.get().push(frame)
                val callback = param.thisObject ?: return
                frame.owner = callbackOwner[maintenance.declaringClass]?.get(callback)
                val current = frame.owner?.let { curlOwners[it] }
                frame.kind = if (current?.restore?.get() === callback) RESTORE else MAINTENANCE
            }
            override fun after(param: Call) { curlFrames.get().pop() }
        })
        hooks.method(animationSetter, object : HookCallback() {
            override fun before(param: Call) {
                val child = param.thisObject ?: return
                val current = curlChildren[child] ?: return
                val frame = curlFrames.get().peek() ?: return
                if (frame.owner !== current.owner.get()) return
                val value = param.args[0] as? Int ?: return
                if (value == 0) {
                    if (frame.kind == RESTORE) current.active = false
                    return // Zero means follow the finger; never coerce it to one millisecond.
                }
                if (frame.kind == DISPATCH && value == 120) {
                    val nextFactor = factor(nativeMode())
                    // The host cancels this old callback only at the next ACTION_UP. At a slow
                    // speed it could otherwise restore zero halfway through a new click.
                    if (current.active && (current.factor != 1f || nextFactor != 1f)) {
                        val restore = current.restore?.get() as? Runnable
                        val owner = current.owner.get() as? View
                        if (restore != null && owner != null) owner.removeCallbacks(restore)
                    }
                    current.factor = nextFactor
                    current.active = true
                }
                if (!current.active || (frame.kind != DISPATCH && frame.kind != SYNTHETIC)) return
                if (value == 120 || value == 200) param.args[0] = ReaderExtraMotionPolicy.duration(value, current.factor)
            }
        })
        val post = View::class.java.getDeclaredMethod("postDelayed", Runnable::class.java, Long::class.javaPrimitiveType)
        hooks.method(post, object : HookCallback() {
            override fun before(param: Call) {
                val owner = param.thisObject ?: return
                val current = curlOwners[owner] ?: return
                if (!current.active) return
                val frame = curlFrames.get().peek() ?: return
                if (frame.owner !== owner) return
                val callback = param.args[0] ?: return
                if (!callbackBelongs(callback, owner)) return
                val delay = param.args[1] as? Long ?: return
                val maintenanceCallback = callback.javaClass == maintenance.declaringClass
                val applies = when (frame.kind) {
                    DISPATCH -> callback.javaClass == first.declaringClass && delay == 16L
                    SYNTHETIC -> when {
                        callback.javaClass == finish.declaringClass && (delay == 32L || delay == 128L) -> true
                        maintenanceCallback && delay == 260L -> {
                            current.restore = WeakReference(callback)
                            true
                        }
                        else -> false
                    }
                    CLEANUP -> if (maintenanceCallback) {
                        current.hide = WeakReference(callback)
                        true
                    } else false
                    MAINTENANCE -> (current.hide?.get() === callback) && delay == 64L
                    else -> false
                }
                // Keep polling at the host's frame cadence; long press and bitmap readiness never match.
                if (applies && current.factor != 1f && delay in 1..Int.MAX_VALUE.toLong()) {
                    param.args[1] = ReaderExtraMotionPolicy.duration(delay.toInt(), current.factor).toLong()
                }
            }
        })
        hooks.method(detach, object : HookCallback() {
            override fun after(param: Call) {
                val owner = param.thisObject ?: return
                curlOwners.remove(owner)?.let { current ->
                    current.active = false
                    current.child.get()?.let { curlChildren.remove(it) }
                }
            }
        })
        listOf(dispatch, cleanup, first, finish, maintenance, symbols["extraCurlTouch"],
            ownerConstructor).forEach(::deoptimize)
    }

    private fun deoptimize(method: Executable) {
        runCatching {
            if (!HookRuntime.deoptimize(method)) HookRuntime.log("ReaMicro extra motion deoptimization declined: $method")
        }.onFailure {
            HookRuntime.logError("ReaMicro extra motion deoptimization failed: $method", it)
        }
    }

    private fun factor(mode: Int): Float = ReaderPageSpeed.normalize(runCatching { speed(mode) }.getOrDefault(1f))

    private class Hooks {
        private val undo = mutableListOf<() -> Unit>()
        fun method(method: Executable, callback: HookCallback) {
            val handle = HookRuntime.hookMethod(method, callback)
            undo += { handle.unhook() }
        }
        fun rollback() { undo.asReversed().forEach { runCatching(it) }; undo.clear() }
    }

    /** WeakHashMap uses equals/hashCode, which is unsuitable for state objects that override either. */
    private class WeakIdentityMap<V> {
        private val entries = mutableListOf<Pair<WeakReference<Any>, V>>()
        @Synchronized operator fun get(key: Any): V? {
            entries.removeAll { it.first.get() == null }
            return entries.firstOrNull { it.first.get() === key }?.second
        }
        @Synchronized fun put(key: Any, value: V) {
            entries.removeAll { it.first.get() == null || it.first.get() === key }
            entries += WeakReference(key) to value
        }
        @Synchronized fun remove(key: Any): V? {
            val old = get(key)
            entries.removeAll { it.first.get() == null || it.first.get() === key }
            return old
        }
    }

    private companion object {
        const val DISPATCH = 1
        const val SYNTHETIC = 2
        const val CLEANUP = 3
        const val MAINTENANCE = 4
        const val RESTORE = 5
        const val OTHER = 6
    }
}

/** Pure boundary policy shared by the hook and JVM tests. */
internal object ReaderExtraMotionPolicy {
    fun duration(value: Int, factor: Float): Int = if (value == 0) 0 else ReaderPageSpeed.duration(value, factor)
}
