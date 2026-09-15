package com.reamicro.fix.cloud.local

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId

class LocalTaskWorkflowTest {
    private lateinit var server: HttpServer
    private lateinit var credential: JSONObject
    private val calls = mutableListOf<Pair<String, JSONObject>>()
    private val replies = mutableMapOf<String, ArrayDeque<JSONObject>>()
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val endpoint = exchange.requestURI.path.substringAfterLast('/')
            val request = JSONObject(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
            calls += endpoint to request
            val queue = replies[endpoint]
            val reply = if (queue.isNullOrEmpty()) {
                JSONObject().put("code", 500).put("message", "未配置测试响应：$endpoint")
            } else if (queue.size == 1) queue.first() else queue.removeFirst()
            val bytes = reply.toString().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        credential = JSONObject().put("baseUrl", "http://127.0.0.1:${server.address.port}/").put("token", "test-token")
        reply("get-taoist-blessing", JSONObject().put("blessing", JSONObject.NULL))
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun reply(endpoint: String, vararg data: JSONObject) {
        replies[endpoint] = ArrayDeque(data.map { JSONObject().put("code", 0).put("data", it) })
    }

    private fun run(type: String, state: JSONObject = JSONObject(), request: JSONObject = JSONObject()) =
        CloudTaskLocalRunner.runTask(type, state, request, credential)

    private fun lore(finished: Boolean = false, claimed: Boolean = false, end: Long = now + 3_600_000L) =
        JSONObject().put("id", 99).put("title", "测试轶闻").put("isFinish", finished)
            .put("claimed", claimed).put("endTime", end).put("exp", 5)

    private fun trip(status: String = "SETTLED", tripId: Long = 42L, end: Long = now - 60_000L) =
        JSONObject().put("id", tripId).put("status", status).put("endTime", end)
            .put("cityCode", "LANGYA").put("transportId", 5).put("principal", 120)
            .put("settlementAmount", 379).put("eventTitle", "购朝鲜马")

    private fun autoMerchant(blessing: String = "") =
        JSONObject().put("merchantAutoComplete", true).put("blessingType", blessing)

    private fun configureMerchantStart(end: Long = now + 6 * 3_600_000L) {
        reply("settle-traveling-merchant", JSONObject().put("success", true).put("trip", trip()))
        reply("start-traveling-merchant", JSONObject().put("success", true).put("trip", trip("TRAVELING", 43, end)))
    }

    @Test
    fun `不祈禳不会被每日轶闻的默认求运覆盖`() {
        reply("get-daily-lore", lore())
        val result = run("yeshe_checkin", request = JSONObject().put("blessingType", ""))
        assertEquals("success", result.result)
        assertFalse(calls.any { it.first == "pray-taoist-blessing" })
        assertFalse(result.message.contains("已祈禳"))
    }

    @Test
    fun `求运必须在生成轶闻之前祈禳且不能沿用求财`() {
        reply("get-taoist-blessing", JSONObject().put("blessing", JSONObject().put("blessingType", "WEALTH")))
        reply("pray-taoist-blessing", JSONObject().put("success", true).put("blessing", JSONObject().put("blessingType", "LUCK")))
        reply("get-daily-lore", lore())
        val result = run("yeshe_checkin", request = JSONObject().put("blessingType", "LUCK"))
        val prayer = calls.indexOfFirst { it.first == "pray-taoist-blessing" }
        assertTrue(prayer >= 0)
        assertTrue(prayer < calls.indexOfFirst { it.first == "get-daily-lore" })
        assertEquals("LUCK", calls[prayer].second.getString("blessingType"))
        assertTrue(result.message.contains("已祈禳求运签"))
    }

    @Test
    fun `祈禳内层失败不能当成已祈禳或继续生成轶闻`() {
        reply("pray-taoist-blessing", JSONObject().put("success", false).put("message", "道具不足"))
        reply("get-daily-lore", lore())
        val result = run("yeshe_checkin", request = JSONObject().put("blessingType", "LUCK"))
        assertEquals("failed", result.result)
        assertFalse(calls.any { it.first == "get-daily-lore" })
        assertFalse(result.message.contains("已祈禳"))
    }

    @Test
    fun `服务端返回错误签种不能写成配置签种成功`() {
        reply("pray-taoist-blessing", JSONObject().put("success", true).put("blessing", JSONObject().put("blessingType", "WEALTH")))
        reply("get-daily-lore", lore())
        val result = run("yeshe_checkin", request = JSONObject().put("blessingType", "LUCK"))
        assertEquals("failed", result.result)
        assertFalse(result.message.contains("已祈禳求运签"))
    }

    @Test
    fun `等待中的轶闻只保存结束时间不提前领取`() {
        val end = now + 5 * 3_600_000L
        reply("get-daily-lore", lore(end = end / 1000))
        val result = run("yeshe_checkin")
        assertEquals(end / 1000 * 1000, result.state.getLong("claimDueAt"))
        assertEquals(end / 1000 * 1000, result.state.getLong("nextRunAtOverride"))
        assertEquals(99L, result.state.getLong("claimLoreId"))
        assertEquals("测试轶闻", result.detail.getString("轶闻"))
        assertFalse(calls.any { it.first == "complete-daily-lore" })
    }

    @Test
    fun `已领取轶闻仍保存完整正文品质与奖励且不重复领取`() {
        val content = "吾友蒋焘，少负才，以文章知名。\n其文皆艳语，虽老儒不能及。".repeat(80)
        val data = lore(finished = true, claimed = true).put("content", content).put("category", "苹野纂闻")
            .put("quality", "GREEN").put("gem", "3").put("propName", "端砚").put("propQuality", "BLUE")
            .put("completeTime", "1789445877")
        reply("get-daily-lore", data)
        val result = run("yeshe_checkin", JSONObject().put("lastCheckinDate", today), JSONObject().put("blessingType", "LUCK"))
        val snapshot = JSONObject(result.detail.toString()).getJSONObject(DAILY_LORE_DETAIL_KEY)
        assertEquals(content, snapshot.getString("content"))
        assertEquals("GREEN", snapshot.getString("quality"))
        assertEquals("BLUE", snapshot.getString("propQuality"))
        assertEquals("端砚", snapshot.getString("propName"))
        assertEquals(3, snapshot.getInt("gem"))
        assertEquals(1789445877L, snapshot.getLong("completeTime"))
        assertTrue(snapshot.getBoolean("claimed"))
        assertEquals(listOf("get-daily-lore"), calls.map { it.first })
    }

    @Test
    fun `领取结果补充奖励时间但不丢失原始轶闻正文`() {
        reply("get-daily-lore", lore(finished = true, end = now - 1000L).put("content", "完整正文").put("quality", "GREEN"))
        reply("complete-daily-lore", JSONObject().put("claimed", true).put("completeTime", "1789445877")
            .put("gem", "3").put("propName", "端砚").put("propQuality", "BLUE"))
        val snapshot = run("yeshe_checkin").detail.getJSONObject(DAILY_LORE_DETAIL_KEY)
        assertEquals("完整正文", snapshot.getString("content"))
        assertEquals("测试轶闻", snapshot.getString("title"))
        assertEquals("GREEN", snapshot.getString("quality"))
        assertEquals("端砚", snapshot.getString("propName"))
        assertEquals(1789445877L, snapshot.getLong("completeTime"))
        assertTrue(snapshot.getBoolean("claimed"))
    }

    @Test
    fun `暂未解锁的记录也保留轶闻内容且不虚报领取`() {
        reply("get-daily-lore", lore(finished = true, end = now - 1000L).put("content", "完整正文").put("quality", "GREEN"))
        reply("complete-daily-lore", JSONObject().put("success", false).put("message", "尚未解锁"))
        val result = run("yeshe_checkin")
        val snapshot = result.detail.getJSONObject(DAILY_LORE_DETAIL_KEY)
        assertEquals("完整正文", snapshot.getString("content"))
        assertFalse(snapshot.getBoolean("claimed"))
        assertTrue(result.detail.getString("奖励").contains("待领取"))
    }

    @Test
    fun `可领奖状态不受旧的未来领取时间阻挡`() {
        reply("get-daily-lore", lore(finished = true, end = now - 1000))
        replies["complete-daily-lore"] = ArrayDeque(listOf(JSONObject().put("code", 0).put("data", JSONObject.NULL)))
        val result = run("yeshe_checkin", JSONObject().put("lastCheckinDate", today).put("claimDueAt", now + 8 * 3_600_000L),
            JSONObject().put("blessingType", "LUCK"))
        assertEquals("success", result.result)
        assertEquals(today, result.state.getString("claimCompletedDate"))
        assertEquals(listOf(99L), calls.filter { it.first == "complete-daily-lore" }.map { it.second.getLong("userLoreId") })
        assertFalse(calls.any { it.first == "pray-taoist-blessing" })
    }

    @Test
    fun `没有结束时间仍然安排后续查询`() {
        reply("get-daily-lore", lore(end = 0))
        val result = run("yeshe_checkin")
        assertTrue(result.state.getLong("nextRunAtOverride") in (now + 1)..(now + 3_660_000))
        assertFalse(calls.any { it.first == "complete-daily-lore" })
    }

    @Test
    fun `领取失败保留领取状态并短时重试`() {
        reply("get-daily-lore", lore(finished = true, end = now - 1000))
        reply("complete-daily-lore", JSONObject().put("success", false).put("message", "操作过于频繁"))
        val result = run("yeshe_checkin", JSONObject().put("lastCheckinDate", today))
        assertEquals("failed", result.result)
        assertTrue(result.state.getLong("nextRunAtOverride") in (now + 1)..(now + 360_000))
        assertFalse(result.state.optString("claimCompletedDate") == today)
        assertEquals("测试轶闻", result.detail.getJSONObject(DAILY_LORE_DETAIL_KEY).getString("title"))
    }

    @Test
    fun `已通知的 SETTLED 行商仍要领奖再续开`() {
        val end = now + 2 * 3_600_000L
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip()))
        configureMerchantStart(end)
        val result = run("traveling_merchant", JSONObject().put("merchantLastNotifiedTripId", 42), autoMerchant())
        assertEquals("success", result.result)
        assertEquals(listOf("get-traveling-merchant", "settle-traveling-merchant", "start-traveling-merchant"),
            calls.filter { !it.first.contains("blessing") }.map { it.first })
        assertEquals(42L, result.state.getLong(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP))
        assertEquals(42L, result.state.getLong(LocalTaskStore.KEY_MERCHANT_RESTART_AFTER))
        assertEquals(end + 60_000L, result.state.getLong("nextRunAtOverride"))
        assertEquals(end, result.state.getLong("merchantEndTime"))
        assertTrue(result.message.contains("收益 +259"))
    }

    @Test
    fun `行商内层领取失败不能续开或记成已领取`() {
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip()))
        reply("settle-traveling-merchant", JSONObject().put("success", false).put("message", "尚未结算"))
        val result = run("traveling_merchant", request = autoMerchant())
        assertEquals("failed", result.result)
        assertEquals(0L, result.state.optLong(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP))
        assertFalse(calls.any { it.first == "start-traveling-merchant" })
    }

    @Test
    fun `续开失败下一轮重试但不重复领取`() {
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip()))
        configureMerchantStart()
        reply("start-traveling-merchant", JSONObject().put("success", false).put("message", "铜钱不足"),
            JSONObject().put("success", true).put("trip", trip("TRAVELING", 43, now + 3_600_000)))
        val first = run("traveling_merchant", request = autoMerchant())
        assertEquals("failed", first.result)
        assertTrue(first.message.contains("铜钱不足"))
        assertEquals(42L, first.state.optLong(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP))
        assertEquals(0L, first.state.optLong(LocalTaskStore.KEY_MERCHANT_RESTART_AFTER))
        val second = run("traveling_merchant", first.state, autoMerchant())
        assertEquals("success", second.result)
        assertEquals(1, calls.count { it.first == "settle-traveling-merchant" })
        assertEquals(2, calls.count { it.first == "start-traveling-merchant" })
    }

    @Test
    fun `在途行商不重复求签也不提前领奖`() {
        val end = now + 3_600_000L
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip("TRAVELING", end = end)))
        val result = run("traveling_merchant", request = autoMerchant("WEALTH"))
        assertEquals("success", result.result)
        assertEquals(end + 60_000L, result.state.getLong("nextRunAtOverride"))
        assertEquals(listOf("get-traveling-merchant"), calls.map { it.first })
    }

    @Test
    fun `行商预计时间已过但未生成结算时短时复查`() {
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip("TRAVELING")))
        val result = run("traveling_merchant", request = autoMerchant("WEALTH"))
        assertEquals("success", result.result)
        assertFalse(result.notify)
        assertEquals(listOf("get-traveling-merchant"), calls.map { it.first })
        assertTrue(result.state.getLong("nextRunAtOverride") in (now + 1)..(now + 120_000))
    }

    @Test
    fun `结算响应的 trip 用于实际奖励而非误报为无行商`() {
        val parsed = CloudTaskLocalRunner.parseMerchantTrip(JSONObject().put("data", JSONObject().put("trip", trip())))
        assertTrue(parsed.hasTrip)
        assertEquals(42L, parsed.tripId)
        assertEquals(379L, parsed.settlementAmount)
    }

    @Test
    fun `无车马行商的零号车马可沿用且不能换回旧车马`() {
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip().put("transportId", 0)))
        configureMerchantStart()
        val result = run("traveling_merchant", JSONObject().put("merchantLastTransportId", 9), autoMerchant())
        assertEquals("success", result.result)
        assertEquals(0L, calls.single { it.first == "start-traveling-merchant" }.second.getLong("transportId"))
    }
}
