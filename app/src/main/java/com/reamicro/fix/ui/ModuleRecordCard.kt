package com.reamicro.fix.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

/**
 * Records need both immediate Sink feedback and long-click selection. The public interactive Card
 * does not expose its interaction source, so this surface composes its public non-interactive Card
 * with one callback owner and the same public feedback primitives.
 */
@Composable
internal fun ModuleRecordCard(
    title: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    description: String? = null,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val sink = remember { ModuleJoinedPressIndication(SinkFeedback()) }
    val indication = LocalIndication.current
    val dim = remember(indication) {
        if (indication is IndicationNodeFactory) ModuleJoinedPressIndication(indication) else indication
    }
    val currentClick = rememberUpdatedState(onClick)
    val currentLongPress = rememberUpdatedState(onLongPress)
    Card(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .pressable(
                interactionSource = interactions,
                indication = sink,
                enabled = enabled,
                delay = null,
            ),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = containerColor),
    ) {
        Column(
            Modifier.combinedClickable(
                interactionSource = interactions,
                indication = dim,
                enabled = enabled,
                onClick = { currentClick.value() },
                onLongClick = { currentLongPress.value() },
                // The existing record-selection callback supplies its single haptic.
                hapticFeedbackEnabled = false,
            ),
        ) {
            BasicComponent(
                title = title,
                titleColor = BasicComponentDefaults.titleColor(color = titleColor),
                summary = description?.takeIf(String::isNotBlank),
                endActions = if (trailing == null) null else ({ trailing() }),
                insideMargin = PaddingValues(16.dp),
            )
        }
    }
}
