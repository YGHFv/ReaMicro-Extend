package com.reamicro.fix.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.utils.PagerFlingTrackerConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

internal fun Modifier.modulePagerGestureOverride(
    pagerState: PagerState,
    flingBehavior: FlingBehavior,
    mode: PagerInterceptionMode,
    enabled: Boolean,
    flingTracker: PagerFlingTrackerConnection,
    isExcluded: (windowPosition: Offset) -> Boolean,
): Modifier {
    // Keep the parent pointer chain attached when a child long-press drag takes ownership.
    // Upstream's enabled=false removes the iOS pointer node and cancels its child hit path.
    // Gate the existing pointer nodes instead; retain the iOS nested-scroll tracker unchanged.
    val elements = Modifier.pagerGestureOverride(
        pagerState = pagerState,
        flingBehavior = flingBehavior,
        mode = mode,
        enabled = true,
        flingTracker = flingTracker,
    ).foldIn(emptyList<Modifier.Element>()) { list, element ->
        list + element
    }
    return elements.foldIndexed(this) { index, modifier, element ->
        // CrossAxis supplies touch and non-touch pointer nodes. iOS adds a nested-scroll
        // element followed by one pointer node. Native supplies no additional elements.
        val pointerElement = when (mode) {
            PagerInterceptionMode.CrossAxis -> true
            PagerInterceptionMode.TapToHalt -> index == elements.lastIndex
            PagerInterceptionMode.Native -> false
        }
        if (pointerElement && element is ModifierNodeElement<*>) {
            @Suppress("UNCHECKED_CAST")
            val libraryElement = element as ModifierNodeElement<Modifier.Node>
            modifier.then(
                when {
                    mode == PagerInterceptionMode.TapToHalt ->
                        IosPagerExclusionElement(libraryElement, enabled, isExcluded)
                    index == elements.lastIndex ->
                        CrossAxisTouchExclusionElement(libraryElement, enabled, isExcluded)
                    else ->
                        CrossAxisNonTouchExclusionElement(libraryElement, enabled, isExcluded)
                },
            )
        } else {
            modifier.then(element)
        }
    }
}

private sealed class PagerExclusionElement : ModifierNodeElement<PagerExclusionNode>() {
    abstract val libraryElement: ModifierNodeElement<Modifier.Node>
    abstract val enabled: Boolean
    abstract val isExcluded: (Offset) -> Boolean

    final override fun create() = PagerExclusionNode(libraryElement, enabled, isExcluded)
    final override fun update(node: PagerExclusionNode) {
        libraryElement.update(node.libraryNode)
        node.updateGate(enabled, isExcluded)
    }
    final override fun InspectorInfo.inspectableProperties() {
        name = "modulePagerGestureOverride"
    }
}

// Compose reuses nodes by element class. Keep different upstream node types distinct so changing
// gesture mode cannot update an iOS coroutine node through a CrossAxis element's typed bridge.
private data class CrossAxisTouchExclusionElement(
    override val libraryElement: ModifierNodeElement<Modifier.Node>,
    override val enabled: Boolean,
    override val isExcluded: (Offset) -> Boolean,
) : PagerExclusionElement()

private data class CrossAxisNonTouchExclusionElement(
    override val libraryElement: ModifierNodeElement<Modifier.Node>,
    override val enabled: Boolean,
    override val isExcluded: (Offset) -> Boolean,
) : PagerExclusionElement()

private data class IosPagerExclusionElement(
    override val libraryElement: ModifierNodeElement<Modifier.Node>,
    override val enabled: Boolean,
    override val isExcluded: (Offset) -> Boolean,
) : PagerExclusionElement()

private class PagerExclusionNode(
    libraryElement: ModifierNodeElement<Modifier.Node>,
    private var enabled: Boolean,
    var isExcluded: (Offset) -> Boolean,
) : DelegatingNode(), PointerInputModifierNode, GlobalPositionAwareModifierNode {
    val libraryNode = delegate(libraryElement.create())
    private val libraryPointer = libraryNode as PointerInputModifierNode
    private var coordinates: LayoutCoordinates? = null
    private var inGesture = false
    private var excluded = false

    fun updateGate(enabled: Boolean, isExcluded: (Offset) -> Boolean) {
        if (this.enabled && !enabled) {
            libraryPointer.onCancelPointerInput()
            // A re-enabled pager must not join the middle of the child's drag.
            if (inGesture) excluded = true
        }
        this.enabled = enabled
        this.isExcluded = isExcluded
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onDensityChange() {
        onCancelPointerInput()
        libraryNode.onDensityChange()
    }

    override fun onLayoutDirectionChange() {
        onCancelPointerInput()
        libraryNode.onLayoutDirectionChange()
    }

    override fun onViewConfigurationChange() {
        onCancelPointerInput()
        libraryPointer.onViewConfigurationChange()
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && !inGesture) {
            val down = pointerEvent.changes.firstOrNull { it.pressed }
            if (down != null) {
                inGesture = true
                val windowPosition = coordinates?.takeIf { it.isAttached }?.localToWindow(down.position)
                excluded = !enabled || (windowPosition != null && isExcluded(windowPosition))
            }
        }
        if (enabled && !excluded) libraryPointer.onPointerEvent(pointerEvent, pass, bounds)

        if (pass == PointerEventPass.Final && pointerEvent.changes.none { it.pressed }) {
            inGesture = false
            excluded = false
        }
    }

    override fun onCancelPointerInput() {
        libraryPointer.onCancelPointerInput()
        inGesture = false
        excluded = false
    }
}
