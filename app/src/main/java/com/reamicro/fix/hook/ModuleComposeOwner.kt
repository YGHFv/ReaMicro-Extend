package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** Module-owned owners: never cast the host's separately loaded Lifecycle/Compose classes. */
internal class ModuleComposeOwner(private val activity: Activity, private val onHostDestroyed: () -> Unit = {}) :
    LifecycleOwner, SavedStateRegistryOwner, Application.ActivityLifecycleCallbacks {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private var closed = false

    fun start() {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        activity.application.registerActivityLifecycleCallbacks(this)
        registry.currentState = Lifecycle.State.RESUMED
    }
    fun close() {
        if (closed) return
        closed = true
        activity.application.unregisterActivityLifecycleCallbacks(this)
        registry.currentState = Lifecycle.State.DESTROYED
    }
    override fun onActivityResumed(a: Activity) { if (a === activity && !closed) registry.currentState = Lifecycle.State.RESUMED }
    override fun onActivityPaused(a: Activity) { if (a === activity && !closed) registry.currentState = Lifecycle.State.STARTED }
    override fun onActivityStopped(a: Activity) { if (a === activity && !closed) registry.currentState = Lifecycle.State.CREATED }
    override fun onActivityDestroyed(a: Activity) {
        if (a === activity) { close(); onHostDestroyed() }
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
}
