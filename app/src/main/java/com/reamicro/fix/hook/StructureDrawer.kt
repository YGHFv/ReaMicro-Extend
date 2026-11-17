package com.reamicro.fix.hook

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class StructureDrawerPosition { Closed, Open }

/** 已打开文件的侧栏状态；拖拽、程序开闭和页面恢复共用同一组锚点。 */
@Stable
@OptIn(ExperimentalFoundationApi::class)
internal class StructureDrawerState(initiallyOpen: Boolean = false) {
    internal val draggable = AnchoredDraggableState(
        initialValue = if (initiallyOpen) StructureDrawerPosition.Open else StructureDrawerPosition.Closed,
    )
    private var width by mutableFloatStateOf(0f)

    val isOpen: Boolean get() = draggable.settledValue == StructureDrawerPosition.Open
    internal val offset: Float get() = draggable.offset.takeUnless { it.isNaN() }
        ?: if (isOpen) 0f else -width
    internal val progress: Float get() = if (width > 0f) ((offset + width) / width).coerceIn(0f, 1f)
        else if (isOpen) 1f else 0f

    internal fun updateWidth(value: Float) {
        if (value <= 0f || value == width) return
        width = value
        draggable.updateAnchors(DraggableAnchors {
            StructureDrawerPosition.Closed at -value
            StructureDrawerPosition.Open at 0f
        })
    }

    suspend fun open() = draggable.animateTo(
        StructureDrawerPosition.Open, tween(256, easing = FastOutSlowInEasing),
    )

    suspend fun close() = draggable.animateTo(
        StructureDrawerPosition.Closed, tween(256, easing = FastOutSlowInEasing),
    )

    companion object {
        val saver = Saver<StructureDrawerState, Boolean>(
            save = { it.isOpen },
            restore = { StructureDrawerState(it) },
        )
    }
}

@Composable
internal fun rememberStructureDrawerState(): StructureDrawerState =
    rememberSaveable(saver = StructureDrawerState.saver) { StructureDrawerState() }

/** 只承载文件侧栏；正文不随拖拽移动，遮罩阻止点按穿透并支持关闭。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun StructureNavigationDrawer(
    drawerState: StructureDrawerState,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val progress = drawerState.progress
    fun close() { scope.launch { drawerState.close() } }

    Box(modifier.fillMaxSize().clipToBounds().anchoredDraggable(
        state = drawerState.draggable,
        orientation = Orientation.Horizontal,
        enabled = gesturesEnabled,
        reverseDirection = rtl,
        flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = drawerState.draggable,
            positionalThreshold = { distance -> distance * .5f },
        ),
    )) {
        Box(Modifier.fillMaxSize().then(
            if (drawerState.isOpen) Modifier.clearAndSetSemantics { } else Modifier,
        )) { content() }
        if (progress > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f * progress))
                .pointerInput(drawerState) { detectTapGestures { close() } }
                .semantics {
                    contentDescription = "关闭已打开文件"
                    onClick { close(); true }
                })
        }
        // offset 使用相对坐标，RTL 时自动从右侧进出；宽度由原文件列表决定。
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight()
            .offset { IntOffset(drawerState.offset.roundToInt(), 0) }
            .onSizeChanged { drawerState.updateWidth(it.width.toFloat()) }
            .pointerInput(Unit) { detectTapGestures { } }
            .then(if (progress > 0f) Modifier.semantics {
                paneTitle = "已打开文件"
                dismiss { close(); true }
            } else Modifier.clearAndSetSemantics { })) {
            drawerContent()
        }
    }
}
