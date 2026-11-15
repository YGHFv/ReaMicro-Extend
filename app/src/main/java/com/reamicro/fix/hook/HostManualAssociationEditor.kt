package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.withContext

/** Host-styled metadata draft; the original host candidate screen still confirms the association. */
internal class HostManualAssociationEditor(
    private val activity: Activity,
    initial: ManualAssociationDraft,
    sources: List<BookSource>,
    private val isCurrent: () -> Boolean,
    private val onChooseCover: () -> Unit,
    private val coverPreview: (String) -> Bitmap?,
    private val onClosed: () -> Unit,
    private val onPreview: (ManualAssociationDraft) -> Unit,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    private enum class Selection { PLATFORM, STATUS }
    private data class Choice(val id: String, val title: String)

    private val availableSources = sources.distinctBy { it.id }
    private val service = ManualAssociationService { availableSources }
    private var draft by mutableStateOf(initial.copy(source = availableSources.firstOrNull { it == initial.source }
        ?: availableSources.firstOrNull() ?: initial.source))
    private var selection by mutableStateOf<Selection?>(null)
    private var submitted by mutableStateOf(false)
    private var coverImportBusy by mutableStateOf(false)
    private var coverRevision by mutableIntStateOf(0)
    private var problem by mutableStateOf<String?>(null)
    private var configurationEpoch by mutableIntStateOf(0)
    private var closed = false
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

    private fun preview() {
        if (closed || submitted || coverImportBusy) return
        val normalized = draft.normalized()
        val validation = service.validate(normalized)
        if (!validation.isValid) { problem = validation.firstError; return }
        submitted = true
        problem = null
        try {
            check(!activity.isFinishing && !activity.isDestroyed && isCurrent()) { "关联页面已变化，请重新打开手动编辑" }
            onPreview(normalized)
        } catch (error: Throwable) {
            if (!closed) {
                submitted = false
                problem = error.message ?: "生成关联预览失败，请重试"
            }
            return
        }
        dismiss()
    }

    private fun changed(next: ManualAssociationDraft) {
        if (closed || submitted) return
        draft = next
        problem = null
    }

    fun setCoverBusy(busy: Boolean) {
        if (!closed) coverImportBusy = busy
    }

    fun coverImported(path: String) {
        if (!closed && isCurrent()) {
            coverRevision++
            changed(draft.copy(coverUrl = path))
        }
    }

    fun showCoverProblem(message: String) {
        if (!closed && isCurrent()) problem = message
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
        val validation = service.validate(draft)
        val submit = { focus.clearFocus(); keyboard?.hide(); preview() }
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
                                CoverField(scale, native) {
                                    focus.clearFocus(); keyboard?.hide(); onChooseCover()
                                }
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
                                    onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(words = it)) }
                                SelectionField("状态", draft.status.ifBlank { "未填写" }, scale, native) {
                                    focus.clearFocus(); keyboard?.hide(); selection = Selection.STATUS
                                }
                                Field("简介", draft.intro, "未填写", scale, native, multiline = true,
                                    error = validation.introError,
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
                        Button(onClick = submit, enabled = validation.isValid && !submitted && !coverImportBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape((4f * scale).dp), colors = ButtonDefaults.buttonColors(
                                containerColor = native.role("Primary"), contentColor = native.role("OnPrimary"),
                                disabledContainerColor = native.role("SurfaceContainerHighest"),
                                disabledContentColor = foreground.copy(alpha = .38f))) {
                            Text(if (submitted) "正在生成…" else "预览关联", style = typography.labelLarge)
                        }
                        TextButton(onClick = { dismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape((4f * scale).dp),
                            contentPadding = PaddingValues((12f * scale).dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = native.role("SurfaceContainerHighest"),
                                contentColor = Color(native.captionArgb))) {
                            Text("取消", style = typography.bodyLarge)
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
    private fun CoverField(scale: Float, native: HostThemeColors.Snapshot, choose: () -> Unit) {
        val path = draft.coverUrl
        val preview by produceState<Pair<Boolean, Bitmap?>>(true to null, path, coverRevision) {
            value = true to null
            value = false to withContext(ModuleUiRuntime.io) { coverPreview(path) }
        }
        val bitmap = preview.second
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((4f * scale).dp)) {
            Text("封面", style = MaterialTheme.typography.labelMedium, color = native.role("OnBackground"))
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp),
                horizontalArrangement = Arrangement.spacedBy((16f * scale).dp),
                verticalAlignment = Alignment.CenterVertically) {
                // Same 64 × 1.4 image, 4dp inset and 8dp outer shape as the host BookCover.
                Box(Modifier.clip(RoundedCornerShape((8f * scale).dp))
                    .border((.8f * scale).dp, Color(native.borderVariantArgb), RoundedCornerShape((8f * scale).dp))
                    .background(Color(native.pageArgb)).padding((4f * scale).dp)) {
                    Box(Modifier.size((64f * scale).dp, (89.6f * scale).dp)
                        .clip(RoundedCornerShape((4f * scale).dp)).background(native.role("SurfaceContainerHighest")),
                        contentAlignment = Alignment.Center) {
                        if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), "封面预览",
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(HostEmbeddedIcons.ImageOutline, null, Modifier.size(24.dp), tint = Color(native.captionArgb))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((4f * scale).dp)) {
                    TextButton(onClick = choose, enabled = !submitted && !coverImportBusy,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (coverImportBusy) "正在读取…" else if (path.isBlank()) "选择封面" else "更换封面",
                            style = MaterialTheme.typography.labelLarge)
                    }
                    Text(when {
                        coverImportBusy || preview.first -> "正在读取封面…"
                        path.isBlank() -> "可选择本地图片"
                        bitmap != null -> "确认关联后保存"
                        path.startsWith("http", true) -> "当前网络封面"
                        else -> "封面预览不可用，可重新选择"
                    }, color = Color(native.captionArgb), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
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
                Surface(shape = RoundedCornerShape((8f * scale).dp), color = Color(native.brightArgb),
                    border = BorderStroke((.8f * scale).dp, Color(native.borderVariantArgb))) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "取消选择") { selection = null }
                        .padding(horizontal = (18f * scale).dp, vertical = (16f * scale).dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("取消", style = MaterialTheme.typography.bodyLarge, color = Color(native.captionArgb))
                    }
                }
            }
        }
    }
}
