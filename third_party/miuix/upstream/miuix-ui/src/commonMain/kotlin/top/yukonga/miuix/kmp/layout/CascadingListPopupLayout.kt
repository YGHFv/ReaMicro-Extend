// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.layout

import androidx.compose.animation.core.SpringSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.anim.folmeSpring

private const val MAIN_SPRING_DAMPING = 0.95f
private const val EXPAND_SPRING_DAMPING = 0.99f
private const val EXPAND_SPRING_RESPONSE = 0.45f
private const val COLLAPSE_SPRING_RESPONSE = 0.2f
private const val ARROW_EXPAND_SPRING_RESPONSE = 0.2f
private const val ARROW_COLLAPSE_SPRING_RESPONSE = 0.3f
private const val PRIMARY_SHRUNK_SCALE = 0.95f

/**
 * The springs and the figures a cascading popup stacks on.
 *
 * A menu built outside this file stacks the same way by reading them from here. Restating any of
 * them elsewhere lets the two drift apart, and the stacking is one motion however the panel is
 * painted.
 */
object CascadingPopupDefaults {

    /** How far the first menu shrinks while a second one stands in front of it. */
    val PrimaryShrunkScale: Float = PRIMARY_SHRUNK_SCALE

    /** How far the trigger row's chevron turns once the second menu is open, in degrees. */
    fun arrowRotation(layoutDirection: LayoutDirection): Float = if (layoutDirection == LayoutDirection.Ltr) -90f else 90f

    /**
     * The spring the second menu opens and closes on.
     *
     * @param expanding Whether the menu is opening. It closes on a shorter and firmer spring.
     */
    fun <T> expandSpring(expanding: Boolean): SpringSpec<T> = if (expanding) {
        folmeSpring(EXPAND_SPRING_DAMPING, EXPAND_SPRING_RESPONSE)
    } else {
        folmeSpring(MAIN_SPRING_DAMPING, COLLAPSE_SPRING_RESPONSE)
    }

    /**
     * The spring the chevron turns on, which trails the panel on the way back.
     *
     * @param expanding Whether the menu is opening.
     */
    fun <T> arrowSpring(expanding: Boolean): SpringSpec<T> = folmeSpring(
        damping = MAIN_SPRING_DAMPING,
        response = if (expanding) ARROW_EXPAND_SPRING_RESPONSE else ARROW_COLLAPSE_SPRING_RESPONSE,
    )
}
