package com.reamicro.fix.hook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable internal fun BoxScope.ReaderSearchFastScroller(
    state: LazyListState, palette: StructureHome130Style.Palette, scale: Float,
) {
    val count by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (count <= 20) return
    val fraction by remember(state) { derivedStateOf {
        SearchListScrollPosition.fraction(state.firstVisibleItemIndex, state.layoutInfo.totalItemsCount,
            !state.canScrollForward)
    } }
    val latestCount by rememberUpdatedState(count)
    val commands = remember(state) { Channel<Int>(Channel.CONFLATED) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val listDragging by state.interactionSource.collectIsDraggedAsState()
    var manualScroll by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(listDragging, scrolling, dragging) {
        if (listDragging) manualScroll = true
        else if (!scrolling && !dragging) manualScroll = false
    }
    val visible = searchThumbVisible(listDragging, dragging, manualScroll, scrolling)
    var height by remember { mutableIntStateOf(0) }
    val thumbHeight = with(LocalDensity.current) { (48 * scale).dp.toPx() }
    val travel = (height - thumbHeight).coerceAtLeast(1f)
    fun move(value: Float) {
        if (!value.isFinite()) return
        drag = value.coerceIn(0f, 1f)
        commands.trySend(SearchListScrollPosition.index(drag!!, latestCount))
    }
    LaunchedEffect(state, commands) {
        for (index in commands) {
            val total = state.layoutInfo.totalItemsCount
            if (total > 0) coroutineScope {

                launch { state.scrollToItem(index.coerceIn(0, total - 1)) }.join()
            }
            if (!dragging) drag = null
        }
    }
    DisposableEffect(commands) { onDispose { commands.close() } }
    if (!visible) return
    Box(Modifier.align(Alignment.CenterEnd).padding(vertical = (4 * scale).dp)
        .width(32.dp).fillMaxHeight().onSizeChanged { height = it.height }
        .semantics {
            contentDescription = "搜索结果快速滚动"
            progressBarRangeInfo = ProgressBarRangeInfo(drag ?: fraction, 0f..1f)
            setProgress { move(it); true }
        }
        .pointerInput(state, travel) {
            detectVerticalDragGestures(
                onDragStart = { dragging = true; move((it.y - thumbHeight / 2) / travel) },
                onDragEnd = { dragging = false; move(drag ?: fraction) },
                onDragCancel = { dragging = false; drag = null },
            ) { change, amount ->
                change.consume()
                move((drag ?: fraction) + amount / travel)
            }
        }) {
        Column(Modifier.align(Alignment.TopCenter)
            .offset { IntOffset(0, ((drag ?: fraction) * travel).roundToInt()) }
            .size((16 * scale).dp, (48 * scale).dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape).border((.5f * scale).dp, palette.borderVariant, CircleShape),
            verticalArrangement = Arrangement.spacedBy((4 * scale).dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(2) { Box(Modifier.size((6 * scale).dp, (1 * scale).dp).background(palette.borderVariant)) }
        }
    }
}
