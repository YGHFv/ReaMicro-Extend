package com.reamicro.fix.settings

import java.util.concurrent.atomic.AtomicReference

internal class PreferenceSnapshotCache<T : Any> {
    private class State<T>(val value: T? = null)

    private val state = AtomicReference(State<T>())

    fun getOrBuild(build: () -> T?): T? {
        val previous = state.get()
        previous.value?.let { return it }
        val value = build() ?: return null
        state.compareAndSet(previous, State(value))
        return value
    }

    fun clear() {
        state.set(State())
    }
}
