package top.yukonga.scripta.editor.render

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import top.yukonga.scripta.editor.EditorColors

private val THUMB_IDLE_WIDTH = 5.dp
private val THUMB_ACTIVE_WIDTH = 8.dp
private val THUMB_EDGE_MARGIN = 4.dp

@Composable
internal fun ScrollbarOverlay(
    colors: EditorColors,
    alpha: () -> Float,
    dragging: () -> Boolean,
    thumbTopOverridePx: () -> Float,
    scrollY: () -> Float,
    maxScrollY: () -> Float,
    minThumbPx: Float,
    modifier: Modifier = Modifier,
) {

    val activeFraction by animateFloatAsState(
        targetValue = if (dragging()) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "scrollbarActive",
    )
    Canvas(modifier) {
        val a = alpha()
        if (a <= 0f) return@Canvas
        val vh = size.height
        val vw = size.width
        val marginPx = THUMB_EDGE_MARGIN.toPx()

        val maxY = maxScrollY()
        val thumbH = ScrollbarMath.thumbHeight(vh, maxY, minThumbPx)
        if (thumbH > 0f) {
            val drag = dragging()
            val override = thumbTopOverridePx()
            val top = if (drag && override >= 0f) override.coerceIn(0f, vh - thumbH)
            else ScrollbarMath.thumbTop(vh, maxY, thumbH, scrollY())
            val f = activeFraction
            val w = lerp(THUMB_IDLE_WIDTH.toPx(), THUMB_ACTIVE_WIDTH.toPx(), f)
            drawRoundRect(
                color = lerp(colors.scrollbarThumb, colors.scrollbarThumbActive, f),
                topLeft = Offset(vw - w - marginPx, top),
                size = Size(w, thumbH),
                cornerRadius = CornerRadius(w / 2f),
                alpha = a,
            )
        }
    }
}
