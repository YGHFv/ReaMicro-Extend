package com.reamicro.fix.hook

internal object StructureToolbarLayout {
    data class Horizontal(val textStart: Int, val textWidth: Int, val actionsStart: Int)
    data class Vertical(val height: Int, val titleTop: Int, val subtitleTop: Int, val controlCenter: Int)

    fun horizontal(width: Int, leading: Int, actions: Int, edge: Int, gap: Int): Horizontal {
        val start = (edge + leading).coerceAtMost(width)
        val actionsStart = (width - edge - actions).coerceAtLeast(start)
        return Horizontal(start, (actionsStart - gap - start).coerceAtLeast(0), actionsStart)
    }

    fun vertical(
        titleHeight: Int,
        subtitleHeight: Int,
        barHeight: Int,
        preferredCenter: Int,
        bottom: Int,
    ): Vertical {
        val center = preferredCenter.coerceIn(0, barHeight)
        val titleTop = (center - titleHeight / 2).coerceAtLeast(0)
        val subtitleTop = if (subtitleHeight <= 0) 0
            else (barHeight - bottom - subtitleHeight).coerceAtLeast(0)
        return Vertical(barHeight, titleTop, subtitleTop, center)
    }
}
