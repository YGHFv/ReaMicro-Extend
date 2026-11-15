package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.database.ContentObserver
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.ModuleUiRuntime
import com.reamicro.fix.xposed.HookRuntime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal class ModuleComposeOwner(private val activity: Activity, private val onHostDestroyed: () -> Unit = {}) :
    LifecycleOwner, SavedStateRegistryOwner, Application.ActivityLifecycleCallbacks {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private var closed = false
    private var started = false
    private var tasks: ModuleUiRuntime.OwnedScope? = null
    private var recomposer: Recomposer? = null
    private var frameClock: PausableMonotonicFrameClock? = null
    private val views = linkedSetOf<ComposeView>()
    private val animationHandler by lazy { ModuleResources.handler(Looper.getMainLooper()) }
    private var observingAnimationScale = false
    private val animationScale = object : MotionDurationScale {
        var value by mutableFloatStateOf(readAnimationScale())
        override val scaleFactor: Float get() = value
    }
    private val animationObserver = object : ContentObserver(animationHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!closed) animationScale.value = readAnimationScale()
        }
    }

    private fun readAnimationScale(): Float =
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it.isFinite() && it >= 0f } ?: 1f

    fun start() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Compose owner requires the main thread" }
        check(!closed) { "Compose owner is already closed" }
        if (started) return
        tasks = ModuleUiRuntime.scope("模块面板")
        try {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
            activity.application.registerActivityLifecycleCallbacks(this)
            started = true
            state(Lifecycle.State.RESUMED)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Set an explicitly owned recomposer before setContent can create a window-global one. */
    fun attach(view: ComposeView) {
        check(started && !closed) { "Compose owner is not active" }
        if (view in views) return
        check(!view.hasComposition) { "ComposeView was composed before its owner was attached" }
        ModuleUiRuntime.prepareCompose()
        val parent = recomposer ?: run {
            val clock = PausableMonotonicFrameClock(AndroidUiFrameClock(Choreographer.getInstance()))
            val context = requireNotNull(tasks).scope.coroutineContext + clock + animationScale
            val created = Recomposer(context)
            frameClock = clock
            recomposer = created
            activity.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationObserver,
            )
            observingAnimationScale = true
            requireNotNull(tasks).scope.launch(context, start = CoroutineStart.UNDISPATCHED) {
                created.runRecomposeAndApplyChanges()
            }
            updateFrameClock()
            created
        }
        view.setParentCompositionContext(parent)
        views += view
    }

    private fun state(value: Lifecycle.State) {
        registry.currentState = value
        updateFrameClock()
    }

    private fun updateFrameClock() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            frameClock?.resume()
            recomposer?.resumeCompositionFrameClock()
        } else {
            frameClock?.pause()
            recomposer?.pauseCompositionFrameClock()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        fun release(action: () -> Unit) {
            runCatching(action).onFailure {
                ModuleUiRuntime.recordFailure("模块面板资源回收失败：${it.javaClass.simpleName}")
                HookRuntime.logError("ReaMicro Compose owner cleanup failed", it)
            }
        }
        release { activity.application.unregisterActivityLifecycleCallbacks(this) }
        if (observingAnimationScale) release {
            activity.contentResolver.unregisterContentObserver(animationObserver)
        }
        observingAnimationScale = false
        animationHandler.removeCallbacksAndMessages(null)
        views.forEach { view ->
            release { ModuleUiRuntime.awaitViewDetached(view) }
            release { view.disposeComposition() }
        }
        views.clear()
        release {
            if (registry.currentState != Lifecycle.State.INITIALIZED) state(Lifecycle.State.DESTROYED)
        }
        release { recomposer?.cancel() }
        recomposer = null
        frameClock = null
        // The runtime removes this scope only from Job.invokeOnCompletion, after every child exits.
        tasks?.close()
        tasks = null
    }
    override fun onActivityResumed(a: Activity) { if (a === activity && !closed) state(Lifecycle.State.RESUMED) }
    override fun onActivityPaused(a: Activity) { if (a === activity && !closed) state(Lifecycle.State.STARTED) }
    override fun onActivityStopped(a: Activity) { if (a === activity && !closed) state(Lifecycle.State.CREATED) }
    override fun onActivityDestroyed(a: Activity) {
        if (a === activity) { close(); onHostDestroyed() }
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) { if (a === activity && !closed) state(Lifecycle.State.STARTED) }
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
}
