package com.reamicro.fix.reader

/** Map the host's speech-speed ruler linearly to the control's own units. */
internal class ReaderRulerRange(private val min: Float, private val max: Float) {
    init { require(min.isFinite() && max.isFinite() && max > min) }
    fun native(value: Float): Float = .5f + ((value - min) / (max - min)).coerceIn(0f, 1f) * 1.5f
    fun actual(value: Float): Float = min + ((value - .5f) / 1.5f).coerceIn(0f, 1f) * (max - min)
}
