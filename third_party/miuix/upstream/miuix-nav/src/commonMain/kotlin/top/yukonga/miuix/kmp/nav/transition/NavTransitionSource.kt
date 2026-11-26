// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.nav.transition

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.nav.transition.internal.NavCornerShape
import top.yukonga.miuix.kmp.nav.transition.internal.NavSourceOutline

internal enum class NavSourceDrawing { InPlace, Handoff, Covered }

/** A retained Compose drawing whose layer can outlive the source node during an exit. */
@Stable
internal class NavTransitionSource(internal val graphicsContext: GraphicsContext) {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
        private set
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null)
        private set
    internal var shape: Shape? by mutableStateOf(null)
    internal var squircleEnabled by mutableStateOf(false)
    internal var density: Density by mutableStateOf(Density(1f))
    internal var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
    private val outline by lazy(LazyThreadSafetyMode.NONE) { NavSourceOutline() }
    private var references = 0
    private var pressed by mutableStateOf(false)
    private var pendingCapture by mutableStateOf(false)
    private val drawingOwners = mutableStateMapOf<Any, State<NavSourceDrawing>>()
    private var windowBounds by mutableStateOf<Rect?>(null)

    internal fun claimDrawing(owner: Any, drawing: State<NavSourceDrawing>) {
        drawingOwners[owner] = drawing
    }

    internal fun releaseDrawing(owner: Any) {
        drawingOwners.remove(owner)
    }

    internal val hasLivePlacement: Boolean
        get() = coordinates?.isAttached == true

    internal val size: Size?
        get() = (coordinates?.size ?: layer?.size)?.let { Size(it.width.toFloat(), it.height.toFloat()) }

    internal val currentBounds: Rect?
        get() = coordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false)
            ?.takeIf { it.isFinite && !it.isEmpty }

    internal fun placed(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        // LayoutCoordinates is mutable in place. Observe geometry changes even when its identity
        // stays the same (for example when a lazy list scrolls).
        if (references != 0) windowBounds = currentBounds
    }

    internal fun observePlacement() {
        windowBounds
    }

    internal fun sourceShape(corners: NavTransitionCorners): Shape = shape ?: NavCornerShape(corners, squircleEnabled)

    internal fun setPressed(pressed: Boolean) {
        this.pressed = pressed
        releaseIfUnused()
    }

    internal fun draw(scope: ContentDrawScope) {
        var handoff = false
        for (owner in drawingOwners.values) {
            when (owner.value) {
                NavSourceDrawing.Covered -> return
                NavSourceDrawing.Handoff -> handoff = true
                NavSourceDrawing.InPlace -> Unit
            }
        }
        if (pendingCapture || pressed || handoff) {
            val recording = layer ?: graphicsContext.createGraphicsLayer()
            with(scope) { recording.record { scope.drawContent() } }
            layer = recording
            pendingCapture = false
            if (!handoff) scope.drawLayer(recording)
        } else {
            scope.drawContent()
        }
    }

    internal fun containsVisiblePoint(host: LayoutCoordinates, positionInWindow: Offset, corners: NavTransitionCorners): Boolean {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return false
        val visible = host.navVisibleBoundsOf(coordinates) ?: return false
        if (!visible.contains(host.windowToLocal(positionInWindow))) return false
        val position = coordinates.windowToLocal(positionInWindow)
        val size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        return outline.update(sourceShape(corners), size, layoutDirection, density) && outline.contains(position)
    }

    internal fun detach() {
        coordinates = null
        windowBounds = null
        pressed = false
        releaseIfUnused()
    }

    internal fun retain() {
        references++
        if (hasLivePlacement && layer == null) pendingCapture = true
    }

    internal fun release() {
        check(references > 0)
        references--
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (references != 0 || pressed) return
        pendingCapture = false
        val unused = layer
        layer = null
        windowBounds = null
        if (unused != null) graphicsContext.releaseGraphicsLayer(unused)
    }

    /** Validate geometry before retaining any drawing resources. */
    internal fun capture(cornerRadius: Dp, containerColor: Color, corners: NavTransitionCorners): NavTransitionAnchor? {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
        val bounds = currentBounds ?: return null
        val size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        val sourceShape = sourceShape(corners)
        if (!outline.update(sourceShape, size, layoutDirection, density)) return null
        return NavTransitionAnchor(bounds, cornerRadius, containerColor, corners, sourceShape).also {
            it.source = this
            it.retain()
        }
    }
}

/** Bounds left after ancestor clipping and intersection with the navigation viewport. */
internal fun LayoutCoordinates.navVisibleBoundsOf(source: LayoutCoordinates): Rect? {
    if (!isAttached || !source.isAttached) return null
    val clipped = localBoundingBoxOf(source, clipBounds = true)
    if (!clipped.isFinite || clipped.isEmpty) return null
    val viewport = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
    return clipped.intersect(viewport).takeUnless { it.isEmpty }
}
