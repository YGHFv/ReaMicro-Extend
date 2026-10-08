package com.reamicro.fix.hook

internal object HostThoughtInputStyle {
    const val SURFACE_ALPHA = .98f
    const val BORDER_ALPHA = .9f
    const val BORDER_DP = 1f
    const val SHADOW_DP = 6f
    const val INPUT_BOX_MIN_UDP = 48f
    const val TEXT_MIN_UDP = 24f
    const val INPUT_VIEWPORT_MAX_UDP = 280f
    const val ACTION_GAP_UDP = 12f
    const val SAVE_MIN_WIDTH_UDP = 80f
    const val SAVE_HEIGHT_UDP = 32f
    const val SAVE_CONTENT_PADDING_DP = 0f
    const val SAVE_DISABLED_CONTAINER_ALPHA = .24f
    const val SAVE_DISABLED_CONTENT_ALPHA = .7f

    fun countLabel(text: String): String = "${text.codePointCount(0, text.length)} 字"
}
