package com.reamicro.fix.cloud.local

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalTaskSnapshotTest {
    @Test
    fun `stale snapshots cannot restore cleared history or remove newer records`() {
        val current = JSONObject().put("recordsClearedAt", 1500L)
        val incoming = JSONObject().put("records", JSONArray()
            .put(JSONObject().put("at", 1000L)).put(JSONObject().put("at", 2000L)))
        applyLocalTaskRecordsSnapshot(current, incoming)
        assertEquals(1, current.getJSONArray("records").length())
        assertEquals(2000L, current.getJSONArray("records").getJSONObject(0).getLong("at"))
        assertEquals(1500L, current.getLong("recordsClearedAt"))
    }

    @Test
    fun `handoff accepts runtime progress without undoing newer configuration`() {
        val current = JSONObject().put("enabled", true).put("configUpdatedAt", 2L)
            .put("durationMinutes", 10).put("nextRunAt", 1000L).put("dailyReadMinutes", 0)
        val incoming = JSONObject().put("enabled", true).put("configUpdatedAt", 1L)
            .put("durationMinutes", 30).put("nextRunAt", 2000L).put("dailyReadMinutes", 30)
        val merged = mergeLocalTaskSnapshot(current, incoming)
        assertEquals(10, merged.getInt("durationMinutes"))
        assertEquals(30, merged.getInt("dailyReadMinutes"))
        assertEquals(1000L, merged.getLong("nextRunAt"))
    }

    @Test
    fun `snapshot cannot reenable a newly disabled task`() {
        val current = JSONObject().put("enabled", false).put("configUpdatedAt", 2L).put("nextRunAt", 0L)
        val incoming = JSONObject().put("enabled", true).put("configUpdatedAt", 1L)
            .put("nextRunAt", 2000L).put("dailyCounter", 2)
        val merged = mergeLocalTaskSnapshot(current, incoming)
        assertFalse(merged.getBoolean("enabled"))
        assertEquals(0L, merged.getLong("nextRunAt"))
        assertEquals(2, merged.getInt("dailyCounter"))
    }
}
