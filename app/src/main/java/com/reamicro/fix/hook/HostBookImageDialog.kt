package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.core.InjectedModuleContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HostBookImageDialog(
    private val activity: Activity,
    private val title: String,
    private val generated: Boolean,
    private val initialPrompt: String = "",
    private val model: String = "",
    private val fetch: (String, String) -> ByteArray,
    private val applyImage: (ByteArray) -> (() -> Unit),
    private val saveImage: ((ByteArray) -> Unit)? = null,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private var dark by mutableStateOf(StructureHome130Style.isDark(activity))
    private var busy by mutableStateOf(false)
    private var committing by mutableStateOf(false)
    private var view: ComposeView? = null
    private var closed = false
    private val callback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { dark = StructureHome130Style.isDark(activity) }
        override fun onLowMemory() = Unit
    }
    override fun cancel() { if (!committing) super.cancel() }
    override fun show() {
        if (isShowing || closed || activity.isFinishing || activity.isDestroyed) return
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        owner.start()
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { this@HostBookImageDialog.ImageContent() }
        }
        view = compose
        setContentView(compose)
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        setOnDismissListener {
            closed = true
            view?.disposeComposition(); view = null; owner.close()
            activity.unregisterComponentCallbacks(callback)
        }
        activity.registerComponentCallbacks(callback)
        try {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            super.show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setDimAmount(0f)
        } catch (e: Exception) {
            compose.disposeComposition(); owner.close(); closed = true
            activity.unregisterComponentCallbacks(callback)
            throw e
        }
    }

    private data class Preview(val bytes: ByteArray, val bitmap: Bitmap, val key: Pair<String, String>)
    private data class Action(val label: String, val enabled: Boolean, val run: () -> Unit)

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable private fun ImageContent() {
        val palette = StructureHome130Style.palette(activity, dark)
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(scale, LocalConfiguration.current) { BookImageDialogStyle.typography(activity, scale) }
        MaterialTheme(colorScheme = palette.controlsScheme(), typography = typography) {
            val scope = rememberCoroutineScope()
            var input by rememberSaveable { mutableStateOf(initialPrompt) }
            var size by rememberSaveable { mutableStateOf("2k") }
            var preview by remember { mutableStateOf<Preview?>(null) }
            var message by remember { mutableStateOf("") }
            var failed by remember { mutableStateOf(false) }
            fun currentKey() = input.trim() to if (generated) size.trim() else ""
            val key = currentKey()
            val previewKey = preview?.key
            val usable = preview != null && previewKey == key
            fun task(label: String, writes: Boolean = false, action: suspend () -> Unit) {
                if (busy) return
                busy = true; committing = writes; failed = false; message = label
                scope.launch {
                    try { action() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed = true; message = e.message?.take(160) ?: "操作失败，请重试" }
                    finally { busy = false; committing = false }
                }
            }
            val actions = buildList {
                add(Action(if (generated) "生成" else "预览", !busy && input.isNotBlank()) {
                    val request = currentKey()
                    task(if (generated) "正在生成图片…" else "正在下载图片…") {

                        preview = null
                        preview = withContext(Dispatchers.IO) {
                            val bytes = fetch(request.first, request.second)
                            Preview(bytes, decodePreview(bytes), request)
                        }
                        message = "预览已就绪，点击应用才会写入"
                    }
                })
                if (saveImage != null) add(Action("保存", !busy && usable) {
                    val data = preview?.takeIf { it.key == currentKey() }?.bytes ?: return@Action
                    task("正在保存到相册…", writes = true) {
                        withContext(Dispatchers.IO) { saveImage.invoke(data) }
                        message = "已保存到相册"
                    }
                })
                add(Action("应用", !busy && usable) {
                    val data = preview?.takeIf { it.key == currentKey() }?.bytes ?: return@Action
                    task("正在应用图片…", writes = true) {
                        val finish = withContext(Dispatchers.IO) { applyImage(data) }
                        finish(); dismiss()
                    }
                })
            }
            ModalBottomSheet(
                onDismissRequest = { if (!committing) dismiss() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !committing }),

                containerColor = palette.content, contentColor = palette.text, tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = (BookImageDialogStyle.SHEET_CORNER * scale).dp,
                    topEnd = (BookImageDialogStyle.SHEET_CORNER * scale).dp),

                contentWindowInsets = { WindowInsets.safeDrawing },
                dragHandle = {
                    Box(Modifier.fillMaxWidth().padding(top = (BookImageDialogStyle.HANDLE_TOP * scale).dp,
                        bottom = (BookImageDialogStyle.HANDLE_BOTTOM * scale).dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size((BookImageDialogStyle.HANDLE_WIDTH * scale).dp,
                            (BookImageDialogStyle.HANDLE_HEIGHT * scale).dp)
                            .background(palette.surfaceHighest, RoundedCornerShape((1f * scale).dp)))
                    }
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val density = LocalDensity.current
                    val sheetFocus = LocalFocusManager.current
                    val sheetKeyboard = LocalSoftwareKeyboardController.current
                    val measurer = rememberTextMeasurer()
                    val buttonSizes = actions.map { measurer.measure(AnnotatedString(it.label), typography.bodyLarge).size }
                    val buttonWidth = with(density) { buttonSizes.maxOf { it.width }.toDp().value } + 36f * scale
                    val buttonHeight = with(density) { buttonSizes.maxOf { it.height }.toDp().value } + 20f * scale
                    val layout = BookImageDialogLayout.resolve(maxWidth.value, maxHeight.value, scale,
                        density.fontScale, actions.size, buttonWidth, buttonHeight)
                    val gap = (12f * scale).dp

                    val outerScroll = rememberScrollState()
                    val bodyScroll = rememberScrollState()
                    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
                        .then(if (layout.scrollAll) Modifier.verticalScroll(outerScroll) else Modifier)
                        .padding(horizontal = layout.inset.dp).padding(bottom = (12f * scale).dp)) {
                        Text(title, style = typography.titleSmall, modifier = Modifier.fillMaxWidth()
                            .padding(bottom = gap).semantics { heading() })
                        Column(Modifier.fillMaxWidth()
                            .then(if (layout.scrollAll) Modifier else Modifier.weight(1f, fill = false).verticalScroll(bodyScroll)),
                            verticalArrangement = Arrangement.spacedBy(gap)) {
                            if (generated) Text("当前模型：$model\n参考图：当前封面（可用时上传）",
                                style = typography.bodySmall, color = palette.caption)
                            InputCard(if (generated) "提示词内容" else "图片链接", input, !busy, palette, scale,
                                lines = if (generated) layout.promptLines else 3,
                                hint = if (generated) "描述要生成的图片" else "https://…",
                                uri = !generated) { input = it; message = ""; failed = false }
                            if (generated) InputCard("图片尺寸", size, !busy, palette, scale, lines = 1, hint = "2k",
                                singleLine = true) { size = it; message = ""; failed = false }
                            preview?.let { result ->
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((8f * scale).dp)) {
                                    Text("图片预览", style = typography.labelMedium, color = palette.caption)
                                    Image(result.bitmap.asImageBitmap(), "${title}预览",
                                        Modifier.fillMaxWidth().height(layout.previewHeight.dp)
                                            .clip(RoundedCornerShape((BookImageDialogStyle.CARD_CORNER * scale).dp))
                                            .background(palette.page), contentScale = ContentScale.Fit)
                                }
                            }
                            if (!usable && preview != null) Text("输入已改变，请重新${if (generated) "生成" else "预览"}",
                                color = palette.caption, style = typography.bodySmall)
                            if (busy && generated && !committing) Text("关闭窗口不会撤回已发送的生成请求",
                                style = typography.bodySmall, color = palette.caption)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.text, trackColor = palette.surfaceHighest)
                            if (message.isNotEmpty()) Text(message, color = if (failed) palette.error else palette.caption,
                                style = typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Spacer(Modifier.height((2f * scale).dp))
                        }
                        HorizontalDivider(Modifier.padding(vertical = gap), color = palette.surfaceHighest)
                        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = layout.buttonColumns,
                            horizontalArrangement = Arrangement.spacedBy((8f * scale).dp),
                            verticalArrangement = Arrangement.spacedBy((8f * scale).dp)) {
                            actions.forEach { action ->
                                OutlinedButton(onClick = { sheetFocus.clearFocus(); sheetKeyboard?.hide(); action.run() }, enabled = action.enabled,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shape = RoundedCornerShape((BookImageDialogStyle.BUTTON_CORNER * scale).dp),
                                    border = BorderStroke((BookImageDialogStyle.BORDER * scale).dp, palette.borderVariant),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text,
                                        disabledContentColor = palette.caption),
                                    contentPadding = PaddingValues(horizontal = (12f * scale).dp, vertical = (10f * scale).dp)) {
                                    Text(action.label, style = typography.bodyLarge)
                                }
                            }
                        }
                        Spacer(Modifier.height(gap))

                        OutlinedButton(onClick = { dismiss() }, enabled = !committing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape((BookImageDialogStyle.CARD_CORNER * scale).dp),
                            border = BorderStroke((BookImageDialogStyle.BORDER * scale).dp, palette.borderVariant),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = palette.page,
                                contentColor = palette.caption, disabledContentColor = palette.caption)) {
                            Text(if (busy) "关闭" else "取消", style = typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }

    @Composable private fun InputCard(label: String, value: String, enabled: Boolean,
        palette: StructureHome130Style.Palette, scale: Float, lines: Int, hint: String,
        uri: Boolean = false, singleLine: Boolean = false, changed: (String) -> Unit) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val shape = RoundedCornerShape((BookImageDialogStyle.CARD_CORNER * scale).dp)
        val card = Modifier.fillMaxWidth().clip(shape).background(palette.page)
            .border((BookImageDialogStyle.BORDER * scale).dp, palette.borderVariant, shape)
            .padding((12f * scale).dp)
        val controlStyle = MaterialTheme.typography.bodyLarge
        val editor: @Composable (Modifier) -> Unit = { modifier ->
            BasicTextField(value, { next -> changed(if (uri) next.replace("\r", "").replace("\n", "") else next) },
                modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
                enabled = enabled, singleLine = singleLine, minLines = 1,
                maxLines = if (singleLine) 1 else lines,
                keyboardOptions = KeyboardOptions(keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text,
                    imeAction = if (uri || singleLine) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                textStyle = controlStyle.copy(color = palette.text), cursorBrush = SolidColor(palette.text),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, color = palette.caption, style = controlStyle,
                            maxLines = if (singleLine) 1 else lines,
                            softWrap = !singleLine, overflow = TextOverflow.Clip)
                        field()
                    }
                })
        }

        Column(card, verticalArrangement = Arrangement.spacedBy((8f * scale).dp)) {
            Text(label, style = controlStyle, color = palette.caption)
            editor(Modifier.fillMaxWidth())
        }
    }

    private fun decodePreview(raw: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码，请更换链接或重新生成" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片无法解码，请更换链接或重新生成")
    }
}
