package com.reamicro.fix.hook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin

internal object StructureFileMotion {
    private fun scale() = spring<Float>(dampingRatio = 1f, stiffness = 1500f)
    fun enter() = scaleIn(animationSpec = scale(), initialScale = .1f,
        transformOrigin = TransformOrigin.Center)
    fun exit() = scaleOut(animationSpec = scale(), targetScale = .1f,
        transformOrigin = TransformOrigin.Center)
}

@Composable
internal fun <T : Any> StructureFileTransition(
    target: T?,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any,
    content: @Composable (T) -> Unit,
) {

    var retained by remember { mutableStateOf<T?>(null) }
    SideEffect { if (target != null) retained = target }
    val shown = target ?: retained
    AnimatedVisibility(visible = target != null, modifier = modifier,
        enter = StructureFileMotion.enter(), exit = StructureFileMotion.exit()) {
        if (shown != null) AnimatedContent(targetState = shown, contentKey = contentKey,
            label = "structure-file-content") { file -> content(file) }
    }
}
