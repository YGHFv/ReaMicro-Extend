package top.yukonga.scripta.editor.render

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorEngine
import top.yukonga.scripta.editor.LineNumberMode
import top.yukonga.scripta.editor.LruCache
import top.yukonga.scripta.editor.highlight.HighlightCache
import top.yukonga.scripta.editor.highlight.SyntaxColors
import top.yukonga.scripta.editor.highlight.clipSpansToWindow
import top.yukonga.scripta.editor.highlight.highlightedText
import top.yukonga.scripta.editor.text.BracketPair
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class GridSliceKey(val line: Int, val start: Int, val end: Int, val version: Int)

private fun measureGridSlice(
    engine: EditorEngine,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    syntax: SyntaxColors,
    highlightCache: HighlightCache?,
    line: Int,
    from: Int,
    until: Int,
): TextLayoutResult {
    val slice = engine.buffer.textInRange(TextRange(TextPosition(line, from), TextPosition(line, until)))
    val spans = highlightCache?.spansForLine(line) { engine.buffer.lineText(it) } ?: emptyList()
    val clipped = clipSpansToWindow(spans, from, until)
    return if (clipped.isEmpty()) textMeasurer.measure(slice, textStyle, softWrap = false)
    else textMeasurer.measure(highlightedText(slice, clipped, syntax, colorOnly = true), textStyle, softWrap = false)
}

private const val GRID_SLICE_QUANTUM = 32

private fun buildTeardropPath(tipX: Float, tipY: Float, ux: Float, uy: Float, r: Float, l: Float): Path {
    val cx = tipX + l * ux
    val cy = tipY + l * uy
    val gamma = acos((r / l).toDouble().coerceIn(-1.0, 1.0))
    val phi = atan2((tipY - cy).toDouble(), (tipX - cx).toDouble())
    val t1 = phi + gamma
    val t1x = (cx + r * cos(t1)).toFloat()
    val t1y = (cy + r * sin(t1)).toFloat()
    val rad2deg = 180.0 / PI
    val bounds = Rect(cx - r, cy - r, cx + r, cy + r)
    return Path().apply {
        moveTo(tipX, tipY)
        lineTo(t1x, t1y)

        arcTo(bounds, (t1 * rad2deg).toFloat(), ((2 * PI - 2 * gamma) * rad2deg).toFloat(), forceMoveTo = false)
        close()
    }
}

@Composable
fun EditorCanvas(
    engine: EditorEngine,
    colors: EditorColors,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    numberStyle: TextStyle,
    lineHeightPx: Float,
    gutterWidthPx: Float,
    padXPx: Float,
    lineNumberMode: LineNumberMode,
    scrollX: () -> Float,
    scrollY: () -> Float,
    firstVisibleLine: (Float) -> Int,
    lineTopPx: (Int) -> Float,
    refBaselinePx: Float,
    caretHandleVisible: () -> Boolean,
    selectionHandlesVisible: () -> Boolean = { true },
    handleRadiusPx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    charW: Float,
    isGridLine: (Int) -> Boolean,
    gridRefBaseline: Float,
    gridRefCursorTop: Float,
    gridRefCursorBottom: Float,

    bracketMatch: () -> BracketPair? = { null },

    highlightCache: HighlightCache? = null,
    previewScale: () -> Float = { 1f },
    previewTx: () -> Float = { 0f },
    previewTy: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {

    val numberLayoutCache = remember(numberStyle) { LruCache<Int, TextLayoutResult>(4096) }

    val gridSliceCache = remember(textStyle, colors.syntax, highlightCache) { LruCache<GridSliceKey, TextLayoutResult>(256) }

    val diag = 0.70710677f
    val caretTeardrop = remember(handleRadiusPx) { buildTeardropPath(0f, 0f, 0f, 1f, handleRadiusPx, handleRadiusPx * 1.4f) }
    val startTeardrop = remember(handleRadiusPx) { buildTeardropPath(0f, 0f, -diag, diag, handleRadiusPx, handleRadiusPx * 1.4f) }
    val endTeardrop = remember(handleRadiusPx) { buildTeardropPath(0f, 0f, diag, diag, handleRadiusPx, handleRadiusPx * 1.4f) }
    Canvas(modifier) {

        val sX = scrollX()
        val sY = scrollY()

        val s = previewScale()
        val hTranslateText = previewTx()
        val vTranslate = previewTy()
        val preTop = -vTranslate / s
        val preBottom = (size.height - vTranslate) / s

        val hlLeft = -hTranslateText / s
        val hlWidth = size.width / s
        val bufVersion = engine.buffer.version
        val pinnedToScreen = lineNumberMode == LineNumberMode.PinnedToScreen

        val gutterScroll = if (pinnedToScreen) 0f else sX

        drawRect(colors.background, topLeft = Offset.Zero, size = size)

        val deferredNumbers = ArrayList<Pair<Int, Float>>()

        withTransform({ translate(hTranslateText, vTranslate); scale(s, s, Offset.Zero) }) {
            val sel = engine.selection
            val comp = engine.composing
            val textX = gutterWidthPx + padXPx - sX
            val lineCount = engine.buffer.lineCount

            fun drawLineNumber(line: Int, top: Float) {
                deferredNumbers.add(line to top)
            }

            var line = firstVisibleLine(preTop).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
            while (line < lineCount) {
                val top = lineTopPx(line) - sY
                if (top >= preBottom) break

                if (isGridLine(line)) {

                    val h = lineHeightPx
                    if (top + h > preTop) {
                        val lineLen = engine.buffer.lineLength(line)
                        val textTop = top + (refBaselinePx - gridRefBaseline)
                        if (sel.isEmpty && sel.start.line == line) {
                            drawRect(colors.currentLine, topLeft = Offset(hlLeft, top), size = Size(hlWidth, h))
                        }

                        bracketMatch()?.let { bm ->
                            if (bm.open.line == line && bm.open.column < lineLen) {
                                drawRect(colors.bracketMatch, topLeft = Offset(textX + bm.open.column * charW, top), size = Size(charW, h))
                            }
                            if (bm.close.line == line && bm.close.column < lineLen) {
                                drawRect(colors.bracketMatch, topLeft = Offset(textX + bm.close.column * charW, top), size = Size(charW, h))
                            }
                        }
                        if (!sel.isEmpty && line >= sel.start.line && line <= sel.end.line) {
                            val cS = if (line == sel.start.line) sel.start.column else 0
                            val cE = if (line == sel.end.line) sel.end.column else lineLen
                            if (cE > cS) {
                                drawRect(colors.selection, topLeft = Offset(textX + cS * charW, top), size = Size((cE - cS) * charW, h))
                            }
                            if (line != sel.end.line) {
                                drawRect(
                                    colors.selection,
                                    topLeft = Offset(textX + lineLen * charW, top),
                                    size = Size(lineHeightPx * 0.4f, h)
                                )
                            }
                        }

                        val cols = if (s == 1f) {
                            EditorGeometry.gridVisibleColumns(
                                sX,
                                (size.width - gutterWidthPx - padXPx * 2).coerceAtLeast(1f),
                                charW,
                                lineLen
                            )
                        } else {
                            EditorGeometry.gridVisibleColumns(sX - hTranslateText / s, size.width / s, charW, lineLen)
                        }
                        if (cols.last > cols.first) {
                            val q = GRID_SLICE_QUANTUM
                            val qStart = (cols.first / q) * q
                            val qEnd = (((cols.last + q - 1) / q) * q).coerceAtMost(lineLen)
                            val sliceLayout = gridSliceCache.getOrPut(GridSliceKey(line, qStart, qEnd, bufVersion)) {
                                measureGridSlice(engine, textMeasurer, textStyle, colors.syntax, highlightCache, line, qStart, qEnd)
                            }

                            drawText(sliceLayout, topLeft = Offset(textX + qStart * charW, textTop))
                        }
                        drawLineNumber(line, top)
                    }
                    line++
                    continue
                }

                val layout = layoutFor(line)
                if (layout == null) {
                    line++; continue
                }
                val h = layout.size.height.toFloat()
                val textTop = top + (refBaselinePx - layout.firstBaseline)
                if (top + h > preTop) {

                    if (sel.isEmpty && sel.start.line == line) {
                        drawRect(colors.currentLine, topLeft = Offset(hlLeft, top), size = Size(hlWidth, h))
                    }

                    bracketMatch()?.let { bm ->
                        val lineLen = engine.buffer.lineLength(line)
                        fun drawBracketAt(col: Int) {
                            if (col < lineLen) {
                                val p = layout.getPathForRange(col, col + 1)
                                p.translate(Offset(textX, top))
                                drawPath(p, colors.bracketMatch)
                            }
                        }
                        if (bm.open.line == line) drawBracketAt(bm.open.column)
                        if (bm.close.line == line) drawBracketAt(bm.close.column)
                    }

                    if (!sel.isEmpty && line >= sel.start.line && line <= sel.end.line) {
                        val lineLen = engine.buffer.lineLength(line)
                        val cS = if (line == sel.start.line) sel.start.column else 0
                        val cE = if (line == sel.end.line) sel.end.column else lineLen
                        if (cE > cS) {
                            val path = layout.getPathForRange(cS, cE)
                            path.translate(Offset(textX, top))
                            drawPath(path, colors.selection)
                        }
                        if (line != sel.end.line) {
                            val cr = layout.getCursorRect(lineLen)
                            drawRect(
                                colors.selection,
                                topLeft = Offset(textX + cr.left, top),
                                size = Size(lineHeightPx * 0.4f, lineHeightPx)
                            )
                        }
                    }

                    drawText(layout, topLeft = Offset(textX, textTop))

                    drawLineNumber(line, top)
                }
                line++
            }

            comp?.let { c ->
                val cLine = c.start.line
                if (isGridLine(cLine)) {
                    val textTop = lineTopPx(cLine) - sY + (refBaselinePx - gridRefBaseline)
                    val len = engine.buffer.lineLength(cLine)
                    val xS = textX + c.start.column.coerceIn(0, len) * charW
                    val xE = textX + c.end.column.coerceIn(0, len) * charW
                    val y = textTop + gridRefCursorBottom - 2f
                    drawLine(colors.cursor, Offset(xS, y), Offset(xE, y), strokeWidth = 3f)
                } else {
                    val layout = layoutFor(cLine)
                    if (layout != null) {
                        val textTop = lineTopPx(cLine) - sY + (refBaselinePx - layout.firstBaseline)
                        val len = engine.buffer.lineLength(cLine)
                        val startRect = layout.getCursorRect(c.start.column.coerceIn(0, len))
                        val endRect = layout.getCursorRect(c.end.column.coerceIn(0, len))
                        if (startRect.top == endRect.top) {
                            val y = textTop + startRect.bottom - 2f
                            drawLine(colors.cursor, Offset(textX + startRect.left, y), Offset(textX + endRect.left, y), strokeWidth = 3f)
                        }
                    }
                }
            }

            fun drawHandle(kind: HandleKind, pos: TextPosition) {
                val caretLeft: Float
                val crBottomY: Float
                if (isGridLine(pos.line)) {
                    val col = pos.column.coerceIn(0, engine.buffer.lineLength(pos.line))
                    val base = lineTopPx(pos.line) - sY + (refBaselinePx - gridRefBaseline)
                    caretLeft = textX + col * charW
                    crBottomY = base + gridRefCursorBottom
                } else {
                    val layout = layoutFor(pos.line) ?: return
                    val cr = layout.getCursorRect(pos.column.coerceIn(0, engine.buffer.lineLength(pos.line)))
                    val base = lineTopPx(pos.line) - sY + (refBaselinePx - layout.firstBaseline)
                    caretLeft = textX + cr.left
                    crBottomY = base + cr.bottom
                }

                val path = when (kind) {
                    HandleKind.Caret -> caretTeardrop
                    HandleKind.SelectionStart -> startTeardrop
                    HandleKind.SelectionEnd -> endTeardrop
                }
                translate(caretLeft, crBottomY) { drawPath(path, colors.handle) }
            }
            if (s == 1f) {
                if (!sel.isEmpty) {
                    if (selectionHandlesVisible()) {
                        drawHandle(HandleKind.SelectionStart, sel.start)
                        drawHandle(HandleKind.SelectionEnd, sel.end)
                    }
                } else if (caretHandleVisible()) {
                    drawHandle(HandleKind.Caret, sel.start)
                }
            }
        }

        val gutterHTranslate = if (pinnedToScreen) 0f else hTranslateText
        withTransform({ translate(gutterHTranslate, vTranslate); scale(s, s, Offset.Zero) }) {
            drawRect(colors.gutterBackground, topLeft = Offset(-gutterScroll, preTop), size = Size(gutterWidthPx, preBottom - preTop))
            for ((line, top) in deferredNumbers) {
                val num = numberLayoutCache.getOrPut(line) { textMeasurer.measure((line + 1).toString(), numberStyle) }
                val numTop = top + (refBaselinePx - num.firstBaseline)
                drawText(
                    num,
                    color = colors.gutterForeground,
                    topLeft = Offset(gutterWidthPx - padXPx - num.size.width - gutterScroll, numTop)
                )
            }
        }
    }
}

@Composable
fun CursorOverlay(
    engine: EditorEngine,
    colors: EditorColors,
    caretVisible: () -> Boolean,
    scrollX: () -> Float,
    scrollY: () -> Float,
    lineTopPx: (Int) -> Float,
    refBaselinePx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    charW: Float,
    isGridLine: (Int) -> Boolean,
    gridRefBaseline: Float,
    gridRefCursorTop: Float,
    gridRefCursorBottom: Float,
    gutterWidthPx: Float,
    padXPx: Float,
    previewScale: () -> Float = { 1f },
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.graphicsLayer { alpha = if (caretVisible()) 1f else 0f }) {
        if (previewScale() != 1f) return@Canvas
        val sel = engine.selection
        if (!sel.isEmpty) return@Canvas
        val sX = scrollX()
        val sY = scrollY()
        val textX = gutterWidthPx + padXPx - sX
        val cLine = sel.start.line
        val col = sel.start.column.coerceIn(0, engine.buffer.lineLength(cLine))
        if (isGridLine(cLine)) {
            val textTop = lineTopPx(cLine) - sY + (refBaselinePx - gridRefBaseline)
            val x = textX + col * charW
            drawLine(
                colors.cursor,
                Offset(x, textTop + gridRefCursorTop + 1f),
                Offset(x, textTop + gridRefCursorBottom - 1f),
                strokeWidth = 2.5f
            )
        } else {
            val layout = layoutFor(cLine) ?: return@Canvas
            val textTop = lineTopPx(cLine) - sY + (refBaselinePx - layout.firstBaseline)
            val cr = layout.getCursorRect(col)
            drawLine(
                colors.cursor,
                Offset(textX + cr.left, textTop + cr.top + 1f),
                Offset(textX + cr.left, textTop + cr.bottom - 1f),
                strokeWidth = 2.5f
            )
        }
    }
}

private val MAGNIFIER_WIDTH = 132.dp
private val MAGNIFIER_HEIGHT = 82.dp
private val MAGNIFIER_GAP = 22.dp
private val MAGNIFIER_MARGIN = 8.dp
private val MAGNIFIER_POPUP_PAD = 30.dp
private val MAGNIFIER_BORDER = 1.dp
private const val MAGNIFIER_SCALE = 1.4f

private val MAGNIFIER_DROP_RADIUS = 14.dp
private val MAGNIFIER_DROP_DY = 5.dp
private const val MAGNIFIER_DROP_ALPHA = 0.30f

private val MAGNIFIER_REFRACTION_HEIGHT = 16.dp
private val MAGNIFIER_REFRACTION_AMOUNT = 10.dp
private const val MAGNIFIER_DISPERSION = 0.15f
private const val MAGNIFIER_DEPTH_EFFECT = 1f

private class LoupeGeom(
    val cLine: Int,
    val col: Int,
    val caretX: Float,
    val caretTopY: Float,
    val caretBotY: Float,
    val refX: Float,
    val refY: Float,
    val cx: Float,
    val cyc: Float,
    val rect: Rect,
    val radius: Float,
)

@Composable
fun MagnifierOverlay(
    engine: EditorEngine,
    colors: EditorColors,
    active: () -> Boolean,
    dragPos: () -> Offset?,
    caretVisible: () -> Boolean,
    viewportWidth: () -> Float,
    contentTopInWindow: () -> Float,
    scrollX: () -> Float,
    scrollY: () -> Float,
    lineTopPx: (Int) -> Float,
    lineHeightPx: Float,
    refBaselinePx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    numberStyle: TextStyle,
    charW: Float,
    isGridLine: (Int) -> Boolean,
    gridRefBaseline: Float,
    gridRefCursorTop: Float,
    gridRefCursorBottom: Float,
    gutterWidthPx: Float,
    padXPx: Float,

    highlightCache: HighlightCache? = null,
) {

    val showing = active()
    val alpha = animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = tween(durationMillis = if (showing) 160 else 260),
        label = "magnifierAlpha",
    )

    val visible by remember { derivedStateOf { alpha.value > 0.001f } }

    val lastRef = remember { floatArrayOf(Float.NaN, Float.NaN) }

    val glassSupported = remember { isMagnifierGlassSupported() }
    val density = LocalDensity.current

    val glassEffect = remember(density, glassSupported) {
        if (!glassSupported) null else with(density) {
            magnifierGlassRenderEffect(
                left = MAGNIFIER_POPUP_PAD.toPx(),
                top = MAGNIFIER_POPUP_PAD.toPx(),
                width = MAGNIFIER_WIDTH.toPx(),
                height = MAGNIFIER_HEIGHT.toPx(),
                cornerRadius = MAGNIFIER_HEIGHT.toPx() / 2f,
                refractionHeight = MAGNIFIER_REFRACTION_HEIGHT.toPx(),
                refractionAmount = MAGNIFIER_REFRACTION_AMOUNT.toPx(),
                depthEffect = MAGNIFIER_DEPTH_EFFECT,
                chromaticAberration = MAGNIFIER_DISPERSION,
            )
        }
    }

    fun computeLoupe(d: Density): LoupeGeom? {
        val sX = scrollX()
        val sY = scrollY()
        val pos = engine.caret
        val cLine = pos.line
        val col = pos.column.coerceIn(0, engine.buffer.lineLength(cLine))
        val textX = gutterWidthPx + padXPx - sX
        val caretX: Float
        val caretTopY: Float
        val caretBotY: Float
        if (isGridLine(cLine)) {
            val base = lineTopPx(cLine) - sY + (refBaselinePx - gridRefBaseline)
            caretX = textX + col * charW
            caretTopY = base + gridRefCursorTop
            caretBotY = base + gridRefCursorBottom
        } else {
            val layout = layoutFor(cLine) ?: return null
            val cr = layout.getCursorRect(col)
            val base = lineTopPx(cLine) - sY + (refBaselinePx - layout.firstBaseline)
            caretX = textX + cr.left
            caretTopY = base + cr.top
            caretBotY = base + cr.bottom
        }
        val caretMidY = (caretTopY + caretBotY) / 2f

        val dp = dragPos()
        val refX: Float
        val refY: Float
        if (dp != null) {
            refX = dp.x
            refY = dp.y
            lastRef[0] = refX
            lastRef[1] = refY
        } else {
            refX = if (lastRef[0].isNaN()) caretX else lastRef[0]
            refY = if (lastRef[1].isNaN()) caretMidY else lastRef[1]
        }
        val wl = with(d) { MAGNIFIER_WIDTH.toPx() }
        val hl = with(d) { MAGNIFIER_HEIGHT.toPx() }
        val gap = with(d) { MAGNIFIER_GAP.toPx() }
        val margin = with(d) { MAGNIFIER_MARGIN.toPx() }
        val radius = hl / 2f
        val halfW = wl / 2f

        val cx = refX.coerceIn(margin + halfW, (viewportWidth() - margin - halfW).coerceAtLeast(margin + halfW))

        val minTop = margin - contentTopInWindow()
        val top = (refY - lineHeightPx / 2f - gap - hl).coerceAtLeast(minTop)
        val cyc = top + hl / 2f
        return LoupeGeom(cLine, col, caretX, caretTopY, caretBotY, refX, refY, cx, cyc, Rect(cx - halfW, top, cx + halfW, top + hl), radius)
    }

    Box(
        Modifier.offset {
            if (alpha.value <= 0.001f) IntOffset.Zero
            else computeLoupe(this)?.let { IntOffset(it.rect.left.roundToInt(), it.rect.top.roundToInt()) } ?: IntOffset.Zero
        }
    ) {
        if (visible) {
            val padPx = with(density) { MAGNIFIER_POPUP_PAD.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(-padPx, -padPx),
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {

                Box(
                    Modifier
                        .size(MAGNIFIER_WIDTH + MAGNIFIER_POPUP_PAD * 2, MAGNIFIER_HEIGHT + MAGNIFIER_POPUP_PAD * 2)
                        .graphicsLayer { this.alpha = alpha.value },
                ) {

                    Box(
                        Modifier
                            .offset(MAGNIFIER_POPUP_PAD, MAGNIFIER_POPUP_PAD)
                            .size(MAGNIFIER_WIDTH, MAGNIFIER_HEIGHT)
                            .dropShadow(
                                shape = RoundedCornerShape(percent = 50),
                                shadow = Shadow(
                                    radius = MAGNIFIER_DROP_RADIUS,
                                    spread = 0.dp,
                                    color = Color.Black.copy(alpha = MAGNIFIER_DROP_ALPHA),
                                    offset = DpOffset(0.dp, MAGNIFIER_DROP_DY),
                                ),
                            ),
                    )
                    Canvas(
                        Modifier
                            .size(MAGNIFIER_WIDTH + MAGNIFIER_POPUP_PAD * 2, MAGNIFIER_HEIGHT + MAGNIFIER_POPUP_PAD * 2)
                            .graphicsLayer {
                                renderEffect = glassEffect
                            }
                    ) {
                        val g = computeLoupe(this) ?: return@Canvas
                        val m = MAGNIFIER_SCALE
                        val pad = MAGNIFIER_POPUP_PAD.toPx()
                        val wl = MAGNIFIER_WIDTH.toPx()
                        val hl = MAGNIFIER_HEIGHT.toPx()
                        val radius = g.radius
                        val sY = scrollY()
                        val textX = gutterWidthPx + padXPx - scrollX()
                        val sel = engine.selection
                        val loupeRect = Rect(pad, pad, pad + wl, pad + hl)

                        val clip = Path().apply { addRoundRect(RoundRect(loupeRect, CornerRadius(radius, radius))) }
                        clipPath(clip) {
                            drawRect(colors.background, topLeft = loupeRect.topLeft, size = loupeRect.size)

                            withTransform({
                                translate((pad + wl / 2f) - m * g.refX, (pad + hl / 2f) - m * g.refY)
                                scale(m, m, Offset.Zero)
                            }) {
                                val lineCount = engine.buffer.lineCount
                                val first = (g.cLine - 2).coerceAtLeast(0)
                                val last = (g.cLine + 2).coerceAtMost(lineCount - 1)
                                var ln = first
                                while (ln <= last) {
                                    val lineTop = lineTopPx(ln) - sY

                                    val inSel = !sel.isEmpty && ln >= sel.start.line && ln <= sel.end.line
                                    if (isGridLine(ln)) {
                                        val textTop = lineTop + (refBaselinePx - gridRefBaseline)
                                        val len = engine.buffer.lineLength(ln)
                                        if (inSel) {
                                            val cS = if (ln == sel.start.line) sel.start.column else 0
                                            val cE = if (ln == sel.end.line) sel.end.column else len
                                            if (cE > cS) drawRect(
                                                colors.selection,
                                                topLeft = Offset(textX + cS * charW, lineTop),
                                                size = Size((cE - cS) * charW, lineHeightPx)
                                            )
                                            if (ln != sel.end.line) drawRect(
                                                colors.selection,
                                                topLeft = Offset(textX + len * charW, lineTop),
                                                size = Size(lineHeightPx * 0.4f, lineHeightPx)
                                            )
                                        }
                                        val c0 = (g.col - 24).coerceIn(0, len)
                                        val c1 = (g.col + 24).coerceIn(0, len)
                                        if (c1 > c0) {

                                            val sl =
                                                measureGridSlice(engine, textMeasurer, textStyle, colors.syntax, highlightCache, ln, c0, c1)
                                            drawText(sl, topLeft = Offset(textX + c0 * charW, textTop))
                                        }
                                    } else {
                                        val layout = layoutFor(ln)
                                        if (layout != null) {
                                            val textTop = lineTop + (refBaselinePx - layout.firstBaseline)
                                            if (inSel) {
                                                val len = engine.buffer.lineLength(ln)
                                                val cS = if (ln == sel.start.line) sel.start.column else 0
                                                val cE = if (ln == sel.end.line) sel.end.column else len
                                                if (cE > cS) {
                                                    val path = layout.getPathForRange(cS, cE)
                                                    path.translate(Offset(textX, lineTop))
                                                    drawPath(path, colors.selection)
                                                }
                                                if (ln != sel.end.line) {
                                                    val cr = layout.getCursorRect(len)
                                                    drawRect(
                                                        colors.selection,
                                                        topLeft = Offset(textX + cr.left, lineTop),
                                                        size = Size(lineHeightPx * 0.4f, lineHeightPx)
                                                    )
                                                }
                                            }

                                            drawText(layout, topLeft = Offset(textX, textTop))
                                        }
                                    }
                                    ln++
                                }

                                run {
                                    val gTop = lineTopPx(first) - sY
                                    val gBot = lineTopPx(last) - sY + lineHeightPx
                                    drawRect(colors.gutterBackground, topLeft = Offset(0f, gTop), size = Size(gutterWidthPx, gBot - gTop))
                                    var gln = first
                                    while (gln <= last) {
                                        val lt = lineTopPx(gln) - sY
                                        val num = textMeasurer.measure((gln + 1).toString(), numberStyle)
                                        val numTop = lt + (refBaselinePx - num.firstBaseline)
                                        drawText(
                                            num,
                                            color = colors.gutterForeground,
                                            topLeft = Offset(gutterWidthPx - padXPx - num.size.width, numTop)
                                        )
                                        gln++
                                    }
                                }

                                if (sel.isEmpty && caretVisible()) {
                                    drawLine(
                                        colors.cursor,
                                        Offset(g.caretX, g.caretTopY),
                                        Offset(g.caretX, g.caretBotY),
                                        strokeWidth = 1.6f
                                    )
                                }
                            }

                        }

                        if (!glassSupported) {
                            drawRoundRect(
                                color = colors.gutterForeground.copy(alpha = 0.35f),
                                topLeft = loupeRect.topLeft,
                                size = loupeRect.size,
                                cornerRadius = CornerRadius(radius, radius),
                                style = Stroke(width = MAGNIFIER_BORDER.toPx()),
                            )
                        }
                    }

                    Box(
                        Modifier
                            .offset(MAGNIFIER_POPUP_PAD, MAGNIFIER_POPUP_PAD)
                            .size(MAGNIFIER_WIDTH, MAGNIFIER_HEIGHT)
                            .innerShadow(
                                shape = RoundedCornerShape(percent = 50),
                                shadow = Shadow(
                                    radius = 14.dp,
                                    spread = 0.dp,
                                    color = Color.Black.copy(alpha = 0.25f),
                                    offset = DpOffset(0.dp, 1.dp)
                                ),
                            ),
                    )
                }
            }
        }
    }
}
