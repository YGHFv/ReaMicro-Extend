package com.reamicro.fix.ui

import kotlin.math.roundToLong

internal class MerchantPrincipalScale(minimum: Long, capacity: Long) {
    val upper: Long = capacity.coerceAtLeast(0L)
    val lower: Long = minimum.coerceAtLeast(1L).coerceAtMost(upper)
    val enabled: Boolean get() = upper > 0L

    fun clamp(amount: Long): Long = when {
        amount <= 0L -> 0L
        !enabled -> amount
        else -> amount.coerceIn(lower, upper)
    }

    fun fractionFor(amount: Long): Float {
        if (!enabled || amount <= 0L) return 0f
        val value = clamp(amount)
        if (value >= upper) return 1f
        return (MINIMUM_FRACTION +
            (1.0 - MINIMUM_FRACTION) * (value - lower).toDouble() / (upper - lower))
            .toFloat().coerceIn(MINIMUM_FRACTION, 1f)
    }

    fun amountFor(fraction: Float): Long {
        if (!enabled || fraction.isNaN()) return 0L
        val position = fraction.coerceIn(0f, 1f)
        return when {
            position <= MINIMUM_FRACTION / 2f -> 0L
            position >= 1f -> upper
            position <= MINIMUM_FRACTION || lower == upper -> lower
            else -> {
                val progress = (position.toDouble() - MINIMUM_FRACTION) / (1.0 - MINIMUM_FRACTION)
                (lower.toDouble() + progress * (upper - lower).toDouble())
                    .roundToLong().coerceIn(lower, upper)
            }
        }
    }

    companion object {
        internal const val MINIMUM_FRACTION = 0.05f
    }
}
