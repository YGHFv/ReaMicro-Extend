package com.reamicro.fix.hook

import kotlin.math.min

internal class ReaderSearchBarPlacement {
    data class Point(val x: Float, val y: Float)
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private var horizontal = .5f
    private var vertical = 1f

    fun position(bounds: Bounds) = Point(
        bounds.left + (bounds.right - bounds.left) * horizontal,
        bounds.top + (bounds.bottom - bounds.top) * vertical,
    )

    fun moveBy(dx: Float, dy: Float, bounds: Bounds): Point {
        val previous = position(bounds)
        if (dx.isFinite() && bounds.right > bounds.left) {
            horizontal = ((previous.x + dx).coerceIn(bounds.left, bounds.right) - bounds.left) /
                (bounds.right - bounds.left)
        }
        if (dy.isFinite() && bounds.bottom > bounds.top) {
            vertical = ((previous.y + dy).coerceIn(bounds.top, bounds.bottom) - bounds.top) /
                (bounds.bottom - bounds.top)
        }
        return position(bounds)
    }

    companion object {
        fun bounds(
            width: Int, height: Int, barWidth: Int, barHeight: Int,
            leftInset: Int, topInset: Int, rightInset: Int, bottomInset: Int, margin: Float,
        ): Bounds {
            fun axis(extent: Int, bar: Int, startInset: Int, endInset: Int): Pair<Float, Float> {
                val size = extent.coerceAtLeast(0).toFloat()
                val start = startInset.toFloat().coerceIn(0f, size)
                val end = (size - endInset.coerceAtLeast(0)).coerceIn(start, size)

                val free = (end - start - bar.coerceAtLeast(0)).coerceAtLeast(0f)
                val padding = min(margin.coerceAtLeast(0f), free / 2f)
                return (start + padding) to (start + free - padding)
            }
            val (left, right) = axis(width, barWidth, leftInset, rightInset)
            val (top, bottom) = axis(height, barHeight, topInset, bottomInset)
            return Bounds(left, top, right, bottom)
        }
    }
}
