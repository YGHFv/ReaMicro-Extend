package com.reamicro.fix.hook

import kotlin.math.floor

internal data class BookImageDialogLayout(
    val inset: Float,
    val scrollAll: Boolean,
    val buttonColumns: Int,
    val previewHeight: Float,
    val promptLines: Int,
) {
    companion object {
        fun resolve(width: Float, height: Float, scale: Float, fontScale: Float,
            actionCount: Int, measuredButtonWidth: Float = 0f, measuredButtonHeight: Float = 48f): BookImageDialogLayout {
            val s = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
            val f = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
            val w = width.coerceAtLeast(0f)
            val h = height.coerceAtLeast(0f)
            val inset = minOf(if (w < 360f * s) 12f * s else 20f * s, w / 10f)
            val gap = 8f * s
            val minButton = maxOf(88f * s, 64f * s * f, measuredButtonWidth)
            val columns = floor((w - 2f * inset + gap) / (minButton + gap)).toInt()
                .coerceIn(1, actionCount.coerceAtLeast(1))

            val rows = (actionCount.coerceAtLeast(1) + columns - 1) / columns
            val footer = rows * maxOf(48f, measuredButtonHeight) + (rows - 1) * gap + 48f + 36f * s
            val scrollAll = h < 480f * s || f >= 1.5f || footer + 40f * s > h * .55f
            return BookImageDialogLayout(inset, scrollAll, columns,
                minOf(if (scrollAll) 144f * s else 224f * s, h * .32f).coerceAtLeast(0f),
                if (scrollAll) 3 else 5)
        }
    }
}
