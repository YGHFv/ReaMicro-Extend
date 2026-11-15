package com.reamicro.fix.hook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset

internal object StructureFileMotion {
    // Both host NavHosts use full-width horizontal slides with this IntOffset spring.
    // Module Compose cannot accept the host's obfuscated transition objects.
    fun slide() = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f,
        visibilityThreshold = IntOffset(1, 1))
    fun enter() = slideInHorizontally(animationSpec = slide(), initialOffsetX = { it })
    fun exit() = slideOutHorizontally(animationSpec = slide(), targetOffsetX = { it })
    fun pushExit() = slideOutHorizontally(animationSpec = slide(), targetOffsetX = { -it })
    fun popEnter() = slideInHorizontally(animationSpec = slide(), initialOffsetX = { -it })
}

@Composable
internal fun Modifier.structureCoveredPage(covered: Boolean): Modifier {
    var width by remember { mutableIntStateOf(0) }
    val offset by animateIntOffsetAsState(
        targetValue = if (covered) IntOffset(-width, 0) else IntOffset.Zero,
        animationSpec = StructureFileMotion.slide(),
        label = "structure-editor-backing-page",
    )
    return this.onSizeChanged { width = it.width }.offset { offset }
}

/** Keep the outgoing page's prepared content while another route is loading or returning. */
@Composable
internal fun <T : Any> rememberStructurePageContent(key: Any, value: T?): T? {
    var retained by remember(key) { mutableStateOf<T?>(null) }
    SideEffect { if (value != null) retained = value }
    return value ?: retained
}

private data object StructureRootPageKey

@Composable
internal fun <T : Any> StructureFileTransition(
    target: T?,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any,
    animated: Boolean = true,
    rootContent: @Composable () -> Unit,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentKey = { it?.let(contentKey) ?: StructureRootPageKey },
        transitionSpec = {
            when {
                !animated -> EnterTransition.None togetherWith ExitTransition.None
                initialState != null && targetState == null ->
                    StructureFileMotion.popEnter() togetherWith StructureFileMotion.exit()
                else -> StructureFileMotion.enter() togetherWith StructureFileMotion.pushExit()
            }.using(null)
        },
        label = "structure-file-content",
    ) { file ->
        if (file == null) rootContent() else content(file)
    }
}
