package com.reamicro.fix.ui

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
import top.yukonga.miuix.kmp.utils.horizontalPagerSwipeOverride

/**
 * Filter a complete touch stream before miuix's Initial-pass cross-axis interceptor sees it.
 * The actual paging, fling, accessibility and settling still belong to miuix.
 *
 * miuix 0.9.4 exposes its interceptor as one ModifierNodeElement, but no exclusion predicate.
 * DelegatingNode dispatches pointer events to this implementing node; we explicitly forward
 * only non-excluded streams to the attached library delegate. No recomposition race on DOWN.
 */
internal fun Modifier.crossAxisPagerWithExclusion(
    pagerState: PagerState,
    enabled: Boolean,
    isExcluded: (windowPosition: Offset) -> Boolean,
): Modifier {
    if (!enabled) return this
    val elements = Modifier.horizontalPagerSwipeOverride(pagerState).foldIn(emptyList<Modifier.Element>()) { list, element ->
        list + element
    }
    @Suppress("UNCHECKED_CAST")
    val element = elements.single() as ModifierNodeElement<Modifier.Node>
    return then(PagerExclusionElement(element, isExcluded))
}

private data class PagerExclusionElement(
    val libraryElement: ModifierNodeElement<Modifier.Node>,
    val isExcluded: (Offset) -> Boolean,
) : ModifierNodeElement<PagerExclusionNode>() {
    override fun create() = PagerExclusionNode(libraryElement, isExcluded)
    override fun update(node: PagerExclusionNode) {
        libraryElement.update(node.libraryNode)
        node.isExcluded = isExcluded
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "crossAxisPagerWithExclusion"
    }
}

private class PagerExclusionNode(
    libraryElement: ModifierNodeElement<Modifier.Node>,
    var isExcluded: (Offset) -> Boolean,
) : DelegatingNode(), PointerInputModifierNode, GlobalPositionAwareModifierNode {
    val libraryNode = delegate(libraryElement.create())
    private val libraryPointer = libraryNode as PointerInputModifierNode
    private var coordinates: LayoutCoordinates? = null
    private var inGesture = false
    private var excluded = false

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && !inGesture) {
            val down = pointerEvent.changes.firstOrNull { it.pressed }
            if (down != null) {
                inGesture = true
                val windowPosition = coordinates?.takeIf { it.isAttached }?.localToWindow(down.position)
                excluded = windowPosition != null && isExcluded(windowPosition)
            }
        }
        if (!excluded) libraryPointer.onPointerEvent(pointerEvent, pass, bounds)
        // Keep ownership until every finger has lifted, including the final event pass.
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
