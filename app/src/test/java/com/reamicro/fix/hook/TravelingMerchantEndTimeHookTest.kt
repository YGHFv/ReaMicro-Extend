package com.reamicro.fix.hook

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TravelingMerchantEndTimeHookTest {
    @Test
    fun formatsReturnedEndTimeInDeviceTimeZone() {
        assertEquals(
            "2026-08-28 19:43:19",
            formatTravelingMerchantEndTime(
                epochSeconds = 1_787_917_399L,
                zoneId = ZoneId.of("Asia/Shanghai"),
            ),
        )
    }

    @Test
    fun preservesReturnedSeconds() {
        assertEquals(
            "1970-01-01 00:00:01",
            formatTravelingMerchantEndTime(
                epochSeconds = 1L,
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }
}
