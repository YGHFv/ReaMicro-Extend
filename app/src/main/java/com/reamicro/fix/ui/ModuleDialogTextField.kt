package com.reamicro.fix.ui

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassTextField

/** Match the input material to the dialog surface selected by the application theme. */
@Composable
internal fun ModuleDialogTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    inputTransformation: InputTransformation? = null,
) {
    if (useModuleGlass()) {
        GlassTextField(
            state = state,
            label = label,
            modifier = modifier,
            enabled = enabled,
            lineLimits = lineLimits,
            inputTransformation = inputTransformation,
        )
    } else {
        TextField(
            state = state,
            label = label,
            modifier = modifier,
            enabled = enabled,
            lineLimits = lineLimits,
            inputTransformation = inputTransformation,
        )
    }
}
