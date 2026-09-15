package com.reamicro.fix.cloud.ksu

import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTaskEngine
import com.reamicro.fix.cloud.local.LocalTaskKey
import com.reamicro.fix.cloud.local.rescheduleLocalTasks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

class KsuTaskEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = System.currentTimeMillis()
    private val today = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()

    private fun task(enabled: Boolean = true, next: Long = 0L) = JSONObject()
        .put("enabled", enabled).put("nextRunAt", next).put("blessingType", "")
        .put("configUpdatedAt", 1L).put("automationStateVersion", 1)

    private fun repository(tasks: JSONObject): KsuTaskRepository = KsuTaskRepository(temporary.newFolder()).apply {
        configure(JSONObject().put("accounts", JSONObject().put("3", JSONObject().put("tasks", tasks).put("token", "test-secret"))), enable = true)
    }

    @Test
    fun `crash after claiming but before queueing draw still leaves an immediate wake`() {
        val repository = repository(JSONObject().put("yeshe_checkin", task(next = now + 86_400_000L)).put("yeshe_draw_card", task()))
        repository.recordState("3", "yeshe_checkin", JSONObject().put("claimLoreId", 99L).put("claimCompletedDate", today))
        val next = repository.snapshot().getLong("nextTaskAt")
        assertTrue(next in now..System.currentTimeMillis())
    }

    @Test
    fun `unexpected failure cannot roll back a saved checkpoint`() {
        val repository = repository(JSONObject().put("cloud_auto_read", task()))
        repository.recordState("3", "cloud_auto_read", JSONObject().put("dailyReadMinutes", 5))
        val engine = LocalTaskEngine(repository, { now }, { _, _, _, _ ->
            repository.recordState("3", "cloud_auto_read", JSONObject().put("dailyReadMinutes", 35))
            error("interrupted after checkpoint")
        })
        assertEquals("failed", engine.runDue().single().result)
        assertEquals(35, repository.runtimeState("3", "cloud_auto_read").getInt("dailyReadMinutes"))
    }

    @Test
    fun `draw waits for reward even when force running all tasks`() {
        val repository = repository(JSONObject().put("yeshe_draw_card", task()))
        val calls = mutableListOf<String>()
        val engine = LocalTaskEngine(repository, { now }, { type, _, _, _ -> calls += type; CloudTaskLocalRunner.Outcome("success", "ok") })
        assertTrue(engine.runDue(force = true).isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals(0L, repository.snapshot().getLong("nextTaskAt"))
    }

    @Test
    fun `rescheduling keeps idle reward draws event based`() {
        val repository = repository(JSONObject().put("yeshe_draw_card", task()))
        assertEquals(0, rescheduleLocalTasks(repository, now))
        assertEquals(0L, repository.snapshot().getLong("nextTaskAt"))
    }

    @Test
    fun `claim reward triggers draw immediately and exactly once`() {
        val repository = repository(JSONObject().put("yeshe_checkin", task()).put("yeshe_draw_card", task()))
        val calls = mutableListOf<String>()
        val engine = LocalTaskEngine(repository, { now }, { type, _, request, _ ->
            calls += type
            assertEquals("", request.getString("blessingType"))
            if (type == "yeshe_checkin") CloudTaskLocalRunner.Outcome("success", "claimed",
                JSONObject().put("claimLoreId", 99L).put("claimCompletedDate", today).put("lastCheckinDate", today))
            else CloudTaskLocalRunner.Outcome("success", "drawn")
        })
        assertEquals(2, engine.runDue().size)
        assertEquals(listOf("yeshe_checkin", "yeshe_draw_card"), calls)
        assertFalse(repository.runtimeState("3", "yeshe_draw_card").optBoolean("drawPending"))
        assertEquals(0L, repository.get("3", "yeshe_draw_card")!!.nextRunAt)
        assertTrue(engine.runDue().isEmpty())
    }

    @Test
    fun `failed linked draw retains trigger and retries after delay`() {
        val repository = repository(JSONObject().put("yeshe_checkin", task(next = now + 86_400_000L)).put("yeshe_draw_card", task()))
        repository.recordState("3", "yeshe_checkin", JSONObject().put("claimLoreId", 99L).put("claimCompletedDate", today))
        val engine = LocalTaskEngine(repository, { now }, { _, _, _, _ -> CloudTaskLocalRunner.Outcome("failed", "retry") })
        assertEquals(1, engine.runDue().size)
        assertTrue(repository.runtimeState("3", "yeshe_draw_card").optBoolean("drawPending"))
        assertEquals(now + 300_000L, repository.get("3", "yeshe_draw_card")!!.nextRunAt)
        assertTrue(engine.runDue().isEmpty())
    }

    @Test
    fun `manual claim still persists a pending reward event`() {
        val repository = repository(JSONObject().put("yeshe_checkin", task()).put("yeshe_draw_card", task()))
        val engine = LocalTaskEngine(repository, { now }, { _, _, _, _ -> CloudTaskLocalRunner.Outcome("success", "claimed",
            JSONObject().put("claimLoreId", 99L).put("claimCompletedDate", today)) })
        engine.runDue(requested = LocalTaskKey("3", "yeshe_checkin"))
        assertTrue(repository.runtimeState("3", "yeshe_draw_card").optBoolean("drawPending"))
        assertEquals(now, repository.get("3", "yeshe_draw_card")!!.nextRunAt)
    }

    @Test
    fun `disabled draw is never automatically enabled by a claim`() {
        val repository = repository(JSONObject().put("yeshe_checkin", task(next = now + 1000)).put("yeshe_draw_card", task(enabled = false)))
        repository.recordState("3", "yeshe_checkin", JSONObject().put("claimLoreId", 99L).put("claimCompletedDate", today))
        assertTrue(LocalTaskEngine(repository, { now }).runDue().isEmpty())
        assertFalse(repository.get("3", "yeshe_draw_card")!!.enabled)
    }

    @Test
    fun `credential loss records actionable status and avoids one minute wake loop`() {
        val repository = repository(JSONObject().put("pawn", task()))
        repository.clearCredentials()
        val results = LocalTaskEngine(repository, { now }).runDue()
        assertEquals("paused", results.single().result)
        assertTrue(results.single().message.contains("登录凭据"))
        assertEquals(now + 3_600_000L, repository.get("3", "pawn")!!.nextRunAt)
    }

    @Test
    fun `disabled backend cannot execute even manually`() {
        val repository = repository(JSONObject().put("pawn", task()))
        repository.disable()
        assertTrue(LocalTaskEngine(repository).runDue(requested = LocalTaskKey("3", "pawn")).isEmpty())
    }
}
