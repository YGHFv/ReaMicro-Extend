package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.View
import android.view.Window
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.association.ManualAssociationService
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.reload.ModuleUiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-styled metadata draft; the original host candidate screen still confirms the association. */
internal class HostManualAssociationEditor(
    private val activity: Activity,
    initial: ManualAssociationDraft,
    sources: List<BookSource>,
    private val isCurrent: () -> Boolean,
    private val prepareCover: (String, () -> Unit) -> String?,
    private val onClosed: () -> Unit,
    private val onPreview: (ManualAssociationDraft, String?) -> Unit,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    private enum class Selection { PLATFORM, STATUS }
    private data class Choice(val id: String, val title: String)

    private val availableSources = sources.distinctBy { it.id }
    private val service = ManualAssociationService { availableSources }
    private var draft by mutableStateOf(initial.copy(source = availableSources.firstOrNull { it == initial.source }
        ?: availableSources.firstOrNull() ?: initial.source))
    private var selection by mutableStateOf<Selection?>(null)
    private var submitted by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)
    private var configurationEpoch by mutableIntStateOf(0)
    @Volatile private var closed = false
    private var compose: ComposeView? = null
    private var callbackRegistered = false
    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { configurationEpoch++ }
        override fun onLowMemory() = Unit
    }

    override fun show() {
        if (isShowing || closed) return
        if (activity.isFinishing || activity.isDestroyed) { release(); return }
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener { release() }
            owner.start()
            val view = ComposeView(context)
            compose = view
            view.apply {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                owner.attach(this)
                setContent { this@HostManualAssociationEditor.Content() }
            }
            setContentView(view)
            window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                findViewById<View>(android.R.id.content)?.apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                }
            }
            activity.registerComponentCallbacks(configuration)
            callbackRegistered = true
            val native = HostThemeColors.snapshot(activity, HostEmbeddedStyle.isDark(activity))
            configureEpubEdgeToEdgeWindow(window, native.contentArgb, native.dark)
            window?.setWindowAnimations(0)
            super.show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setDimAmount(0f)
        } catch (error: Throwable) {
            runCatching { release() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun dismiss() {
        try { release() } finally { super.dismiss() }
    }

    private fun release() {
        if (closed) return
        closed = true
        val view = compose
        compose = null
        selection = null
        draft = draft.copy(title = "", author = "", intro = "", words = "", status = "", coverUrl = "")
        try {
            view?.disposeComposition()
        } finally {
            try { owner.close() } finally {
                if (callbackRegistered) {
                    callbackRegistered = false
                    activity.unregisterComponentCallbacks(configuration)
                }
                onClosed()
            }
        }
    }

    private fun preview(scope: CoroutineScope) {
        if (closed || submitted) return
        val normalized = draft.normalized()
        val validation = service.validate(normalized)
        if (!validation.isValid) { problem = validation.firstError; return }
        submitted = true
        problem = null
        scope.launch {
            try {
                val jobContext = currentCoroutineContext()
                val staged = withContext(ModuleUiRuntime.io) {
                    prepareCover(normalized.coverUrl) {
                        jobContext.ensureActive()
                        check(!closed && !activity.isFinishing && !activity.isDestroyed && isCurrent()) {
                            "关联页面已变化，请重新打开手动编辑"
                        }
                    }
                }
                check(!closed && !activity.isFinishing && !activity.isDestroyed && isCurrent()) {
                    "关联页面已变化，请重新打开手动编辑"
                }
                onPreview(normalized, staged)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!closed) problem = error.message ?: "生成关联预览失败，请重试"
            } finally {
                if (!closed) submitted = false
            }
        }
    }

    private fun changed(next: ManualAssociationDraft) {
        if (closed || submitted) return
        draft = next
        problem = null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Content() {
        val config = LocalConfiguration.current
        val native = HostThemeColors.snapshot(activity, HostEmbeddedStyle.isDark(activity))
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(configurationEpoch, config, scale, EmbeddedHostUi.typographyStyles) {
            BookImageDialogStyle.typography(activity, scale)
        }
        val foreground = native.role("OnBackground")
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val scope = rememberCoroutineScope()
        val validation = service.validate(draft)
        val submit = { focus.clearFocus(); keyboard?.hide(); preview(scope) }
        val maxHeight = (config.screenHeightDp * .75f).dp
        LaunchedEffect(native) {
            configureEpubEdgeToEdgeWindow(window, native.contentArgb, native.dark)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setDimAmount(0f)
        }
        MaterialTheme(colorScheme = native.controlsScheme(), typography = typography) {
            ModalBottomSheet(onDismissRequest = { dismiss() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = RoundedCornerShape(topStart = (20f * scale).dp, topEnd = (20f * scale).dp),
                containerColor = Color(native.contentArgb), contentColor = foreground, tonalElevation = 0.dp,
                contentWindowInsets = { WindowInsets.safeDrawing },
                dragHandle = { SheetHandle(scale, native) }) {
                // The sheet needs the full window for its bottom anchor; limit only its content.
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                    .padding(horizontal = (20f * scale).dp).padding(bottom = (16f * scale).dp)) {
                    Text("手动编辑", Modifier.semantics { heading() }.padding(bottom = (16f * scale).dp),
                        style = typography.labelLarge, color = foreground)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy((18f * scale).dp)) {
                        Surface(shape = RoundedCornerShape((8f * scale).dp), color = Color(native.brightArgb),
                            contentColor = foreground, border = BorderStroke((.8f * scale).dp,
                                Color(native.borderVariantArgb).copy(alpha = .68f))) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = (18f * scale).dp, vertical = (16f * scale).dp),
                                verticalArrangement = Arrangement.spacedBy((12f * scale).dp)) {
                                Field("封面地址", draft.coverUrl, "请输入封面地址", scale, native,
                                    keyboardType = KeyboardType.Uri, error = validation.coverError.takeIf { draft.coverUrl.isNotEmpty() },
                                    onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(coverUrl = it)) }
                                Field("书名", draft.title, "请输入书名", scale, native,
                                    error = validation.titleError.takeIf { draft.title.isNotEmpty() },
                                    onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(title = it)) }
                                Field("作者", draft.author, "请输入作者", scale, native,
                                    error = validation.authorError.takeIf { draft.author.isNotEmpty() },
                                    onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(author = it)) }
                                SelectionField("平台", draft.source.displayName, scale, native, validation.sourceError) {
                                    focus.clearFocus(); keyboard?.hide(); selection = Selection.PLATFORM
                                }
                                Field("字数", draft.words, "未填写", scale, native,
                                    error = validation.wordsError.takeIf { draft.words.isNotEmpty() },
                                    onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(words = it)) }
                                SelectionField("状态", draft.status.ifBlank { "请选择状态" }, scale, native,
                                    validation.statusError.takeIf { draft.status.isNotEmpty() }) {
                                    focus.clearFocus(); keyboard?.hide(); selection = Selection.STATUS
                                }
                                Field("简介", draft.intro, "未填写", scale, native, multiline = true,
                                    error = validation.introError.takeIf { draft.intro.isNotEmpty() },
                                    onNext = submit) { changed(draft.copy(intro = it)) }
                            }
                        }
                        problem?.let {
                            Text(it, style = typography.bodyMedium, color = native.role("Error"),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Spacer(Modifier.height((8f * scale).dp))
                    }
                    Column(Modifier.fillMaxWidth().padding(top = (4f * scale).dp),
                        verticalArrangement = Arrangement.spacedBy((8f * scale).dp)) {
                        com.reamicro.fix.ui.DialogActionButton(onClick = submit, enabled = validation.isValid && !submitted,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (submitted) "正在生成…" else "预览", style = typography.labelLarge)
                        }
                        com.reamicro.fix.ui.DialogActionButton(onClick = { dismiss() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("取消", style = typography.labelLarge)
                        }
                    }
                }
            }
            selection?.let { current ->
                val choices = if (current == Selection.PLATFORM) availableSources.map { Choice(it.id, it.displayName) }
                    else ManualAssociationDraft.STATUS_OPTIONS.map { Choice(it, it.ifBlank { "未填写" }) }
                val selected = if (current == Selection.PLATFORM) draft.source.id else draft.status
                ChoiceSheet(if (current == Selection.PLATFORM) "选择平台" else "状态", choices, selected, scale, native) { id ->
                    if (current == Selection.PLATFORM) availableSources.firstOrNull { it.id == id }
                        ?.let { changed(draft.copy(source = it)) }
                    else changed(draft.copy(status = id))
                    selection = null
                }
            }
        }
    }

    @Composable
    private fun SheetHandle(scale: Float, native: HostThemeColors.Snapshot) {
        Box(Modifier.fillMaxWidth().padding(top = (14f * scale).dp, bottom = (16f * scale).dp),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size((40f * scale).dp, (4f * scale).dp)
                .background(native.role("SurfaceContainerHighest"), RoundedCornerShape(scale.dp)))
        }
    }

    @Composable
    private fun Field(
        label: String, value: String, hint: String, scale: Float, native: HostThemeColors.Snapshot,
        keyboardType: KeyboardType = KeyboardType.Text, multiline: Boolean = false, error: String? = null,
        onNext: () -> Unit, onChange: (String) -> Unit,
    ) {
        val foreground = native.role("OnBackground")
        val caption = Color(native.captionArgb)
        val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        val height = ((if (multiline) 72f else 48f) * scale).dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((4f * scale).dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = foreground)
            BasicTextField(value, onChange, enabled = !submitted, singleLine = !multiline,
                minLines = if (multiline) 3 else 1, maxLines = if (multiline) 3 else 1,
                textStyle = style.copy(color = foreground), cursorBrush = SolidColor(native.role("Primary")),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType,
                    imeAction = if (multiline) ImeAction.Default else ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onNext() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = height).border((1.2f * scale).dp,
                    if (error == null) Color(native.borderVariantArgb).copy(alpha = .76f) else native.role("Error"),
                    RoundedCornerShape((12f * scale).dp)).semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = height)
                        .padding(horizontal = (14f * scale).dp, vertical = if (multiline) (10f * scale).dp else 0.dp),
                        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = style, color = caption,
                            maxLines = if (multiline) 3 else 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                })
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = native.role("Error"),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }

    @Composable
    private fun SelectionField(
        label: String, value: String, scale: Float, native: HostThemeColors.Snapshot,
        error: String? = null, onClick: () -> Unit,
    ) {
        val foreground = native.role("OnBackground")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((4f * scale).dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = foreground)
            Row(Modifier.fillMaxWidth().heightIn(min = (48f * scale).dp).border((1.2f * scale).dp,
                if (error == null) Color(native.borderVariantArgb).copy(alpha = .76f) else native.role("Error"),
                RoundedCornerShape((12f * scale).dp)).clickable(enabled = !submitted, role = Role.Button,
                    onClickLabel = "选择$label", onClick = onClick).padding(horizontal = (14f * scale).dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(value, modifier = Modifier.weight(1f), color = foreground,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Icon(HostEmbeddedIcons.Expanded, null, Modifier.size(24.dp), tint = foreground)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = native.role("Error")) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ChoiceSheet(
        title: String, choices: List<Choice>, selected: String, scale: Float,
        native: HostThemeColors.Snapshot, choose: (String) -> Unit,
    ) {
        val foreground = native.role("OnBackground")
        val maxHeight = (LocalConfiguration.current.screenHeightDp * .75f).dp
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = choices.indexOfFirst { it.id == selected }.coerceAtLeast(0),
        )
        ModalBottomSheet(onDismissRequest = { selection = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = (20f * scale).dp, topEnd = (20f * scale).dp),
            containerColor = Color(native.contentArgb), contentColor = foreground, tonalElevation = 0.dp,
            contentWindowInsets = { WindowInsets.safeDrawing },
            dragHandle = { SheetHandle(scale, native) }) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .padding(horizontal = (20f * scale).dp).padding(bottom = (16f * scale).dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }.padding(bottom = (16f * scale).dp))
                Surface(modifier = Modifier.weight(1f, fill = false),
                    shape = RoundedCornerShape((8f * scale).dp), color = Color(native.brightArgb),
                    border = BorderStroke((.8f * scale).dp, Color(native.borderVariantArgb))) {
                    LazyColumn(state = listState) {
                        itemsIndexed(choices) { index, choice ->
                            if (index > 0) HorizontalDivider(color = Color(native.borderVariantArgb),
                                thickness = (.8f * scale).dp, modifier = Modifier.padding(start = (54f * scale).dp))
                            val active = choice.id == selected
                            val tint = if (active) native.role("Primary") else foreground
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = active, enabled = !submitted, role = Role.RadioButton) { choose(choice.id) }
                                .padding(horizontal = (18f * scale).dp, vertical = (16f * scale).dp),
                                horizontalArrangement = Arrangement.spacedBy((16f * scale).dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = active, onClick = null, modifier = Modifier.size(24.dp),
                                    colors = RadioButtonDefaults.colors(selectedColor = tint, unselectedColor = foreground))
                                Text(choice.title, style = MaterialTheme.typography.labelLarge, color = tint,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height((16f * scale).dp))
                com.reamicro.fix.ui.DialogActionButton(onClick = { selection = null },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
