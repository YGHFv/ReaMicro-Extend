package com.reamicro.fix.hook

import kotlin.math.roundToInt

internal object ReaderPageSpeed {
    const val DEFAULT = 1f
    const val MIN = 0.5f
    const val MAX = 2f
    val names = listOf("平移", "覆盖", "无", "滚动", "仿真")
    fun adjustable(mode: Int): Boolean = mode == 0 || mode == 1 || mode == 4
    fun key(mode: Int): String {
        require(mode in names.indices)
        return "speed_mode_$mode"
    }
    fun normalize(value: Float): Float =
        if (!value.isFinite()) DEFAULT else (value.coerceIn(MIN, MAX) * 10).roundToInt() / 10f
    fun duration(baselineMs: Int, multiplier: Float): Int =
        (baselineMs / normalize(multiplier)).roundToInt().coerceAtLeast(1)
}
