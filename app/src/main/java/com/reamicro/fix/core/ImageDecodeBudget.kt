package com.reamicro.fix.core

/** Bound decoded pixels independently of the image's compressed file size. */
object ImageDecodeBudget {
    private const val MAX_PIXELS = 8L * 1024L * 1024L
    private const val MAX_DIMENSION = 8192L

    @JvmStatic
    fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        var sample = 1
        while (true) {
            val sampledWidth = (width.toLong() + sample - 1) / sample
            val sampledHeight = (height.toLong() + sample - 1) / sample
            if (sampledWidth <= MAX_DIMENSION && sampledHeight <= MAX_DIMENSION &&
                sampledWidth * sampledHeight <= MAX_PIXELS) return sample
            sample *= 2
        }
    }
}
