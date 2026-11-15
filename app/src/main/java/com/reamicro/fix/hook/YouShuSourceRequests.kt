package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.association.provider.YouShuWebSearchBridge
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** A later source toggle or master disable must invalidate an earlier login result. */
internal object YouShuSourceRequests {
    internal class Request(
        val revision: Long,
        activity: Activity?,
        val identity: AccountHostRuntime.Identity?,
        val cancellation: TaskCancellation = TaskCancellation(),
    ) {
        val activity = WeakReference(activity)
    }

    private val requests = WeakHashMap<ReaMicroSettingsHook, Request>()

    init { ModuleResources.cleanup(::invalidateAll) }

    fun begin(owner: ReaMicroSettingsHook): Request {
        val activity = owner.activityProvider()
        val value = Request(YouShuWebSearchBridge.executionRevision, activity,
            runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull())
        val previous = synchronized(requests) { requests.put(owner, value) }
        previous?.cancellation?.cancel()
        return value
    }

    fun current(owner: ReaMicroSettingsHook, value: Request): Boolean {
        if (synchronized(requests) { requests[owner] !== value } || value.cancellation.isCancelled ||
            !YouShuWebSearchBridge.isExecutionCurrent(value.revision) || !owner.settings.snapshot().moduleEnabled) return false
        val activity = value.activity.get() ?: return false
        return owner.activityProvider() === activity && !activity.isFinishing && !activity.isDestroyed &&
            runCatching {
                value.identity?.let { AccountHostRuntime.isCurrentStorage(it, activity) }
                    ?: (AccountHostRuntime.storageSnapshot(activity) == null)
            }.getOrDefault(false)
    }

    fun complete(owner: ReaMicroSettingsHook, value: Request) {
        synchronized(requests) { if (requests[owner] === value) requests.remove(owner) }
        value.cancellation.cancel()
    }

    fun invalidateAll() {
        val pending = synchronized(requests) { requests.values.toList().also { requests.clear() } }
        pending.forEach { it.cancellation.cancel() }
    }
}
