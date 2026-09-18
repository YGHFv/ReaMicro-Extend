package com.reamicro.fix.cloud.local

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
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

    @Test
    fun `阅读每日上限可以小于单本配置时长`() {
        reply("update-read-time-by-date", JSONObject())
        val request = JSONObject().put("books", JSONArray().put(JSONObject().put("bookId", 1)))
            .put("durationMinutes", 30).put("dailyLimitMinutes", 10)
        val result = run("cloud_auto_read", request = request)
        assertEquals("success", result.result)
        assertEquals(10, result.state.getInt("dailyReadMinutes"))
        assertEquals(600, calls.single().second.getJSONArray("list").getJSONObject(0).getInt("duration"))
    }

    @Test
    fun `中断后不再发起下一次祈愿且保留进度`() {
        reply("wish", JSONObject().put("success", true).put("props", JSONArray()))
        try {
            val result = CloudTaskLocalRunner.runTask("yeshe_draw_card", JSONObject(), JSONObject().put("dailyLimit", 3), credential) {
                Thread.currentThread().interrupt()
            }
            assertEquals("failed", result.result)
            assertEquals(1, result.state.getInt("dailyCounter"))
            assertEquals(1, calls.size)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `典当逐次落盘并保留中途失败之前的铜钱`() {
        reply("get-pawn-count", JSONObject().put("remaining", 2).put("usedToday", 1).put("specialPropId", 999))
        reply("get-user-materials", JSONObject().put("materials", JSONArray().put(JSONObject()
            .put("propId", 999).put("userPropId", 42).put("quantity", 2))))
        reply("pawn", JSONObject().put("success", true).put("coin", 8), JSONObject().put("success", false))
        val checkpoints = mutableListOf<JSONObject>()
        val result = CloudTaskLocalRunner.runTask("pawn", JSONObject(), JSONObject(), credential) {
            checkpoints += JSONObject(it.toString())
        }
        assertEquals("failed", result.result)
        assertEquals(2, checkpoints.single().getInt("pawnUsedToday"))
        assertEquals(8L, checkpoints.single().getLong("pawnLastCoin"))
    }

    @Test
    fun `两次祈愿使用两次单抽而不是不支持的二连抽`() {
        reply("wish", JSONObject().put("success", true).put("props", JSONArray()))
        val result = run("yeshe_draw_card", request = JSONObject().put("dailyLimit", 2))
        assertEquals("success", result.result)
        assertEquals(listOf(1, 1), calls.map { it.second.getInt("count") })
        assertEquals(2, result.state.getInt("dailyCounter"))
    }

    @Test
    fun `祈愿中途失败保留已消费次数和检查点`() {
        reply("wish", JSONObject().put("success", true).put("props", JSONArray()),
            JSONObject().put("success", false).put("message", "操作频繁"))
        val checkpoints = mutableListOf<Int>()
        val result = CloudTaskLocalRunner.runTask("yeshe_draw_card", JSONObject(), JSONObject().put("dailyLimit", 3), credential) {
            checkpoints += it.getInt("dailyCounter")
        }
        assertEquals("failed", result.result)
        assertEquals(1, result.state.getInt("dailyCounter"))
        assertEquals(listOf(1), checkpoints)
    }

    @Test
    fun `彩筹不足停止消费但不是虚报一次成功抽卡`() {
        reply("wish", JSONObject().put("success", false).put("message", "彩筹不足"))
        val result = run("yeshe_draw_card")
        assertEquals("success", result.result)
        assertEquals(0, result.state.getInt("dailyCounter"))
        assertEquals("彩筹已用完", result.message)
    }

    @Test
    fun `多本阅读按真实总分钟记账并遵守上限`() {
        reply("update-read-time-by-date", JSONObject())
        val books = JSONArray().put(JSONObject().put("bookId", 1)).put(JSONObject().put("bookId", 2)).put(JSONObject().put("bookId", 3))
        val result = run("cloud_auto_read", request = JSONObject().put("books", books).put("durationMinutes", 30).put("dailyLimitMinutes", 50))
        assertEquals("success", result.result)
        assertEquals(50, result.state.getInt("dailyReadMinutes"))
        assertEquals(listOf(1800, 1200), calls.map { it.second.getJSONArray("list").getJSONObject(0).getInt("duration") })
    }

    @Test
    fun `最近阅读缺少用户书籍ID时不能改用公共云书ID`() {
        reply("get-read-record-list", JSONObject().put("list", JSONArray().put(JSONObject().put("cloudBookId", 99))))
        val result = run("cloud_auto_read")
        assertEquals("failed", result.result)
        assertEquals(listOf("get-read-record-list"), calls.map { it.first })
    }

    @Test
    fun `多本阅读部分失败保留已上报分钟和轮转位置`() {
        reply("update-read-time-by-date", JSONObject(), JSONObject().put("success", false).put("message", "操作频繁"))
        val books = JSONArray().put(JSONObject().put("bookId", 1)).put(JSONObject().put("bookId", 2))
        val result = run("cloud_auto_read", request = JSONObject().put("books", books).put("durationMinutes", 30))
        assertEquals("failed", result.result)
        assertEquals(30, result.state.getInt("dailyReadMinutes"))
        assertEquals(1, result.state.getInt("bookRotation"))
    }

    @Test
    fun `典当内层失败不能记为领取铜钱成功`() {
        reply("get-pawn-count", JSONObject().put("remaining", 2).put("specialPropId", 999).put("specialPropName", "测试期物"))
        reply("get-user-materials", JSONObject().put("materials", JSONArray().put(
            JSONObject().put("propId", 999).put("userPropId", 42).put("quantity", 2))))
        reply("pawn", JSONObject().put("success", true).put("coin", 8), JSONObject().put("success", false).put("message", "频繁"))
        val result = run("pawn")
        assertEquals("failed", result.result)
        assertEquals(1, result.state.getInt("pawnUsedToday"))
        assertEquals(8L, result.state.getLong("pawnLastCoin"))
    }

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

    @Test
    fun `轶闻把结构化奖励写进正文并落进详情`() {
        reply("get-daily-lore", lore(finished = true, claimed = true)
            .put("gem", 3).put("propName", "端砚").put("propQuality", "BLUE"))
        val result = run("yeshe_checkin")
        assertEquals("success", result.result)
        // 通知与任务记录都不点开就能看到领到了什么，且顺序按品质优先。
        assertTrue(result.message.contains("（端砚 x1、彩筹 x3、阅历 x5）"))
        val items = result.detail.getJSONArray(CloudTaskLocalRunner.KEY_REWARD_ITEMS)
        assertEquals(3, items.length())
        // 详情里的数组保持游戏侧顺序（阅历/彩筹/期物），只有通知摘要按品质重排。
        assertEquals("阅历", items.getJSONObject(0).getString("name"))
        assertEquals("", items.getJSONObject(0).getString("quality"))
        assertEquals("端砚", items.getJSONObject(2).getString("name"))
        assertEquals("BLUE", items.getJSONObject(2).getString("quality"))
    }

    @Test
    fun `新开行商的消息带结束时间和运签效果`() {
        val end = now + 6 * 3_600_000L
        reply("get-traveling-merchant", JSONObject().put("activeTrip", trip()))
        configureMerchantStart(end)
        reply("get-taoist-blessing", JSONObject().put("blessing", JSONObject()
            .put("blessingType", "WEALTH").put("name", "小利签").put("description", "商事盈利收益率提升 10%")))
        val result = run("traveling_merchant", request = autoMerchant("WEALTH"))
        assertEquals("success", result.result)
        assertTrue(result.message.contains("已开启新行商"))
        assertTrue(result.message.contains("结束时间"))
        assertTrue(result.message.contains("运签效果：商事盈利收益率提升 10%"))
        assertEquals("商事盈利收益率提升 10%", result.detail.getString("新行商运签效果"))
        assertTrue(result.detail.getString("新行商结束时间").isNotBlank())
    }

    @Test
    fun `禁当清单可配置且清空后照常典当`() {
        reply("get-pawn-count", JSONObject().put("remaining", 1).put("specialPropId", 12).put("specialPropName", "剡藤"))
        reply("get-user-materials", JSONObject().put("materials", JSONArray().put(JSONObject()
            .put("propId", 12).put("userPropId", 42).put("quantity", 1))))
        reply("pawn", JSONObject().put("success", true).put("coin", 5))
        // 没带清单 = 旧配置，沿用默认禁当清单：剡藤要留着祈禳，这次不发典当请求。
        val skipped = run("pawn")
        assertEquals("success", skipped.result)
        assertTrue(skipped.message.contains("跳过典当"))
        assertFalse(calls.any { it.first == "pawn" })
        calls.clear()
        // 显式清空 = 用户明确要求不禁止任何期物，照常典当。
        val pawned = run("pawn", request = JSONObject().put("forbiddenPawnPropIds", JSONArray()))
        assertEquals("success", pawned.result)
        assertTrue(pawned.message.contains("获得铜钱 5 文"))
        assertEquals(1, calls.count { it.first == "pawn" })
    }

    @Test
    fun `典当把当日期物与背包物品记进图鉴`() {
        reply("get-pawn-count", JSONObject().put("remaining", 1).put("specialPropId", 21)
            .put("specialPropName", "青玉").put("specialPropQuality", "BLUE"))
        reply("get-user-materials", JSONObject().put("materials", JSONArray()
            // 字段名照抄宿主 MaterialItem：name/quality，不是 propName/propQuality。
            .put(JSONObject().put("propId", 21).put("name", "青玉").put("quality", "BLUE")
                .put("userPropId", 42).put("quantity", 1))
            // 没名字的条目（别的材料）不该混进期物清单。
            .put(JSONObject().put("propId", 31).put("quantity", 3))))
        reply("pawn", JSONObject().put("success", true).put("coin", 5))
        val result = run("pawn", request = JSONObject().put("forbiddenPawnPropIds", JSONArray()))
        assertEquals("success", result.result)
        val catalog = CloudTaskLocalRunner.pawnPropCatalog(result.state)
        assertEquals("青玉", catalog.getJSONObject("21").getString("name"))
        assertEquals("BLUE", catalog.getJSONObject("21").getString("quality"))
        assertEquals(1, catalog.length())
    }

    @Test
    fun `主动刷新期物图鉴能一次拿到当日期物与背包全部物品`() {
        // 配置页点「刷新期物清单」走的就是这条路径：不必等任务跑过一遍。
        reply("get-pawn-count", JSONObject().put("remaining", 1).put("specialPropId", 12)
            .put("specialPropName", "剡藤").put("specialPropQuality", "GREEN"))
        reply("get-user-materials", JSONObject().put("materials", JSONArray()
            .put(JSONObject().put("propId", 21).put("name", "青玉").put("quality", "BLUE").put("quantity", 2))
            .put(JSONObject().put("propId", 22).put("name", "残卷").put("quality", "RED").put("quantity", 1))))
        val fetched = CloudTaskLocalRunner.fetchPawnPropCatalog("test-token", credential.getString("baseUrl"))
        assertEquals(null, fetched.error)
        assertEquals("剡藤", fetched.catalog.getJSONObject("12").getString("name"))
        assertEquals("青玉", fetched.catalog.getJSONObject("21").getString("name"))
        assertEquals("BLUE", fetched.catalog.getJSONObject("21").getString("quality"))
        assertEquals("RED", fetched.catalog.getJSONObject("22").getString("quality"))
        assertEquals(3, fetched.catalog.length())
    }

    @Test
    fun `主动刷新期物图鉴时单个接口失败仍返回另一半并说明原因`() {
        // 只配背包不配当日期物接口：背包那半必须照常返回，失败原因走 error 交给界面提示。
        reply("get-user-materials", JSONObject().put("materials", JSONArray()
            .put(JSONObject().put("propId", 21).put("name", "青玉").put("quality", "BLUE").put("quantity", 2))))
        val fetched = CloudTaskLocalRunner.fetchPawnPropCatalog("test-token", credential.getString("baseUrl"))
        assertEquals("青玉", fetched.catalog.getJSONObject("21").getString("name"))
        assertTrue(fetched.error.orEmpty().contains("当日期物"))
    }

    @Test
    fun `被禁当而跳过的期物同样进图鉴`() {
        // 今天的期物是剡藤：默认清单会跳过典当，但名字与品质只有这次能拿到，必须记下来。
        reply("get-pawn-count", JSONObject().put("remaining", 2).put("specialPropId", 12)
            .put("specialPropName", "剡藤").put("specialPropQuality", "PURPLE"))
        val result = run("pawn")
        assertEquals("success", result.result)
        assertTrue(result.message.contains("跳过典当"))
        assertFalse(calls.any { it.first == "pawn" })
        assertEquals("剡藤", CloudTaskLocalRunner.pawnPropCatalog(result.state).getJSONObject("12").getString("name"))
    }
}
