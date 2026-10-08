package com.reamicro.fix.ui.effect

internal data class AboutScrollProgress(
    val backgroundFade: Float,
    val iconFade: Float,
    val nameFade: Float,
    val versionFade: Float,
)

internal object AboutScrollMath {
    @JvmStatic
    fun calculate(
        scrollPixels: Float,
        spacerPixels: Float,
        bottomGapPixels: Float,
        versionBlockPixels: Float,
        nameBlockPixels: Float,
    ): AboutScrollProgress {
        val offset = scrollPixels.coerceAtLeast(0f)
        fun progress(value: Float, length: Float) =
            (value / length.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val stage1 = bottomGapPixels.coerceAtLeast(0f)
        val stage2 = versionBlockPixels.coerceAtLeast(1f)
        val stage3 = nameBlockPixels.coerceAtLeast(1f)
        val versionDelay = stage1 * 0.5f
        return AboutScrollProgress(
            backgroundFade = progress(offset, spacerPixels),
            versionFade = progress(offset - versionDelay, stage1 - versionDelay),
            nameFade = progress(offset - stage1, stage2),
            iconFade = progress(offset - stage1 - stage2, stage3),
        )
    }
}
