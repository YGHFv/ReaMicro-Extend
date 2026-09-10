package com.reamicro.fix.cloud.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTaskMerchantTest {
    private val runner = CloudTaskLocalRunner

    @Test
    fun `profit text shows gain when settlement exceeds principal`() {
        assertEquals("购朝鲜马 · 收益 +259", runner.merchantProfitText("购朝鲜马", 379, 120))
    }

    @Test
    fun `profit text shows loss when settlement below principal`() {
        assertEquals("行商 · 亏损 -50", runner.merchantProfitText("", 70, 120))
    }

    @Test
    fun `parses active trip with second level end time`() {
        val body = JSONObject("""
            {"data":{"activeTrip":{"id":42,"status":"RUNNING","endTime":1000,"settlementAmount":379,"principal":120,"eventTitle":"购朝鲜马"}}}
        """.trimIndent())
        val trip = runner.parseMerchantTrip(body)
        assertTrue(trip.hasTrip)
        assertEquals(42L, trip.tripId)
        assertEquals(1_000_000L, trip.endTimeMs)
        assertEquals("购朝鲜马", trip.eventTitle)
    }

    @Test
    fun `no active trip parses as absent`() {
        val trip = runner.parseMerchantTrip(JSONObject("""{"data":{"cities":[]}}"""))
        assertFalse(trip.hasTrip)
    }

    @Test
    fun `phase is in transit before end time`() {
        val trip = CloudTaskLocalRunner.MerchantTrip(hasTrip = true, tripId = 1, status = "RUNNING", endTimeMs = 10_000L)
        assertEquals(CloudTaskLocalRunner.MerchantPhase.IN_TRANSIT, runner.merchantPhase(trip, 5_000L))
    }

    @Test
    fun `phase is arrived at or after end time`() {
        val trip = CloudTaskLocalRunner.MerchantTrip(hasTrip = true, tripId = 1, status = "RUNNING", endTimeMs = 10_000L)
        assertEquals(CloudTaskLocalRunner.MerchantPhase.ARRIVED, runner.merchantPhase(trip, 10_000L))
    }

    @Test
    fun `phase is settled when status settled`() {
        val trip = CloudTaskLocalRunner.MerchantTrip(hasTrip = true, tripId = 1, status = "SETTLED", endTimeMs = 10_000L)
        assertEquals(CloudTaskLocalRunner.MerchantPhase.SETTLED, runner.merchantPhase(trip, 20_000L))
    }
}
