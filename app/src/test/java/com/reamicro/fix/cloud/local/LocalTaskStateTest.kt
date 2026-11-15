package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalTaskStateTest {
    @Test fun staleCompletionCannotUndoPauseOrNewSchedule() {
        val current = JSONObject().put("enabled", false).put("configUpdatedAt", 200L)
            .put("nextRunAt", 0L).put("lastRunAt", 10L)
        LocalTaskRuntimeMerge.complete(current, 100L,
            JSONObject().put("lastRunAt", 20L).put("nextRunAt", 300L).put("lastMessage", "已完成"), 30L)
        assertFalse(current.getBoolean("enabled"))
        assertEquals(0L, current.getLong("nextRunAt"))
        assertEquals(20L, current.getLong("lastRunAt"))
        assertEquals("已完成", current.getString("lastMessage"))
    }

    @Test fun newConfigurationRetainsLaterRuntimeButOwnsItsSchedule() {
        val current = JSONObject().put("enabled", true).put("configUpdatedAt", 100L)
            .put("runtimeUpdatedAt", 400L).put("lastRunAt", 300L).put("nextRunAt", 500L)
        val incoming = JSONObject().put("enabled", true).put("configUpdatedAt", 200L)
            .put("runtimeUpdatedAt", 250L).put("lastRunAt", 150L).put("nextRunAt", 600L)
        val merged = LocalTaskRuntimeMerge.snapshot(current, incoming)
        assertEquals(300L, merged.getLong("lastRunAt"))
        assertEquals(600L, merged.getLong("nextRunAt"))
        assertEquals(400L, merged.getLong("runtimeUpdatedAt"))
    }

    @Test fun deletedRecordStaysDeletedWhenRawExecutorHistoryArrives() {
        val record = LocalTaskRecord(100L, "yeshe_checkin", "success", "签到完成", "")
        val json = JSONObject().put("at", record.at).put("taskType", record.taskType)
            .put("result", record.result).put("message", record.message).put("detail", record.detail)
        val raw = JSONArray().put(JSONObject(json.toString())).put(JSONObject(json.toString()))
        val account = JSONObject().put("records", JSONArray(raw.toString()))
        assertEquals(1, deleteSelectedLocalTaskRecords(account, listOf(record)))
        applyLocalTaskRecordDeletionsSnapshot(account, JSONObject().put("records", raw))
        assertEquals(1, account.getJSONArray("records").length())
        applyLocalTaskRecordDeletionsSnapshot(account, JSONObject(account.toString()))
        assertEquals(1, account.getJSONArray("records").length())
    }

    @Test fun bridgeCannotAuthenticateAnArbitraryPackageOrClaimedUid() {
        assertFalse(LocalTaskBridgeProtocol.authorized(-1, 1000, listOf("app.zhendong.reamicro")))
        assertFalse(LocalTaskBridgeProtocol.authorized(2000, 1000, listOf("com.android.shell")))
        assertTrue(LocalTaskBridgeProtocol.authorized(1000, 1000, emptyList()))
        assertTrue(LocalTaskBridgeProtocol.authorized(2000, 1000, listOf("app.zhendong.reamicro")))
    }
}
