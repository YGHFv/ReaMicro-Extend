// Copyright 2026, compose-miuix-ui contributors and ReaMicro Extend contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from miuix-glass GlassNavigationBar.kt at 45645854; upstream snapshot remains unchanged.

package top.yukonga.miuix.kmp.integration

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationBarLayout
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.glass.glassPressScale
import top.yukonga.miuix.kmp.glass.navigationDragTarget
import top.yukonga.miuix.kmp.glass.navigationIndicatorBounds
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.Platform
import top.yukonga.miuix.kmp.utils.platform

/**
 * Upstream GlassNavigationBar renderer with explicit icon, caption and internal spacing sizes.
 *
 * The upstream public bar exposes height but fixes content geometry. This adapter changes only
 * those dimensions; material, drag/selection springs, hit regions, press and accessibility stay
 * owned by the same upstream renderer. No display-density or graphics-scale override is used.
 *
 * [labelSize] is a TextUnit so an explicit caller value participates in normal system font scaling.
 * Its default retains the upstream fixed-pixel label policy. Content can still grow for two lines.
 */
@Composable
fun SizedGlassNavigationBar(
    items: List<GlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    defaultWindowInsetsPadding: Boolean = true,
    style: GlassStyle = GlassDefaults.Style,
    shape: GlassShape = GlassShape(CornerSize(50), CornerSize(50), CornerSize(50), CornerSize(50)),
    alpha: Float = 1f,
    visible: Boolean = true,
    stroke: EdgeStroke? = GlassNavigationBarDefaults.stroke(),
    shadow: GlassShadow? = GlassShadows.Float,
    height: Dp = GlassNavigationBarDefaults.Height,
    material: GlassMaterial? = GlassNavigationBarDefaults.material(),
    indicatorColor: Color = GlassNavigationBarDefaults.indicatorColor(),
    indicatorPressedColor: Color = GlassNavigationBarDefaults.indicatorPressedColor(),
    selectedColor: Color = if (MiuixTheme.isDynamicColor) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
    unselectedColor: Color = MiuixTheme.colorScheme.onBackground,
    trailingAction: (@Composable () -> Unit)? = null,
    iconSize: Dp = GlassNavigationBarDefaults.IconSize,
    labelSize: TextUnit = with(LocalDensity.current) {
        if (fontScale >= 1.6f) GlassNavigationBarDefaults.LargeLabelSize.toSp()
        else GlassNavigationBarDefaults.LabelSize.toSp()
    },
    contentPaddingVertical: Dp = GlassNavigationBarDefaults.ContentPaddingVertical,
    labelSpacing: Dp = GlassNavigationBarDefaults.LabelSpacing,
) {
    if (items.isEmpty()) return
    val index = selectedIndex.coerceIn(0, items.lastIndex)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val contentInset = with(density) { GlassNavigationBarDefaults.ContentPaddingHorizontal.toPx() }
    val overhang = with(density) { GlassNavigationBarDefaults.IndicatorOverhang.toPx() }

    var barWidth by remember { mutableIntStateOf(0) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val dragging = remember { mutableStateOf(false) }

    val showScale = remember { Animatable(if (visible) 1f else GlassMotion.NAV_HIDE_SCALE) }
    var interactionEnabled by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            interactionEnabled = true
            delay(GlassMotion.NAV_SHOW_DELAY_MS.milliseconds)
            showScale.animateTo(1f, GlassMotion.navShowHide())
        } else {
            showScale.animateTo(GlassMotion.NAV_HIDE_SCALE, GlassMotion.navShowHide())
            interactionEnabled = false
        }
    }

    val scope = rememberCoroutineScope()
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    fun animateIndicator(
        newLeft: Float,
        newRight: Float,
        leftSpring: SpringSpec<Float> = GlassMotion.edgeSpring(newLeft <= left.value),
        rightSpring: SpringSpec<Float> = GlassMotion.edgeSpring(newLeft > left.value),
    ) {
        // All motion owns the same two Animatables. Retarget through animateTo so their current
        // positions and velocities survive drag, reversal, selection and release interruptions.
        // These jobs belong to the composition, not to a restarting selection effect.
        scope.launch { left.animateTo(newLeft, leftSpring) }
        scope.launch { right.animateTo(newRight, rightSpring) }
    }

    val slot = if (barWidth <= 0) 0f else (barWidth - contentInset * 2f) / items.size
    val chipWidth = slot + overhang * 2f
    val targetLeft = contentInset - overhang + index * slot
    val targetRight = targetLeft + chipWidth

    LaunchedEffect(targetLeft, targetRight, dragging.value) {
        if (slot <= 0f) return@LaunchedEffect
        if (dragging.value) return@LaunchedEffect
        if (right.value <= left.value) {
            left.snapTo(targetLeft)
            right.snapTo(targetRight)
            return@LaunchedEffect
        }
        animateIndicator(targetLeft, targetRight)
    }

    val chipHeldPx = with(density) {
        (height - GlassNavigationBarDefaults.IndicatorPaddingVertical * 2).toPx()
    }
    val chipPressed = pressedIndex >= 0
    val chipScale by animateFloatAsState(
        targetValue = if (chipPressed) glassPressScale(chipHeldPx, density.density) else 1f,
        animationSpec = if (chipPressed) GlassMotion.pressDown() else GlassMotion.pressUp(),
        label = "glassNavigationIndicatorPress",
    )
    val chipFill = animateColorAsState(
        targetValue = if (chipPressed) indicatorPressedColor else indicatorColor,
        animationSpec = if (chipPressed) GlassMotion.navPressEnter() else GlassMotion.navPressExit(),
        label = "glassNavigationIndicatorFill",
    )

    val onSelectState by rememberUpdatedState(onSelect)
    val bottomPadding = if (defaultWindowInsetsPadding) {
        val navigationBarBottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        val floatingMargin = if (platform() == Platform.IOS) {
            36.dp
        } else if (navigationBarBottom == 0.dp) {
            32.dp
        } else {
            16.dp + navigationBarBottom
        }
        val captionBarBottom = WindowInsets.captionBar.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        val animatedCaptionBarHeight by animateDpAsState(
            targetValue = captionBarBottom.coerceAtLeast(0.dp),
            animationSpec = tween(durationMillis = 300),
            label = "glassNavigationCaptionBarHeight",
        )
        floatingMargin + animatedCaptionBarHeight
    } else {
        0.dp
    }
    GlassNavigationBarLayout(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding)
            .graphicsLayer {
                val show = showScale.value
                scaleX = show
                scaleY = show
                this.alpha = ((show - GlassMotion.NAV_HIDE_SCALE) / (1f - GlassMotion.NAV_HIDE_SCALE))
                    .coerceIn(0f, 1f)
            }
            .blur(
                radius = (
                    (1f - ((showScale.value - GlassMotion.NAV_HIDE_SCALE) / (1f - GlassMotion.NAV_HIDE_SCALE)))
                        .coerceIn(0f, 1f) * GlassMotion.NAV_HIDE_BLUR_DP
                    ).dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded,
            ),
        trailingAction = trailingAction?.let { action ->
            {
                Box(
                    modifier = Modifier
                        .then(if (interactionEnabled) Modifier else Modifier.clearAndSetSemantics { })
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                if (interactionEnabled) placeable.placeRelative(0, 0)
                            }
                        },
                ) { action() }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .glassPanel(
                    backdrop = backdrop,
                    shape = shape,
                    style = style,
                    alpha = alpha,
                    material = material,
                    stroke = stroke,
                    shadow = shadow,
                    shading = false,
                    fallback = Modifier.clip(shape).background(MiuixTheme.colorScheme.surfaceContainer),
                )
                .onSizeChanged { barWidth = it.width }
                .then(
                    if (interactionEnabled) {
                        Modifier.pointerInput(items.size, contentInset, overhang, layoutDirection) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val selectionWidth = size.width.toFloat()
                                if (selectionWidth <= contentInset * 2f) return@awaitEachGesture
                                // Freeze hit regions for this gesture: changing a destination can
                                // resize the capsule, which must not select another destination.
                                fun logicalX(x: Float) = if (layoutDirection == LayoutDirection.Rtl) size.width - x else x
                                fun indexAt(x: Float): Int {
                                    val span = (selectionWidth - contentInset * 2f) / items.size
                                    return if (span <= 0f) {
                                        0
                                    } else {
                                        ((x - contentInset) / span).toInt().coerceIn(0, items.lastIndex)
                                    }
                                }

                                val minLeft = contentInset - overhang
                                val downX = logicalX(down.position.x)
                                var selectionX = downX
                                var current = indexAt(downX)
                                pressedIndex = current
                                onSelectState(current)
                                var grabOffset = Float.NaN
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed || change.isConsumed) break
                                        // A destination can add or remove the trailing action while
                                        // this gesture is still active. Read its current geometry.
                                        val width = size.width.toFloat()
                                        val chipW = (width - contentInset * 2f) / items.size + overhang * 2f
                                        val maxLeft = (width - chipW).coerceAtLeast(0f)
                                        val x = logicalX(change.position.x)
                                        // Both positions use the current local coordinates, so their
                                        // delta excludes movement caused by the capsule's placement.
                                        val pointerDelta = change.position.x - change.previousPosition.x
                                        val delta = if (layoutDirection == LayoutDirection.Rtl) -pointerDelta else pointerDelta
                                        selectionX += delta
                                        if (grabOffset.isNaN()) {
                                            if (abs(selectionX - downX) < viewConfiguration.touchSlop) {
                                                pressedIndex = indexAt(selectionX)
                                                continue
                                            }
                                            val bounds = navigationIndicatorBounds(left.value, right.value, width, minLeft)
                                            grabOffset = x - bounds.x
                                            dragging.value = true
                                        }
                                        val chipLeft = (x - grabOffset).coerceIn(0f, maxLeft)
                                        val next = indexAt(selectionX)
                                        val target = navigationDragTarget(chipLeft, chipW, width, delta, next != current)
                                        animateIndicator(target.left, target.right, target.leftSpring, target.rightSpring)
                                        if (next != current) {
                                            current = next
                                            onSelectState(next)
                                        }
                                        pressedIndex = next
                                        change.consume()
                                    }
                                } finally {
                                    pressedIndex = -1
                                    dragging.value = false
                                    // Releasing restarts the selection effect using the latest size.
                                    // Do not settle against geometry captured at pointer down.
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = GlassNavigationBarDefaults.IndicatorPaddingVertical)
                    .layout { measurable, constraints ->
                        // Both edges are clamped on every frame, not just at the target. Keep the
                        // spring alive so the other edge still rebounds.
                        val bounds = navigationIndicatorBounds(
                            left.value,
                            right.value,
                            constraints.maxWidth.toFloat(),
                            contentInset - overhang,
                        )
                        val start = bounds.x.roundToInt()
                        val width = (bounds.y.roundToInt() - start).coerceAtLeast(0)
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = width, maxWidth = width),
                        )
                        layout(constraints.maxWidth, placeable.height) {
                            placeable.placeRelative(IntOffset(start, 0))
                        }
                    }
                    .graphicsLayer {
                        scaleX = chipScale
                        scaleY = chipScale
                    }
                    .clip(RoundedCornerShape(percent = 50))
                    .drawBehind { drawRect(chipFill.value) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = GlassNavigationBarDefaults.ContentPaddingHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { position, item ->
                    key(item) {
                        val selected = position == index
                        val tint by animateColorAsState(
                            targetValue = if (selected) selectedColor else unselectedColor,
                            animationSpec = GlassMotion.navContent(),
                            label = "glassNavigationTint",
                        )
                        val pressAlpha = animateFloatAsState(
                            targetValue = if (position == pressedIndex) {
                                GlassNavigationBarDefaults.PressedAlpha
                            } else {
                                1f
                            },
                            animationSpec = GlassMotion.navContentFloat(),
                            label = "glassNavigationPress",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(
                                    horizontal = GlassNavigationBarDefaults.IndicatorOverhang,
                                    vertical = contentPaddingVertical,
                                )
                                .then(
                                    if (interactionEnabled) {
                                        Modifier
                                            .semantics(mergeDescendants = true) {
                                                this.role = Role.Tab
                                                this.selected = selected
                                                (item.contentDescription ?: item.label)?.let {
                                                    this.contentDescription = it
                                                }
                                                onClick {
                                                    onSelectState(position)
                                                    true
                                                }
                                            }
                                            .onKeyEvent { event ->
                                                val activationKey = event.key == Key.Enter ||
                                                    event.key == Key.NumPadEnter ||
                                                    event.key == Key.Spacebar
                                                if (activationKey) {
                                                    if (event.type == KeyEventType.KeyUp) onSelectState(position)
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                            .focusable()
                                    } else {
                                        Modifier.clearAndSetSemantics { }
                                    },
                                )
                                .graphicsLayer { this.alpha = pressAlpha.value },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(labelSpacing),
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(iconSize),
                                    tint = tint,
                                )
                                if (item.label != null) {
                                    Text(
                                        text = item.label,
                                        style = MiuixTheme.textStyles.footnote2.copy(fontSize = labelSize),
                                        color = tint,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
