package com.reamicro.fix.reload

import android.app.Activity
import android.os.Looper
import java.util.concurrent.RejectedExecutionException

private val activityMainHandler by lazy {
    ModuleResources.handler(Looper.getMainLooper())
}

/** Preserve Activity's immediate main-thread dispatch while owning queued work. */
fun Activity.runOnModuleUiThread(action: Runnable) {
    if (!ModuleResources.accepting) return
    if (Looper.myLooper() == Looper.getMainLooper()) {
        val lease = ModuleResources.lease() ?: return
        lease.use { action.run() }
    } else {
        try {
            activityMainHandler.post(action)
        } catch (_: RejectedExecutionException) {
            // Retirement can win the race before the first handler is created.
        }
    }
}

fun Activity.runOnModuleUiThread(action: () -> Unit) =
    runOnModuleUiThread(Runnable(action))
