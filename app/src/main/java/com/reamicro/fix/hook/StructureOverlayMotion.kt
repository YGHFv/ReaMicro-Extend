package com.reamicro.fix.hook

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

internal object StructureOverlayMotion {
    private fun fade() = spring<Float>(dampingRatio = 1f, stiffness = 400f)
    private fun slide() = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f,
        visibilityThreshold = IntOffset(1, 1))
    fun enter() = fadeIn(animationSpec = fade()) +
        slideInVertically(animationSpec = slide(), initialOffsetY = { it })
    fun exit() = slideOutVertically(animationSpec = slide(), targetOffsetY = { it }) +
        fadeOut(animationSpec = fade())
}
