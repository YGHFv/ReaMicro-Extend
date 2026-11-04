package com.reamicro.fix.reader

object ReaderColorPacking {
    fun srgb(argb: Int): Long = (argb.toLong() and 0xFFFFFFFFL) shl 32
}
