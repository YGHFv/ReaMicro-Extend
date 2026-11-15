// Adapted from KernelSU manager (GPL-3.0); see assets/licenses/KernelSU-effect-LICENSE.txt.

package com.reamicro.fix.ui.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Own the prepared material and its clock independently of a retained pager page. */
@Stable
internal class BgEffectState {
    var painter by mutableStateOf<BgEffectPainter?>(null)
    val colorStage = Animatable(0f)
    var animationTimeSeconds = 0f
}
