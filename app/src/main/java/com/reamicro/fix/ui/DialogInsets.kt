package com.reamicro.fix.ui

internal const val DIALOG_INSIDE_DP = 24f
internal data class DialogFooterPadding(val bottomExtra: Float, val horizontalExtra: Float)

/**
 * WindowDialog already provides 24dp content margins and navigation/IME padding.
 * Add only missing corner clearance, avoiding double application of system insets.
 * Even with no gesture indicator and no radius metadata, the native 24dp margin remains.
 */
internal fun dialogFooterPadding(cornerRadius: Float): DialogFooterPadding {
    val half = (cornerRadius.takeIf { it.isFinite() && it >= 0f } ?: 0f) / 2f
    val extra = (half - DIALOG_INSIDE_DP).coerceAtLeast(0f)
    return DialogFooterPadding(extra, extra)
}
