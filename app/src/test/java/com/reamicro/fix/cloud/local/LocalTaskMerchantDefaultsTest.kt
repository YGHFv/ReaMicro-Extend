package com.reamicro.fix.cloud.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动开新行商时用户留空则沿用上次行商配置」的行为。
 *
 * 宿主（阅微）只在 ViewModel 内存里保存用户选中的城池/车马/本金，重启即丢；所以模块必须
 * 自己把这趟行商实际使用的参数记下来，供下次留空时沿用。
 */
class LocalTaskMerchantDefaultsTest {
    private val runner = CloudTaskLocalRunner

    private fun remembered(
        city: String = "",
        transport: Long = 0L,
        principal: Long = 0L,
    ) = CloudTaskLocalRunner.MerchantConfig(city, transport, principal)

    @Test
    fun `explicit config wins over remembered`() {
        val request = JSONObject()
            .put("merchantCityCode", "PENGLAI")
            .put("merchantTransportId", 7L)
            .put("merchantPrincipal", 500L)
        val resolved = runner.resolveStartConfig(request, remembered("CHANGAN", 3L, 120L))
        assertEquals("PENGLAI", resolved.cityCode)
        assertEquals(7L, resolved.transportId)
        assertEquals(500L, resolved.principal)
    }

    @Test
    fun `blank config falls back to last trip`() {
        // 用户什么都没填：三项都应沿用上次行商配置。
        val resolved = runner.resolveStartConfig(JSONObject(), remembered("CHANGAN", 3L, 120L))
        assertEquals("CHANGAN", resolved.cityCode)
        assertEquals(3L, resolved.transportId)
        assertEquals(120L, resolved.principal)
        assertTrue(resolved.isComplete)
    }

    @Test
    fun `each field falls back independently`() {
        // 只填了本金：城池与车马沿用上次，本金用用户填的。
        val request = JSONObject().put("merchantPrincipal", 999L)
        val resolved = runner.resolveStartConfig(request, remembered("CHANGAN", 3L, 120L))
        assertEquals("CHANGAN", resolved.cityCode)
        assertEquals(3L, resolved.transportId)
        assertEquals(999L, resolved.principal)
    }

    @Test
    fun `blank city string does not shadow remembered value`() {
        val request = JSONObject().put("merchantCityCode", "   ")
        val resolved = runner.resolveStartConfig(request, remembered("CHANGAN", 3L, 120L))
        assertEquals("CHANGAN", resolved.cityCode)
    }

    @Test
    fun `nothing configured is incomplete`() {
        val resolved = runner.resolveStartConfig(JSONObject(), remembered())
        assertFalse(resolved.isComplete)
    }

    @Test
    fun `parses trip city transport and principal for remembering`() {
        val body = JSONObject(
            """
            {"data":{"activeTrip":{"id":42,"status":"RUNNING","endTime":1000,"settlementAmount":379,
             "principal":120,"eventTitle":"购朝鲜马","cityCode":"PENGLAI","transportId":9}}}
            """.trimIndent(),
        )
        val trip = runner.parseMerchantTrip(body)
        assertEquals("PENGLAI", trip.cityCode)
        assertEquals(9L, trip.transportId)
        assertEquals(120L, trip.principal)
    }
}
