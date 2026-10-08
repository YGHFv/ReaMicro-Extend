package com.reamicro.fix.hook

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import com.reamicro.fix.R
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal data class StructureEditorModel(
    val identity: Any,
    val label: String,
    val value: String,
    val allowEmpty: Boolean = false,
    val sourceMode: Boolean = false,
    val note: String? = null,
    val title: String? = null,
    val confirmLabel: String? = null,
    val singleLine: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StructureEditorOverlay(
    model: StructureEditorModel?,
    palette: StructureHome130Style.Palette,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEdited: () -> Unit,
    onSave: (String) -> Unit,
    onValueChanged: (String) -> Unit = {},
    supportingContent: @Composable () -> Unit = {},
) {
    var retained by remember { mutableStateOf<StructureEditorModel?>(null) }
    SideEffect { if (model != null && retained !== model) retained = model }
    val shown = model ?: retained
    val visible by rememberUpdatedState(model != null)
    val enabled by rememberUpdatedState(!busy)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(model?.identity) {
        if (model == null && retained != null) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    val visibilityState = remember { MutableTransitionState(false) }
    LaunchedEffect(model != null) { visibilityState.targetState = model != null }
    if (shown == null) return

    BoxWithConstraints(Modifier.fillMaxSize().imePadding().zIndex(3f),
        contentAlignment = Alignment.Center) {

        val geometry = StructureEditorMetrics.layout(maxWidth.value, maxHeight.value,
            search = shown.singleLine)
        val horizontal = geometry.horizontalInset.dp
        val bottom = geometry.bottomReservation.dp
        val fieldMaxHeight = geometry.fieldMaxHeight.dp
        val scrimAlpha by animateFloatAsState(
            targetValue = if (model != null) .1f else 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
            label = "structure-editor-scrim",
        )
        if (model != null || visibilityState.currentState || !visibilityState.isIdle || scrimAlpha > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(enabled = !busy, indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClickLabel = "关闭编辑框", onClick = { if (model != null) onDismiss() }))
        }
        AnimatedVisibility(
            visibleState = visibilityState,
            modifier = Modifier.align(Alignment.Center).wrapContentSize(),
            enter = StructureOverlayMotion.enter(),
            exit = StructureOverlayMotion.exit(),
        ) {
            key(shown.identity) {
                DisposableEffect(shown.identity) {
                    onDispose { if (!visible) retained = null }
                }
                var value by remember { mutableStateOf(TextFieldValue(shown.value, TextRange(0, shown.value.length))) }
                val requester = remember { FocusRequester() }
                LaunchedEffect(shown.identity) {
                    withFrameNanos { }
                    if (visible && enabled) {
                        requester.requestFocus()
                        keyboard?.show()
                    }
                }
                val canSave = visible && !busy && (shown.allowEmpty || value.text.isNotBlank())
                val submit = {
                    if (canSave) {
                        keyboard?.hide()
                        onSave(value.text)
                    }
                }
                Column(Modifier.padding(horizontal = horizontal).padding(bottom = bottom)
                    .shadow(StructureEditorMetrics.shadow.dp, RoundedCornerShape(StructureEditorMetrics.corner.dp))
                    .clip(RoundedCornerShape(StructureEditorMetrics.corner.dp)).background(palette.bright)
                    .heightIn(max = (maxHeight - bottom).coerceAtLeast(96.dp))
                    .verticalScroll(rememberScrollState())
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .semantics { paneTitle = shown.title ?: "编辑"; isTraversalGroup = true }
                    .padding(16.dp)) {
                    Text(shown.title ?: stringResource(R.string.epub_structure_editor_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall, color = palette.text)
                    if (shown.label.isNotBlank()) Text(shown.label, Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = palette.caption)
                    if (!shown.note.isNullOrBlank()) Text(shown.note, Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = palette.caption,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(top = 12.dp), thickness = .8.dp, color = palette.borderVariant)

                    TextField(
                        value = value,
                        onValueChange = { value = it; onEdited(); onValueChanged(it.text) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = fieldMaxHeight).focusRequester(requester),
                        enabled = visible && !busy, singleLine = shown.singleLine,
                        keyboardOptions = KeyboardOptions(imeAction = if (shown.singleLine) ImeAction.Search else ImeAction.Default,
                            autoCorrectEnabled = !shown.sourceMode),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        textStyle = if (shown.sourceMode) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                            else MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(StructureEditorMetrics.corner.dp),
                        isError = error != null,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = palette.text, unfocusedTextColor = palette.text,
                            disabledTextColor = palette.caption, cursorColor = palette.text,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                        ),
                    )
                    supportingContent()
                    if (!error.isNullOrBlank()) Text(error, Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = palette.error)
                    HorizontalDivider(Modifier.padding(bottom = 16.dp), thickness = .8.dp, color = palette.borderVariant)
                    OutlinedButton(
                        onClick = submit,
                        modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = shown.confirmLabel ?: "保存编辑内容" },
                        enabled = canSave,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(.8.dp, palette.borderVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = palette.text, disabledContentColor = palette.caption),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = palette.native.role("Tertiary"), trackColor = palette.content)
                        else Text(shown.confirmLabel ?: stringResource(R.string.epub_structure_editor_confirm), style = MaterialTheme.typography.labelMedium,
                            color = if (canSave) palette.text else palette.caption)
                    }
                }
            }
        }
    }
}
