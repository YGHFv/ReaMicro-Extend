package com.reamicro.fix.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import kotlinx.coroutines.CancellationException

/**
 * Keep normal taps and scrolling under Foundation's Main/Final arbitration. Once long press is
 * accepted, the list owns Initial-pass moves and up, before nested switches and action buttons.
 */
internal suspend fun PointerInputScope.detectModuleTaskDrag(
    onStart: (Offset) -> Boolean,
    onDelta: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        var accepted = false
        try {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            // A switch/button already owns this down. Holding it must not also reorder its task.
            if (down.isConsumed) return@awaitEachGesture
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!onStart(held.position)) return@awaitEachGesture
            accepted = true
            held.consume()
            var pointer = held.id
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointer }
                event.changes.forEach { it.consume() }
                if (change == null || !change.pressed) {
                    val remaining = event.changes.firstOrNull { it.pressed }
                    if (remaining == null) {
                        accepted = false
                        onEnd()
                        break
                    }
                    pointer = remaining.id
                    continue
                }
                val delta = change.positionChangeIgnoreConsumed()
                if (delta != Offset.Zero) onDelta(delta)
            }
        } catch (error: CancellationException) {
            if (accepted) {
                accepted = false
                onCancel()
            }
            throw error
        } finally {
            if (accepted) onCancel()
        }
    }
}
