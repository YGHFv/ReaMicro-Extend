package com.reamicro.fix.hook

import com.reamicro.fix.hook.discover.DiscoverLayoutArtwork

internal object BookshelfHeaderGeometry {
    const val BUTTON_SIZE = 36
    const val GAP = 8
    // MT: native TaskAlt occupies x/y 2..22 in a 24-unit vector, drawn in a 20 UDP canvas.
    private const val SELECTION_INK_SIZE = 20f * 20f / 24f
    // Filled rectangles look heavier than TaskAlt's outline at equal bounds. Keep both layout
    // glyphs slightly narrower than the circle while retaining the native 36 UDP touch target.
    private const val LAYOUT_INK_SIZE = SELECTION_INK_SIZE * 0.93f

    fun stylePadding(grid: Boolean): Float {
        val inkWidth = if (grid) DiscoverLayoutArtwork.gridInkWidth else DiscoverLayoutArtwork.listInkWidth
        val canvas = LAYOUT_INK_SIZE * DiscoverLayoutArtwork.viewport / inkWidth
        return (BUTTON_SIZE - canvas) / 2f
    }
}
