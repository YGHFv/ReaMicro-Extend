package com.reamicro.fix.cloud.root

import org.json.JSONObject

internal class RootExecutionHandoff(
    private val persistMode: (Boolean) -> Unit,
    private val exchange: (String, JSONObject?) -> JSONObject,
    private val restore: (JSONObject) -> Unit,
) {
    fun enable(payload: JSONObject) {
        persistMode(true)
        try {
            check(restoreSnapshot(exchange("enable", payload))) {
                "Root 未确认接管，请检查模块状态后重新开启"
            }
        } catch (failure: Exception) {
            if (failure !is InterruptedException && !Thread.currentThread().isInterrupted) {
                try {
                    restoreSnapshot(exchange("snapshot", null))
                } catch (confirmation: Exception) {
                    failure.addSuppressed(confirmation)
                }
            }
            throw failure
        }
    }

    fun synchronize(payload: JSONObject): Boolean {
        val snapshot = try {
            exchange("configure", payload)
        } catch (failure: Exception) {
            if (failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
            val current = try {
                exchange("snapshot", null).also { executionActive(it) }
            } catch (confirmation: Exception) {
                failure.addSuppressed(confirmation)
                throw failure
            }
            if (executionActive(current)) throw failure
            current
        }
        return restoreSnapshot(snapshot)
    }

    fun disable() {
        restoreProgress()
    }

    fun remove(hasProgress: Boolean, cleanup: () -> Boolean): Removal {
        val progressSaved = try {
            if (hasProgress) restoreProgress()
            true
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            false
        }
        val pendingReboot = cleanup()
        persistMode(false)
        return Removal(pendingReboot, progressSaved)
    }

    class Removal(val pendingReboot: Boolean, val progressSaved: Boolean)

    private fun restoreProgress() {
        val snapshot = exchange("disable", null)
        check(snapshot.opt("enabled") == false) { "Root 仍持有执行权，未切换" }
        // A failed local snapshot write must not leave a confirmed stop selected as Root.
        persistMode(false)
        restore(snapshot)
    }

    private fun restoreSnapshot(snapshot: JSONObject): Boolean {
        val active = executionActive(snapshot)
        if (!active) {
            // Wait for any finishing executor and revoke credentials before returning to manual mode.
            restoreProgress()
            return false
        }
        restore(snapshot)
        return true
    }

    private fun executionActive(snapshot: JSONObject): Boolean {
        check(snapshot.opt("enabled") is Boolean &&
            (!snapshot.has("moduleEnabled") || snapshot.opt("moduleEnabled") is Boolean)) {
            "Root 快照缺少执行状态，保留当前模式"
        }
        return snapshot.getBoolean("enabled") && snapshot.optBoolean("moduleEnabled", true)
    }
}
