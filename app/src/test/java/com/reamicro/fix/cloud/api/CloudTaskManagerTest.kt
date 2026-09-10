package com.reamicro.fix.cloud.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTaskManagerTest {
    @Test
    fun `draw task uses reward event instead of daily time`() {
        val schedule = cloudAutomationSchedule("yeshe_draw_card", "00:05")

        assertEquals("yeshe_checkin_reward_claimed", schedule.getString("event"))
        assertEquals(false, schedule.has("timeOfDay"))
    }

    @Test
    fun `parses safe automation configuration`() {
        val task = parseCloudTask(JSONObject("""
            {
              "data": {
                "id": "task_1",
                "taskType": "cloud_auto_read",
                "credentialId": "rea_1",
                "status": "scheduled",
                "enabled": true,
                "schedule": {"timeOfDay": "07:05"},
                "configuration": {
                  "durationMinutes": 45,
                  "dailyLimit": 0,
                  "books": [{"bookId": 7, "name": "测试图书"}]
                },
                "nextRunAt": 1234,
                "lastMessage": "执行成功"
              }
            }
        """.trimIndent()))

        assertEquals("07:05", task.timeOfDay)
        assertEquals(45, task.durationMinutes)
        assertEquals(0, task.dailyDrawLimit)
        assertEquals(listOf(CloudTaskBook(7, "测试图书")), task.books)
        assertEquals(true, task.enabled)
    }

    @Test
    fun `parses legacy cloud book id as configured user book id`() {
        val task = parseCloudTask(JSONObject("""
            {
              "taskType": "cloud_auto_read",
              "configuration": {
                "books": [{"cloudBookId": 7, "name": "旧版配置"}]
              }
            }
        """.trimIndent()))

        assertEquals(listOf(CloudTaskBook(7, "旧版配置")), task.books)
    }

    @Test
    fun `traveling merchant schedule uses fixed interval`() {
        val schedule = cloudAutomationSchedule("traveling_merchant", "00:05")
        assertEquals(TRAVELING_MERCHANT_POLL_SECONDS, schedule.getLong("intervalSeconds"))
        assertEquals(false, schedule.has("timeOfDay"))
    }

    @Test
    fun `traveling merchant runs on device`() {
        assertEquals("device", cloudTaskExecutionMode("traveling_merchant"))
    }

    @Test
    fun `parses traveling merchant configuration`() {
        val task = parseCloudTask(JSONObject("""
            {
              "data": {
                "id": "task_m",
                "taskType": "traveling_merchant",
                "configuration": {
                  "merchantAutoComplete": true,
                  "merchantCityCode": "PENGLAI",
                  "merchantPrincipal": 120,
                  "merchantTransportId": 5
                }
              }
            }
        """.trimIndent()))
        assertEquals(true, task.merchantAutoComplete)
        assertEquals("PENGLAI", task.merchantCityCode)
        assertEquals(120L, task.merchantPrincipal)
        assertEquals(5L, task.merchantTransportId)
    }

    @Test
    fun `uses safe defaults when old server omits configuration`() {
        val task = parseCloudTask(JSONObject("""
            {
              "id": "task_2",
              "taskType": "yeshe_checkin",
              "credentialId": "rea_1",
              "schedule": {"timeOfDay": "00:05"}
            }
        """.trimIndent()))

        assertEquals("00:05", task.timeOfDay)
        assertEquals(30, task.durationMinutes)
        assertEquals(3, task.dailyDrawLimit)
        assertEquals(emptyList<CloudTaskBook>(), task.books)
    }
}
