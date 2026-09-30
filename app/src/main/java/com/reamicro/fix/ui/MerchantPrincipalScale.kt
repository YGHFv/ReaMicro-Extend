package com.reamicro.fix.ui

import kotlin.math.roundToLong

/**
 * 本金轨道：0 是独立的「沿用上次」档；轻滑到下限后，其余 95% 按有效金额线性分布。
 * 零档保留半个档位的吸附范围，避免大负重下 0 与下限挤在同一个像素里。
 * 与 Compose 分离，确保回显、拖动和更换车马采用同一套双向映射。
 */
internal class MerchantPrincipalScale(minimum: Long, capacity: Long) {
    val upper: Long = capacity.coerceAtLeast(0L)
    val lower: Long = minimum.coerceAtLeast(1L).coerceAtMost(upper)
    val enabled: Boolean get() = upper > 0L

    fun clamp(amount: Long): Long = when {
        amount <= 0L -> 0L
        !enabled -> amount // 清单尚未加载时不要丢失已保存的本金。
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
