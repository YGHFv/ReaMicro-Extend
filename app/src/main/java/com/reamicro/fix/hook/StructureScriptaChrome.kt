package com.reamicro.fix.hook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.yukonga.scripta.editor.EditorColors

internal object StructureScriptaChrome {
    fun colors(palette: StructureHome130Style.Palette): EditorColors =
        (if (palette.dark) EditorColors.Default else EditorColors.Light).copy(
            background = palette.content,
            foreground = palette.text,
            gutterBackground = palette.content,
            gutterForeground = palette.caption,
            cursor = palette.primary,
            handle = palette.primary,
            selection = palette.primary.copy(alpha = .22f),
            currentLine = palette.primary.copy(alpha = .045f),
            symbolBarBackground = palette.content,
            symbolBarForeground = palette.text,
            symbolBarPressed = palette.primary.copy(alpha = .14f),
            findMatch = palette.primary.copy(alpha = .16f),
            findMatchActive = palette.primary.copy(alpha = .35f),
        )
}

@Composable
internal fun StructureSourceHeader(extension: String, state: StructureScriptaUiState, palette: StructureHome130Style.Palette) {
    Row(Modifier.fillMaxWidth().heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp)
        .padding(horizontal = 16.dp, vertical = StructureSearchChromeMetrics.sectionGap.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${extension.uppercase(java.util.Locale.ROOT)} 源码", Modifier.weight(1f),
            style = structureEditorControlTextStyle(), color = palette.text)
        Text(when { state.loading -> "读取中"; state.saving -> "保存中"; state.searching -> "处理中"; state.modified -> "未保存"; else -> "未修改" },
            style = structureEditorControlTextStyle(), color = if (state.modified) palette.primary else palette.caption)
    }
    HorizontalDivider(thickness = StructureContentStyle.dividerWidth, color = palette.divider)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StructureEditorFooter(
    palette: StructureHome130Style.Palette, state: StructureScriptaUiState,
    line: Int, column: Int, format: String, softWrap: Boolean, showDetails: Boolean,
    onUndo: () -> Unit, onRedo: () -> Unit, onSearch: () -> Unit,
    onSave: () -> Unit, onGotoLine: () -> Unit, onSoftWrap: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides StructureSearchChromeMetrics.actionTouchHeight.dp) {
        Column(Modifier.fillMaxWidth().padding(bottom = StructureSearchChromeMetrics.sectionGap.dp)) {
            HorizontalDivider(thickness = StructureContentStyle.dividerWidth, color = palette.divider)
            if (showDetails) FlowRow(Modifier.fillMaxWidth().heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text("行 $line · 列 $column", style = structureEditorControlTextStyle(), color = palette.caption)
                Text(format, style = structureEditorControlTextStyle(), color = palette.caption)
            }

            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StructureSearchChromeMetrics.controlGap.dp),
                verticalArrangement = Arrangement.spacedBy(StructureSearchChromeMetrics.sectionGap.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                IconButton(modifier = Modifier.size(StructureSearchChromeMetrics.actionTouchHeight.dp), enabled = state.canUndo, onClick = onUndo) {
                    Icon(StructureEditorIcons.Undo, "撤销", tint = if (state.canUndo) palette.text else palette.icon)
                }
                IconButton(modifier = Modifier.size(StructureSearchChromeMetrics.actionTouchHeight.dp), enabled = state.canRedo, onClick = onRedo) {
                    Icon(StructureEditorIcons.Redo, "重做", tint = if (state.canRedo) palette.text else palette.icon)
                }
                IconButton(modifier = Modifier.size(StructureSearchChromeMetrics.actionTouchHeight.dp), enabled = state.canSearch, onClick = onSearch) {
                    Icon(StructureIcons.FindReplace, "查找替换", tint = if (state.canSearch) palette.text else palette.icon)
                }
                StructureCompactOutlinedAction(if (state.saving) "保存中" else "保存",
                    state.canSave, palette, onClick = onSave, busy = state.saving)
                TextButton(enabled = state.interactive, onClick = onGotoLine,
                    contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp)) {
                    Text("行号", style = structureEditorControlTextStyle())
                }
                SearchToggle("换行", softWrap, state.interactive, palette, onSoftWrap)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StructureScriptaSearchPanel(
    palette: StructureHome130Style.Palette, modifier: Modifier = Modifier,
    query: String, replacement: String, scope: EpubSearchScope, regex: Boolean, textOnly: Boolean,
    enabled: Boolean, processing: Boolean, hasMatches: Boolean, status: String,
    onQueryChange: (String) -> Unit, onReplacementChange: (String) -> Unit,
    onScopeChange: (EpubSearchScope) -> Unit, onRegexChange: (Boolean) -> Unit, onTextOnlyChange: (Boolean) -> Unit,
    onSearch: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onReplaceCurrent: () -> Unit, onReplaceAll: () -> Unit, onClose: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide(); focusManager.clearFocus() }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides StructureSearchChromeMetrics.actionTouchHeight.dp) {
        Column(modifier.fillMaxWidth().background(palette.content)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(vertical = StructureSearchChromeMetrics.sectionGap.dp),
                verticalArrangement = Arrangement.spacedBy(StructureSearchChromeMetrics.sectionGap.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status.ifBlank { "查找替换" }, Modifier.weight(1f), style = structureEditorControlTextStyle(), color = palette.caption)
                    IconButton(modifier = Modifier.size(StructureSearchChromeMetrics.actionTouchHeight.dp), enabled = !processing, onClick = onClose) {
                        Icon(StructureHome130Icons.Close, "关闭查找替换", Modifier.size(20.dp), tint = palette.caption)
                    }
                }
                SearchField(query, "查找内容", palette, enabled, ImeAction.Search, onQueryChange, onSearch,
                    requestFocusOnShow = true)
                SearchField(replacement, "替换为（留空则删除）", palette, enabled, ImeAction.Done, onReplacementChange, {})
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StructureSearchChromeMetrics.resultActionGap.dp),
                    verticalArrangement = Arrangement.spacedBy(StructureSearchChromeMetrics.sectionGap.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = enabled, onClick = {
                        onScopeChange(if (scope == EpubSearchScope.CURRENT) EpubSearchScope.HTML else EpubSearchScope.CURRENT)
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(if (scope == EpubSearchScope.CURRENT) "范围：当前" else "范围：HTML", style = structureEditorControlTextStyle())
                    }
                    SearchToggle("正则", regex, enabled, palette) { onRegexChange(!regex) }
                    SearchToggle("仅文本", textOnly, enabled, palette) { onTextOnlyChange(!textOnly) }
                    StructureCompactOutlinedAction(if (processing) "查找中" else "查找",
                        enabled && query.isNotEmpty(), palette, onClick = onSearch, busy = processing)
                    StructureSearchTextAction("上一个", enabled && hasMatches, palette, onPrevious)
                    StructureSearchTextAction("下一个", enabled && hasMatches, palette, onNext)
                    StructureSearchTextAction("替换当前", enabled && hasMatches, palette, onReplaceCurrent)
                    StructureSearchTextAction("替换全部", enabled && query.isNotEmpty(), palette, onReplaceAll)
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, label: String, palette: StructureHome130Style.Palette,
    enabled: Boolean, imeAction: ImeAction, onValueChange: (String) -> Unit, onSubmit: () -> Unit,
    requestFocusOnShow: Boolean = false) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestFocusOnShow && enabled) {
            withFrameNanos { }
            focus.requestFocus()
        }
    }
    val textStyle = structureEditorControlTextStyle()
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).focusRequester(focus).semantics { contentDescription = label },
        textStyle = textStyle.copy(color = palette.text),
        cursorBrush = SolidColor(palette.primary),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { if (enabled) onSubmit() }),
        decorationBox = { inner ->
            Surface(shape = RoundedCornerShape(StructureContentStyle.actionCorner), color = palette.page,
                border = BorderStroke(StructureContentStyle.dividerWidth, palette.borderVariant)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(label, style = textStyle, color = palette.caption)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun SearchToggle(label: String, selected: Boolean, enabled: Boolean,
    palette: StructureHome130Style.Palette, onToggle: () -> Unit) {
    val textStyle = structureEditorControlTextStyle()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val side = StructureSearchChromeMetrics.checkboxSide(textStyle.fontSize.value, density.fontScale).dp
    val markColor = MaterialTheme.colorScheme.onPrimary
    val boxColor = (if (selected) palette.primary else palette.caption).copy(alpha = if (enabled) 1f else .45f)
    Row(Modifier.heightIn(min = StructureSearchChromeMetrics.actionTouchHeight.dp).clip(RoundedCornerShape(4.dp))
        .toggleable(value = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox,
            onValueChange = { onToggle() })
        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {

        androidx.compose.foundation.Canvas(Modifier.size(side)) {
            val stroke = 1.dp.toPx()
            drawRoundRect(color = boxColor,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                style = if (selected) androidx.compose.ui.graphics.drawscope.Fill
                    else androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (selected) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .22f, size.height * .51f)
                    lineTo(size.width * .43f, size.height * .72f)
                    lineTo(size.width * .80f, size.height * .28f)
                }
                drawPath(path, markColor, style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        Text(label, style = textStyle, color = if (enabled) palette.text else palette.caption)
    }
}

@Composable
internal fun StructureScriptaUnsavedPrompt(
    visible: Boolean, filename: String, switching: Boolean, palette: StructureHome130Style.Palette, busy: Boolean,
    onContinue: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    var retainedName by remember { mutableStateOf(filename) }
    var retainedSwitching by remember { mutableStateOf(switching) }
    SideEffect { if (visible) { retainedName = filename; retainedSwitching = switching } }
    if (!visible && state.isIdle && !state.currentState) return
    BoxWithConstraints(Modifier.fillMaxSize().semantics { isTraversalGroup = true; paneTitle = "处理未保存修改" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = StructureContentStyle.overlayScrimAlpha))
            .clickable(enabled = visible && !busy, indication = null,
                interactionSource = remember { MutableInteractionSource() }, onClick = onContinue))
        AnimatedVisibility(visibleState = state, enter = StructureOverlayMotion.enter(), exit = StructureOverlayMotion.exit()) {
            Column(Modifier.padding(horizontal = if (maxWidth >= 320.dp) 48.dp else 16.dp)
                .heightIn(max = maxHeight).shadow(16.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)).background(palette.bright)
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(16.dp)) {
                Text(if (retainedSwitching) "切换文件？" else "保存修改？",
                    style = MaterialTheme.typography.titleSmall, color = palette.text)
                Text(if (retainedSwitching) "切换前需要处理 $retainedName 的未保存修改。"
                    else "返回后将丢弃 $retainedName 的未保存修改。",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = palette.caption)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = StructureContentStyle.dividerWidth,
                    color = palette.borderVariant)
                for ((label, color, action) in listOf(
                    Triple(if (retainedSwitching) "保存并切换" else "保存并返回", palette.primary, onSave),
                    Triple("继续编辑", palette.text, onContinue), Triple("不保存", palette.error, onDiscard))) {
                    OutlinedButton(onClick = action, enabled = visible && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(StructureContentStyle.actionCorner),
                        border = BorderStroke(StructureContentStyle.dividerWidth, palette.borderVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private object StructureEditorIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name = name, defaultWidth = 24.dp,
        defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = true).apply {
        addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    }.build()
    val Undo = icon("Undo", "M12.5,8C9.85,8 7.45,8.99 5.6,10.6L2,7v9h9l-3.62,-3.62C8.77,11.22 10.54,10.5 12.5,10.5c3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val Redo = icon("Redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22l2.36,0.78c1.05,-3.19 4.06,-5.5 7.6,-5.5c1.96,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z")
}
