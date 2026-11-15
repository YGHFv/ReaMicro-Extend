package com.reamicro.fix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * The caller owns the displayed order and persistence. Only task keys belong in [orderedKeys];
 * surrounding summary/note items in the LazyColumn remain stationary.
 */
@Composable
internal fun rememberModuleTaskReorderState(
    listState: LazyListState,
    orderedKeys: List<String>,
    onMove: (String, String) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    onDragFinished: () -> Unit,
    enabled: () -> Boolean = { true },
): ModuleTaskReorderState {
    val keys = rememberUpdatedState(orderedKeys)
    val move = rememberUpdatedState(onMove)
    val dragStateChange = rememberUpdatedState(onDragStateChange)
    val dragFinished = rememberUpdatedState(onDragFinished)
    val canStart = rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val state = remember(listState) {
        ModuleTaskReorderState(listState, keys, move, dragStateChange, dragFinished, canStart, scope)
    }
    SideEffect { state.density = density }
    LaunchedEffect(state, orderedKeys) { state.validateKeys() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/**
 * Apply to the outer, single layout node of a keyed LazyColumn task item. While [isLifted] is true,
 * disable that item's animateItem placement animation so it cannot fight the finger translation.
 */
@Stable
internal class ModuleTaskReorderState internal constructor(
    private val listState: LazyListState,
    private val orderedKeys: State<List<String>>,
    private val onMove: State<(String, String) -> Unit>,
    private val onDragStateChange: State<(Boolean) -> Unit>,
    private val onDragFinished: State<() -> Unit>,
    private val enabled: State<() -> Boolean>,
    private val scope: CoroutineScope,
) {
    var draggedKey by mutableStateOf<String?>(null)
        private set

    private var settlingKey by mutableStateOf<String?>(null)
    private var draggedTop by mutableFloatStateOf(0f)
    private var drag: Drag? = null
    private var settling: Settling? = null
    private var driverJob: Job? = null
    private var settlingJob: Job? = null
    private val items = mutableMapOf<String, ModuleTaskReorderItem>()

    internal var density = 1f

    fun isLifted(key: String): Boolean = draggedKey == key || settlingKey == key

    internal fun isDragging(key: String): Boolean = drag?.key == key

    internal fun registerItem(key: String, item: ModuleTaskReorderItem) {
        items[key] = item
    }

    internal fun unregisterItem(key: String, item: ModuleTaskReorderItem) {
        if (items[key] === item) {
            items.remove(key)
            if (drag?.key == key) finish(key, animate = false)
            if (settlingKey == key) clearSettling()
        }
    }

    /** Pointer positions are in the stable list's space, never in the translated item's space. */
    internal fun startAt(position: Offset): String? {
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { info ->
            info.key is String && info.key in orderedKeys.value &&
                position.y >= info.offset - layout.viewportStartOffset &&
                position.y < info.offset - layout.viewportStartOffset + info.size
        } ?: return null
        val key = row.key as String
        val item = items[key] ?: return null
        val grab = position.y - (row.offset - layout.viewportStartOffset)
        return key.takeIf { start(it, grab, item.container, item.placement) }
    }

    internal fun translation(key: String, placement: ModuleTaskItemPlacement): Float {
        val active = draggedKey == key
        val returning = settlingKey == key
        if (!active && !returning) return 0f
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        val measuredTop = item?.let { it.offset - layout.viewportStartOffset.toFloat() }
            ?: placement.top
            ?: return 0f
        return if (active) {
            draggedTop - measuredTop
        } else {
            settling?.let { (it.screenTop - measuredTop) * it.progress.value } ?: 0f
        }
    }

    internal fun start(
        key: String,
        grabOffset: Float,
        container: PinnableContainer?,
        placement: ModuleTaskItemPlacement,
    ): Boolean {
        if (drag != null || !enabled.value() || key !in orderedKeys.value) return false
        val layout = listState.layoutInfo
        if (layout.reverseLayout || layout.orientation != Orientation.Vertical) return false
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        val screenTop = item.offset - layout.viewportStartOffset.toFloat() + translation(key, placement)
        clearSettling()
        val owned = Drag(key, grabOffset, Pin(container?.pin()), placement)
        drag = owned
        draggedTop = screenTop
        draggedKey = key
        onDragStateChange.value(true)

        // One driver owns the whole gesture. Opening a short scroll transaction per frame leaves
        // requestScrollToItem free to preserve the viewport anchor when its first item is swapped.
        driverJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                listState.stopScroll(MutatePriority.PreventUserInput)
                var previousFrame = withFrameNanos { it }
                while (drag === owned) {
                    val frame = withFrameNanos { it }
                    val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.032f)
                    previousFrame = frame
                    validateKeys()
                    if (drag !== owned) break
                    moveIfNeeded(owned)
                    if (drag !== owned || owned.pending != null) continue
                    val velocity = edgeVelocity(listState.layoutInfo, owned)
                    if (velocity == 0f) continue
                    listState.scroll(MutatePriority.PreventUserInput) { scrollBy(velocity * seconds) }
                    if (drag === owned) moveIfNeeded(owned)
                }
            } finally {
                if (drag === owned) finish(owned.key)
            }
        }
        return true
    }

    internal fun dragBy(key: String, amount: Float) {
        val owned = drag?.takeIf { it.key == key } ?: return
        draggedTop += amount
        moveIfNeeded(owned)
    }

    internal fun finish(key: String, animate: Boolean = true) {
        val owned = drag?.takeIf { it.key == key } ?: return
        val screenTop = draggedTop
        drag = null
        draggedKey = null
        driverJob?.cancel()
        driverJob = null

        if (animate && key in orderedKeys.value &&
            (listState.layoutInfo.visibleItemsInfo.any { it.key == key } || owned.placement.top != null)
        ) {
            val returning = Settling(key, screenTop, owned.pin)
            settling = returning
            settlingKey = key
            settlingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    returning.progress.animateTo(
                        0f,
                        tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    )
                } finally {
                    if (settling === returning) {
                        settling = null
                        settlingKey = null
                    }
                    returning.pin.release()
                }
            }
        } else {
            owned.pin.release()
        }
        onDragStateChange.value(false)
        // Canceled gestures retain and save the moves already shown to the user.
        onDragFinished.value()
    }

    internal fun validateKeys() {
        drag?.takeIf { it.key !in orderedKeys.value }?.let { finish(it.key, animate = false) }
        settling?.takeIf { it.key !in orderedKeys.value }?.let { clearSettling() }
    }

    internal fun dispose() {
        drag?.let { finish(it.key, animate = false) }
        clearSettling()
        items.clear()
    }

    private fun clearSettling() {
        settlingJob?.cancel()
        settlingJob = null
        settling?.pin?.release()
        settling = null
        settlingKey = null
    }

    private fun moveIfNeeded(owned: Drag) {
        if (drag !== owned) return
        val keys = orderedKeys.value
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == owned.key }
        val from = keys.indexOf(owned.key)
        if (from < 0) {
            finish(owned.key, animate = false)
            return
        }
        owned.pending?.let { pending ->
            val target = layout.visibleItemsInfo.firstOrNull { it.key == pending.target }
            val targetOrder = keys.indexOf(pending.target)
            val orderApplied = targetOrder >= 0 && (from > targetOrder) == pending.down
            val layoutApplied = item != null && target != null &&
                (item.index > target.index) == pending.down
            if (layout !== pending.layout && orderApplied && layoutApplied) {
                owned.pending = null
            } else {
                // An ignored callback or a removed target must not leave input locked indefinitely.
                if (targetOrder < 0 || System.nanoTime() > pending.deadline) finish(owned.key)
                return
            }
        }

        val itemSize = item?.size ?: owned.placement.height
        val measuredTop = item?.let { it.offset - layout.viewportStartOffset.toFloat() }
            ?: owned.placement.top
            ?: return
        val desiredCenter = draggedTop + itemSize / 2f
        val measuredCenter = measuredTop + itemSize / 2f
        val direction = when {
            desiredCenter > measuredCenter + density -> 1
            desiredCenter < measuredCenter - density -> -1
            else -> return
        }
        val targetKey = keys.getOrNull(from + direction) ?: return
        val target = layout.visibleItemsInfo.firstOrNull { it.key == targetKey } ?: return
        val targetCenter = target.offset - layout.viewportStartOffset + target.size / 2f
        val crossed = if (direction > 0) desiredCenter > targetCenter + density * 2f
        else desiredCenter < targetCenter - density * 2f
        if (!crossed) return

        owned.pending = PendingMove(
            target = targetKey,
            down = direction > 0,
            layout = layout,
            deadline = System.nanoTime() + 1_000_000_000L,
        )
        preserveFirstVisibleAnchor(item, target)
        onMove.value(owned.key, targetKey)
    }

    private fun preserveFirstVisibleAnchor(item: LazyListItemInfo?, target: LazyListItemInfo) {
        val first = listState.firstVisibleItemIndex
        if (item?.index == first || target.index == first) {
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
    }

    private fun edgeVelocity(layout: LazyListLayoutInfo, owned: Drag): Float {
        val top = layout.beforeContentPadding.toFloat()
        val bottom = (layout.viewportEndOffset - layout.viewportStartOffset -
            layout.afterContentPadding).toFloat()
        val edge = min(56f * density, (bottom - top) / 4f)
        if (edge <= 0f) return 0f
        val pointer = draggedTop + owned.grabOffset
        val penetration = when {
            pointer < top + edge && listState.canScrollBackward ->
                -((top + edge - pointer) / edge).coerceIn(0f, 1f)
            pointer > bottom - edge && listState.canScrollForward ->
                ((pointer - bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        return penetration * abs(penetration) * 880f * density
    }

    private class Pin(private val handle: PinnableContainer.PinnedHandle?) {
        private var released = false
        fun release() {
            if (released) return
            released = true
            handle?.release()
        }
    }

    private class Drag(
        val key: String,
        val grabOffset: Float,
        val pin: Pin,
        val placement: ModuleTaskItemPlacement,
    ) {
        var pending: PendingMove? = null
    }

    private class PendingMove(
        val target: String,
        val down: Boolean,
        val layout: LazyListLayoutInfo,
        val deadline: Long,
    )

    private class Settling(val key: String, val screenTop: Float, val pin: Pin) {
        val progress = Animatable(1f)
    }
}

/** LazyListLayoutInfo omits pinned items outside its visible index range; placement still runs. */
@Stable
internal class ModuleTaskItemPlacement {
    private var coordinates: LayoutCoordinates? = null
    private var version by mutableIntStateOf(0)

    val top: Float?
        get() {
            version
            val child = coordinates?.takeIf { it.isAttached } ?: return null
            val parent = child.parentLayoutCoordinates?.takeIf { it.isAttached } ?: return null
            // onPlaced lies outside our translated graphicsLayer, so this is the natural item top.
            return parent.localPositionOf(child, Offset.Zero).y
        }

    val height: Int get() = coordinates?.size?.height ?: 0

    fun placed(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        version++
    }
}

internal class ModuleTaskReorderItem {
    var container: PinnableContainer? = null
    val placement = ModuleTaskItemPlacement()
}

@Composable
internal fun Modifier.taskReorderItem(state: ModuleTaskReorderState, key: String): Modifier {
    val container = LocalPinnableContainer.current
    val item = remember(state, key) { ModuleTaskReorderItem() }
    SideEffect { item.container = container }
    DisposableEffect(state, key, item) {
        state.registerItem(key, item)
        onDispose { state.unregisterItem(key, item) }
    }
    return this
        .zIndex(if (state.isLifted(key)) 1f else 0f)
        .onPlaced(item.placement::placed)
        .graphicsLayer {
            translationY = state.translation(key, item.placement)
            clip = false
        }
}

internal fun Modifier.taskReorderContainer(state: ModuleTaskReorderState): Modifier =
    pointerInput(state) {
        var acceptedKey: String? = null
        detectModuleTaskDrag(
            onStart = { position -> state.startAt(position).also { acceptedKey = it } != null },
            onDelta = { amount -> acceptedKey?.let { state.dragBy(it, amount.y) } },
            onEnd = { acceptedKey?.let { state.finish(it) }; acceptedKey = null },
            onCancel = { acceptedKey?.let { state.finish(it) }; acceptedKey = null },
        )
    }
