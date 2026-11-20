package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.reload.ModuleUiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「在线图片」与「生成封面 / 横幅」底部弹窗。
 * 按宿主书籍详情图片菜单的实测规格绘制：顶部 20dp 圆角容器、40×4 拖拽条、
 * 0.8dp 描边的 8dp 圆角卡片，以及「图标 - 文字 - 箭头」动作行；只用 Compose foundation。
 */
internal class HostBookImageDialog(
    private val activity: Activity,
    private val title: String,
    private val generated: Boolean,
    private val initialPrompt: String = "",
    private val model: String = "",
    private val fetch: (String, String) -> ByteArray,
    private val applyImage: (ByteArray) -> (() -> Unit),
    private val saveImage: ((ByteArray) -> Unit)? = null,
    private val checkCurrent: () -> Unit = {},
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private var dark by mutableStateOf(HostEmbeddedStyle.isDark(activity))
    private var busy by mutableStateOf(false)
    private var committing by mutableStateOf(false)
    private var view: ComposeView? = null
    @Volatile private var closed = false
    private var configurationRegistered = false
    private val callback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { dark = HostEmbeddedStyle.isDark(activity) }
        override fun onLowMemory() = Unit
    }
    override fun cancel() { if (!committing) super.cancel() }
    override fun show() {
        if (isShowing || runCatching(::requireDialogCurrent).isFailure) return
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setOnDismissListener { release() }
            owner.start()
            val compose = ComposeView(context)
            view = compose
            compose.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                owner.attach(this)
                setContent { this@HostBookImageDialog.ImageContent() }
            }
            setContentView(compose)
            window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            activity.registerComponentCallbacks(callback)
            configurationRegistered = true
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            super.show()
            window?.let { com.reamicro.fix.ui.DialogWindowStyle.apply(it, activity.window) }
        } catch (e: Throwable) {
            if (isShowing) runCatching { dismiss() }.onFailure(e::addSuppressed)
            runCatching { release() }.onFailure(e::addSuppressed)
            throw e
        }
    }

    private fun requireDialogCurrent() {
        check(!closed && !activity.isFinishing && !activity.isDestroyed) { "图片页面已经关闭" }
        checkCurrent()
    }

    private fun release() {
        if (closed) return
        closed = true
        val compose = view
        view = null
        try {
            compose?.disposeComposition()
        } finally {
            try {
                owner.close()
            } finally {
                if (configurationRegistered) {
                    configurationRegistered = false
                    activity.unregisterComponentCallbacks(callback)
                }
            }
        }
    }

    private data class Preview(val bytes: ByteArray, val bitmap: Bitmap, val key: Pair<String, String>)
    private data class Action(val label: String, val icon: ImageVector, val enabled: Boolean, val run: () -> Unit)

    @Composable private fun ImageContent() {
        val palette = HostEmbeddedStyle.palette(activity, dark)
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(scale, LocalConfiguration.current) { BookImageDialogStyle.typography(activity, scale) }
        val look = remember(palette, scale, typography) { HostDialogLook(palette, scale, typography) }
        val scope = rememberCoroutineScope()
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        var input by rememberSaveable { mutableStateOf(initialPrompt) }
        var size by rememberSaveable { mutableStateOf(DEFAULT_SIZE) }
        var preview by remember { mutableStateOf<Preview?>(null) }
        var message by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        var running by remember { mutableStateOf<String?>(null) }
        fun currentKey() = input.trim() to if (generated) size.trim() else ""
        val usable = preview != null && preview?.key == currentKey()
        fun task(owner: String, label: String, writes: Boolean = false, action: suspend () -> Unit) {
            if (busy) return
            if (runCatching(::requireDialogCurrent).isFailure) { dismiss(); return }
            busy = true; committing = writes; failed = false; message = label; running = owner
            scope.launch {
                try { requireDialogCurrent(); action(); if (!closed) requireDialogCurrent() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (runCatching(::requireDialogCurrent).isFailure) dismiss()
                    else { failed = true; message = e.message?.take(160) ?: "操作失败，请重试" }
                }
                finally { busy = false; committing = false; running = null }
            }
        }
        val fetchLabel = if (generated) "生成" else "预览"
        val actions = buildList {
            add(Action(fetchLabel, if (generated) HostEmbeddedIcons.AutoAwesome else HostEmbeddedIcons.ImageOutline,
                !busy && input.isNotBlank()) {
                val request = currentKey()
                task(fetchLabel, if (generated) "正在生成图片…" else "正在下载图片…") {
                    preview = null
                    val loadedPreview = withContext(ModuleUiRuntime.io) {
                        val bytes = fetch(request.first, request.second)
                        requireDialogCurrent()
                        Preview(bytes, decodePreview(bytes), request)
                    }
                    requireDialogCurrent()
                    preview = loadedPreview
                    message = "预览已就绪，点击应用才会写入"
                }
            })
            if (saveImage != null) add(Action("保存", HostEmbeddedIcons.Download, !busy && usable) {
                val data = preview?.takeIf { it.key == currentKey() }?.bytes ?: return@Action
                task("保存", "正在保存到相册…", writes = true) {
                    withContext(ModuleUiRuntime.io) { requireDialogCurrent(); saveImage.invoke(data) }
                    requireDialogCurrent()
                    message = "已保存到相册"
                }
            })
            add(Action("应用", HostEmbeddedIcons.Check, !busy && usable) {
                val data = preview?.takeIf { it.key == currentKey() }?.bytes ?: return@Action
                task("应用", "正在应用图片…", writes = true) {
                    val finish = withContext(ModuleUiRuntime.io) { requireDialogCurrent(); applyImage(data) }
                    requireDialogCurrent()
                    finish(); dismiss()
                }
            })
        }

        val previewHeight = minOf(look.dp(224f), (LocalConfiguration.current.screenHeightDp * .32f).dp)
        HostDialogSheet(look, title, onDismiss = { dismiss() }, dismissEnabled = !committing,
            cancelLabel = if (busy) "关闭" else "取消", scrollToEndKey = preview, body = {
                HostDialogCard(look) {
                    HostDialogField(look, if (generated) "提示词" else "图片链接", input, !busy,
                        hint = if (generated) "描述要生成的图片" else "https://…",
                        minLines = if (generated) PROMPT_MIN_LINES else 1,
                        maxLines = if (generated) PROMPT_MAX_LINES else URL_MAX_LINES,
                        uri = !generated) { input = it; message = ""; failed = false }
                    if (generated) {
                        HostDialogDivider(look)
                        HostDialogField(look, "图片尺寸", size, !busy, hint = DEFAULT_SIZE, minLines = 1, maxLines = 1,
                            singleLine = true, trailing = {
                                SIZE_PRESETS.forEach { preset ->
                                    Chip(look, preset.uppercase(), size.trim().equals(preset, ignoreCase = true),
                                        !busy) { size = preset; message = ""; failed = false }
                                }
                            }) { size = it; message = ""; failed = false }
                        HostDialogDivider(look)
                        Fact(look, "当前模型", model)
                        HostDialogDivider(look)
                        Fact(look, "参考图", "当前封面（可用时上传）")
                    }
                }
                preview?.let { result ->
                    HostDialogCard(look) {
                        Image(result.bitmap.asImageBitmap(), "${title}预览",
                            Modifier.fillMaxWidth().height(previewHeight), contentScale = ContentScale.Fit)
                    }
                }
                val notes = buildList {
                    if (!usable && preview != null) add("输入已改变，请重新$fetchLabel" to false)
                    if (busy && generated && !committing) add("关闭窗口不会撤回已发送的生成请求" to false)
                    if (message.isNotEmpty()) add(message to failed)
                }
                if (notes.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = look.dp(2f))
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(look.dp(4f))) {
                    notes.forEach { (text, error) ->
                        HostDialogLabel(text, typography.bodySmall, if (error) palette.error else palette.caption)
                    }
                }
            }, actions = {
                actions.forEachIndexed { index, action ->
                    if (index > 0) HostDialogDivider(look, look.dp(54f))
                    HostDialogAction(look, action.label, action.icon, palette.text, typography.labelLarge,
                        action.enabled, running == action.label) {
                        focus.clearFocus(); keyboard?.hide(); action.run()
                    }
                }
            })
    }

    @Composable private fun Fact(look: HostDialogLook, label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(horizontal = look.dp(ROW_START), vertical = look.dp(14f)),
            horizontalArrangement = Arrangement.spacedBy(look.dp(ROW_ICON_GAP)),
            verticalAlignment = Alignment.CenterVertically) {
            HostDialogLabel(label, look.type.bodyMedium, look.palette.caption)
            HostDialogLabel(value, look.type.bodyMedium, look.palette.text, Modifier.weight(1f), maxLines = 2, align = TextAlign.End)
        }
    }

    /** 宿主按钮规格（40dp 高、4dp 圆角、0.8dp 描边）的尺寸快捷项。 */
    @Composable private fun Chip(look: HostDialogLook, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
        Box(Modifier.height(look.dp(40f)).widthIn(min = look.dp(44f)).clip(look.button)
            .border(look.border, if (selected) look.palette.text else look.palette.borderVariant, look.button)
            .clickable(remember { MutableInteractionSource() }, look.press, enabled = enabled, role = Role.RadioButton,
                onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = look.dp(10f)), contentAlignment = Alignment.Center) {
            HostDialogLabel(label, look.type.labelMedium, if (selected) look.palette.text else look.palette.caption, maxLines = 1)
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

    private companion object {
        const val DEFAULT_SIZE = "2k"
        val SIZE_PRESETS = listOf("1k", "2k", "4k")
        const val PROMPT_MIN_LINES = 3
        const val PROMPT_MAX_LINES = 6
        const val URL_MAX_LINES = 3
        const val ROW_START = 18f
        const val ROW_END = 12f
        const val ROW_ICON_GAP = 16f
    }
}
