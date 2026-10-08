package top.yukonga.scripta.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollable2DState
import androidx.compose.foundation.gestures.scrollable2D
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.scripta.editor.find.GotoLineBar
import top.yukonga.scripta.editor.highlight.HighlightCache
import top.yukonga.scripta.editor.highlight.HighlightSpan
import top.yukonga.scripta.editor.highlight.SyntaxHighlighter
import top.yukonga.scripta.editor.highlight.highlightedText
import top.yukonga.scripta.editor.input.EditorKeyCommand
import top.yukonga.scripta.editor.input.editorRightEdgeGestureExclusion
import top.yukonga.scripta.editor.input.editorTextInput
import top.yukonga.scripta.editor.input.insertTypedCharacter
import top.yukonga.scripta.editor.input.resolveEditorKeyCommand
import top.yukonga.scripta.editor.menu.EditorClipboardActions
import top.yukonga.scripta.editor.menu.EditorContextAction
import top.yukonga.scripta.editor.menu.EditorContextMenu
import top.yukonga.scripta.editor.menu.SelectionActionToolbar
import top.yukonga.scripta.editor.render.CursorOverlay
import top.yukonga.scripta.editor.render.EditorCanvas
import top.yukonga.scripta.editor.render.EditorGeometry
import top.yukonga.scripta.editor.render.HandleKind
import top.yukonga.scripta.editor.render.MagnifierOverlay
import top.yukonga.scripta.editor.render.ScrollbarMath
import top.yukonga.scripta.editor.render.ScrollbarOverlay
import top.yukonga.scripta.editor.render.VisualRowIndex
import top.yukonga.scripta.editor.render.ZoomMath
import top.yukonga.scripta.editor.text.BracketMatcher
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import androidx.compose.ui.input.pointer.isShiftPressed as isKeyboardShiftPressed

private const val LONG_LINE_THRESHOLD = 2000

private const val ZOOM_MIN_CHANGE_SP = 0.1f

private const val ZOOM_MIN_SP = 8f
private const val ZOOM_MAX_SP = 40f

private const val LAYOUT_BASE_FONT_SP = 14f
private const val PAD_X_BASE_DP = 8f

private const val BOTTOM_SCROLL_PAD_FRACTION = 0.2f

private const val RIGHT_SCROLL_PAD_FRACTION = 0.1f

private val SCROLLBAR_MIN_THUMB = 48.dp
private val SCROLLBAR_HOT_ZONE = 24.dp
private val SCROLLBAR_GRAB_SLACK = 8.dp

private const val SCROLLBAR_IDLE_MS = 1200
private const val SCROLLBAR_QUIET_GRAB_MS = 150

private class ScrollbarClock {
    var last: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
    var shown = false
    var fading = false
    var hovering = false
}

private class CachedLayout(
    var validatedVersion: Int,
    val content: String,
    val spans: List<HighlightSpan>,
    val layout: TextLayoutResult,
)

private class NarrowFlag(var validatedVersion: Int, val length: Int, val hash: Int, val narrow: Boolean)

private fun isNarrowGridText(s: String): Boolean = s.all { it.code in 0x20..0x7E }

@Composable
fun CodeEditor(
    controller: CodeEditorController,
    modifier: Modifier = Modifier,
    colors: EditorColors = EditorColors.Default,
    readOnly: Boolean = false,
    softWrap: Boolean = false,
    consumeSystemInsets: Boolean = true,
    controlTextStyle: TextStyle = TextStyle(fontSize = 15.sp),
    onFindRequested: () -> Unit,
    onFindNext: () -> Unit,
    onFindPrevious: () -> Unit,
    onGotoLineRequested: () -> Unit,
    lineNumberMode: LineNumberMode = LineNumberMode.PinnedToScreen,
    symbols: List<EditorSymbol> = DefaultEditorSymbols,

    autoClosePairs: Boolean = true,

    highlighter: SyntaxHighlighter? = null,
) {

    val engine = controller.engine
    val gotoSession = controller.gotoLine
    engine.autoClosePairs = autoClosePairs

    val bracketMatchState = remember(engine) {
        derivedStateOf {
            engine.buffer.version
            BracketMatcher.findMatch(engine.buffer, engine.caret)
        }
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }

    val editorFocused = interaction.collectIsFocusedAsState()

    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val overscroll = rememberOverscrollEffect()

    var fontSizeSp by remember { mutableFloatStateOf(15f) }

    var previewScale by remember { mutableFloatStateOf(1f) }

    var previewTx by remember { mutableFloatStateOf(0f) }
    var previewTy by remember { mutableFloatStateOf(0f) }

    var zoomWrapAnchorPos by remember { mutableStateOf<TextPosition?>(null) }
    val lineHeightSp = fontSizeSp * 1.5f

    val lineHeightStyle = remember {
        LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
    }

    val platformTextStyle = remember { editorNoFontPaddingStyle() }
    val textStyle = remember(colors, fontSizeSp) {
        TextStyle(
            color = colors.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            lineHeightStyle = lineHeightStyle,

            textMotion = TextMotion.Animated,
            platformStyle = platformTextStyle
        )
    }
    val numberStyle = remember(colors, fontSizeSp) {
        TextStyle(
            color = colors.gutterForeground,
            fontFamily = FontFamily.Monospace,

            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            lineHeightStyle = lineHeightStyle,
            textMotion = TextMotion.Animated,
            platformStyle = platformTextStyle
        )
    }
    val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }

    val padXPx = with(density) { (PAD_X_BASE_DP * fontSizeSp / LAYOUT_BASE_FONT_SP).dp.toPx() }

    val handleRadiusPx = with(density) { 8.dp.toPx() }
    val handleSlopPx = with(density) { 10.dp.toPx() }

    val refBaselinePx = remember(textStyle) { measurer.measure("Ag中", textStyle).firstBaseline }

    val gridRef = remember(textStyle) { measurer.measure("0", textStyle) }
    val charWpx = remember(gridRef) { gridRef.getCursorRect(1).left }
    val gridRefBaseline = gridRef.firstBaseline
    val gridRefCursor = remember(gridRef) { gridRef.getCursorRect(0) }

    val narrowCache = remember(engine) { LruCache<Int, NarrowFlag>(256) }
    fun isNarrowLine(line: Int): Boolean {
        val version = engine.buffer.version
        val cached = narrowCache[line]
        if (cached != null && cached.validatedVersion == version) return cached.narrow
        val content = engine.buffer.lineText(line)

        if (cached != null && cached.length == content.length && cached.hash == content.hashCode()) {
            cached.validatedVersion = version
            return cached.narrow
        }
        val narrow = isNarrowGridText(content)
        narrowCache[line] = NarrowFlag(version, content.length, content.hashCode(), narrow)
        return narrow
    }

    fun isGridLine(line: Int): Boolean =
        !softWrap && charWpx > 0f && engine.buffer.lineLength(line) > LONG_LINE_THRESHOLD && isNarrowLine(line)

    engine.buffer.version
    val lineCount = engine.buffer.lineCount

    val resolvedHighlighter = highlighter
    val highlightCache = remember(resolvedHighlighter, engine) { resolvedHighlighter?.let { HighlightCache(it) } }

    val highlightSeenVersion = remember(highlightCache) { intArrayOf(-1) }
    fun ensureHighlightFresh() {
        if (highlightCache != null && highlightSeenVersion[0] != engine.buffer.version) {
            highlightSeenVersion[0] = engine.buffer.version
            engine.consumeDirty()?.let { d -> highlightCache.invalidate(d.from, d.endExclusive, d.structural) }
        }
    }
    ensureHighlightFresh()

    val gutterDigits = EditorGeometry.gutterDigits(lineCount)
    val gutterWidthPx = remember(gutterDigits, numberStyle) {
        measurer.measure("0".repeat(gutterDigits), numberStyle).size.width + padXPx * 2
    }

    var scrollY by remember { mutableFloatStateOf(0f) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    var contentTopInWindow by remember { mutableFloatStateOf(0f) }

    val textAreaWidthPx = (viewportWidth - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
    val widthBucket = if (softWrap) textAreaWidthPx.toInt() else 0

    val rowIndex = remember(softWrap, widthBucket, engine.contentGeneration) {
        VisualRowIndex(if (softWrap) engine.buffer.lineCount else 1).also { engine.consumeLineSplices() }
    }

    fun drainRowSplices() {
        val splices = engine.consumeLineSplices()
        if (softWrap) for (s in splices) rowIndex.splice(s.startLine, s.oldLines, s.newLines)
    }
    drainRowSplices()

    val layoutCache = remember(engine, softWrap, widthBucket, fontSizeSp, colors, resolvedHighlighter) {
        LruCache<Int, CachedLayout>(4096)
    }

    fun layoutFor(line: Int): TextLayoutResult? {
        if (line < 0 || line >= engine.buffer.lineCount) return null
        if (isGridLine(line)) return null
        ensureHighlightFresh()
        drainRowSplices()
        val version = engine.buffer.version
        val cached = layoutCache[line]

        if (cached != null && cached.validatedVersion == version) {

            if (softWrap) rowIndex.setRows(line, cached.layout.lineCount)
            return cached.layout
        }

        val content = engine.buffer.lineText(line)
        val spans = highlightCache?.spansForLine(line) { engine.buffer.lineText(it) } ?: emptyList()
        if (cached != null && cached.content == content && cached.spans == spans) {
            cached.validatedVersion = version
            if (softWrap) rowIndex.setRows(line, cached.layout.lineCount)
            return cached.layout
        }
        val annotated = highlightedText(content, spans, colors.syntax)
        val measured = if (softWrap) {
            measurer.measure(
                annotated,
                textStyle,
                softWrap = true,
                constraints = Constraints(maxWidth = textAreaWidthPx.toInt().coerceAtLeast(1))
            )
        } else {
            measurer.measure(annotated, textStyle, softWrap = false)
        }
        layoutCache[line] = CachedLayout(version, content, spans, measured)
        if (softWrap) rowIndex.setRows(line, measured.lineCount)
        return measured
    }

    fun lineTopPx(line: Int): Float {
        if (softWrap) drainRowSplices()
        return if (softWrap) rowIndex.rowsBefore(line) * lineHeightPx else line * lineHeightPx
    }

    fun lineAtPx(y: Float): Int {
        if (softWrap) drainRowSplices()
        val row = (y / lineHeightPx).toInt().coerceAtLeast(0)
        return if (softWrap) rowIndex.lineAtRow(row) else row.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    }

    fun pageLines(): Int = ((viewportHeight / lineHeightPx).toInt() - 1).coerceAtLeast(1)

    val scrollLine by remember(rowIndex, lineHeightPx, softWrap, lineCount) { derivedStateOf { lineAtPx(scrollY) } }

    val firstVisibleLine = (scrollLine - 3).coerceAtLeast(0)
    val approxRows = (viewportHeight / lineHeightPx).toInt() + 8
    val measureEnd = (firstVisibleLine + approxRows).coerceAtMost((lineCount - 1).coerceAtLeast(0))

    val widestSeen = remember(softWrap, widthBucket, engine.contentGeneration) { floatArrayOf(0f, fontSizeSp) }
    if (widestSeen[1] != fontSizeSp) {
        if (widestSeen[1] > 0f) widestSeen[0] *= fontSizeSp / widestSeen[1]
        widestSeen[1] = fontSizeSp
    }
    for (ln in firstVisibleLine..measureEnd) {
        if (isGridLine(ln)) {
            val w = engine.buffer.lineLength(ln) * charWpx
            if (w > widestSeen[0]) widestSeen[0] = w
        } else {
            val l = layoutFor(ln)
            if (!softWrap) {

                val w = l?.getLineRight(0) ?: 0f
                if (w > widestSeen[0]) widestSeen[0] = w
            }
        }
    }

    val contentHeight = (if (softWrap) rowIndex.totalRows() else lineCount) * lineHeightPx

    val bottomScrollPadPx = viewportHeight * BOTTOM_SCROLL_PAD_FRACTION
    val maxScrollY = (contentHeight - viewportHeight + bottomScrollPadPx).coerceAtLeast(0f)
    val maxScrollYSettle = (contentHeight - viewportHeight).coerceAtLeast(0f)
    val maxScrollX = if (softWrap) 0f else {
        (gutterWidthPx + padXPx * 2 + widestSeen[0] + viewportWidth * RIGHT_SCROLL_PAD_FRACTION - viewportWidth)
            .coerceAtLeast(0f)
    }

    LaunchedEffect(fontSizeSp) {
        if (!softWrap) return@LaunchedEffect
        val ap = zoomWrapAnchorPos ?: return@LaunchedEffect
        val layout = layoutFor(ap.line) ?: return@LaunchedEffect
        val col = ap.column.coerceIn(0, engine.buffer.lineLength(ap.line))
        val vr = layout.getLineForOffset(col)
        val anchorRowTopY = lineTopPx(ap.line) + layout.getLineTop(vr)
        scrollY = anchorRowTopY.coerceIn(0f, maxScrollY)
    }

    val scrollbarAlpha = remember(engine) { Animatable(0f) }
    val scrollbarShowTick = remember(engine) { mutableIntStateOf(0) }
    val scrollbarDragging = remember(engine) { mutableStateOf(false) }
    val scrollbarThumbTopOverride = remember(engine) { mutableFloatStateOf(-1f) }
    val scrollbarShownState = remember(engine) { mutableStateOf(false) }
    val scrollbarClock = remember(engine) { ScrollbarClock() }

    fun markUserScroll() {
        scrollbarClock.last = TimeSource.Monotonic.markNow()
        if (!scrollbarClock.shown || scrollbarClock.fading) {
            scrollbarClock.shown = true
            scrollbarClock.fading = false
            scrollbarShowTick.intValue++
        }
    }

    LaunchedEffect(engine) {
        snapshotFlow { scrollbarShowTick.intValue }.collectLatest { tick ->
            if (tick == 0) return@collectLatest
            scrollbarShownState.value = true
            scrollbarAlpha.animateTo(1f, tween(100))
            while (true) {
                withFrameNanos { }
                if (scrollbarDragging.value || scrollbarClock.hovering) {
                    scrollbarClock.last = TimeSource.Monotonic.markNow()
                    continue
                }
                if (scrollbarClock.last.elapsedNow() >= SCROLLBAR_IDLE_MS.milliseconds) break
            }
            scrollbarClock.fading = true
            scrollbarAlpha.animateTo(0f, tween(300))

            scrollbarClock.fading = false
            scrollbarClock.shown = false
            scrollbarShownState.value = false
        }
    }

    val scrollInteraction = remember { MutableInteractionSource() }
    val draggingHolder = remember { booleanArrayOf(false) }
    val flingAxis = remember { intArrayOf(0) }
    LaunchedEffect(scrollInteraction) {
        scrollInteraction.interactions.collect { i ->
            draggingHolder[0] = when (i) {
                is DragInteraction.Start -> true
                is DragInteraction.Stop, is DragInteraction.Cancel -> false
                else -> draggingHolder[0]
            }
        }
    }

    val prevViewportH = remember { floatArrayOf(0f) }
    LaunchedEffect(maxScrollY, maxScrollX, viewportHeight) {
        val grew = viewportHeight > prevViewportH[0]
        prevViewportH[0] = viewportHeight
        val scrolling = draggingHolder[0] ||
                scrollbarClock.last.elapsedNow() < SCROLLBAR_QUIET_GRAB_MS.milliseconds
        scrollY = scrollY.coerceIn(0f, if (grew && !scrolling) maxScrollYSettle else maxScrollY)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
    }
    val scroll2D = rememberScrollable2DState { delta ->
        markUserScroll()
        if (draggingHolder[0]) {

            flingAxis[0] = 0
            val cx = (scrollX - delta.x).coerceIn(0f, maxScrollX)
            val cy = (scrollY - delta.y).coerceIn(0f, maxScrollY)
            val consumed = Offset(scrollX - cx, scrollY - cy)
            scrollX = cx; scrollY = cy
            consumed
        } else {

            if (flingAxis[0] == 0) flingAxis[0] = if (abs(delta.y) >= abs(delta.x)) 1 else 2
            if (flingAxis[0] == 1) {
                val cy = (scrollY - delta.y).coerceIn(0f, maxScrollY)
                val consumedY = scrollY - cy
                scrollY = cy
                Offset(delta.x, consumedY)
            } else {
                val cx = (scrollX - delta.x).coerceIn(0f, maxScrollX)
                val consumedX = scrollX - cx
                scrollX = cx
                Offset(consumedX, delta.y)
            }
        }
    }

    var selectionAnchor by remember { mutableStateOf<TextPosition?>(null) }
    var selectionWordAnchor by remember { mutableStateOf<TextRange?>(null) }

    var selectionLineAnchor by remember { mutableStateOf<Int?>(null) }

    var selectionDragPos by remember { mutableStateOf<Offset?>(null) }

    var selectionDragActive by remember { mutableStateOf(false) }

    var caretDragPos by remember { mutableStateOf<Offset?>(null) }
    var caretDragActive by remember { mutableStateOf(false) }

    var handleDragActive by remember { mutableStateOf(false) }

    var lastInteractionWasMouse by remember { mutableStateOf(false) }

    var showTouchMenu by remember { mutableStateOf(false) }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuAnchor by remember { mutableStateOf<Offset?>(null) }

    var blink by remember { mutableStateOf(true) }

    LaunchedEffect(readOnly) {
        if (readOnly) return@LaunchedEffect
        snapshotFlow { engine.selection }.collectLatest {
            blink = true
            while (true) {
                delay(500.milliseconds); blink = !blink
            }
        }
    }

    var caretHandleVisible by remember { mutableStateOf(false) }
    var caretHandleToken by remember { mutableIntStateOf(0) }
    fun pingCaretHandle() {
        caretHandleVisible = true; caretHandleToken++
    }

    LaunchedEffect(Unit) {
        snapshotFlow { caretHandleToken }.collectLatest { token ->
            if (token == 0) return@collectLatest
            delay(4000.milliseconds); caretHandleVisible = false
        }
    }
    LaunchedEffect(engine.buffer.version) {

        caretHandleVisible = false
        showTouchMenu = false
        showContextMenu = false
    }

    LaunchedEffect(Unit) {
        var wasActive = false
        snapshotFlow { handleDragActive || selectionDragActive }.collectLatest { active ->
            if (wasActive && !active && !lastInteractionWasMouse) showTouchMenu = true
            wasActive = active
        }
    }

    fun caretContentXOf(line: Int, column: Int): Float? {
        val col = column.coerceAtMost(engine.buffer.lineLength(line))
        return if (isGridLine(line)) col * charWpx else layoutFor(line)?.getCursorRect(col)?.left
    }

    fun revealScrollXFor(caretCX: Float, curScrollX: Float): Float {
        val textAreaW = (viewportWidth - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
        val margin = padXPx * 3
        return when {
            caretCX < curScrollX -> (caretCX - margin).coerceIn(0f, maxScrollX)
            caretCX > curScrollX + textAreaW -> (caretCX - textAreaW + margin).coerceIn(0f, maxScrollX)
            else -> curScrollX
        }
    }

    val revealCaretIntoViewLive = rememberUpdatedState {

        val caret = engine.caret
        val line = caret.line

        val curRow = if (softWrap) {
            layoutFor(line)?.getLineForOffset(caret.column.coerceIn(0, engine.buffer.lineLength(line))) ?: 0
        } else 0
        val caretTop = lineTopPx(line) + curRow * lineHeightPx
        val caretBottom = caretTop + lineHeightPx
        if (caretTop < scrollY) scrollY = caretTop
        else if (caretBottom > scrollY + viewportHeight) scrollY = caretBottom - viewportHeight

        if (!softWrap && viewportWidth > 0f && !engine.hasGoalColumn) {
            caretContentXOf(line, caret.column)?.let { scrollX = revealScrollXFor(it, scrollX) }
        }
    }

    LaunchedEffect(engine) {

        var first = true
        var lastSel = engine.selection
        var lastTick = engine.revealTick
        var lastVh = viewportHeight
        var lastVw = viewportWidth
        snapshotFlow {
            listOf(engine.revealTick, engine.selection, viewportHeight, viewportWidth, selectionDragActive || caretDragActive)
        }.collect {
            if (viewportHeight <= 0f) return@collect
            if (selectionDragActive || caretDragActive) return@collect

            val sel = engine.selection
            val tick = engine.revealTick
            val explicit = first || sel != lastSel || tick != lastTick
            val shrank = viewportHeight < lastVh || viewportWidth < lastVw
            first = false
            lastSel = sel
            lastTick = tick
            lastVh = viewportHeight
            lastVw = viewportWidth
            val scrolling = draggingHolder[0] ||
                    scrollbarClock.last.elapsedNow() < SCROLLBAR_QUIET_GRAB_MS.milliseconds
            if (!EditorGeometry.shouldRevealCaret(explicit, shrank, editorFocused.value, scrolling)) return@collect
            revealCaretIntoViewLive.value()
        }
    }

    val hitGutterWidth = rememberUpdatedState(gutterWidthPx)

    fun positionAtWithScroll(offset: Offset, sY: Float, sX: Float): TextPosition {
        val ln = lineAtPx(offset.y + sY)
        val localX = (offset.x - hitGutterWidth.value - padXPx + sX).coerceAtLeast(0f)
        if (isGridLine(ln)) {
            return TextPosition(ln, EditorGeometry.gridXToColumn(localX, charWpx, engine.buffer.lineLength(ln)))
        }
        val layout = layoutFor(ln)

        val shift = refBaselinePx - (layout?.firstBaseline ?: refBaselinePx)
        val layoutY = (offset.y + sY) - lineTopPx(ln) - shift
        val col = layout?.getOffsetForPosition(Offset(localX, layoutY)) ?: 0
        return TextPosition(ln, col.coerceAtMost(engine.buffer.lineLength(ln)))
    }

    fun positionAt(offset: Offset): TextPosition =
        positionAtWithScroll(offset, scrollY.coerceIn(0f, maxScrollY), scrollX.coerceIn(0f, maxScrollX))

    val positionAtLive = rememberUpdatedState<(Offset) -> TextPosition> { positionAt(it) }

    val readOnlyLive = rememberUpdatedState(readOnly)

    val clipboardActions = remember(engine, clipboard, clipboardScope) {
        EditorClipboardActions(engine, clipboard, clipboardScope) { readOnlyLive.value }
    }

    fun caretScreenColumnRect(pos: TextPosition): FloatArray? {
        val col = pos.column.coerceIn(0, engine.buffer.lineLength(pos.line))
        val sY = scrollY.coerceIn(0f, maxScrollY)
        val sX = scrollX.coerceIn(0f, maxScrollX)
        if (isGridLine(pos.line)) {
            val base = lineTopPx(pos.line) - sY + (refBaselinePx - gridRefBaseline)
            val x = gutterWidthPx + padXPx - sX + col * charWpx
            return floatArrayOf(x, base + gridRefCursor.top, base + gridRefCursor.bottom)
        }
        val layout = layoutFor(pos.line) ?: return null
        val cr = layout.getCursorRect(col)
        val base = lineTopPx(pos.line) - sY + (refBaselinePx - layout.firstBaseline)
        return floatArrayOf(gutterWidthPx + padXPx - sX + cr.left, base + cr.top, base + cr.bottom)
    }

    val caretRectLive = rememberUpdatedState<(TextPosition) -> FloatArray?> { caretScreenColumnRect(it) }

    val lineTopPxLive = rememberUpdatedState<(Int) -> Float> { lineTopPx(it) }
    val lineAtPxLive = rememberUpdatedState<(Float) -> Int> { lineAtPx(it) }
    val liveLineCount = rememberUpdatedState(lineCount)

    fun hitsVisibleTouchHandle(p: Offset): Boolean {
        if (lastInteractionWasMouse) return false
        fun hit(kind: HandleKind, at: TextPosition): Boolean {
            val r = caretRectLive.value(at) ?: return false
            return EditorGeometry.handleGeometry(kind, r[0], r[1], r[2], handleRadiusPx, handleSlopPx).hitContains(p.x, p.y)
        }

        val sel = engine.selection
        return if (!sel.isEmpty) {
            hit(HandleKind.SelectionEnd, sel.end) || hit(HandleKind.SelectionStart, sel.start)
        } else {
            caretHandleVisible && !readOnlyLive.value && hit(HandleKind.Caret, sel.start)
        }
    }

    val liveMaxScrollY = rememberUpdatedState(maxScrollY)
    val liveMaxScrollX = rememberUpdatedState(maxScrollX)

    val liveLineHeightPx = rememberUpdatedState(lineHeightPx)

    val liveContentHeight = rememberUpdatedState(contentHeight)
    val liveContentWidth = rememberUpdatedState(gutterWidthPx + padXPx * 2 + widestSeen[0])

    fun commitZoom(fontStart: Float, s: Float, tx: Float, ty: Float) {
        val newFont = ZoomMath.commitFontSize(fontStart, s, ZOOM_MIN_SP, ZOOM_MAX_SP)

        if (abs(newFont - fontStart) < ZOOM_MIN_CHANGE_SP && abs(tx) < 0.5f && abs(ty) < 0.5f) return
        val k = newFont / fontStart
        if (softWrap) {

            val topContentYOld = (scrollY - ty / s).coerceAtLeast(0f)
            zoomWrapAnchorPos = positionAtWithScroll(Offset(0f, 0f), topContentYOld, 0f)
            scrollY = ZoomMath.provisionalScrollYWrap(topContentYOld, k, 0f)
            scrollX = 0f
        } else {

            scrollX = (s * scrollX - tx).coerceAtLeast(0f)
            scrollY = (s * scrollY - ty).coerceAtLeast(0f)
        }
        fontSizeSp = newFont
        previewScale = 1f; previewTx = 0f; previewTy = 0f
    }

    val commitZoomLive =
        rememberUpdatedState<(Float, Float, Float, Float) -> Unit> { fontStart, s, tx, ty -> commitZoom(fontStart, s, tx, ty) }

    LaunchedEffect(selectionDragActive) {
        if (!selectionDragActive) return@LaunchedEffect
        while (true) {
            val pos = selectionDragPos ?: break
            val wordAnchor = selectionWordAnchor
            val charAnchor = selectionAnchor
            val lineAnchor = selectionLineAnchor
            if (wordAnchor == null && charAnchor == null && lineAnchor == null) break
            val maxY = liveMaxScrollY.value
            val maxX = liveMaxScrollX.value
            val stepY = edgeAutoScrollSpeed(pos.y, viewportHeight, lineHeightPx)
            var stepX = edgeAutoScrollSpeed(pos.x, viewportWidth, lineHeightPx)

            if (stepX != 0f && maxX > 0f) {
                val cur = positionAtWithScroll(pos, scrollY, scrollX)
                val lineLen = engine.buffer.lineLength(cur.line)
                if ((stepX > 0f && cur.column >= lineLen) || (stepX < 0f && cur.column <= 0)) stepX = 0f
            }
            val newY = if (stepY != 0f && maxY > 0f) (scrollY + stepY).coerceIn(0f, maxY) else scrollY
            var newX = if (stepX != 0f && maxX > 0f) (scrollX + stepX).coerceIn(0f, maxX) else scrollX

            if (!softWrap && maxX > 0f) {
                val c = positionAtWithScroll(pos, newY, newX)
                caretContentXOf(c.line, c.column)?.let { newX = revealScrollXFor(it, newX) }
            }
            if (newY != scrollY || newX != scrollX) {
                scrollY = newY
                scrollX = newX
                val caret = positionAtWithScroll(pos, newY, newX)
                when {
                    wordAnchor != null -> engine.selectWordRange(wordAnchor, caret)
                    lineAnchor != null -> {
                        val a = minOf(lineAnchor, caret.line)
                        val b = maxOf(lineAnchor, caret.line)
                        engine.setSelection(TextPosition(a, 0), TextPosition(b, engine.buffer.lineLength(b)))
                    }

                    else -> charAnchor?.let { engine.setSelection(it, caret) }
                }
            }
            withFrameNanos { }
        }
    }

    LaunchedEffect(caretDragActive) {
        if (!caretDragActive) return@LaunchedEffect
        while (true) {
            val pos = caretDragPos ?: break
            val maxY = liveMaxScrollY.value
            val maxX = liveMaxScrollX.value
            val stepY = edgeAutoScrollSpeed(pos.y, viewportHeight, lineHeightPx)
            var stepX = edgeAutoScrollSpeed(pos.x, viewportWidth, lineHeightPx)

            if (stepX != 0f && maxX > 0f) {
                val cur = positionAtWithScroll(pos, scrollY, scrollX)
                val lineLen = engine.buffer.lineLength(cur.line)
                if ((stepX > 0f && cur.column >= lineLen) || (stepX < 0f && cur.column <= 0)) stepX = 0f
            }
            val newY = if (stepY != 0f && maxY > 0f) (scrollY + stepY).coerceIn(0f, maxY) else scrollY
            var newX = if (stepX != 0f && maxX > 0f) (scrollX + stepX).coerceIn(0f, maxX) else scrollX

            if (!softWrap && maxX > 0f) {
                val c = positionAtWithScroll(pos, newY, newX)
                caretContentXOf(c.line, c.column)?.let { newX = revealScrollXFor(it, newX) }
            }
            if (newY != scrollY || newX != scrollX) {
                scrollY = newY
                scrollX = newX
                engine.setCursor(positionAtWithScroll(pos, newY, newX))
            }
            withFrameNanos { }
        }
    }

    var vGoalX by remember { mutableStateOf<Float?>(null) }
    var vGoalCaret by remember { mutableStateOf<TextPosition?>(null) }
    fun moveCaretVisual(dir: Int, extend: Boolean) {
        val from = engine.caret
        val layout = layoutFor(from.line) ?: run {
            engine.moveCaretVertically(dir, extend); return
        }
        val col = from.column.coerceIn(0, engine.buffer.lineLength(from.line))
        val curRow = layout.getLineForOffset(col)
        val goalX = if (vGoalX != null && vGoalCaret == from) vGoalX!! else layout.getCursorRect(col).left
        val step = EditorGeometry.visualVerticalTarget(from.line, curRow, dir, lineCount) { l -> layoutFor(l)?.lineCount ?: 1 }
            ?: return
        val targetLayout = if (step.line == from.line) layout else (layoutFor(step.line) ?: return)
        val yMid = (targetLayout.getLineTop(step.row) + targetLayout.getLineBottom(step.row)) / 2f
        val newCol = targetLayout.getOffsetForPosition(Offset(goalX, yMid))
            .coerceIn(0, engine.buffer.lineLength(step.line))
        val to = TextPosition(step.line, newCol)
        if (extend) engine.extendSelectionTo(to) else engine.setCursor(to)
        vGoalX = goalX
        vGoalCaret = to
    }

    val bottomBarInsets = if (!consumeSystemInsets) WindowInsets(0, 0, 0, 0) else WindowInsets.navigationBars
        .union(WindowInsets.captionBar)
        .union(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom)
    val showSymbolBar = !readOnly && symbols.isNotEmpty()

    Column(modifier.background(colors.background)) {

        GotoLineBar(
            session = gotoSession,
            lineCount = lineCount,
            colors = colors, controlTextStyle = controlTextStyle,
            onRequestEditorFocus = { focusRequester.requestFocus() },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)

                .then(if (showSymbolBar) Modifier else Modifier.windowInsetsPadding(bottomBarInsets))
                .clipToBounds()
                .overscroll(overscroll)
                .onSizeChanged { viewportWidth = it.width.toFloat(); viewportHeight = it.height.toFloat() }
                .onGloballyPositioned { contentTopInWindow = it.positionInWindow().y }

                .then(
                    if (scrollbarShownState.value) {
                        Modifier.editorRightEdgeGestureExclusion(with(density) { SCROLLBAR_HOT_ZONE.toPx() })
                    } else Modifier
                )
                .scrollable2D(scroll2D, overscrollEffect = overscroll, interactionSource = scrollInteraction)

                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()

                            if (event.type == PointerEventType.Move &&
                                event.changes.none { it.pressed } &&
                                event.changes.firstOrNull()?.type == PointerType.Mouse
                            ) {
                                val inZone = event.changes.first().position.x >= size.width - SCROLLBAR_HOT_ZONE.toPx()
                                if (inZone != scrollbarClock.hovering) {
                                    scrollbarClock.hovering = inZone
                                    if (inZone) markUserScroll()
                                }
                            }
                            if (event.type != PointerEventType.Scroll) continue
                            var dx = 0f
                            var dy = 0f
                            event.changes.forEach { dx += it.scrollDelta.x; dy += it.scrollDelta.y }
                            if (dx == 0f && dy == 0f) continue
                            markUserScroll()
                            val step = liveLineHeightPx.value * 3f
                            scrollY = (scrollY + dy * step).coerceIn(0f, liveMaxScrollY.value)
                            scrollX = (scrollX + dx * step).coerceIn(0f, liveMaxScrollX.value)

                            showTouchMenu = false
                            showContextMenu = false
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .pointerInput(Unit) {

                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var active = false
                        var committed = false
                        var fontStart = fontSizeSp
                        var prevCentroid = Offset.Unspecified
                        var freeTx = 0f
                        var freeTy = 0f
                        var panLast = Offset.Unspecified
                        var panActive = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                panLast = Offset.Unspecified; panActive = false

                                val c = event.calculateCentroid(useCurrent = true)
                                if (!active) {

                                    active = true; committed = false
                                    fontStart = fontSizeSp
                                    previewScale = 1f; previewTx = 0f; previewTy = 0f
                                    freeTx = 0f; freeTy = 0f
                                    prevCentroid = Offset.Unspecified
                                } else if (c != Offset.Unspecified) {
                                    if (prevCentroid == Offset.Unspecified) {
                                        prevCentroid = c
                                    } else {

                                        val newScale = ZoomMath.clampScaleToFontRange(
                                            previewScale * event.calculateZoom(),
                                            fontStart,
                                            ZOOM_MIN_SP,
                                            ZOOM_MAX_SP
                                        )
                                        val ez = if (previewScale > 0f) newScale / previewScale else 1f
                                        if (!softWrap) freeTx = ez * freeTx + (c.x - ez * prevCentroid.x)
                                        freeTy = ez * freeTy + (c.y - ez * prevCentroid.y)
                                        previewScale = newScale
                                        prevCentroid = c

                                        val vw = viewportWidth
                                        val vh = viewportHeight
                                        if (!softWrap) {
                                            val maxSX = (newScale * liveContentWidth.value - vw).coerceAtLeast(0f)
                                            previewTx = newScale * scrollX - (newScale * scrollX - freeTx).coerceIn(0f, maxSX)
                                        }

                                        val maxSY =
                                            (newScale * liveContentHeight.value - vh + vh * BOTTOM_SCROLL_PAD_FRACTION).coerceAtLeast(0f)
                                        previewTy = newScale * scrollY - (newScale * scrollY - freeTy).coerceIn(0f, maxSY)
                                    }
                                }

                                event.changes.forEach { it.consume() }
                            } else if (active && !committed) {

                                commitZoomLive.value(fontStart, previewScale, previewTx, previewTy)
                                committed = true; active = false
                                val p = event.changes.firstOrNull { it.pressed }
                                panLast = p?.position ?: Offset.Unspecified

                                event.changes.forEach { it.consume() }
                            } else if (committed && pressed >= 1) {

                                val p = event.changes.firstOrNull { it.pressed }
                                if (p != null) {
                                    if (panLast != Offset.Unspecified) {
                                        val d = p.position - panLast
                                        if (!panActive) {

                                            if (d.getDistance() > viewConfiguration.touchSlop) {
                                                panActive = true; panLast = p.position
                                            }
                                        } else {
                                            markUserScroll()
                                            scrollX = (scrollX - d.x).coerceIn(0f, liveMaxScrollX.value)
                                            scrollY = (scrollY - d.y).coerceIn(0f, liveMaxScrollY.value)
                                            panLast = p.position
                                        }
                                    } else panLast = p.position
                                    p.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (active && !committed) commitZoomLive.value(fontStart, previewScale, previewTx, previewTy)
                    }
                }

                .editorTextInput(
                    engine,
                    enabled = !readOnly,
                    caretRectInEditor = {
                        caretRectLive.value(engine.caret)?.let { r -> Rect(r[0], r[1], r[0] + 1f, r[2]) }
                    },
                )
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val shift = ev.isShiftPressed

                    resolveEditorKeyCommand(ev)?.let { cmd ->
                        when (cmd) {

                            EditorKeyCommand.SelectAll -> clipboardActions.perform(EditorContextAction.SelectAll)
                            EditorKeyCommand.Copy -> clipboardActions.perform(EditorContextAction.Copy)
                            EditorKeyCommand.Cut -> clipboardActions.perform(EditorContextAction.Cut)
                            EditorKeyCommand.Paste -> clipboardActions.perform(EditorContextAction.Paste)
                            EditorKeyCommand.Undo -> clipboardActions.perform(EditorContextAction.Undo)
                            EditorKeyCommand.Redo -> clipboardActions.perform(EditorContextAction.Redo)

                            EditorKeyCommand.Find -> {
                                gotoSession.close(); onFindRequested()
                            }

                            EditorKeyCommand.Replace -> {
                                gotoSession.close(); onFindRequested()
                            }

                            EditorKeyCommand.FindNext -> onFindNext()
                            EditorKeyCommand.FindPrev -> onFindPrevious()
                            EditorKeyCommand.GotoLine -> {
                                onGotoLineRequested()
                            }

                            EditorKeyCommand.ToggleComment -> if (!readOnlyLive.value) {
                                val linePrefix = resolvedHighlighter?.lineCommentPrefix
                                if (linePrefix != null) engine.toggleLineComment(linePrefix)
                                else resolvedHighlighter?.blockComment?.let { engine.toggleBlockComment(it.open, it.close) }
                            }

                            EditorKeyCommand.WordLeft -> engine.moveCaretByWord(-1, shift)
                            EditorKeyCommand.WordRight -> engine.moveCaretByWord(1, shift)
                            EditorKeyCommand.LineStart -> engine.moveCaretToLineStart(shift)
                            EditorKeyCommand.LineEnd -> engine.moveCaretToLineEnd(shift)
                            EditorKeyCommand.DocStart -> engine.moveCaretToDocStart(shift)
                            EditorKeyCommand.DocEnd -> engine.moveCaretToDocEnd(shift)
                            EditorKeyCommand.PageUp -> engine.movePage(-1, pageLines(), shift)
                            EditorKeyCommand.PageDown -> engine.movePage(1, pageLines(), shift)
                        }
                        return@onKeyEvent true
                    }

                    when (ev.key) {

                        Key.Escape -> when {

                            gotoSession.visible -> {
                                gotoSession.close(); true
                            }

                            else -> false
                        }

                        Key.DirectionLeft -> {
                            engine.moveCaretHorizontally(-1, shift); true
                        }

                        Key.DirectionRight -> {
                            engine.moveCaretHorizontally(1, shift); true
                        }

                        Key.DirectionUp -> {
                            if (softWrap) moveCaretVisual(-1, shift) else engine.moveCaretVertically(-1, shift); true
                        }

                        Key.DirectionDown -> {
                            if (softWrap) moveCaretVisual(1, shift) else engine.moveCaretVertically(1, shift); true
                        }

                        Key.Backspace -> {
                            if (!readOnly) engine.backspace(); true
                        }

                        Key.Delete -> {
                            if (!readOnly) engine.deleteForward(); true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            if (!readOnly) engine.insertNewlineAutoIndent(); true
                        }

                        Key.Tab -> if (readOnly) false else {
                            when {
                                shift -> engine.outdentSelectedLines()
                                engine.selStart != engine.selEnd -> engine.indentSelectedLines()
                                else -> engine.insert(EditorEngine.INDENT_UNIT)
                            }
                            true
                        }

                        else -> insertTypedCharacter(engine, ev, readOnly)
                    }
                }
                .focusRequester(focusRequester)
                .focusable(interactionSource = interaction)

                .pointerInput(engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        if (down.type == PointerType.Mouse) return@awaitEachGesture
                        lastInteractionWasMouse = false
                        showTouchMenu = false
                        showContextMenu = false

                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        } ?: return@awaitEachGesture
                        val tapPos = positionAtLive.value(up.position)

                        val onExistingCaret = engine.selection.isEmpty && engine.caret == tapPos
                        engine.setCursor(tapPos)
                        focusRequester.requestFocus()
                        if (!readOnlyLive.value) {
                            engine.requestShowKeyboard?.invoke()
                            pingCaretHandle()
                        }
                        showTouchMenu = onExistingCaret
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        val up2 = if (second != null) waitForUpOrCancellation() else null
                        if (up2 != null) {
                            val pos2 = positionAtLive.value(up2.position)

                            if ((up2.position - up.position).getDistance() <= viewConfiguration.touchSlop) {
                                val w = engine.wordRangeAt(pos2)
                                engine.setSelection(w.start, w.end)
                                focusRequester.requestFocus()
                                showTouchMenu = true
                            } else {
                                engine.setCursor(pos2)
                                focusRequester.requestFocus()
                                showTouchMenu = false
                            }
                        }
                    }
                }
                .pointerInput(engine) {

                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->

                            if (!lastInteractionWasMouse && !scrollbarDragging.value) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val w = engine.wordRangeAt(positionAtLive.value(p))
                                engine.setSelection(w.start, w.end)

                                selectionWordAnchor = w
                                selectionAnchor = null
                                focusRequester.requestFocus()
                                selectionDragPos = p
                                selectionDragActive = true
                            }
                        },
                        onDrag = { change, _ ->
                            selectionDragPos = change.position
                            selectionWordAnchor?.let { engine.selectWordRange(it, positionAtLive.value(change.position)) }
                        },
                        onDragEnd = { selectionDragPos = null; selectionDragActive = false; selectionWordAnchor = null },
                        onDragCancel = { selectionDragPos = null; selectionDragActive = false; selectionWordAnchor = null },
                    )
                }

                .pointerInput(engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type == PointerType.Mouse) return@awaitEachGesture
                        val p = down.position
                        val sel = engine.selection
                        fun hit(kind: HandleKind, at: TextPosition): Boolean {
                            val r = caretRectLive.value(at) ?: return false
                            return EditorGeometry.handleGeometry(kind, r[0], r[1], r[2], handleRadiusPx, handleSlopPx)
                                .hitContains(p.x, p.y)
                        }

                        var kind: HandleKind? = null
                        var anchor: TextPosition? = null
                        var grabbed: TextPosition? = null
                        if (!sel.isEmpty) {
                            when {
                                hit(HandleKind.SelectionEnd, sel.end) -> {
                                    kind = HandleKind.SelectionEnd; anchor = sel.start; grabbed = sel.end
                                }

                                hit(HandleKind.SelectionStart, sel.start) -> {
                                    kind = HandleKind.SelectionStart; anchor = sel.end; grabbed = sel.start
                                }
                            }
                        } else if (caretHandleVisible && !readOnlyLive.value && hit(HandleKind.Caret, sel.start)) {
                            kind = HandleKind.Caret; grabbed = sel.start
                        }
                        if (kind == null) return@awaitEachGesture

                        down.consume()
                        lastInteractionWasMouse = false
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                        val gr = grabbed?.let { caretRectLive.value(it) }
                        val grabDy = if (gr != null) (gr[1] + gr[2]) / 2f - p.y else 0f
                        fun mapped(o: Offset): TextPosition = positionAtLive.value(Offset(o.x, o.y + grabDy))

                        if (kind == HandleKind.Caret) {
                            engine.setCursor(mapped(p)); pingCaretHandle()

                            val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                            if (slop != null) {

                                var lastHaptic = engine.caret
                                caretDragPos = Offset(slop.position.x, slop.position.y + grabDy)
                                engine.setCursor(mapped(slop.position))
                                if (engine.caret != lastHaptic) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                }
                                caretDragActive = true
                                handleDragActive = true
                                drag(down.id) { change ->
                                    caretDragPos = Offset(change.position.x, change.position.y + grabDy)
                                    engine.setCursor(mapped(change.position))
                                    if (engine.caret != lastHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                    }
                                    change.consume()
                                }
                                caretDragActive = false
                                handleDragActive = false
                                caretDragPos = null
                            } else {

                                if (!readOnlyLive.value) engine.requestShowKeyboard?.invoke()
                                showTouchMenu = true
                            }
                            pingCaretHandle()
                        } else {
                            val a = anchor!!

                            val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                            if (slop != null) {
                                selectionAnchor = a
                                selectionWordAnchor = null

                                selectionDragPos = Offset(slop.position.x, slop.position.y + grabDy)
                                selectionDragActive = true
                                handleDragActive = true

                                var lastHaptic = grabbed
                                engine.setSelection(a, mapped(slop.position))
                                if (engine.caret != lastHaptic) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                }
                                drag(down.id) { change ->
                                    selectionDragPos = Offset(change.position.x, change.position.y + grabDy)
                                    engine.setSelection(a, mapped(change.position))
                                    if (engine.caret != lastHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                    }
                                    change.consume()
                                }
                                selectionDragPos = null
                                selectionDragActive = false
                                handleDragActive = false
                            }
                        }
                    }
                }

                .pointerInput(engine) {

                    var lastClickTime = 0L
                    var lastClickPos = Offset.Zero
                    var clickCount = 0
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        if (down.type != PointerType.Mouse) return@awaitEachGesture
                        down.consume()
                        lastInteractionWasMouse = true
                        showTouchMenu = false

                        val shift = currentEvent.keyboardModifiers.isKeyboardShiftPressed
                        val downPos = down.position
                        val clickPos = positionAtLive.value(downPos)

                        if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                        focusRequester.requestFocus()
                        showContextMenu = false

                        val now = down.uptimeMillis
                        val within = (now - lastClickTime) <= viewConfiguration.doubleTapTimeoutMillis &&
                                (downPos - lastClickPos).getDistance() <= viewConfiguration.touchSlop
                        clickCount = if (within) clickCount + 1 else 1
                        if (clickCount > 3) clickCount = 1
                        lastClickTime = now
                        lastClickPos = downPos

                        var charAnchor: TextPosition? = null
                        var wordAnchor: TextRange? = null
                        var lineAnchor: Int? = null
                        when {

                            shift -> {
                                val fixed =
                                    if (engine.caret == engine.selection.end) engine.selection.start else engine.selection.end
                                engine.setSelection(fixed, clickPos)
                                charAnchor = fixed
                            }

                            clickCount >= 3 -> {
                                val line = clickPos.line
                                engine.setSelection(TextPosition(line, 0), TextPosition(line, engine.buffer.lineLength(line)))
                                lineAnchor = line
                            }

                            clickCount == 2 -> {
                                val w = engine.wordRangeAt(clickPos)
                                engine.setSelection(w.start, w.end)
                                wordAnchor = w
                            }

                            else -> {
                                engine.setCursor(clickPos)
                                charAnchor = clickPos
                            }
                        }

                        val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                        if (slop != null) {
                            val cAnchor = charAnchor
                            val wAnchor = wordAnchor
                            val lAnchor = lineAnchor
                            fun extendTo(o: Offset) {
                                val c = positionAtLive.value(o)
                                when {
                                    wAnchor != null -> engine.selectWordRange(wAnchor, c)
                                    lAnchor != null -> {
                                        val a = minOf(lAnchor, c.line)
                                        val b = maxOf(lAnchor, c.line)
                                        engine.setSelection(TextPosition(a, 0), TextPosition(b, engine.buffer.lineLength(b)))
                                    }

                                    else -> engine.setSelection(cAnchor ?: c, c)
                                }
                            }

                            selectionAnchor = cAnchor
                            selectionWordAnchor = wAnchor
                            selectionLineAnchor = lAnchor
                            selectionDragPos = slop.position
                            selectionDragActive = true
                            extendTo(slop.position)
                            drag(down.id) { change ->
                                selectionDragPos = change.position
                                extendTo(change.position)
                                change.consume()
                            }
                            selectionDragPos = null
                            selectionDragActive = false
                            selectionAnchor = null
                            selectionWordAnchor = null
                            selectionLineAnchor = null
                        }
                    }
                }

                .pointerInput(engine) {
                    awaitPointerEventScope {
                        var wasSecondary = false
                        var anchor = Offset.Zero
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull()
                            if (ch == null || ch.type != PointerType.Mouse) {
                                wasSecondary = false
                                continue
                            }
                            val isSecondary = e.buttons.isSecondaryPressed
                            if (isSecondary && !wasSecondary) {

                                e.changes.forEach { it.consume() }
                                lastInteractionWasMouse = true
                                showTouchMenu = false
                                anchor = ch.position
                                val p = positionAtLive.value(ch.position)
                                val sel = engine.selection
                                if (sel.isEmpty || p < sel.start || p > sel.end) engine.setCursor(p)
                            } else if (!isSecondary && wasSecondary) {

                                e.changes.forEach { it.consume() }
                                contextMenuAnchor = anchor
                                showContextMenu = true
                            }
                            wasSecondary = isSecondary
                        }
                    }
                }

                .pointerInput(engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!scrollbarClock.shown || scrollbarClock.fading) return@awaitEachGesture
                        if (scrollbarClock.last.elapsedNow() < SCROLLBAR_QUIET_GRAB_MS.milliseconds) return@awaitEachGesture
                        val vh = viewportHeight
                        val maxY = liveMaxScrollY.value
                        val thumbH = ScrollbarMath.thumbHeight(vh, maxY, SCROLLBAR_MIN_THUMB.toPx())
                        if (thumbH <= 0f) return@awaitEachGesture
                        val thumbTop = ScrollbarMath.thumbTop(vh, maxY, thumbH, scrollY)
                        val hit = ScrollbarMath.hitThumb(
                            down.position.x, down.position.y,
                            viewportWidth, SCROLLBAR_HOT_ZONE.toPx(), thumbTop, thumbH, SCROLLBAR_GRAB_SLACK.toPx(),
                        )
                        if (!hit) return@awaitEachGesture
                        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        if (hitsVisibleTouchHandle(down.position)) return@awaitEachGesture
                        down.consume()
                        scrollbarDragging.value = true
                        scrollbarThumbTopOverride.floatValue = thumbTop
                        val grabOffset = down.position.y - thumbTop

                        val grabLine = lineAtPxLive.value(scrollY)
                        val downY = down.position.y
                        var dragStarted = false
                        fun applyDrag(y: Float) {
                            val target = ScrollbarMath.dragTargetLine(grabLine, downY, y, vh, thumbH, liveLineCount.value)
                            scrollY = lineTopPxLive.value(target).coerceIn(0f, liveMaxScrollY.value)
                            scrollbarThumbTopOverride.floatValue = (y - grabOffset).coerceIn(0f, vh - thumbH)
                            scrollbarClock.last = TimeSource.Monotonic.markNow()
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) break
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                break
                            }

                            if (!dragStarted && abs(ch.position.y - downY) > viewConfiguration.touchSlop) dragStarted = true
                            if (dragStarted) applyDrag(ch.position.y)
                            ch.consume()
                        }
                        scrollbarDragging.value = false
                        scrollbarThumbTopOverride.floatValue = -1f
                        markUserScroll()
                    }
                }

                .pointerHoverIcon(PointerIcon.Text)
        ) {
            EditorCanvas(
                engine = engine,
                colors = colors,
                textMeasurer = measurer,
                textStyle = textStyle,
                numberStyle = numberStyle,
                lineHeightPx = lineHeightPx,
                gutterWidthPx = gutterWidthPx,
                padXPx = padXPx,
                lineNumberMode = lineNumberMode,
                scrollX = { scrollX.coerceIn(0f, maxScrollX) },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },

                firstVisibleLine = { extraTopPx -> (lineAtPx(scrollY.coerceIn(0f, maxScrollY) + extraTopPx) - 3).coerceAtLeast(0) },
                lineTopPx = ::lineTopPx,
                refBaselinePx = refBaselinePx,

                caretHandleVisible = { caretHandleVisible && !readOnly && !lastInteractionWasMouse && editorFocused.value },
                selectionHandlesVisible = { !lastInteractionWasMouse },
                handleRadiusPx = handleRadiusPx,
                layoutFor = ::layoutFor,
                charW = charWpx,
                isGridLine = ::isGridLine,
                gridRefBaseline = gridRefBaseline,
                gridRefCursorTop = gridRefCursor.top,
                gridRefCursorBottom = gridRefCursor.bottom,

                bracketMatch = { bracketMatchState.value },
                highlightCache = highlightCache,

                previewScale = { previewScale },
                previewTx = { previewTx },
                previewTy = { previewTy },
                modifier = Modifier.fillMaxSize(),
            )

            CursorOverlay(
                engine = engine,
                colors = colors,

                caretVisible = { !readOnly && blink && editorFocused.value },
                scrollX = { scrollX.coerceIn(0f, maxScrollX) },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },
                lineTopPx = ::lineTopPx,
                refBaselinePx = refBaselinePx,
                layoutFor = ::layoutFor,
                charW = charWpx,
                isGridLine = ::isGridLine,
                gridRefBaseline = gridRefBaseline,
                gridRefCursorTop = gridRefCursor.top,
                gridRefCursorBottom = gridRefCursor.bottom,
                gutterWidthPx = gutterWidthPx,
                padXPx = padXPx,
                previewScale = { previewScale },
                modifier = Modifier.fillMaxSize(),
            )

            ScrollbarOverlay(
                colors = colors,
                alpha = { scrollbarAlpha.value },
                dragging = { scrollbarDragging.value },
                thumbTopOverridePx = { scrollbarThumbTopOverride.floatValue },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },
                maxScrollY = { liveMaxScrollY.value },
                minThumbPx = with(density) { SCROLLBAR_MIN_THUMB.toPx() },
                modifier = Modifier.fillMaxSize(),
            )

            MagnifierOverlay(
                engine = engine,
                colors = colors,
                active = { handleDragActive },

                dragPos = { caretDragPos ?: selectionDragPos },
                caretVisible = { !readOnly && blink && editorFocused.value },
                viewportWidth = { viewportWidth },
                contentTopInWindow = { contentTopInWindow },
                scrollX = { scrollX.coerceIn(0f, maxScrollX) },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },
                lineTopPx = ::lineTopPx,
                lineHeightPx = lineHeightPx,
                refBaselinePx = refBaselinePx,
                layoutFor = ::layoutFor,
                textMeasurer = measurer,
                textStyle = textStyle,
                numberStyle = numberStyle,
                charW = charWpx,
                isGridLine = ::isGridLine,
                gridRefBaseline = gridRefBaseline,
                gridRefCursorTop = gridRefCursor.top,
                gridRefCursorBottom = gridRefCursor.bottom,
                gutterWidthPx = gutterWidthPx,
                padXPx = padXPx,
                highlightCache = highlightCache,
            )

            SelectionActionToolbar(
                engine = engine,
                colors = colors,
                show = { showTouchMenu && !lastInteractionWasMouse && !handleDragActive && !selectionDragActive },
                readOnly = readOnly,
                posRect = { caretRectLive.value(it) },
                onPerform = { clipboardActions.perform(it); showTouchMenu = false },
            )

            EditorContextMenu(
                engine = engine,
                colors = colors,
                readOnly = readOnly,
                show = { showContextMenu },
                anchor = { contextMenuAnchor },
                onDismiss = { showContextMenu = false },
                onPerform = { clipboardActions.perform(it); showContextMenu = false },
            )
        }

        if (showSymbolBar) {
            SymbolBar(
                symbols = symbols,
                colors = colors,
                onSymbol = { symbol -> engine.typeCharacter(symbol.value) },
                controlTextStyle = controlTextStyle,
                windowInsets = bottomBarInsets,
            )
        }
    }
}

private fun edgeAutoScrollSpeed(pos: Float, extent: Float, unit: Float): Float {
    if (extent <= 0f) return 0f
    val hot = unit * 2.5f
    val maxStep = unit * 0.6f
    return when {
        pos < hot -> -maxStep * ((hot - pos) / hot).coerceIn(0f, 3f)
        pos > extent - hot -> maxStep * ((pos - (extent - hot)) / hot).coerceIn(0f, 3f)
        else -> 0f
    }
}
