// Adapted from KernelSU manager (GPL-3.0); see assets/licenses/KernelSU-effect-LICENSE.txt.
// Mirrored from compose-miuix-ui example.

package com.reamicro.fix.ui.effect

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun Modifier.bgEffectDraw(
    painter: BgEffectPainter?,
    preset: BgEffectConfig.Config,
    deviceType: DeviceType,
    isDarkTheme: Boolean,
    surface: Color,
    effectBackground: Boolean,
    isFullSize: Boolean,
    playing: Boolean,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then BgEffectElement(
    painter = painter,
    preset = preset,
    deviceType = deviceType,
    isDarkTheme = isDarkTheme,
    surface = surface,
    effectBackground = effectBackground,
    isFullSize = isFullSize,
    playing = playing,
    colorStage = colorStage,
    alpha = alpha,
)

@SuppressLint("ModifierNodeInspectableProperties")
private data class BgEffectElement(
    val painter: BgEffectPainter?,
    val preset: BgEffectConfig.Config,
    val deviceType: DeviceType,
    val isDarkTheme: Boolean,
    val surface: Color,
    val effectBackground: Boolean,
    val isFullSize: Boolean,
    val playing: Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<BgEffectNode>() {

    override fun create(): BgEffectNode = BgEffectNode(
        painter = painter,
        preset = preset,
        deviceType = deviceType,
        isDarkTheme = isDarkTheme,
        surface = surface,
        effectBackground = effectBackground,
        isFullSize = isFullSize,
        playing = playing,
        colorStage = colorStage,
        alpha = alpha,
    )

    override fun update(node: BgEffectNode) {
        node.update(
            painter = painter,
            preset = preset,
            deviceType = deviceType,
            isDarkTheme = isDarkTheme,
            surface = surface,
            effectBackground = effectBackground,
            isFullSize = isFullSize,
            playing = playing,
            colorStage = colorStage,
            alpha = alpha,
        )
    }
}

private class BgEffectNode(
    private var painter: BgEffectPainter?,
    private var preset: BgEffectConfig.Config,
    private var deviceType: DeviceType,
    private var isDarkTheme: Boolean,
    private var surface: Color,
    private var effectBackground: Boolean,
    private var isFullSize: Boolean,
    private var playing: Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {

    private var animationJob: Job? = null
    private var revealJob: Job? = null
    private var animTime: Float = 0f
    private var startOffset: Float = 0f
    private var revealAlpha: Float = 1f
    private var drewFallback: Boolean = false

    override fun onAttach() {
        syncPlayback()
    }

    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
        revealJob?.cancel()
        revealJob = null
        // A detached page must not replay a half-finished reveal when it comes back.
        revealAlpha = 1f
        drewFallback = false
    }

    fun update(
        painter: BgEffectPainter?,
        preset: BgEffectConfig.Config,
        deviceType: DeviceType,
        isDarkTheme: Boolean,
        surface: Color,
        effectBackground: Boolean,
        isFullSize: Boolean,
        playing: Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        val needsReveal = effectBackground && painter != null &&
            (this.painter == null || !this.effectBackground) && drewFallback
        this.painter = painter
        this.preset = preset
        this.deviceType = deviceType
        this.isDarkTheme = isDarkTheme
        this.surface = surface
        this.effectBackground = effectBackground
        this.isFullSize = isFullSize
        this.colorStage = colorStage
        this.alpha = alpha

        this.playing = playing
        if (!effectBackground || painter == null) {
            revealJob?.cancel()
            revealJob = null
            revealAlpha = 1f
        } else if (needsReveal) {
            drewFallback = false
            if (isAttached) startReveal() else revealAlpha = 1f
        }
        syncPlayback()
        invalidateDraw()
    }

    private fun syncPlayback() {
        val shouldPlay = isAttached && playing && effectBackground &&
            painter != null && revealAlpha >= 1f
        if (shouldPlay) {
            if (animationJob?.isActive != true) startAnimation()
        } else {
            animationJob?.cancel()
            animationJob = null
        }
    }

    private fun startReveal() {
        revealJob?.cancel()
        animationJob?.cancel()
        animationJob = null
        revealAlpha = 0f
        revealJob = coroutineScope.launch {
            // Only needed if a fallback frame was already drawn before preparation finished.
            // Fade the effect, not the surface, cards, text, or the whole page. Normal prepared
            // entries display their static effect immediately and never repeat this transition.
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
            ) { value, _ ->
                revealAlpha = value.coerceIn(0f, 1f)
                invalidateDraw()
            }
            revealAlpha = 1f
            revealJob = null
            syncPlayback()
        }
    }

    private fun startAnimation() {
        animationJob?.cancel()
        startOffset = animTime
        animationJob = coroutineScope.launch {
            val minDeltaNanos = 1_000_000_000L / 60L
            val origin = withFrameNanos { it }
            var lastEmit = origin
            while (isActive) {
                val now = withFrameNanos { it }
                if (now - lastEmit < minDeltaNanos) continue
                lastEmit = now
                animTime = startOffset + (now - origin) / 1_000_000_000f
                invalidateDraw()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun ContentDrawScope.draw() {
        drawRect(surface)
        val effectPainter = painter
        if (effectBackground && effectPainter != null) {
            val alphaValue = (alpha() * revealAlpha).coerceIn(0f, 1f)
            if (alphaValue > 0f) {
                val drawHeight = if (isFullSize) size.height * 0.8f else size.height * 0.5f

                effectPainter.updateResolution(size.width, size.height)
                effectPainter.updateBoundIfNeeded(drawHeight, size.height, size.width)
                effectPainter.updatePresetIfNeeded(deviceType, isDarkTheme)
                effectPainter.updateColors(preset, colorStage())
                effectPainter.updateAnimTime(animTime)
                effectPainter.updatePointsAnim(animTime, preset)

                drawRect(effectPainter.brush, alpha = alphaValue)
            }
        } else {
            drewFallback = true
        }
        drawContent()
    }
}
