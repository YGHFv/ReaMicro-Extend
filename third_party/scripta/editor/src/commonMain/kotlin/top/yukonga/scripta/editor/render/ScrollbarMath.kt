package top.yukonga.scripta.editor.render

object ScrollbarMath {

    fun thumbHeight(viewport: Float, maxScroll: Float, minThumb: Float): Float {
        if (maxScroll <= 0f || viewport <= 0f) return 0f
        val content = viewport + maxScroll
        val h = (viewport * viewport / content).coerceAtLeast(minThumb).coerceAtMost(viewport)
        return if (viewport - h <= 0f) 0f else h
    }

    fun thumbTop(viewport: Float, maxScroll: Float, thumbH: Float, scroll: Float): Float =
        if (maxScroll <= 0f) 0f else (viewport - thumbH) * (scroll / maxScroll).coerceIn(0f, 1f)

    fun dragTargetLine(grabLine: Int, downY: Float, nowY: Float, viewport: Float, thumbH: Float, lineCount: Int): Int {
        val last = (lineCount - 1).coerceAtLeast(0)
        val track = viewport - thumbH
        if (track <= 0f || last == 0) return grabLine.coerceIn(0, last)
        val deltaLines = ((nowY - downY) / track * last).toInt()
        return (grabLine + deltaLines).coerceIn(0, last)
    }

    fun hitThumb(x: Float, y: Float, viewportWidth: Float, hotWidth: Float, thumbTop: Float, thumbH: Float, slack: Float): Boolean =
        x >= viewportWidth - hotWidth && y >= thumbTop - slack && y <= thumbTop + thumbH + slack
}
