// Adapted from KernelSU manager (GPL-3.0); see assets/licenses/KernelSU-effect-LICENSE.txt.

package com.reamicro.fix.ui.effect

import android.util.Log
import android.os.Build
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    prepareEffect: Boolean = true,
    state: BgEffectState = remember { BgEffectState() },
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier.background(surface), content = content)
        return
    }
    Box(
        modifier = modifier,
    ) {
        val deviceType = if ((LocalConfiguration.current.screenWidthDp >= 600)) DeviceType.PAD else DeviceType.PHONE
        val isDarkTheme = (MiuixTheme.colorScheme.surface.luminance() < 0.5f)
        val painter = state.painter
        LaunchedEffect(prepareEffect, state) {
            if (!prepareEffect || state.painter != null) return@LaunchedEffect

            state.painter = withContext(Dispatchers.Default) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else try {
                    BgEffectPainter()
                } catch (error: RuntimeException) {
                    Log.w("ReaMicroAbout", "Unable to prepare the about background", error)
                    null
                }
            }
        }

        val preset = remember(deviceType, isDarkTheme) {
            BgEffectConfig.get(deviceType, isDarkTheme)
        }

        val colorStage = state.colorStage

        LaunchedEffect(dynamicBackground, effectBackground, preset, painter) {
            if (!dynamicBackground || !effectBackground || painter == null) return@LaunchedEffect
            val animatesColors = preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect

            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong().milliseconds)
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()

                .graphicsLayer()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    playing = dynamicBackground && effectBackground,
                    colorStage = { colorStage.value },
                    alpha = alpha,
                    state = state,
                ),
        )
        content()
    }
}
