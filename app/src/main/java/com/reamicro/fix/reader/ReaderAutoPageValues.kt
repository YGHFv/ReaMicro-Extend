package com.reamicro.fix.reader

import kotlin.math.roundToInt

/** Integer seconds/minutes on a linear drag range; ruler labels do not quantize the control. */
internal object ReaderAutoPageValues {
    const val MIN_INTERVAL_SECONDS = 5
    const val MAX_INTERVAL_SECONDS = 60
    const val MAX_TIMER_MINUTES = 180
    const val DEFAULT_INTERVAL_SECONDS = 10

    fun interval(value: Float): Int = if (value.isFinite()) {
        value.coerceIn(MIN_INTERVAL_SECONDS.toFloat(), MAX_INTERVAL_SECONDS.toFloat()).roundToInt()
    } else DEFAULT_INTERVAL_SECONDS

    fun minutes(value: Float): Int = if (value.isFinite()) {
        value.coerceIn(0f, MAX_TIMER_MINUTES.toFloat()).roundToInt()
    } else 0

    fun legacyHours(value: Float): Int = minutes(value * 60f)

    fun durationLabel(value: Int): String {
        val minutes = value.coerceIn(0, MAX_TIMER_MINUTES)
        val hours = minutes / 60
        val remaining = minutes % 60
        return when {
            minutes == 0 -> "不限时"
            hours == 0 -> "${remaining}分钟"
            remaining == 0 -> "${hours}小时"
            else -> "${hours}小时${remaining}分"
        }
    }

    fun tick(value: Float): String = ((value / 5f).roundToInt() * 5).toString()
}
