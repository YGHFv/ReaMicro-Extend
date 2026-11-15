package com.reamicro.fix.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private var movementJob: Job? = null
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private var feedbackGeneration = 0L
    private var requestedValue by mutableFloatStateOf(initialValue)
    private var currentVelocity by mutableFloatStateOf(0f)

    private val velocityTracker = VelocityTracker()
    private val startMark = TimeSource.Monotonic.markNow()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = requestedValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = currentVelocity

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCancelled()
                release()
            }
        ) { change, dragAmount ->
            val position = change.position
            val previousPosition = change.previousPosition
            val isInside = canDrag(position)
            val wasInside = canDrag(previousPosition)
            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        feedbackGeneration++
        releaseJob?.cancel()
        releaseJob = null
        pressJob?.cancel()
        velocityTracker.resetTracking()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        val generation = feedbackGeneration
        releaseJob = animationScope.launch {
            // Use the owning Compose frame clock; this also follows the host's motion policy.
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = maxOf(
                    (valueRange.endInclusive - valueRange.start) * 0.025f,
                    visibilityThreshold,
                )
                snapshotFlow { abs(valueAnimation.value - requestedValue) }
                    .first { it <= threshold }
            }
            if (generation != feedbackGeneration) return@launch
            // The press may finish while the indicator travels, but cannot keep writing scales
            // once this request begins releasing them.
            pressJob?.cancelAndJoin()
            if (generation != feedbackGeneration) return@launch
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        if (target == requestedValue && (movementJob?.isActive == true || this.value == target)) return
        startMovement(target, trackVelocity = true)
    }

    fun animateToValue(value: Float) {
        press()
        startMovement(value.coerceIn(valueRange), trackVelocity = false)
        release()
    }

    private fun startMovement(target: Float, trackVelocity: Boolean) {
        movementJob?.cancel()
        requestedValue = target
        movementJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutatorMutex.mutate {
                // Keep the mutation owned until both animations end. Every new drag or click
                // cancels this job and its children before taking control of the same indicator.
                coroutineScope {
                    if (!trackVelocity && currentVelocity != 0f) {
                        launch {
                            velocityAnimation.snapTo(currentVelocity)
                            velocityAnimation.animateTo(0f, velocityAnimationSpec) {
                                currentVelocity = this.value
                            }
                        }
                    }
                    valueAnimation.animateTo(target, valueAnimationSpec) {
                        if (trackVelocity) updateVelocity()
                    }
                }
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            startMark.elapsedNow().inWholeMilliseconds,
            Offset(value, 0f),
        )
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
        // Velocity is a draw-phase value. Updating it directly avoids one coroutine and
        // Animatable mutation for every movement frame; programmatic settling remains a spring.
        currentVelocity = velocityTracker.calculateVelocity().x / span
    }
}
