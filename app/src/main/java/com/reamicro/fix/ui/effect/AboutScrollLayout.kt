// See assets/licenses/KernelSU-effect-LICENSE.txt.
package com.reamicro.fix.ui.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Stable
internal class AboutBrandMotion(
    private val listState: LazyListState,
    private val density: Float,
) {
    var headerHeightPx by mutableIntStateOf(0)
    var spacerHeightPx by mutableIntStateOf(0)
    private var nameHeightPx by mutableIntStateOf(0)
    private var versionHeightPx by mutableIntStateOf(0)
    val progress by derivedStateOf {
        val offset = if (listState.firstVisibleItemIndex > 0) {
            spacerHeightPx.toFloat()
        } else listState.firstVisibleItemScrollOffset.toFloat()
        AboutScrollMath.calculate(
            scrollPixels = offset,
            spacerPixels = spacerHeightPx.toFloat(),
            bottomGapPixels = 126f * density,
            versionBlockPixels = versionHeightPx + 5f * density,
            nameBlockPixels = nameHeightPx + 12f * density,
        )
    }

    fun logoModifier(): Modifier = Modifier.graphicsLayer {
        val p = progress.iconFade
        alpha = 1f - p
        scaleX = 1f - p * 0.05f
        scaleY = scaleX
    }

    fun nameModifier(): Modifier = Modifier
        .onSizeChanged { nameHeightPx = it.height }
        .graphicsLayer {
            val p = progress.nameFade
            alpha = 1f - p
            scaleX = 1f - p * 0.05f
            scaleY = scaleX
        }

    fun versionModifier(): Modifier = Modifier
        .onSizeChanged { versionHeightPx = it.height }
        .graphicsLayer {
            val p = progress.versionFade
            alpha = 1f - p
            scaleX = 1f - p * 0.05f
            scaleY = scaleX
        }
}

@Composable
internal fun AboutScrollLayout(
    playing: Boolean,
    bottomPadding: Dp,
    header: @Composable (AboutBrandMotion) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val motion = remember(listState, density.density, density.fontScale) {
        AboutBrandMotion(listState, density.density)
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val contentTop = statusTop + 52.dp
    val brandingTop = contentTop + 40.dp + 52.dp
    val spacerHeight = with(density) { motion.headerHeightPx.toDp() } + 92.dp + 126.dp
    val supported = remember { isRuntimeShaderSupported() }
    val backdrop = rememberLayerBackdrop()
    val effectVisible by remember(motion) {
        derivedStateOf { motion.progress.backgroundFade < 1f }
    }
    BgEffectBackground(
        dynamicBackground = playing && effectVisible,
        effectBackground = supported,
        modifier = Modifier.fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .graphicsLayer(),
        bgModifier = Modifier.layerBackdrop(backdrop),
        isFullSize = true,
        alpha = { 1f - motion.progress.backgroundFade },
    ) {
        CompositionLocalProvider(LocalAboutBackdrop provides if (supported) backdrop else null) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = brandingTop)
                    .onSizeChanged { motion.headerHeightPx = it.height },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { header(motion) }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
                contentPadding = PaddingValues(top = contentTop),
                overscrollEffect = null,
            ) {
                item(key = "reamicrofix_about_brand_spacer") {
                    Box(
                        Modifier.fillMaxWidth().height(spacerHeight)
                            .onSizeChanged { motion.spacerHeightPx = it.height },
                    )
                }
                item(key = "reamicrofix_about_details") {

                    Column(
                        Modifier.fillParentMaxHeight()
                            .padding(bottom = bottomPadding + 8.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
