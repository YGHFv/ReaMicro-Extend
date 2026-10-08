package com.reamicro.fix.hook

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.widget.Toast
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.xposed.XposedBridge

private const val FAILURE_PID = "scroll_failure_v2_pid"
private const val FAILURE_PROCESS = "scroll_failure_v2_process"
private const val FAILURE_TIME = "scroll_failure_v2_time"

internal fun ReaderHook.initializeScrollCrashRecovery() {
    if (scrollCrashRecoveryInitialized) return
    val context = activityProvider()?.applicationContext ?: return
    synchronized(scrollCrashRecoveryInFlight) {
        if (scrollCrashRecoveryInitialized) return
        runCatching {
            val prefs = context.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
            val legacy = prefs.contains(SCROLL_CRASH_PENDING_KEY)
            val failure = if (prefs.contains(FAILURE_TIME)) ScrollRenderFailure(
                prefs.getInt(FAILURE_PID, -1), prefs.getString(FAILURE_PROCESS, "").orEmpty(),
                prefs.getLong(FAILURE_TIME, 0),
            ) else null
            val exits = if (failure != null && Build.VERSION.SDK_INT >= 30) runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, 16).orEmpty().map {
                        ScrollProcessExit(it.pid, it.processName, it.timestamp, it.reason)
                    }
            }.getOrDefault(emptyList()) else emptyList()
            scrollCrashRecoveryPending = shouldRecoverScrollFailure(failure, exits, context.packageName, System.currentTimeMillis())

            if (legacy || failure != null) prefs.edit().remove(SCROLL_CRASH_PENDING_KEY)
                .remove(FAILURE_PID).remove(FAILURE_PROCESS).remove(FAILURE_TIME).apply()
            if (legacy) XposedBridge.log("$LOG_PREFIX legacy scroll entry marker discarded; flip_style unchanged")
            if (failure != null) XposedBridge.log("$LOG_PREFIX scroll recovery evidence verified=${scrollCrashRecoveryPending}")
        }.onFailure {
            scrollCrashRecoveryPending = false
            XposedBridge.log("$LOG_PREFIX scroll recovery evidence unavailable; preserving flip_style: ${it.javaClass.simpleName}")
        }
        scrollCrashRecoveryInitialized = true
    }
}

internal fun ReaderHook.markScrollRenderFailure(error: Throwable) {
    if (!isScrollRenderFailure(error)) return
    val context = activityProvider()?.applicationContext ?: return
    runCatching {
        scrollCrashMarkerOwnedByThisProcess = true

        val saved = context.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE).edit()
            .remove(SCROLL_CRASH_PENDING_KEY).putInt(FAILURE_PID, Process.myPid())
            .putString(FAILURE_PROCESS, context.packageName).putLong(FAILURE_TIME, System.currentTimeMillis()).commit()
        XposedBridge.log("$LOG_PREFIX scroll render exception recorded=$saved type=${error.javaClass.name}")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX scroll failure evidence write failed: ${it.javaClass.simpleName}")
    }
}

internal fun ReaderHook.clearScrollCrashPending(reason: String) {
    scrollCrashRecoveryPending = false
    if (!scrollCrashMarkerOwnedByThisProcess) return
    scrollCrashMarkerOwnedByThisProcess = false
    runCatching {
        activityProvider()?.applicationContext?.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(SCROLL_CRASH_PENDING_KEY)?.remove(FAILURE_PID)?.remove(FAILURE_PROCESS)?.remove(FAILURE_TIME)?.apply()
    }
    XposedBridge.log("$LOG_PREFIX handled scroll failure evidence cleared: $reason")
}

internal fun ReaderHook.isPreviousScrollCrashPending(): Boolean {
    initializeScrollCrashRecovery()
    return scrollCrashRecoveryPending && !scrollCrashMarkerOwnedByThisProcess
}

internal fun ReaderHook.restoreTranslateFlipStyleIfScrollCrashed(session: Any, source: String): Boolean {
    if (!isPreviousScrollCrashPending()) return false
    if (!scrollCrashRecoveryInFlight.compareAndSet(false, true)) return true
    Thread {
        try {
            if (currentSessionRef?.get() !== session) return@Thread
            val flow = callNoArg(session, "getFlipStyleFlow") ?: return@Thread
            val continuation = classLoader.loadClass("kotlin.coroutines.Continuation")
            val first = classLoader.loadClass("kotlinx.coroutines.flow.FlowKt")
                .getMethod("first", classLoader.loadClass("kotlinx.coroutines.flow.Flow"), continuation)
            val style = (invokeSelectionHostSuspend { first.invoke(null, flow, it) } as? Number)?.toInt()
            if (!mayApplyScrollRecovery(style, currentSessionRef?.get() === session)) return@Thread
            forceTranslateFlipStyle(session)
            val activity = activityProvider()
            activity?.runOnUiThread {
                Toast.makeText(activity, "上次滚动渲染发生崩溃，已切换为平移翻页", Toast.LENGTH_SHORT).show()
            }
            XposedBridge.log("$LOG_PREFIX confirmed scroll crash fallback switched flip_style to translate from $source")
        } catch (error: Throwable) {
            XposedBridge.log("$LOG_PREFIX confirmed scroll recovery failed; releasing render guard: ${error.javaClass.simpleName}")
        } finally {
            scrollCrashRecoveryPending = false
            scrollCrashRecoveryInFlight.set(false)
        }
    }.apply { name = "ReaMicroConfirmedScrollRecovery"; isDaemon = true; start() }
    return true
}

private fun ReaderHook.forceTranslateFlipStyle(session: Any) {
    val keys = classLoader.loadClass(PREF_KEYS_CLASS)
    val instance = keys.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    val key = keys.getMethod("getFLIP_STYLE").invoke(instance)
    val update = session.javaClass.methods.single { it.name == "update" && it.parameterTypes.size == 3 }
        .apply { isAccessible = true }
    invokeSelectionHostSuspend { update.invoke(session, key, Integer.valueOf(FLIP_STYLE_TRANSLATE), it) }
}
