package com.reamicro.fix.cloud.root

import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.cloud.local.LocalTaskRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

internal object RootNightDiagnostics {
    private val directory = File(RootTaskRepository.STATE_DIRECTORY)
    private val log = File(directory, "night-diagnostic.jsonl")
    private var startedAt = 0L
    private var failureProbed = false
    @Volatile private var enabled = false

    fun begin() {
        enabled = Process.myUid() == 0
        startedAt = SystemClock.elapsedRealtime()
        failureProbed = false
        capture("run_start", JSONObject()
            .put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND).put("model", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("appBuildTimeMs", BuildConfig.BUILD_TIME)
            .put("networkPolicy", RootVendorNetworkPolicy.forDevice(Build.MANUFACTURER, Build.BRAND).id))
    }

    fun finish(records: List<LocalTaskRecord>) {
        capture("run_end", JSONObject().put("attemptedCount", records.size)
            .put("successCount", records.count { it.result == "success" })
            .put("failedCount", records.count { it.result == "failed" })
            .put("pausedCount", records.count { it.result == "paused" }))
        enabled = false
    }

    fun abort(error: Throwable) {
        capture("run_aborted", JSONObject().put("errorType", error.javaClass.name))
        enabled = false
    }

    fun networkPreparation(event: RootNetworkEvent) {
        capture(event.name, stateJson(event.state)
            .put("preparationElapsedMs", event.elapsedMs).put("vendor", event.vendor)
            .put("dnsReady", event.dns?.ready ?: JSONObject.NULL)
            .put("dnsError", event.dns?.errorType.orEmpty())
            .put("dnsElapsedMs", event.dnsElapsedMs ?: JSONObject.NULL)
            .put("gaiError", event.dns?.gaiError ?: JSONObject.NULL)
            .put("errno", event.dns?.errno ?: JSONObject.NULL)
            .put("motionAccepted", event.motionAccepted ?: JSONObject.NULL)
            .put("vendorWakeAccepted", event.vendorWakeAccepted ?: JSONObject.NULL))
    }

    fun requestFailure(url: String, error: Throwable, elapsedMs: Long) {
        if (!enabled) return
        val detail = JSONObject().put("host", runCatching { URI(url).host }.getOrNull().orEmpty())
            .put("requestElapsedMs", elapsedMs).put("exceptionChain", exceptionChain(error))
        // 每轮最多补采一次真实失败时状态，避免诊断把多个失败拖成额外长等待。
        if (!failureProbed && error !is InterruptedException && !Thread.currentThread().isInterrupted) {
            failureProbed = true
            val state = try {
                RootTaskNetwork.probe(3_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                RootNetworkState(probeError = "InterruptedException")
            } catch (failure: Exception) {
                RootNetworkState(probeError = failure.javaClass.simpleName)
            }
            detail.put("failureNetwork", stateJson(state))
        }
        capture("request_failure", detail)
    }

    internal fun exceptionChain(error: Throwable): JSONArray {
        val result = JSONArray()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun append(current: Throwable, relation: String) {
            if (result.length() >= 16 || !visited.add(current)) return
            result.put(JSONObject().put("relation", relation).put("type", current.javaClass.name).apply {
                if (current is android.system.ErrnoException) put("errno", current.errno)
                rootGaiError(current)?.let { put("gaiError", it) }
            })
            current.cause?.let { append(it, "cause") }
            current.suppressed.take(4).forEach { append(it, "suppressed") }
        }
        append(error, "root")
        return result
    }

    private fun stateJson(state: RootNetworkState): JSONObject = JSONObject()
        .put("deep", state.deep).put("light", state.light)
        .put("network", state.network ?: JSONObject.NULL).put("defaultNetwork", state.defaultNetwork)
        .put("probeError", state.probeError).put("screen", state.screen ?: JSONObject.NULL)
        .put("airplaneMode", state.airplaneMode ?: JSONObject.NULL)
        .put("oplusDeepSleep", state.oplusDeepSleep ?: JSONObject.NULL)
        .put("oplusNetworkDisabled", state.oplusNetworkDisabled ?: JSONObject.NULL)
        .put("oplusNetworkType", state.oplusNetworkType ?: JSONObject.NULL)
        .put("oplusNetworkRestricted", state.oplusNetworkRestricted)
        .put("xiaomiSleep", state.xiaomiSleep ?: JSONObject.NULL)

    @Synchronized
    private fun capture(event: String, detail: JSONObject) {
        if (!enabled) return
        runCatching {
            val entry = JSONObject().put("diagnosticSchema", 4).put("event", event)
                .put("wallTimeMs", System.currentTimeMillis()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("sinceRunStartMs", SystemClock.elapsedRealtime() - startedAt)
                .put("uid", Process.myUid()).put("pid", Process.myPid()).put("detail", detail)
            for (name in listOf("heartbeat", "wake-at", "wake-mode", "next-run-at")) {
                val value = runCatching {
                    File(directory, name).reader().use { reader ->
                        val buffer = CharArray(128)
                        val size = reader.read(buffer)
                        if (size > 0) String(buffer, 0, size).trim() else ""
                    }
                }.getOrDefault("")
                entry.put(name, value)
            }
            if (log.length() >= 1024 * 1024) {
                val previous = File(directory, "night-diagnostic.previous.jsonl")
                previous.delete()
                check(log.renameTo(previous))
            }
            log.appendText(entry.toString() + "\n", Charsets.UTF_8)
            log.setReadable(false, false); log.setWritable(false, false)
            log.setReadable(true, true); log.setWritable(true, true)
        }
    }
}
