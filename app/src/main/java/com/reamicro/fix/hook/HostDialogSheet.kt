package com.reamicro.fix.hook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reamicro.fix.ui.ModuleTypography
import kotlinx.coroutines.launch

/** 宿主实测尺寸随宿主界面缩放一起换算。 */
internal class HostDialogLook(val palette: HostEmbeddedStyle.Palette, val scale: Float, val type: ModuleTypography) {
    fun dp(value: Float): Dp = (value * scale).dp
    val card = RoundedCornerShape(dp(BookImageDialogStyle.CARD_CORNER))
    val button = RoundedCornerShape(dp(BookImageDialogStyle.BUTTON_CORNER))
    val border get() = dp(BookImageDialogStyle.BORDER)
    val press = HostPressIndication(palette.text.copy(alpha = .1f))
}

/** Shared host sheet: the image, association and metadata surfaces use the same measured geometry. */
@Composable
internal fun HostDialogSheet(
    look: HostDialogLook,
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    cancelLabel: String = "取消",
    scrollToEndKey: Any? = null,
    scroll: ScrollState = rememberScrollState(),
    choiceList: Boolean = false,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val dismissAllowed by rememberUpdatedState(dismissEnabled)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(ENTER_MILLIS, easing = FastOutSlowInEasing)) }
    fun close() {
        if (!dismissAllowed || closing) return
        closing = true
        scope.launch {
            shown.animateTo(0f, tween(EXIT_MILLIS, easing = FastOutSlowInEasing))
            // A write may begin while the closing animation is still running.
            if (dismissAllowed) dismiss() else {
                drag = 0f
                shown.animateTo(1f, tween(ENTER_MILLIS, easing = FastOutSlowInEasing))
                closing = false
            }
        }
    }
    val selection = remember(look.palette) {
        TextSelectionColors(look.palette.primary, look.palette.primary.copy(alpha = .4f))
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * shown.value) }
                .pointerInput(Unit) { detectTapGestures { close() } })
            BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                contentAlignment = Alignment.BottomCenter) {
                val inset = if (maxWidth < look.dp(360f)) look.dp(12f) else look.dp(20f)
                val gap = look.dp(16f)
                val sheetHeight = maxHeight * if (choiceList) .7f else 1f
                val fontScale = LocalDensity.current.fontScale
                // A chooser pins its cancel row when the header, footer and a usable list row fit.
                val roomy = if (choiceList) sheetHeight >= look.dp(160f + 48f * fontScale)
                    else sheetHeight >= look.dp(560f) && fontScale < 1.5f
                LaunchedEffect(scrollToEndKey) {
                    if (scrollToEndKey != null) { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) }
                }
                val dismissDistance = with(LocalDensity.current) { look.dp(96f).toPx() }
                val dragState = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) }
                val shape = RoundedCornerShape(topStart = look.dp(BookImageDialogStyle.SHEET_CORNER),
                    topEnd = look.dp(BookImageDialogStyle.SHEET_CORNER))
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = sheetHeight)
                    .graphicsLayer { translationY = (1f - shown.value) * size.height + drag }
                    .clip(shape).background(look.palette.content)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .semantics { paneTitle = title }) {
                    Column(Modifier.fillMaxWidth().draggable(dragState, Orientation.Vertical,
                        enabled = dismissEnabled && !closing, onDragStopped = { velocity ->
                            if (drag > dismissDistance || velocity > FLING_VELOCITY) close()
                            else Animatable(drag).animateTo(0f) { drag = value }
                        }), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = look.dp(BookImageDialogStyle.HANDLE_TOP),
                            bottom = look.dp(BookImageDialogStyle.HANDLE_BOTTOM))
                            .size(look.dp(BookImageDialogStyle.HANDLE_WIDTH), look.dp(BookImageDialogStyle.HANDLE_HEIGHT))
                            .background(look.palette.surfaceHighest))
                        HostDialogLabel(title, look.type.titleSmall, look.palette.text, Modifier.fillMaxWidth()
                            .padding(horizontal = inset).padding(bottom = gap).semantics { heading() })
                    }
                    val footer: @Composable ColumnScope.() -> Unit = {
                        if (actions != null) {
                            HostDialogCard(look, actions)
                            Spacer(Modifier.height(gap))
                        }
                        HostDialogCard(look) {
                            HostDialogAction(look, cancelLabel, HostEmbeddedIcons.Cancel, look.palette.caption,
                                look.type.bodyLarge, enabled = dismissEnabled && !closing) { close() }
                        }
                    }
                    // Keep fields at one composition location when IME insets change the available height.
                    // Moving the body between branches disposes the focused field and closes the IME.
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                        .then(if (roomy) Modifier else Modifier.verticalScroll(scroll))
                        .padding(horizontal = inset).padding(bottom = gap)) {
                        if (body != null) {
                            val bodyModifier = Modifier.fillMaxWidth()
                                .then(if (roomy) Modifier.weight(1f, fill = false) else Modifier)
                            val bodyScroll = if (roomy) Modifier.verticalScroll(scroll) else Modifier
                            if (choiceList) Column(bodyModifier) {
                                HostDialogCard(look) {
                                    Column(Modifier.fillMaxWidth().then(bodyScroll), content = body)
                                }
                            } else Column(bodyModifier.then(bodyScroll),
                                verticalArrangement = Arrangement.spacedBy(gap), content = body)
                            Spacer(Modifier.height(gap))
                        }
                        Column(Modifier.fillMaxWidth(), content = footer)
                    }
                }
            }
        }
    }
}

@Composable internal fun HostDialogLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start) {
    BasicText(text, modifier, style.copy(color = color, textAlign = align),
        overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** 宿主卡片：clip → 0.8dp 描边 → 底色。 */
@Composable internal fun HostDialogCard(look: HostDialogLook, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(look.card).border(look.border, look.palette.borderVariant, look.card)
        .background(look.palette.page), content = content)
}

@Composable internal fun HostDialogDivider(look: HostDialogLook, inset: Dp = look.dp(ROW_START)) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(look.border).background(look.palette.borderVariant))
}

@Composable internal fun HostDialogIcon(vector: ImageVector, tint: Color, modifier: Modifier) {
    Image(rememberVectorPainter(vector), null, modifier, colorFilter = ColorFilter.tint(tint))
}

/** 宿主菜单行：内边距 18 / 16 / 12 / 16，20dp 图标 + 16dp 间距，尾部箭头下移 2dp。 */
@Composable internal fun HostDialogAction(look: HostDialogLook, label: String, icon: ImageVector, color: Color, style: TextStyle,
    enabled: Boolean = true, loading: Boolean = false, description: String? = null, onClick: () -> Unit) {
    val content = if (enabled || loading) color else color.copy(alpha = color.alpha * DISABLED_ALPHA)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(remember { MutableInteractionSource() }, look.press, enabled = enabled,
            role = Role.Button, onClick = onClick)
        .padding(start = look.dp(ROW_START), top = look.dp(ROW_VERTICAL), end = look.dp(ROW_END),
            bottom = look.dp(ROW_VERTICAL)),
        verticalAlignment = Alignment.CenterVertically) {
        val lead = Modifier.padding(end = look.dp(ROW_ICON_GAP)).size(look.dp(ROW_ICON))
        if (loading) HostDialogSpinner(look, lead) else HostDialogIcon(icon, content, lead)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.dp(2f))) {
            HostDialogLabel(label, style, content)
            if (description != null) HostDialogLabel(description, look.type.bodyMedium, look.palette.caption)
        }
        HostDialogIcon(HostEmbeddedIcons.NavigateNext, look.palette.surfaceHighest, Modifier.padding(top = look.dp(2f)))
    }
}

/** 宿主按钮内的环形进度：20dp、线宽 2dp、tertiary，轨道为弹窗底色。 */
@Composable internal fun HostDialogSpinner(look: HostDialogLook, modifier: Modifier) {
    val turn by rememberInfiniteTransition().animateFloat(0f, 360f,
        infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)))
    val color = look.palette.native.role("Tertiary")
    val track = look.palette.content
    Box(modifier.progressSemantics().drawBehind {
        val stroke = look.dp(2f).toPx()
        val area = Size(size.width - stroke, size.height - stroke)
        val origin = Offset(stroke / 2f, stroke / 2f)
        drawArc(track, 0f, 360f, false, origin, area, style = Stroke(stroke))
        drawArc(color, turn - 90f, SPIN_SWEEP, false, origin, area, style = Stroke(stroke))
    })
}

/** 按下时整行叠加一层前景色，对应宿主 Material 3 的按压态。 */
internal data class HostPressIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color)
}

private class PressNode(private val source: InteractionSource, private val color: Color) :
    Modifier.Node(), DrawModifierNode {
    private var pressed = false
    override fun onAttach() {
        coroutineScope.launch {
            var active = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> active++
                    is PressInteraction.Release, is PressInteraction.Cancel -> active--
                }
                val now = active > 0
                if (now != pressed) { pressed = now; invalidateDraw() }
            }
        }
    }
    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(color)
    }
}


@Composable internal fun HostDialogField(look: HostDialogLook, label: String, value: String, enabled: Boolean, hint: String,
    minLines: Int = 1, maxLines: Int = 1, uri: Boolean = false, singleLine: Boolean = false,
    compact: Boolean = false,
    keyboardType: KeyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text,
    error: String? = null, onNext: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null, changed: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val palette = look.palette
    val style = look.type.bodyLarge
    val fontScale = LocalDensity.current.fontScale
    val inline = compact && singleLine && fontScale < 1.5f
    Column(Modifier.fillMaxWidth().padding(start = look.dp(if (compact) 16f else ROW_START), end = look.dp(ROW_END),
        top = look.dp(if (inline) 0f else if (compact) 12f else 14f),
        bottom = look.dp(if (inline) 0f else if (compact) 4f else 6f))) {
        if (!inline) HostDialogLabel(label, if (compact) style else look.type.labelMedium, palette.caption)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(look.dp(8f)),
            verticalAlignment = Alignment.CenterVertically) {
            if (inline) HostDialogLabel(label, style, palette.caption,
                Modifier.width(look.dp(40f * fontScale)))
            BasicTextField(value, { next -> changed(if (keyboardType == KeyboardType.Uri) next.replace("\r", "").replace("\n", "") else next) },
                Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label },
                enabled = enabled, singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
                maxLines = if (singleLine) 1 else maxLines,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType,
                    imeAction = if (onNext != null) ImeAction.Next else if (uri || singleLine || maxLines == 1) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }, onDone = { focus.clearFocus(); keyboard?.hide() }),
                textStyle = style.copy(color = palette.text), cursorBrush = SolidColor(palette.text),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().padding(vertical = look.dp(if (compact && !inline) 4f else 8f)),
                        contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart) {
                        if (value.isEmpty()) HostDialogLabel(hint, style, palette.caption, maxLines = 1)
                        field()
                    }
                })
            if (trailing != null) trailing()
        }
        error?.let { HostDialogLabel(it, look.type.bodySmall, palette.error,
            Modifier.padding(start = if (inline) look.dp(40f * fontScale + 8f) else 0.dp,
                bottom = look.dp(8f)).semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}


private const val ROW_START = 18f
private const val ROW_END = 12f
private const val ROW_VERTICAL = 16f
private const val ROW_ICON = 20f
private const val ROW_ICON_GAP = 16f
private const val DISABLED_ALPHA = .38f
private const val SCRIM_ALPHA = .32f
private const val ENTER_MILLIS = 280
private const val EXIT_MILLIS = 200
private const val SPIN_MILLIS = 1000
private const val SPIN_SWEEP = 270f
private const val FLING_VELOCITY = 1600f
