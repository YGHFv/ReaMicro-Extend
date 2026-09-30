package com.reamicro.fix.cloud.local

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class AutoReadTimeLockTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalZone: TimeZone
    private lateinit var server: HttpServer
    private lateinit var credential: JSONObject
    private val calls = mutableListOf<JSONObject>()

    @Before
    fun setup() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = """{"code":0,"data":{}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        credential = JSONObject().put("baseUrl", "http://127.0.0.1:${server.address.port}/").put("token", "test-only")
    }

    @After
    fun teardown() {
        server.stop(0)
        TimeZone.setDefault(originalZone)
    }

    private fun at(time: String, day: String = "2026-09-29"): Long =
        ZonedDateTime.parse("${day}T${time}+08:00[Asia/Shanghai]").toInstant().toEpochMilli()

    private fun request(duration: Int = 180, books: Int = 1, limit: Int = 720): JSONObject =
        JSONObject().put("durationMinutes", duration).put("dailyLimitMinutes", limit)
            .put("books", JSONArray((1..books).map { JSONObject().put("bookId", it).put("name", "Book $it") }))

    @Test
    fun normalizesOldConfigAndMinuteBoundary() {
        assertEquals("03:00", AutoReadTimeLock.safeTime("00:00", 180))
        assertEquals("03:01", AutoReadTimeLock.safeTime("03:00", 181))
        assertEquals("23:59", AutoReadTimeLock.safeTime("23:59", 720))
        val task = localTaskFromJson("cloud_auto_read",
            JSONObject().put("timeOfDay", "00:05").put("durationMinutes", 180))
        assertEquals("03:00", task.timeOfDay)
        assertEquals("00:05", localTaskFromJson("pawn", JSONObject()).timeOfDay)
    }

    @Test
    fun threeHoursAreLockedUntilExactlyThreeOClock() {
        for (time in listOf("00:00:00", "02:59:00", "02:59:59")) {
            val outcome = AutoReadTimeLock.deferredOutcome(180, JSONObject(), at(time))!!
            assertEquals("paused", outcome.result)
            assertEquals(at("03:00:00"), outcome.state.getLong("nextRunAtOverride"))
            assertFalse(outcome.notify)
        }
        assertNull(AutoReadTimeLock.deferredOutcome(180, JSONObject(), at("03:00:00")))
    }

    @Test
    fun repeatedRunsCannotReuseElapsedMinutes() {
        val state = JSONObject().put("dailyReadDate", "2026-09-29").put("dailyReadMinutes", 180)
        assertEquals(at("06:00:00"),
            AutoReadTimeLock.deferredOutcome(180, state, at("03:00:00"))!!.state.getLong("nextRunAtOverride"))
        assertNull(AutoReadTimeLock.deferredOutcome(180, state, at("06:00:00")))
        assertEquals(0, AutoReadTimeLock.usedMinutes(state, at("00:00:00", "2026-09-30")))
    }

    @Test
    fun oldSchedulesAndReschedulesRespectTheLock() {
        val now = at("00:00:00")
        val task = LocalTask("cloud_auto_read", enabled = true, durationMinutes = 180, timeOfDay = "00:05", nextRunAt = now)
        assertEquals(at("03:00:00"), nextLocalTaskAt(task, JSONObject(), JSONObject(), now))
        assertEquals(at("03:00:00"), AutoReadTimeLock.nextDailyAt("00:05", 180, now))
        assertEquals(at("03:00:00", "2026-09-30"), AutoReadTimeLock.nextDailyAt("03:00", 180, at("03:00:00")))
        val repo = MemoryRepository(task)
        rescheduleLocalTasks(repo, now)
        assertEquals(at("03:00:00"), repo.task.nextRunAt)
    }

    @Test
    fun requestedAndForcedExecutionCannotBypassTheLock() {
        val now = at("00:00:00")
        val repo = MemoryRepository(LocalTask("cloud_auto_read", enabled = true, durationMinutes = 180, nextRunAt = now))
        var submitted = false
        val engine = LocalTaskEngine(repo, clock = { now }, execute = { _, _, _, _ ->
            submitted = true
            CloudTaskLocalRunner.Outcome("success", "unexpected")
        })
        val result = engine.runDue(force = true, requested = LocalTaskKey("test", "cloud_auto_read")).single()
        assertFalse(submitted)
        assertEquals("paused", result.result)
        assertEquals(at("03:00:00"), repo.task.nextRunAt)
    }

    @Test
    fun midnightDoesNotEvenFetchRecentBooks() {
        val result = CloudTaskLocalRunner.runAutoRead(JSONObject(), request().apply { remove("books") }, credential,
            clock = { at("00:00:00") })
        assertEquals("paused", result.result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun threeOClockAllowsExactlyOneThreeHourBook() {
        val result = CloudTaskLocalRunner.runAutoRead(JSONObject(), request(), credential, clock = { at("03:00:00") })
        assertEquals("success", result.result)
        assertEquals(180, result.state.getInt("dailyReadMinutes"))
        assertEquals(10_800, calls.single().getJSONArray("list").getJSONObject(0).getInt("duration"))
    }

    @Test
    fun multipleBooksCheckpointBeforeWaitingForMoreElapsedTime() {
        val checkpoints = mutableListOf<Int>()
        val result = CloudTaskLocalRunner.runAutoRead(JSONObject(), request(books = 2), credential,
            checkpoint = { checkpoints += it.getInt("dailyReadMinutes") }, clock = { at("03:00:00") })
        assertEquals("paused", result.result)
        assertEquals(1, calls.size)
        assertEquals(listOf(180), checkpoints)
        assertEquals(180, result.state.getInt("dailyReadMinutes"))
        assertEquals(at("06:00:00"), result.state.getLong("nextRunAtOverride"))
    }

    @Test
    fun clockRollbackBeforeSubmissionIsRechecked() {
        var reads = 0
        val result = CloudTaskLocalRunner.runAutoRead(JSONObject(), request(), credential,
            clock = { if (reads++ == 0) at("03:00:00") else at("02:59:00") })
        assertEquals("paused", result.result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun midnightDuringExecutionStopsOldDaySubmission() {
        var reads = 0
        val result = CloudTaskLocalRunner.runAutoRead(JSONObject(), request(), credential,
            clock = { if (reads++ == 0) at("23:59:59") else at("00:00:00", "2026-09-30") })
        assertEquals("paused", result.result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun dailyCapCanStillBeSmallerThanConfiguredBookDuration() {
        val result = CloudTaskLocalRunner.runAutoRead(JSONObject(), request(duration = 30, limit = 10), credential,
            clock = { at("12:00:00") })
        assertEquals("success", result.result)
        assertEquals(10, result.state.getInt("dailyReadMinutes"))
        assertEquals(600, calls.single().getJSONArray("list").getJSONObject(0).getInt("duration"))
    }

    @Test
    fun springDstStillRequiresThreeActualHours() {
        val newYork = ZoneId.of("America/New_York")
        val midnight = ZonedDateTime.parse("2026-03-08T00:00:00-05:00[America/New_York]").toInstant().toEpochMilli()
        val unlock = AutoReadTimeLock.unlockAt(180, 0, midnight, newYork)
        assertEquals(3 * 3_600_000L, unlock - midnight)
        assertEquals(4, java.time.Instant.ofEpochMilli(unlock).atZone(newYork).hour)
    }

    private class MemoryRepository(var task: LocalTask) : LocalTaskRepository {
        private var state = JSONObject()
        override fun accountIds() = listOf("test")
        override fun list(accountId: String) = listOf(task)
        override fun token(accountId: String) = "test-only"
        override fun runtimeState(accountId: String, taskType: String) = JSONObject(state.toString())
        override fun recordState(accountId: String, taskType: String, state: JSONObject) {
            state.keys().forEach { key -> this.state.put(key, state.get(key)) }
            if (state.has("nextRunAt")) task = task.copy(nextRunAt = state.getLong("nextRunAt"))
        }
        override fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord) {
            recordState(accountId, task.taskType, state)
        }
    }
}
