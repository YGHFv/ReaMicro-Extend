package com.reamicro.fix.hook

internal object StructureEditorMetrics {
    const val corner = 8f
    const val shadow = 16f
    const val horizontalInset = 48f
    const val bottomReservation = 144f

    data class Layout(val horizontalInset: Float, val bottomReservation: Float, val fieldMaxHeight: Float)

    fun layout(width: Float, height: Float, search: Boolean = false): Layout {
        val minimumCard = if (search) 320f else 208f
        val bottom = minOf(bottomReservation, (height - minimumCard).coerceAtLeast(0f))
        return Layout(
            horizontalInset = if (width >= 320f) horizontalInset else 16f,
            bottomReservation = bottom,
            fieldMaxHeight = (height - bottom - minimumCard + 56f).coerceIn(56f, 240f),
        )
    }
}
