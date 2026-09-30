package com.reamicro.fix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantPrincipalScaleTest {
    @Test
    fun `zero remains selectable after dragging to the maximum`() {
        for (capacity in listOf(50L, 1000L, 1000000L)) {
            val scale = MerchantPrincipalScale(10L, capacity)
            assertEquals(capacity, scale.amountFor(1f))
            assertEquals(0L, scale.amountFor(0f))
            assertEquals(0L, scale.amountFor(0.02f))
            assertEquals(0f, scale.fractionFor(0L), 0f)
        }
    }

    @Test
    fun `a short drag after zero selects the actual minimum`() {
        for ((minimum, capacity) in listOf(10L to 50L, 10L to 1000000L, 200L to 900000L)) {
            val scale = MerchantPrincipalScale(minimum, capacity)
            assertEquals(minimum, scale.amountFor(0.03f))
            assertEquals(minimum, scale.amountFor(0.05f))
            assertEquals(0.05f, scale.fractionFor(minimum), 0.000001f)
            assertEquals(capacity, scale.amountFor(1f))
            assertEquals(1f, scale.fractionFor(capacity), 0f)
        }
    }

    @Test
    fun `valid amounts occupy equal distances between minimum and maximum`() {
        val scale = MerchantPrincipalScale(10L, 2010L)
        val fractions = listOf(10L, 510L, 1010L, 1510L, 2010L).map(scale::fractionFor)
        fractions.zipWithNext().forEach { (a, b) ->
            assertEquals(0.95f / 4f, b - a, 0.000001f)
        }
        assertEquals(1010L, scale.amountFor(0.525f))
        for (amount in 10L..2010L) {
            assertEquals(amount, scale.amountFor(scale.fractionFor(amount)))
        }
    }

    @Test
    fun `switching transport clamps only positive amounts and preserves zero`() {
        val scale = MerchantPrincipalScale(10L, 50L)
        assertEquals(0L, scale.clamp(0L))
        assertEquals(0L, scale.clamp(-10L))
        assertEquals(10L, scale.clamp(5L))
        assertEquals(50L, scale.clamp(1000L))
        assertEquals(30L, scale.clamp(30L))
    }

    @Test
    fun `unloaded capacity does not discard saved capital`() {
        for (capacity in listOf(0L, -1L)) {
            val scale = MerchantPrincipalScale(10L, capacity)
            assertFalse(scale.enabled)
            assertEquals(300L, scale.clamp(300L))
            assertEquals(0L, scale.amountFor(1f))
            assertEquals(0f, scale.fractionFor(300L), 0f)
        }
    }

    @Test
    fun `a single valid amount still has both zero and a positive endpoint`() {
        for (capacity in listOf(5L, 10L)) {
            val scale = MerchantPrincipalScale(10L, capacity)
            assertEquals(0L, scale.amountFor(0f))
            assertEquals(capacity, scale.amountFor(0.03f))
            assertEquals(capacity, scale.amountFor(1f))
            assertEquals(1f, scale.fractionFor(capacity), 0f)
        }
    }

    @Test
    fun `mapping is monotonic bounded and handles invalid fractions`() {
        for (capacity in listOf(11L, 50L, 1000L, 1000000L, Long.MAX_VALUE)) {
            val scale = MerchantPrincipalScale(10L, capacity)
            var previous = 0L
            for (step in 0..1000) {
                val amount = scale.amountFor(step / 1000f)
                assertTrue(amount >= previous)
                assertTrue(amount == 0L || amount in 10L..capacity)
                previous = amount
            }
            assertEquals(0L, scale.amountFor(Float.NaN))
            assertEquals(0L, scale.amountFor(Float.NEGATIVE_INFINITY))
            assertEquals(capacity, scale.amountFor(Float.POSITIVE_INFINITY))
        }
    }
}
