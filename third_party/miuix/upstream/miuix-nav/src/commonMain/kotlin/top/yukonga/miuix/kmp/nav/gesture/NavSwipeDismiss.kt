// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.nav.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.runtime.NavSettleSink
import top.yukonga.miuix.kmp.nav.runtime.anchoredProgress
import top.yukonga.miuix.kmp.nav.runtime.commitVelocityFloor
import top.yukonga.miuix.kmp.nav.runtime.navBackCommitDecision
import top.yukonga.miuix.kmp.nav.runtime.settleCancel
import top.yukonga.miuix.kmp.nav.runtime.settleTo
import top.yukonga.miuix.kmp.nav.runtime.snapToFinger
import top.yukonga.miuix.kmp.nav.runtime.trackSettle
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

/**
 * Implementation body shared by the public [navSwipeDismiss] and NavDisplay's internal wiring:
 * [settleSink], when present, publishes the release settle as a
 * [top.yukonga.miuix.kmp.nav.transition.NavSettle] context (the bare public modifier drives and
 * settles but publishes no context). [externalGestureOwnership] is an even/odd generation token:
 * odd means another source (such as system predictive back) currently owns the gesture, and every
 * ownership change advances the value. A pointer sequence latches any generation change so queued
 * work cannot resume after a fast external start+finish cycle.
 */
internal fun Modifier.navSwipeDismissImpl(
    enabled: Boolean,
    direction: NavSwipeDirection,
    animatedTop: Animatable<Float, AnimationVector1D>,
    topIndex: Int,
    motion: NavMotion,
    settleSink: NavSettleSink?,
    externalGestureOwnership: () -> Long = { 0L },
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onGesture: (NavGesture?) -> Unit,
): Modifier {
    if (!enabled || direction == NavSwipeDirection.None) return this
    return this then NavSwipeDismissElement(
        direction = direction,
        animatedTop = animatedTop,
        topIndex = topIndex,
        motion = motion,
        settleSink = settleSink,
        externalGestureOwnership = externalGestureOwnership,
        onCommit = onCommit,
        onCancel = onCancel,
        onGesture = onGesture,
    )
}

private data class NavSwipeDismissElement(
    private val direction: NavSwipeDirection,
    private val animatedTop: Animatable<Float, AnimationVector1D>,
    private val topIndex: Int,
    private val motion: NavMotion,
    private val settleSink: NavSettleSink?,
    private val externalGestureOwnership: () -> Long,
    private val onCommit: () -> Unit,
    private val onCancel: () -> Unit,
    private val onGesture: (NavGesture?) -> Unit,
) : ModifierNodeElement<NavSwipeDismissNode>() {
    override fun create(): NavSwipeDismissNode = NavSwipeDismissNode(
        direction = direction,
        animatedTop = animatedTop,
        topIndex = topIndex,
        motion = motion,
        settleSink = settleSink,
        externalGestureOwnership = externalGestureOwnership,
        onCommit = onCommit,
        onCancel = onCancel,
        onGesture = onGesture,
    )

    override fun update(node: NavSwipeDismissNode) {
        node.update(
            direction = direction,
            animatedTop = animatedTop,
            topIndex = topIndex,
            motion = motion,
            settleSink = settleSink,
            externalGestureOwnership = externalGestureOwnership,
            onCommit = onCommit,
            onCancel = onCancel,
            onGesture = onGesture,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "navSwipeDismiss"
        properties["direction"] = direction
        properties["topIndex"] = topIndex
    }
}

private class FollowSample {
    @Volatile
    var fingerProgress: Float = 0f

    @Volatile
    var touchY: Float = 0f
}

private class NavSwipeDismissNode(
    var direction: NavSwipeDirection,
    var animatedTop: Animatable<Float, AnimationVector1D>,
    var topIndex: Int,
    var motion: NavMotion,
    var settleSink: NavSettleSink?,
    var externalGestureOwnership: () -> Long,
    var onCommit: () -> Unit,
    var onCancel: () -> Unit,
    var onGesture: (NavGesture?) -> Unit,
) : DelegatingNode() {
    private val velocityTracker = VelocityTracker()
    private val arbitrator = NavSwipeArbitrator(0f)

    private val pointerInputNode = delegate(
        SuspendingPointerInputModifierNode {
            val activeDirection = direction
            val activeAnimatedTop = animatedTop
            val activeTopIndex = topIndex
            val activeSettleSink = settleSink
            // Edge semantics for gesture-aware transitions: a horizontal back swipe reads as starting from
            // the screen edge the finger travels away from; vertical dismissal has no meaningful edge.
            val swipeEdge = when (activeDirection) {
                NavSwipeDirection.LeftToRight -> NavSwipeEdge.Left
                NavSwipeDirection.RightToLeft -> NavSwipeEdge.Right
                else -> NavSwipeEdge.None
            }
            val isHorizontal = activeDirection == NavSwipeDirection.LeftToRight || activeDirection == NavSwipeDirection.RightToLeft
            // Sign mapping a raw axis delta onto "progress toward dismiss": +1 when the dismiss direction is the
            // axis-positive one (right / down), -1 when it is axis-negative (left / up).
            val dismissSign = when (activeDirection) {
                NavSwipeDirection.LeftToRight, NavSwipeDirection.TopToBottom -> 1f
                NavSwipeDirection.RightToLeft, NavSwipeDirection.BottomToTop -> -1f
                NavSwipeDirection.None -> 0f
            }

            val pointerInputContext = coroutineContext
            awaitEachGesture {
                // Layout extent along the gesture axis, used to normalise finger travel into 0f..1f progress.
                // Read per-gesture from the live pointer scope so a resize between gestures is picked up.
                val extent = (if (isHorizontal) size.width else size.height).toFloat().coerceAtLeast(1f)
                val slop = viewConfiguration.touchSlop

                val down = awaitFirstDown(requireUnconsumed = false)
                val initialExternalOwnership = externalGestureOwnership()
                var sequenceJob: Job? = null
                var followChannel: Channel<Unit>? = null
                var blockedByExternalGesture = false
                fun externalGestureOwnsSequence(): Boolean {
                    if (!blockedByExternalGesture && (
                            initialExternalOwnership and 1L != 0L ||
                                externalGestureOwnership() != initialExternalOwnership
                            )
                    ) {
                        blockedByExternalGesture = true
                        followChannel?.close()
                        sequenceJob?.cancel()
                    }
                    return blockedByExternalGesture
                }

                // Tracks pointer position over time for an accurate instantaneous release velocity. Sampled
                // from the down so the velocity window is populated even before the gesture engages.
                velocityTracker.resetTracking()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                // --- Engagement phase: descendants see Main first. The pure arbiter keeps child
                // consumption evidence independent from navigation's touch-slop eligibility, so a
                // lower-slop child can establish permanent ownership before navigation may engage. ---
                arbitrator.reset(slop)
                var initialDismissTravelPx = 0f
                var claimTouchY = down.position.y
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.fastFirstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (externalGestureOwnsSequence()) return@awaitEachGesture
                    if (hasMultiplePressedPointers(event.changes)) {
                        arbitrator.cancel()
                        return@awaitEachGesture
                    }
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        arbitrator.onEnd()
                        return@awaitEachGesture // lifted before engaging: a tap / short press
                    }
                    val delta = change.positionChangeIgnoreConsumed()
                    if (delta.x == 0f && delta.y == 0f) continue
                    val axisDelta = if (isHorizontal) delta.x else delta.y
                    val crossDelta = if (isHorizontal) delta.y else delta.x
                    when (
                        arbitrator.onPositionChange(
                            towardDeltaPx = dismissSign * axisDelta,
                            crossDeltaPx = crossDelta,
                            consumed = change.isConsumed,
                        )
                    ) {
                        NavSwipeArbitrationOutcome.Continue -> Unit

                        NavSwipeArbitrationOutcome.YieldToChild,
                        NavSwipeArbitrationOutcome.Cancelled,
                        -> return@awaitEachGesture

                        NavSwipeArbitrationOutcome.ClaimNavigation -> {
                            // Keep touch slop as a dead zone, then catch up to the recognized drag.
                            initialDismissTravelPx = arbitrator.initialDismissTravelPx
                            claimTouchY = change.position.y
                            change.consume()
                            break
                        }
                    }
                }

                // Anchor and finger-snap work belongs to this pointer sequence, not to the whole
                // composition. Parenting it to pointerInput also cancels queued mutations when
                // this modifier restarts; external ownership cancels it explicitly below.
                val activeSequenceJob = Job(pointerInputContext[Job])
                sequenceJob = activeSequenceJob
                val sequenceScope = CoroutineScope(pointerInputContext + activeSequenceJob)
                val followSample = FollowSample()
                val sequenceChannel = Channel<Unit>(Channel.CONFLATED)
                followChannel = sequenceChannel

                var anchor = Float.NaN
                sequenceScope.launch {
                    if (externalGestureOwnsSequence()) return@launch
                    activeAnimatedTop.stop()
                    if (externalGestureOwnsSequence()) return@launch
                    anchor = activeTopIndex - activeAnimatedTop.value
                    val initialFingerProgress = initialDismissTravelPx / extent
                    if (initialFingerProgress > 0f) {
                        activeAnimatedTop.snapToFinger(topIndex = activeTopIndex, progress = initialFingerProgress, anchor = anchor)
                        if (externalGestureOwnsSequence()) return@launch
                        onGesture(
                            NavGesture(
                                progress = anchoredProgress(anchor = anchor, fingerProgress = initialFingerProgress).coerceAtLeast(0f),
                                swipeEdge = swipeEdge,
                                touchY = claimTouchY,
                                initialTouchY = down.position.y,
                            ),
                        )
                    }
                    for (@Suppress("UNUSED_VARIABLE") signal in sequenceChannel) {
                        if (externalGestureOwnsSequence()) return@launch
                        val fingerProgress = followSample.fingerProgress
                        val touchY = followSample.touchY
                        activeAnimatedTop.snapToFinger(topIndex = activeTopIndex, progress = fingerProgress, anchor = anchor)
                        if (externalGestureOwnsSequence()) return@launch
                        onGesture(
                            NavGesture(
                                progress = anchoredProgress(anchor = anchor, fingerProgress = fingerProgress).coerceAtLeast(0f),
                                swipeEdge = swipeEdge,
                                touchY = touchY,
                                initialTouchY = down.position.y,
                            ),
                        )
                    }
                }

                // --- Follow phase: we own the pointer. Consume every change on both axes (so neither a nested
                // scroll nor any in-page handler sees the finger, and no cross-axis movement can cancel). The
                // dismiss catches up to the recognized pre-claim travel, then follows 1:1 and ends only on lift. ---
                var dismissTravelPx = initialDismissTravelPx
                var forceCancel = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (externalGestureOwnsSequence()) return@awaitEachGesture
                    val change = event.changes.fastFirstOrNull { it.id == down.id }
                    if (forceCancel || event.changes.fastAny { it.id != down.id && it.pressed } || change == null) {
                        forceCancel = true
                        event.changes.fastForEach { it.consume() }
                        if (!event.changes.fastAny { it.pressed }) break
                        continue
                    }
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        // Compose represents platform cancellation as an already-consumed release.
                        // It must never commit, even before predictive back acquires the driver.
                        forceCancel = change.isConsumed
                        event.changes.fastForEach { it.consume() }
                        break
                    }
                    val delta = change.positionChange()
                    val axisDelta = if (isHorizontal) delta.x else delta.y
                    dismissTravelPx += dismissSign * axisDelta
                    change.consume()
                    // Unclamped here; anchoredProgress clamps the total to [min(anchor, 0), MAX_FINGER_PROGRESS].
                    followSample.fingerProgress = dismissTravelPx / extent
                    followSample.touchY = change.position.y
                    sequenceChannel.trySend(Unit)
                }

                sequenceChannel.close()
                if (externalGestureOwnsSequence()) return@awaitEachGesture

                // --- Release: velocity-first / position-fallback, velocity-continuous spring handoff. ---
                val releaseAnchor = if (anchor.isNaN()) activeTopIndex - activeAnimatedTop.value else anchor
                val progress = anchoredProgress(anchor = releaseAnchor, fingerProgress = dismissTravelPx / extent).coerceAtLeast(0f)
                val velocity = velocityTracker.calculateVelocity()
                val axisVelocity = if (isHorizontal) velocity.x else velocity.y
                val progressVelocity = if (forceCancel) 0f else dismissSign * axisVelocity / extent
                val depthVelocity = -progressVelocity
                if (!forceCancel && navBackCommitDecision(progress = progress, velocity = progressVelocity)) {
                    activeSequenceJob.cancel()
                    if (activeSettleSink != null) {
                        activeSettleSink.pendingSettleVelocity = depthVelocity
                        onCommit()
                    } else {
                        onCommit()
                        coroutineScope.launch {
                            if (externalGestureOwnsSequence()) return@launch
                            val motionNow = motion
                            val floor = motionNow.commit.commitVelocityFloor(activeAnimatedTop.value - (activeTopIndex - 1f))
                            activeAnimatedTop.settleTo(
                                target = (activeTopIndex - 1).toFloat(),
                                spec = motionNow.commit,
                                initialVelocity = depthVelocity.coerceAtLeast(floor),
                            )
                        }
                    }
                } else {
                    coroutineScope.launch {
                        if (externalGestureOwnsSequence()) return@launch
                        val settleBody: suspend (onFrame: (() -> Unit)?) -> Unit = { onFrame ->
                            activeAnimatedTop.settleCancel(
                                target = activeTopIndex.toFloat(),
                                spec = motion.cancel,
                                initialVelocity = depthVelocity.coerceAtMost(0f),
                                onFrame = onFrame,
                            )
                        }
                        if (activeSettleSink != null) {
                            activeSettleSink.trackSettle(phase = NavSettlePhase.Cancel, releaseVelocity = progressVelocity) { onFrame ->
                                settleBody(onFrame)
                            }
                        } else {
                            settleBody(null)
                        }
                        if (externalGestureOwnsSequence()) return@launch
                        onGesture(null)
                        onCancel()
                    }
                    activeSequenceJob.complete()
                }
            }
        },
    )

    fun update(
        direction: NavSwipeDirection,
        animatedTop: Animatable<Float, AnimationVector1D>,
        topIndex: Int,
        motion: NavMotion,
        settleSink: NavSettleSink?,
        externalGestureOwnership: () -> Long,
        onCommit: () -> Unit,
        onCancel: () -> Unit,
        onGesture: (NavGesture?) -> Unit,
    ) {
        val needsPointerReset =
            this.direction != direction ||
                this.topIndex != topIndex ||
                this.animatedTop !== animatedTop
        this.direction = direction
        this.animatedTop = animatedTop
        this.topIndex = topIndex
        this.motion = motion
        this.settleSink = settleSink
        this.externalGestureOwnership = externalGestureOwnership
        this.onCommit = onCommit
        this.onCancel = onCancel
        this.onGesture = onGesture
        if (needsPointerReset) {
            pointerInputNode.resetPointerInputHandler()
        }
    }
}

private fun hasMultiplePressedPointers(changes: List<PointerInputChange>): Boolean {
    var pressedCount = 0
    for (i in changes.indices) {
        if (changes[i].pressed && ++pressedCount > 1) return true
    }
    return false
}
