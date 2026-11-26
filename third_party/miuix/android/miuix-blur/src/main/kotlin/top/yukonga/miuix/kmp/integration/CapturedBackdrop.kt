// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * Records the displayed content once and reuses that recording for display and backdrop consumers.
 *
 * Use a default rememberLayerBackdrop(): the recorded output is also the visible output. The
 * visibility predicate is read in draw, so cached pager pages need no composition work per frame.
 * The upstream snapshot stays unchanged; this bridge shares its coordinate and density handling.
 */
fun Modifier.captureBackdrop(
    backdrop: LayerBackdrop,
    enabled: () -> Boolean = { true },
): Modifier = this then CapturedBackdropElement(backdrop, enabled)

private class CapturedBackdropElement(
    val backdrop: LayerBackdrop,
    val enabled: () -> Boolean,
) : ModifierNodeElement<CapturedBackdropNode>() {
    override fun create() = CapturedBackdropNode(backdrop, enabled)

    override fun update(node: CapturedBackdropNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.enabled = enabled
        node.invalidateDraw()
    }

    override fun equals(other: Any?) = other is CapturedBackdropElement &&
        backdrop === other.backdrop && enabled === other.enabled

    override fun hashCode() = 31 * backdrop.hashCode() + enabled.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "captureBackdrop"
        properties["backdrop"] = backdrop
    }
}

private class CapturedBackdropNode(
    var backdrop: LayerBackdrop,
    var enabled: () -> Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private val recordBlock: DrawScope.() -> Unit = {
        (this as? ContentDrawScope)?.let(backdrop.onDraw)
    }

    override fun ContentDrawScope.draw() {
        if (!enabled()) {
            backdrop.layerCoordinates = null
            drawContent()
            return
        }
        backdrop.layerCoordinates = coordinates?.takeIf { it.isAttached }
        // Use DrawScope.record, as upstream layerBackdrop does: it redirects drawContent()
        // to the layer canvas. The raw GraphicsLayer.record overload leaves it on the screen.
        with(backdrop.graphicsLayer) {
            record(block = recordBlock)
        }
        drawLayer(backdrop.graphicsLayer)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
        coordinates = null
    }
}
