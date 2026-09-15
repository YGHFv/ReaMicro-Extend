package com.reamicro.fix.cloud.ksu

import com.reamicro.fix.cloud.local.LocalTaskRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KsuTaskRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun payload(revision: Long, blessing: String = "", enabled: Boolean = true): JSONObject = JSONObject()
        .put("accounts", JSONObject().put("3", JSONObject().put("token", "test-secret").put("tasks", JSONObject()
            .put("yeshe_checkin", JSONObject().put("enabled", enabled).put("blessingType", blessing)
                .put("configUpdatedAt", revision).put("nextRunAt", 9000L).put("claimLoreId", 99L)))))

    @Test
    fun `restart keeps task progress but snapshots never contain credentials`() {
        val directory = temporary.newFolder()
        val first = KsuTaskRepository(directory)
        first.configure(payload(1), enable = true)
        first.recordState("3", "yeshe_checkin", JSONObject().put("claimDueAt", 8000L))
        val restarted = KsuTaskRepository(directory)
        assertEquals("test-secret", restarted.token("3"))
        assertEquals(8000L, restarted.runtimeState("3", "yeshe_checkin").getLong("claimDueAt"))
        assertFalse(restarted.snapshot().toString().contains("test-secret"))
        assertFalse(restarted.snapshot().getJSONObject("accounts").getJSONObject("3").has("token"))
    }

    @Test
    fun `new configuration changes choices without overwriting daemon progress`() {
        val repository = KsuTaskRepository(temporary.newFolder())
        repository.configure(payload(1, "LUCK"), enable = true)
        repository.recordState("3", "yeshe_checkin", JSONObject().put("claimDueAt", 8000L).put("claimLoreId", 100L))
        repository.configure(payload(2))
        assertEquals("", repository.get("3", "yeshe_checkin")!!.blessingType)
        assertEquals(100L, repository.runtimeState("3", "yeshe_checkin").getLong("claimLoreId"))
        assertEquals(8000L, repository.runtimeState("3", "yeshe_checkin").getLong("claimDueAt"))
        repository.configure(payload(1, "WEALTH"))
        assertEquals("", repository.get("3", "yeshe_checkin")!!.blessingType)
    }

    @Test
    fun `inflight result cannot resurrect a task disabled while networking`() {
        val repository = KsuTaskRepository(temporary.newFolder())
        repository.configure(payload(1), enable = true)
        val running = repository.get("3", "yeshe_checkin")!!
        repository.configure(payload(2, enabled = false))
        repository.recordExecution("3", running, JSONObject().put("nextRunAt", 5000L).put("lastMessage", "claimed"),
            LocalTaskRecord(1000L, "yeshe_checkin", "success", "claimed"))
        assertFalse(repository.get("3", "yeshe_checkin")!!.enabled)
        assertEquals(0L, repository.get("3", "yeshe_checkin")!!.nextRunAt)
        assertEquals("claimed", repository.get("3", "yeshe_checkin")!!.lastMessage)
    }

    @Test
    fun `disable removes trigger and credential copy but preserves handoff snapshot`() {
        val repository = KsuTaskRepository(temporary.newFolder())
        repository.configure(payload(1), enable = true)
        repository.requestRun(JSONObject().put("force", true))
        repository.disable()
        repository.clearCredentials()
        assertFalse(repository.executionEnabled())
        assertEquals("", repository.token("3"))
        assertNull(repository.takeRequest())
        assertEquals(0L, repository.snapshot().getLong("nextTaskAt"))
        assertEquals(99L, repository.runtimeState("3", "yeshe_checkin").getLong("claimLoreId"))
    }

    @Test
    fun `notifications persist until the app acknowledges successful delivery`() {
        val repository = KsuTaskRepository(temporary.newFolder())
        repository.configure(payload(1), enable = true)
        repository.addNotification("3", "yeshe_checkin", "success", "claimed")
        val notices = repository.snapshot().getJSONArray("notifications")
        assertEquals(1, notices.length())
        repository.acknowledge(setOf("unknown"))
        assertEquals(1, repository.snapshot().getJSONArray("notifications").length())
        repository.acknowledge(setOf(notices.getJSONObject(0).getString("id")))
        assertEquals(0, repository.snapshot().getJSONArray("notifications").length())
    }

    @Test
    fun `module disabled flag revokes execution without deleting records`() {
        val repository = KsuTaskRepository(temporary.newFolder()) { false }
        repository.configure(payload(1), enable = true)
        assertFalse(repository.executionEnabled())
        assertTrue(repository.snapshot().getJSONObject("accounts").has("3"))
    }

    @Test
    fun `manual requests are queued instead of silently replacing one another`() {
        val repository = KsuTaskRepository(temporary.newFolder())
        repository.configure(payload(1), enable = true)
        repository.requestRun(JSONObject().put("taskType", "yeshe_checkin"))
        repository.requestRun(JSONObject().put("taskType", "pawn"))
        assertEquals(1L, repository.snapshot().getLong("nextTaskAt"))
        assertEquals("yeshe_checkin", repository.takeRequest()!!.getString("taskType"))
        assertEquals("pawn", repository.takeRequest()!!.getString("taskType"))
        assertNull(repository.takeRequest())
    }

    @Test
    fun `clearing app history also clears old daemon records without resetting task progress`() {
        val repository = KsuTaskRepository(temporary.newFolder())
        repository.configure(payload(1), enable = true)
        val task = repository.get("3", "yeshe_checkin")!!
        repository.recordExecution("3", task, JSONObject().put("claimLoreId", 100L), LocalTaskRecord(1000L, task.taskType, "success", "claimed"))
        val updated = payload(1)
        updated.getJSONObject("accounts").getJSONObject("3").put("recordsClearedAt", 1500L)
        repository.configure(updated)
        assertEquals(0, repository.snapshot().getJSONObject("accounts").getJSONObject("3").getJSONArray("records").length())
        assertEquals(100L, repository.runtimeState("3", task.taskType).getLong("claimLoreId"))
    }
}
