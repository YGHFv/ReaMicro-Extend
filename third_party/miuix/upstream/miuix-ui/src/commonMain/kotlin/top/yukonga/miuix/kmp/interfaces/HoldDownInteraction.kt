// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.interfaces

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction.HoldDown
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction.Release

/**
 * An interaction related to hold down events.
 *
 * @see HoldDown
 * @see Release
 */
interface HoldDownInteraction : Interaction {
    /**
     * An interaction representing a hold down event on a component.
     *
     * @see Release
     */
    class HoldDown : HoldDownInteraction

    /**
     * An interaction representing a [HoldDown] event being released on a component.
     *
     * @property holdDown the source [HoldDown] interaction that is being released
     *
     * @see HoldDown
     */
    class Release(
        val holdDown: HoldDown,
    ) : HoldDownInteraction
}

/**
 * Mirrors [holdDownState] into [interactionSource], releasing any in-flight [HoldDown] on dispose.
 */
@Composable
internal fun HoldDownObserver(
    holdDownState: Boolean,
    interactionSource: MutableInteractionSource,
) {
    if (!holdDownState) return
    LaunchedEffect(interactionSource) {
        val hold = HoldDown()
        interactionSource.emit(hold)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { interactionSource.emit(Release(hold)) }
        }
    }
}
