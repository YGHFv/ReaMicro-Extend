// Copyright 2026, ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.integration

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

/**
 * Retains coordinates without publishing moving row bounds while its popup is closed.
 * Opening samples the current placement; a mounted popup follows subsequent placement changes.
 */
@Stable
internal class GlassPreferenceAnchor {
    private var coordinates: LayoutCoordinates? = null
    var bounds by mutableStateOf(Rect.Zero)
        private set

    fun onPositioned(coordinates: LayoutCoordinates, mounted: Boolean) {
        this.coordinates = coordinates
        if (mounted) capture()
    }

    fun capture() {
        coordinates?.takeIf { it.isAttached }?.let { bounds = it.boundsInRoot() }
    }

    fun clear() {
        coordinates = null
    }
}
