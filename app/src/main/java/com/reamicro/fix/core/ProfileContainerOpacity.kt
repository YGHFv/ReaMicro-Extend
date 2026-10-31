package com.reamicro.fix.core

internal object ProfileContainerOpacity {
    fun applyToArgb(argb: Int, transparency: Int): Int {
        val alpha = (argb ushr 24) * (100 - transparency.coerceIn(0, 100)) / 100
        return (alpha shl 24) or (argb and 0x00ffffff)
    }
}
