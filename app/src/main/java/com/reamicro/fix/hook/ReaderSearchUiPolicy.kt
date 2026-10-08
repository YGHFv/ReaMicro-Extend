package com.reamicro.fix.hook

import kotlin.math.roundToInt

internal fun searchNavigationLabel(confirmed: Boolean, failed: Boolean): String = when {
    failed -> "未定位 "
    confirmed -> "已定位 "
    else -> ""
}

internal fun isSearchThemeIcon(name: String): Boolean =
    name == "Outlined.DarkMode" || name == "Outlined.LightMode"

internal object SearchListScrollPosition {
    fun index(fraction: Float, count: Int): Int =
        if (count <= 1 || !fraction.isFinite()) 0
        else (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()

    fun fraction(first: Int, count: Int, atEnd: Boolean): Float = when {
        count <= 1 -> 0f
        atEnd -> 1f
        else -> first.toFloat().div(count - 1).coerceIn(0f, 1f)
    }
}

internal fun searchThumbVisible(listDragging: Boolean, thumbDragging: Boolean, manualScroll: Boolean, scrolling: Boolean): Boolean =
    listDragging || thumbDragging || (manualScroll && scrolling)
