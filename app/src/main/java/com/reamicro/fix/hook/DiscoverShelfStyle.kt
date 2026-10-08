package com.reamicro.fix.hook

internal object DiscoverShelfStyle {
    const val LIST_COVER_WIDTH = 64
    const val LIST_COVER_HEIGHT = 86.4
    const val COVER_RATIO = 1f / 1.35f
    const val GRID_GAP = 18
    const val GROUP_HORIZONTAL_PADDING = 8
    const val GROUP_VERTICAL_PADDING = 12
}

internal data class DiscoverTextSpec(val maxLines: Int, val minLines: Int, val softWrap: Boolean) {
    companion object {
        const val DEFAULT_MASK = 69626
        const val ELLIPSIS = 2
        fun resolve(singleLine: Boolean, maxLines: Int, reserveLines: Boolean): DiscoverTextSpec {
            val count = if (singleLine) 1 else maxLines.coerceAtLeast(1)
            return DiscoverTextSpec(count, if (reserveLines) count else 1, !singleLine)
        }
    }
}
