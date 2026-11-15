package com.reamicro.fix.cloud.local

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class OptionalPrayerTest {
    @Test fun merchantContinuesAfterMaterialOrOtherPrayerRefusal() {
        for (reason in listOf("祈福材料不足", "今日祈禳次数已用完")) {
            withServer { server ->
                server.prayerResponse = JSONObject().put("success", false).put("message", reason).toString()
                val outcome = server.runMerchant()
                assertEquals("success", outcome.result)
                assertTrue(outcome.message.contains(reason))
                assertTrue(outcome.message.contains("本次跳过祈禳"))
                assertTrue(outcome.message.contains("已开启新行商"))
                assertEquals(listOf("get-traveling-merchant", "get-taoist-blessing", "pray-taoist-blessing",
                    "start-traveling-merchant"), server.calls)
            }
        }
    }

    @Test fun malformedPrayerResponseDoesNotBlockDeparture() {
        withServer { server ->
            server.prayerResponse = "invalid-json"
            val outcome = server.runMerchant()
            assertEquals("success", outcome.result)
            assertTrue(outcome.message.contains("本次跳过祈禳"))
            assertEquals(1, server.calls.count { it == "pray-taoist-blessing" })
            assertEquals(1, server.calls.count { it == "start-traveling-merchant" })
        }
    }

    @Test fun dailyLoreContinuesAfterPrayerFailure() {
        withServer { server ->
            server.prayerResponse = JSONObject().put("code", 400).put("message", "祈福材料不足").toString()
            val outcome = CloudTaskLocalRunner.runTask("yeshe_checkin", JSONObject(),
                JSONObject().put("blessingType", "LUCK"), server.credential)
            assertEquals("success", outcome.result)
            assertTrue(outcome.message.contains("签到完成"))
            assertTrue(outcome.detail.optString("本次祈禳").contains("本次跳过祈禳"))
            assertEquals(listOf("get-taoist-blessing", "pray-taoist-blessing", "get-daily-lore"), server.calls)
        }
    }

    @Test fun failedDepartureRetryDoesNotRepeatSkippedPrayer() {
        withServer { server ->
            server.startRejected = true
            val first = server.runMerchant()
            assertEquals("failed", first.result)
            val departure = first.state.getJSONObject(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE)
            assertEquals("skipped", departure.getString("prayer"))
            server.startRejected = false
            val second = server.runMerchant(first.state)
            assertEquals("success", second.result)
            assertEquals(1, server.calls.count { it == "pray-taoist-blessing" })
            assertEquals(1, server.calls.count { it == "get-taoist-blessing" })
            assertEquals(2, server.calls.count { it == "start-traveling-merchant" })
        }
    }

    @Test fun activeBlessingIsReusedWithoutPrayer() {
        withServer { server ->
            server.activeBlessing = JSONObject().put("blessingType", "SAFETY").put("name", "已有运签")
            val outcome = server.runMerchant()
            assertEquals("success", outcome.result)
            assertTrue(outcome.message.contains("沿用已有"))
            assertFalse(outcome.message.contains("本次跳过祈禳"))
            assertFalse(server.calls.contains("pray-taoist-blessing"))
        }
    }

    @Test fun oldMaterialSkipCheckpointContinuesWithoutPrayer() {
        withServer { server ->
            val state = JSONObject()
            val departure = MerchantDeparture(state, 0L,
                CloudTaskLocalRunner.MerchantConfig("test-city", 0L, 30L), "SAFETY") {}
            departure.skipPrayer("祈福材料不足")
            state.getJSONObject(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE)
                .put("prayer", "materials_unavailable").remove("prayerSkipReason")
            val outcome = server.runMerchant(state)
            assertEquals("success", outcome.result)
            assertTrue(outcome.message.contains("祈福材料不足"))
            assertFalse(server.calls.contains("get-taoist-blessing"))
            assertFalse(server.calls.contains("pray-taoist-blessing"))
        }
    }

    private fun withServer(test: (Server) -> Unit) {
        val server = Server()
        try { test(server) } finally { server.http.stop(0) }
    }

    private class Server {
        val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = mutableListOf<String>()
        var prayerResponse = JSONObject().put("success", false).put("message", "祈福材料不足").toString()
        var startRejected = false
        var activeBlessing: JSONObject? = null
        val credential: JSONObject get() = JSONObject()
            .put("baseUrl", "http://127.0.0.1:${http.address.port}")
            .put("token", "local-test-token")

        init {
            http.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val endpoint = exchange.requestURI.path.substringAfterLast('/')
                calls += endpoint
                val response = when (endpoint) {
                    "get-traveling-merchant" -> JSONObject().put("activeTrip", JSONObject.NULL).toString()
                    "get-taoist-blessing" -> JSONObject().put("blessing", activeBlessing ?: JSONObject.NULL).toString()
                    "pray-taoist-blessing" -> prayerResponse
                    "start-traveling-merchant" -> if (startRejected) {
                        JSONObject().put("success", false).put("message", "暂时无法出发").toString()
                    } else {
                        JSONObject().put("activeTrip", JSONObject().put("id", 101L).put("status", "TRAVELING")
                            .put("cityCode", "test-city").put("transportId", 0L).put("principal", 30L)
                            .put("endTime", System.currentTimeMillis() / 1000L + 3600L)).toString()
                    }
                    "get-daily-lore" -> JSONObject().put("id", 77L).put("claimed", true).put("title", "本地测试轶闻").toString()
                    else -> error("未预期的接口：$endpoint")
                }
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            http.start()
        }

        fun runMerchant(state: JSONObject = JSONObject()): CloudTaskLocalRunner.Outcome =
            CloudTaskLocalRunner.runTask("traveling_merchant", state,
                JSONObject().put("merchantAutoComplete", true).put("blessingType", "SAFETY")
                    .put("merchantCityCode", "test-city").put("merchantTransportId", 0L).put("merchantPrincipal", 30L),
                credential)
    }
}
