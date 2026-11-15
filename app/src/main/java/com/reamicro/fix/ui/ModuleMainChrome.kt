package com.reamicro.fix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.integration.SizedGlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModuleTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    config: ModuleThemeConfig,
    backdrop: Backdrop,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val settings = config.active
    val supported = isRuntimeShaderSupported()
    val blurred = settings.blurEnabled && supported
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val glassTopBar = config.glassComponents && supported
    val progressive = settings.topBarBlur == ModuleTopBarBlur.Progressive
    val gaussianColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.87f))),
    )
    val progressiveColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.3f))),
    )
    Box {
        // GlassTopAppBar owns its progressive band. A separate Gaussian surface keeps the
        // "Gaussian" option a real uniform blur rather than its non-progressive gradient mask.
        if (blurred && (!glassTopBar || !progressive)) {
            val visibility = if (glassTopBar || progressive) {
                Modifier.graphicsLayer {
                    alpha = (-scrollBehavior.state.contentOffset /
                        GlassTopAppBarDefaults.ProgressiveBlurRampDistance.toPx()).coerceIn(0f, 1f)
                }
            } else Modifier
            Box(
                Modifier.matchParentSize()
                    .then(visibility)
                    .then(
                        if (progressive) Modifier.progressiveTextureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = 25f,
                            gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                            colors = progressiveColors,
                        ) else Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = 25f,
                            colors = gaussianColors,
                        ),
                    ),
            )
        }
        if (glassTopBar) {
            GlassTopAppBar(
                title = title,
                largeTitle = title,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                backdrop = if (blurred) backdrop else null,
                progressiveBlur = blurred && progressive,
                backgroundAlpha = { if (blurred && !progressive) 0f else 1f },
                style = GlassDefaults.style(dark),
                bandBrush = if (blurred) GlassTopAppBarDefaults.bandBrush(surface) else SolidColor(surface),
                bandOverhang = if (blurred && progressive) GlassTopAppBarDefaults.BandOverhang else 0.dp,
            )
        } else {
            TopAppBar(
                title = title,
                largeTitle = title,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                color = if (blurred) Color.Transparent else surface,
            )
        }
    }
}

@Composable
internal fun ModuleBottomBar(
    titles: List<String>,
    os3Icons: List<ImageVector>,
    os4Icons: List<ImageVector>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    config: ModuleThemeConfig,
    backdrop: Backdrop,
) {
    val settings = config.active
    if (!settings.showNavigation) return
    val supported = isRuntimeShaderSupported()
    val blurred = settings.blurEnabled && supported
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val style = GlassDefaults.style(dark)
    if (settings.floatingNavigation) {
        val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = if (config.style == ModuleUiStyle.OS4) 24.dp else 28.dp)
                .padding(bottom = if (navigationInset > 0.dp) navigationInset + 8.dp else 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val width = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            if (config.style == ModuleUiStyle.OS3) {
                val height = maxOf(64.dp, (33f + 28f * LocalDensity.current.fontScale).dp)
                FloatingBottomBar(
                    modifier = Modifier.widthIn(max = 600.dp),
                    selectedIndex = selectedIndex,
                    onSelected = onSelect,
                    backdrop = backdrop,
                    tabsCount = titles.size,
                    isBlurEnabled = settings.glassEnabled && supported,
                    height = height,
                ) { activate ->
                    os3Icons.forEachIndexed { index, icon ->
                        FloatingBottomBarItem(
                            selected = selectedIndex == index,
                            onClick = { activate(index) },
                            modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(24.dp))
                            Text(
                                text = titles[index],
                                color = LocalContentColor.current,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                val items = remember(titles, os4Icons) {
                    os4Icons.mapIndexed { index, icon -> GlassNavigationItem(icon = icon, label = titles[index]) }
                }
                SizedGlassNavigationBar(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    backdrop = if (settings.glassEnabled && blurred) backdrop else null,
                    defaultWindowInsetsPadding = false,
                    style = style,
                    height = 56.dp,
                    iconSize = 25.dp,
                    labelSize = 12.sp,
                    contentPaddingVertical = 6.dp,
                    labelSpacing = 2.dp,
                    shape = GlassShape(
                        androidx.compose.foundation.shape.CornerSize(50),
                        androidx.compose.foundation.shape.CornerSize(50),
                        androidx.compose.foundation.shape.CornerSize(50),
                        androidx.compose.foundation.shape.CornerSize(50),
                        smoothing = GlassDefaults.Smoothing,
                    ),
                    modifier = width,
                )
            }
        }
    } else {
        val useOs4Material = config.style == ModuleUiStyle.OS4 && settings.glassEnabled && blurred
        val barModifier: Modifier = when {
            useOs4Material -> Modifier.glass(
                backdrop = backdrop,
                shape = GlassShape(0.dp),
                style = style,
                material = GlassMaterials.actionBarMask(dark),
                shading = false,
            )
            blurred -> Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.87f)))),
            )
            else -> Modifier
        }
        NavigationBar(
            mode = settings.navigationMode,
            color = if (blurred) Color.Transparent else surface,
            modifier = barModifier,
        ) {
            val icons = if (config.style == ModuleUiStyle.OS3) os3Icons else os4Icons
            icons.forEachIndexed { index, icon ->
                NavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    icon = icon,
                    label = titles[index],
                )
            }
        }
    }
}
