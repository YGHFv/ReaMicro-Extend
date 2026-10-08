package top.yukonga.scripta.editor.render

object ZoomMath {

    fun clampScaleToFontRange(scale: Float, fontStart: Float, minSp: Float, maxSp: Float): Float {
        if (fontStart <= 0f) return scale
        return scale.coerceIn(minSp / fontStart, maxSp / fontStart)
    }

    fun commitFontSize(fontStart: Float, scale: Float, minSp: Float, maxSp: Float): Float =
        (fontStart * scale).coerceIn(minSp, maxSp)

    fun provisionalScrollYWrap(anchorContentYOld: Float, k: Float, anchorViewportY: Float): Float =
        (anchorContentYOld * k - anchorViewportY).coerceAtLeast(0f)
}
