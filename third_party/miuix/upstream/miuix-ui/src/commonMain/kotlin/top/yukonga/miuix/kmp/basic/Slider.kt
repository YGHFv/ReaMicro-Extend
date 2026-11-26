// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.basic

import androidx.annotation.IntRange
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/**
 * A [Slider] component with Miuix style.
 *
 * @param value The current value of the [Slider]. If outside of [valueRange] provided, value will be coerced to this range.
 * @param onValueChange The callback to be called when the value changes.
 * @param modifier The modifier to be applied to the [Slider].
 * @param enabled Whether the [Slider] is enabled.
 * @param valueRange Range of values that this slider can take. The passed [value] will be coerced to this range.
 * @param steps If positive, specifies the amount of discrete allowable values between the endpoints of [valueRange].
 *   For example, a range from 0 to 10 with 4 [steps] allows 4 values evenly distributed between 0 and 10 (i.e., 2, 4, 6, 8).
 *   If [steps] is 0, the slider will behave continuously and allow any value from the range. Must not be negative.
 * @param onValueChangeFinished Called when value change has ended. This should not be used to update the slider value
 *   (use [onValueChange] instead), but rather to know when the user has completed selecting a new value by ending a drag or a click.
 * @param reverseDirection Controls the direction of this slider. When false (default), slider increases from left to right.
 *   When true, slider increases from right to left (useful for RTL layouts or custom direction requirements).
 * @param height The height of the [Slider].
 * @param colors The [SliderColors] of the [Slider].
 * @param hapticEffect The haptic effect of the [Slider].
 * @param showKeyPoints Whether to show the key points (step indicators) on the slider. Only works when [keyPoints] is not null.
 * @param keyPoints Custom key point values to display on the slider. If null, uses step positions from [steps] parameter.
 *   Values should be within [valueRange]. For example, for a range of 0f..100f, you might specify listOf(0f, 25f, 50f, 75f, 100f).
 * @param magnetThreshold The magnetic snap threshold as a fraction (0.0 to 1.0). When the slider value is within this
 *   distance from a key point, it will snap to that point. Default is 0.02 (2%). Only applies when [keyPoints] is set.
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    @IntRange(from = 0) steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    reverseDirection: Boolean = false,
    height: Dp = SliderDefaults.MinHeight,
    colors: SliderColors = SliderDefaults.sliderColors(),
    hapticEffect: SliderDefaults.SliderHapticEffect = SliderDefaults.DefaultHapticEffect,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.02f,
) {
    require(steps >= 0) { "steps should be >= 0" }
    require(valueRange.start < valueRange.endInclusive) { "valueRange start should be less than end" }

    val hapticFeedback = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val effectiveReverseDirection = if (layoutDirection == LayoutDirection.Rtl) !reverseDirection else reverseDirection
    val onValueChangeState by rememberUpdatedState(onValueChange)
    val onValueChangeFinishedState by rememberUpdatedState(onValueChangeFinished)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isHoveringThumb by remember { mutableStateOf(false) }
    var layoutWidth by remember { mutableIntStateOf(0) }
    var layoutHeight by remember { mutableIntStateOf(0) }
    val hapticState = remember { SliderHapticState() }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val coercedValue = value.fastCoerceIn(valueRange.start, valueRange.endInclusive)
    val coercedValueState by rememberUpdatedState(coercedValue)

    val coercedFraction = valueToFraction(coercedValue, valueRange)
    val animatedFractionState = animateFloatAsState(
        targetValue = coercedFraction,
        animationSpec = if (isDragging) DraggingProgressAnimationSpec else SettlingProgressAnimationSpec,
        visibilityThreshold = 0.0001f,
    )
    val thumbScaleState = animateFloatAsState(
        targetValue = if (isPressed || isDragging || isHoveringThumb) 1.127f else 1f,
        animationSpec = ThumbScaleAnimationSpec,
        visibilityThreshold = 0.001f,
    )

    val stepFractions = remember(steps) { stepsToTickFractions(steps) }

    val keyPointFractions = remember(keyPoints, stepFractions, valueRange, showKeyPoints) {
        computeKeyPointFractions(keyPoints, stepFractions, valueRange, showKeyPoints)
    }

    val allKeyPointFractions = remember(keyPoints, stepFractions, valueRange) {
        computeAllKeyPointFractions(keyPoints, stepFractions, valueRange)
    }

    val fractionToValue = remember(valueRange, steps, keyPoints, allKeyPointFractions, magnetThreshold) {
        { fraction: Float ->
            resolveValueFromFraction(
                fraction = fraction,
                valueRange = valueRange,
                steps = steps,
                keyPoints = keyPoints,
                allKeyPointFractions = allKeyPointFractions,
                magnetThreshold = magnetThreshold,
            )
        }
    }
    val fractionProvider = remember { { animatedFractionState.value } }
    val thumbScaleProvider = remember { { thumbScaleState.value } }

    Box(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier
                        .onSizeChanged {
                            layoutWidth = it.width
                            layoutHeight = it.height
                        }
                        .pointerInput(effectiveReverseDirection, valueRange) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.last()

                                    if (event.type == PointerEventType.Exit ||
                                        event.type == PointerEventType.Release ||
                                        change.type != PointerType.Mouse
                                    ) {
                                        isHoveringThumb = false
                                        continue
                                    }

                                    val thumbRadius = layoutHeight / 2f
                                    val availableWidth = (layoutWidth - 2f * thumbRadius).fastCoerceAtLeast(0f)
                                    val knobRadius = thumbRadius * 0.72f
                                    val hitRadius = knobRadius + (thumbRadius * 0.5f)

                                    val position = change.position
                                    val fraction = animatedFractionState.value.fastCoerceIn(0f, 1f)
                                    val effectiveFraction = if (effectiveReverseDirection) 1f - fraction else fraction
                                    val thumbX = thumbRadius + effectiveFraction * availableWidth

                                    val isOver = abs(position.x - thumbX) <= hitRadius
                                    if (isHoveringThumb != isOver) {
                                        isHoveringThumb = isOver
                                    }
                                }
                            }
                        }
                        .hoverable(
                            interactionSource = interactionSource,
                            enabled = enabled,
                        )
                        .pointerInput(effectiveReverseDirection, valueRange, steps, keyPoints, magnetThreshold, hapticEffect) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val thumbRadius = layoutHeight / 2f
                                val availableWidth = (layoutWidth - 2f * thumbRadius).fastCoerceAtLeast(0f)
                                val currentFraction = valueToFraction(coercedValueState, valueRange)
                                val currentEffectiveFraction = if (effectiveReverseDirection) 1f - currentFraction else currentFraction
                                val thumbX = thumbRadius + currentEffectiveFraction * availableWidth
                                val hitRadius = thumbRadius * 0.72f + (thumbRadius * 0.5f)
                                val isOnThumb = abs(down.position.x - thumbX) <= hitRadius
                                val grabOffset = if (isOnThumb) down.position.x - thumbX else 0f
                                var activePress = if (isOnThumb) {
                                    PressInteraction.Press(down.position).also {
                                        interactionSource.tryEmit(it)
                                    }
                                } else {
                                    null
                                }
                                try {
                                    var overSlop = Offset.Zero
                                    val pointerSlop = viewConfiguration.touchSlop
                                    var drag: PointerInputChange?
                                    do {
                                        drag = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        if (drag != null && !drag.isConsumed) {
                                            overSlop += drag.positionChange()
                                        }
                                    } while (
                                        drag != null &&
                                        drag.pressed &&
                                        !drag.isConsumed &&
                                        abs(overSlop.x) < pointerSlop &&
                                        abs(overSlop.y) < pointerSlop
                                    )

                                    if (drag != null && !drag.isConsumed && abs(overSlop.x) >= pointerSlop && abs(overSlop.x) >= abs(
                                            overSlop.y,
                                        )
                                    ) {
                                        drag.consume()
                                        activePress?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                                        activePress = null
                                        val dragInteraction = DragInteraction.Start()
                                        interactionSource.tryEmit(dragInteraction)
                                        isDragging = true
                                        dragOffset = down.position.x + overSlop.x - grabOffset
                                        var lastDispatchedValue = coercedValueState
                                        val initialVisualFraction = horizontalVisualFraction(dragOffset, layoutWidth, layoutHeight)
                                        val initialFractionForValue =
                                            if (effectiveReverseDirection) 1f - initialVisualFraction else initialVisualFraction
                                        val initialValue = fractionToValue(initialFractionForValue)
                                        if (initialValue != lastDispatchedValue) {
                                            lastDispatchedValue = initialValue
                                            onValueChangeState(initialValue)
                                        }
                                        hapticState.reset(initialValue, valueRange, coercedValueState)

                                        var dragSucceeded = false
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (change.changedToUpIgnoreConsumed()) {
                                                    change.consume()
                                                    dragSucceeded = true
                                                    break
                                                }
                                                if (change.isConsumed) break
                                                val delta = change.positionChange().x
                                                change.consume()
                                                if (delta != 0f) {
                                                    dragOffset += delta
                                                    val visualFraction = horizontalVisualFraction(dragOffset, layoutWidth, layoutHeight)
                                                    val fractionForValue =
                                                        if (effectiveReverseDirection) 1f - visualFraction else visualFraction
                                                    val calculatedValue = fractionToValue(fractionForValue)
                                                    if (calculatedValue != lastDispatchedValue) {
                                                        lastDispatchedValue = calculatedValue
                                                        onValueChangeState(calculatedValue)
                                                    }
                                                    hapticState.handleHapticFeedback(
                                                        calculatedValue,
                                                        valueRange,
                                                        hapticEffect,
                                                        hapticFeedback,
                                                        allKeyPointFractions,
                                                        hasCustomKeyPoints = keyPoints != null,
                                                    )
                                                }
                                            }
                                        } finally {
                                            isDragging = false
                                            interactionSource.tryEmit(
                                                if (dragSucceeded) {
                                                    DragInteraction.Stop(dragInteraction)
                                                } else {
                                                    DragInteraction.Cancel(
                                                        dragInteraction,
                                                    )
                                                },
                                            )
                                            onValueChangeFinishedState?.invoke()
                                        }
                                    } else if (drag != null && !drag.pressed && !drag.isConsumed) {
                                        drag.consume()
                                        activePress?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                                        activePress = null
                                        val tapX = down.position.x - grabOffset
                                        val visualFraction = horizontalVisualFraction(tapX, layoutWidth, layoutHeight)
                                        val fractionForValue = if (effectiveReverseDirection) 1f - visualFraction else visualFraction
                                        val calculatedValue = fractionToValue(fractionForValue)
                                        if (calculatedValue != coercedValueState) {
                                            onValueChangeState(calculatedValue)
                                        }
                                        hapticState.reset(calculatedValue, valueRange, coercedValueState)
                                        hapticState.handleHapticFeedback(
                                            calculatedValue,
                                            valueRange,
                                            hapticEffect,
                                            hapticFeedback,
                                            allKeyPointFractions,
                                            hasCustomKeyPoints = keyPoints != null,
                                        )
                                        onValueChangeFinishedState?.invoke()
                                    }
                                } finally {
                                    activePress?.let { interactionSource.tryEmit(PressInteraction.Cancel(it)) }
                                }
                            }
                        }
                        .indication(interactionSource, null)
                } else {
                    Modifier
                },
            )
            .semantics {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    coercedValue,
                    valueRange.start..valueRange.endInclusive,
                    if (steps > 0) steps else 0,
                )
                setProgress { target ->
                    val clamped = target.fastCoerceIn(valueRange.start, valueRange.endInclusive)
                    val resolved = snapValueToStep(clamped, valueRange, steps)
                    if (resolved == coercedValue) {
                        false
                    } else {
                        onValueChangeState(resolved)
                        onValueChangeFinishedState?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        SliderTrack(
            backgroundColor = colors.backgroundColor(enabled),
            foregroundColor = colors.foregroundColor(enabled),
            thumbColor = colors.thumbColor(enabled),
            keyPointColor = colors.keyPointColor(),
            keyPointForegroundColor = colors.keyPointForegroundColor(),
            fractionProvider = fractionProvider,
            isDragging = isDragging,
            isVertical = false,
            showKeyPoints = showKeyPoints,
            stepFractions = keyPointFractions,
            thumbScaleProvider = thumbScaleProvider,
            reverseDirection = effectiveReverseDirection,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged {
                    layoutWidth = it.width
                    layoutHeight = it.height
                },
        )
    }
}

/**
 * Internal slider track renderer
 */
@Composable
private fun SliderTrack(
    backgroundColor: Color,
    foregroundColor: Color,
    thumbColor: Color,
    keyPointColor: Color,
    keyPointForegroundColor: Color,
    fractionProvider: () -> Float,
    isDragging: Boolean,
    isVertical: Boolean,
    showKeyPoints: Boolean,
    stepFractions: FloatArray,
    thumbScaleProvider: () -> Float,
    reverseDirection: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundAlphaState = animateFloatAsState(
        targetValue = if (isDragging) 0.044f else 0f,
        animationSpec = tween(150),
        label = "SliderTrackAlpha",
    )

    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .drawBehind {
                drawRect(backgroundColor)
                drawRect(Color.Black, alpha = backgroundAlphaState.value)
            },
    ) {
        val barHeight = size.height
        val barWidth = size.width
        val fraction = fractionProvider().fastCoerceIn(0f, 1f)
        val thumbScale = thumbScaleProvider()

        if (isVertical) {
            val thumbRadius = barWidth / 2f
            val availableHeight = (barHeight - 2f * thumbRadius).fastCoerceAtLeast(0f)
            val effectiveFraction = if (reverseDirection) fraction else (1f - fraction)
            val centerY = thumbRadius + effectiveFraction * availableHeight

            drawLine(
                color = foregroundColor,
                start = Offset(barWidth / 2f, barHeight),
                end = Offset(barWidth / 2f, centerY),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )

            if (showKeyPoints && stepFractions.isNotEmpty()) {
                val keyPointRadius = barWidth / 7.5f
                for (i in stepFractions.indices) {
                    val stepFraction = stepFractions[i]
                    val effectiveStep = if (reverseDirection) stepFraction else (1f - stepFraction)
                    val y = thumbRadius + effectiveStep * availableHeight
                    val kpColor = if (y >= centerY) keyPointForegroundColor else keyPointColor
                    drawCircle(
                        color = kpColor,
                        radius = keyPointRadius,
                        center = Offset(barWidth / 2f, y),
                    )
                }
            }
            drawCircle(
                color = thumbColor,
                radius = thumbRadius * 0.72f * thumbScale,
                center = Offset(barWidth / 2f, centerY),
            )
        } else {
            val thumbRadius = barHeight / 2f
            val availableWidth = (barWidth - 2f * thumbRadius).fastCoerceAtLeast(0f)
            val effectiveFraction = if (reverseDirection) 1f - fraction else fraction
            val centerX = thumbRadius + effectiveFraction * availableWidth
            val startX = if (reverseDirection) barWidth else 0f

            drawLine(
                color = foregroundColor,
                start = Offset(startX, barHeight / 2f),
                end = Offset(centerX, barHeight / 2f),
                strokeWidth = barHeight,
                cap = StrokeCap.Round,
            )

            if (showKeyPoints && stepFractions.isNotEmpty()) {
                val keyPointRadius = barHeight / 7.5f
                for (i in stepFractions.indices) {
                    val stepFraction = stepFractions[i]
                    val effectiveStep = if (reverseDirection) 1f - stepFraction else stepFraction
                    val x = thumbRadius + effectiveStep * availableWidth
                    val isSelected = if (reverseDirection) x >= centerX else x <= centerX
                    val kpColor = if (isSelected) keyPointForegroundColor else keyPointColor
                    drawCircle(
                        color = kpColor,
                        radius = keyPointRadius,
                        center = Offset(x, barHeight / 2f),
                    )
                }
            }
            drawCircle(
                color = thumbColor,
                radius = thumbRadius * 0.72f * thumbScale,
                center = Offset(centerX, barHeight / 2f),
            )
        }
    }
}

/**
 * Manages haptic feedback state for the slider.
 */
@Stable
internal class SliderHapticState {
    private var edgeFeedbackTriggered: Boolean = false
    private var lastStep: Float = 0f
    private var isAtKeyPoint: Boolean = false

    fun reset(
        currentValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        previousValue: Float = currentValue,
    ) {
        val isAtEdge = currentValue == valueRange.start || currentValue == valueRange.endInclusive
        edgeFeedbackTriggered = isAtEdge && previousValue == currentValue
        lastStep = currentValue
        isAtKeyPoint = false
    }

    fun handleHapticFeedback(
        currentValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        hapticEffect: SliderDefaults.SliderHapticEffect,
        hapticFeedback: HapticFeedback,
        keyPointFractions: FloatArray = floatArrayOf(),
        hasCustomKeyPoints: Boolean = false,
    ) {
        if (hapticEffect == SliderDefaults.SliderHapticEffect.None) return

        handleEdgeHaptic(currentValue, valueRange, hapticFeedback)

        if (hapticEffect == SliderDefaults.SliderHapticEffect.Step) {
            handleStepHaptic(currentValue, valueRange, hapticFeedback, keyPointFractions, hasCustomKeyPoints)
        }
    }

    private fun handleEdgeHaptic(
        currentValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        hapticFeedback: HapticFeedback,
    ) {
        val isAtEdge = currentValue == valueRange.start || currentValue == valueRange.endInclusive
        if (isAtEdge && !edgeFeedbackTriggered) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            edgeFeedbackTriggered = true
        } else if (!isAtEdge) {
            edgeFeedbackTriggered = false
        }
    }

    private fun handleStepHaptic(
        currentValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        hapticFeedback: HapticFeedback,
        keyPointFractions: FloatArray,
        hasCustomKeyPoints: Boolean,
    ) {
        val isNotAtEdge = currentValue != valueRange.start && currentValue != valueRange.endInclusive

        if (hasCustomKeyPoints && keyPointFractions.isNotEmpty()) {
            handleKeyPointHaptic(currentValue, valueRange, hapticFeedback, keyPointFractions, isNotAtEdge)
        } else if (!isNotAtEdge) {
            lastStep = currentValue
        } else if (currentValue != lastStep && isNotAtEdge) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastStep = currentValue
        }
    }

    private fun handleKeyPointHaptic(
        currentValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        hapticFeedback: HapticFeedback,
        keyPointFractions: FloatArray,
        isNotAtEdge: Boolean,
    ) {
        val fraction = (currentValue - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        val threshold = 0.005f

        var nearestDist = Float.MAX_VALUE
        for (i in keyPointFractions.indices) {
            val dist = abs(keyPointFractions[i] - fraction)
            if (dist < nearestDist) nearestDist = dist
        }
        val currentlyAtKeyPoint = nearestDist < threshold

        if (currentlyAtKeyPoint && !isAtKeyPoint && isNotAtEdge) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        isAtKeyPoint = currentlyAtKeyPoint
    }
}

private val EmptyFloatArray = floatArrayOf()
private val DraggingProgressAnimationSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 1755f, visibilityThreshold = 0.0001f)
private fun valueToFraction(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
): Float {
    val span = valueRange.endInclusive - valueRange.start
    return if (span > 0f) ((value - valueRange.start) / span).fastCoerceIn(0f, 1f) else 0f
}

private val SettlingProgressAnimationSpec = spring<Float>(dampingRatio = 0.96f, stiffness = 322f, visibilityThreshold = 0.0001f)

private fun stepsToTickFractions(steps: Int): FloatArray = if (steps == 0) EmptyFloatArray else FloatArray(steps + 2) { it.toFloat() / (steps + 1) }

private fun snapValueToStep(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (steps <= 0) return value
    val stepCount = steps + 1
    val start = valueRange.start.toDouble()
    val end = valueRange.endInclusive.toDouble()
    val span = end - start
    if (span <= 0.0) return value
    val fraction = ((value.toDouble() - start) / span).coerceIn(0.0, 1.0)
    val stepIndex = (fraction * stepCount).fastRoundToInt().fastCoerceIn(0, stepCount)
    return (start + span * stepIndex / stepCount).toFloat()
}

private fun resolveValueFromFraction(
    fraction: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    keyPoints: List<Float>? = null,
    allKeyPointFractions: FloatArray,
    magnetThreshold: Float,
): Float {
    val f = fraction.fastCoerceIn(0f, 1f)
    val base = lerp(valueRange.start, valueRange.endInclusive, f)
    return when {
        steps > 0 -> {
            val stepCount = steps + 1
            val start = valueRange.start.toDouble()
            val end = valueRange.endInclusive.toDouble()
            val stepIndex = (f * stepCount).fastRoundToInt().fastCoerceIn(0, stepCount)
            (start + (end - start) * stepIndex / stepCount).toFloat()
        }

        allKeyPointFractions.isNotEmpty() -> {
            var bestIndex = 0
            var closest = allKeyPointFractions[0]
            var bestDist = abs(closest - f)
            for (i in 1 until allKeyPointFractions.size) {
                val cand = allKeyPointFractions[i]
                val dist = abs(cand - f)
                if (dist < bestDist) {
                    bestDist = dist
                    closest = cand
                    bestIndex = i
                }
            }
            if (bestDist < magnetThreshold) {
                keyPoints?.getOrNull(bestIndex)?.fastCoerceIn(valueRange.start, valueRange.endInclusive)
                    ?: lerp(valueRange.start, valueRange.endInclusive, closest)
            } else {
                base
            }
        }

        else -> base
    }
}

private val ThumbScaleAnimationSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 987f, visibilityThreshold = 0.001f)
private fun horizontalVisualFraction(offsetX: Float, sizeWidth: Int, sizeHeight: Int): Float {
    val thumbRadius = sizeHeight / 2f
    val availableWidth = (sizeWidth.toFloat() - 2f * thumbRadius).fastCoerceAtLeast(0f)
    return if (availableWidth == 0f) 0f else ((offsetX - thumbRadius) / availableWidth).fastCoerceIn(0f, 1f)
}

/**
 * Converts point values to normalized fractions within the value range.
 */
private fun pointsToFractions(
    points: List<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
): FloatArray {
    val span = valueRange.endInclusive - valueRange.start
    return FloatArray(points.size) { index ->
        ((points[index] - valueRange.start) / span).fastCoerceIn(0f, 1f)
    }
}

/**
 * Computes key point fractions for slider display.
 * Filters out points too close to edges.
 */
private fun computeKeyPointFractions(
    keyPoints: List<Float>?,
    stepFractions: FloatArray,
    valueRange: ClosedFloatingPointRange<Float>,
    showKeyPoints: Boolean,
): FloatArray = when {
    keyPoints != null -> pointsToFractions(keyPoints, valueRange)
    showKeyPoints -> stepFractions
    else -> floatArrayOf()
}

/**
 * Computes all key point fractions including edge points.
 * Used for haptic feedback and magnetic snapping.
 */
private fun computeAllKeyPointFractions(
    keyPoints: List<Float>?,
    stepFractions: FloatArray,
    valueRange: ClosedFloatingPointRange<Float>,
): FloatArray = when {
    keyPoints != null -> pointsToFractions(keyPoints, valueRange)
    stepFractions.isNotEmpty() -> stepFractions
    else -> floatArrayOf()
}

object SliderDefaults {
    /**
     * The minimum height of the [Slider] and [RangeSlider].
     */
    val MinHeight = 28.dp

    /**
     * The radius of the key points on the [Slider] and [RangeSlider].
     */
    val KeyPointRadius = 3.855.dp

    /**
     * The type of haptic feedback to be used for the slider.
     */
    enum class SliderHapticEffect {
        /** No haptic feedback. */
        None,

        /** Haptic feedback at 0% and 100%. */
        Edge,

        /** Haptic feedback at steps. */
        Step,
    }

    /**
     * The default haptic effect of the [Slider] and [RangeSlider].
     */
    val DefaultHapticEffect = SliderHapticEffect.Edge

    @Composable
    fun sliderColors(
        foregroundColor: Color = MiuixTheme.colorScheme.primary,
        disabledForegroundColor: Color = MiuixTheme.colorScheme.disabledPrimarySlider,
        backgroundColor: Color = MiuixTheme.colorScheme.sliderBackground,
        disabledBackgroundColor: Color = MiuixTheme.colorScheme.disabledSecondary,
        thumbColor: Color = MiuixTheme.colorScheme.onPrimary,
        disabledThumbColor: Color = MiuixTheme.colorScheme.disabledOnPrimary,
        keyPointColor: Color = MiuixTheme.colorScheme.sliderKeyPoint,
        keyPointForegroundColor: Color = MiuixTheme.colorScheme.sliderKeyPointForeground,
    ): SliderColors = remember(
        foregroundColor,
        disabledForegroundColor,
        backgroundColor,
        disabledBackgroundColor,
        thumbColor,
        disabledThumbColor,
        keyPointColor,
        keyPointForegroundColor,
    ) {
        SliderColors(
            foregroundColor = foregroundColor,
            disabledForegroundColor = disabledForegroundColor,
            backgroundColor = backgroundColor,
            disabledBackgroundColor = disabledBackgroundColor,
            thumbColor = thumbColor,
            disabledThumbColor = disabledThumbColor,
            keyPointColor = keyPointColor,
            keyPointForegroundColor = keyPointForegroundColor,
        )
    }
}

@Immutable
data class SliderColors(
    private val foregroundColor: Color,
    private val disabledForegroundColor: Color,
    private val backgroundColor: Color,
    private val disabledBackgroundColor: Color,
    private val thumbColor: Color,
    private val disabledThumbColor: Color,
    private val keyPointColor: Color,
    private val keyPointForegroundColor: Color,
) {
    @Stable
    internal fun foregroundColor(enabled: Boolean): Color = if (enabled) foregroundColor else disabledForegroundColor

    @Stable
    internal fun backgroundColor(enabled: Boolean): Color = if (enabled) backgroundColor else disabledBackgroundColor

    @Stable
    internal fun thumbColor(enabled: Boolean): Color = if (enabled) thumbColor else disabledThumbColor

    @Stable
    internal fun keyPointColor(): Color = keyPointColor

    @Stable
    internal fun keyPointForegroundColor(): Color = keyPointForegroundColor
}
