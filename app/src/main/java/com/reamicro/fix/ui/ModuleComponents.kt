package com.reamicro.fix.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun GroupTitle(text: String) = SmallTitle(text = text)

@Composable
internal fun CardDivider() = HorizontalDivider(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    thickness = 0.5.dp,
    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
)

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String? = null,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showActionDivider: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        modifier = modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).then(bodyModifier),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = containerColor),
        pressFeedbackType = pressFeedbackType,
        onClick = onClick,
        onLongPress = onLongPress,
        showIndication = onClick != null || onLongPress != null,
    ) {
        BasicComponent(
            title = title,
            titleColor = BasicComponentDefaults.titleColor(color = titleColor),
            summary = listOfNotNull(subtitle, description).filter(String::isNotBlank).joinToString("\n").takeIf(String::isNotBlank),
            endActions = if (trailing == null) null else ({ trailing() }),
            insideMargin = PaddingValues(
                start = 16.dp, top = 16.dp, end = 16.dp,
                bottom = if (showActionDivider && actions != null) 0.dp else 16.dp,
            ),
        )
        if (actions != null) {
            if (showActionDivider) CardDivider()
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

@Composable
internal fun CapsuleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(onClick = onClick,
        modifier = Modifier.glassPress(interactionSource, enabled = useModuleGlass()),
        interactionSource = interactionSource,
        minWidth = 35.dp, minHeight = 35.dp, cornerRadius = 50.dp,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
        ),
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, modifier = Modifier.padding(end = 3.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable
internal fun CapsuleTextButton(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(onClick = onClick,
        modifier = Modifier.glassPress(interactionSource, enabled = useModuleGlass()),
        interactionSource = interactionSource,
        minWidth = 35.dp, minHeight = 35.dp, cornerRadius = 50.dp,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
        ),
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
