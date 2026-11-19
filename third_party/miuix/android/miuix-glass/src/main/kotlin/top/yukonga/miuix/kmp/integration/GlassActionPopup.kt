package top.yukonga.miuix.kmp.integration

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/** Host-only Android integration; the vendored upstream Glass popup owns material and motion. */
@Composable
fun GlassActionPopup(
    show: Boolean,
    anchorBounds: Rect,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(show) { visibility.targetState = show }
    // A mirror of the popup's public morph spring retains its host through the exit.
    val transition = rememberTransition(visibility, label = "actionPopupHost")
    transition.animateFloat(transitionSpec = { GlassMotion.popupMorph() }, label = "hostRetention") {
        if (it) 1f else 0f
    }
    val hostVisible = remember { mutableStateOf(false) }
    hostVisible.value = visibility.currentState || visibility.targetState || !visibility.isIdle
    PopupLayout(
        visible = hostVisible,
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        renderInRootScaffold = true,
    ) {
        Box(Modifier.fillMaxSize()) {
            GlassPopup(
                show = show,
                onDismissRequest = onDismissRequest,
                anchorBounds = anchorBounds,
                backdrop = backdrop,
                content = content,
            )
        }
    }
}
