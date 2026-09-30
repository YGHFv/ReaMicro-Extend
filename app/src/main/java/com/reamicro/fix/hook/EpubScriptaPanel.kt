package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.epub.editor.EpubMarkupHighlighter
import com.reamicro.fix.epub.editor.EpubTextFiles
import com.reamicro.fix.epub.editor.EpubTextSnapshot
import com.reamicro.fix.epub.editor.usesScriptaEpubEditor
import com.reamicro.fix.xposed.XposedBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.rememberCodeEditorController
import java.io.File

/** Module-owned Compose island. Never casts host/obfuscated Compose or lifecycle objects. */
internal class EpubScriptaPanel(
    private val activity: Activity,
    private val root: File,
    private val relativePath: String,
    private val onSaved: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val owner = EditorOwner()
    private val palette = mutableStateOf(ModuleDialogTheme.palette(activity))
    private var requestClose: (() -> Unit)? = null
    private var composeView: ComposeView? = null
    private var closed = false
    private val dialog by lazy { object : Dialog(
        com.reamicro.fix.core.InjectedModuleContext.create(activity),
        com.reamicro.fix.R.style.EpubFullScreenDialog,
    ) {
        @Deprecated("Back must confirm unsaved edits")
        override fun onBackPressed() { requestClose?.invoke() ?: dismiss() }
    } }
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            palette.value = ModuleDialogTheme.palette(activity)
            configureWindow()
        }
        override fun onLowMemory() = Unit
    }
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(a: Activity) { if (a === activity) dismissForHostShutdown() }
        override fun onActivityResumed(a: Activity) {
            if (a === activity && !closed) owner.state(Lifecycle.State.RESUMED)
        }
        override fun onActivityPaused(a: Activity) {
            if (a === activity && !closed) owner.state(Lifecycle.State.STARTED)
        }
        override fun onActivityStopped(a: Activity) {
            if (a === activity && !closed) owner.state(Lifecycle.State.CREATED)
        }
        override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
    }

    fun show() {
        require(usesScriptaEpubEditor(relativePath)) { "该类型不使用 Scripta" }
        check(!activity.isFinishing && !activity.isDestroyed) { "页面已关闭" }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        // Uses framework-supplied APK metadata; package visibility is irrelevant.
        val context = com.reamicro.fix.core.InjectedModuleContext.create(activity)
        owner.create()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { EditorContent() }
        }
        composeView = view
        dialog.window?.decorView?.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        dialog.setContentView(view)
        dialog.window?.decorView?.findViewById<android.view.View>(android.R.id.content)?.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        dialog.setOnDismissListener { cleanup() }
        activity.registerComponentCallbacks(configCallbacks)
        activity.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        try {
            dialog.show()
            configureWindow()
            owner.state(Lifecycle.State.RESUMED)
            if (Build.VERSION.SDK_INT >= 33) {
                dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { requestClose?.invoke() ?: dialog.dismiss() }
            }
        } catch (error: Throwable) {
            cleanup()
            throw error
        }
    }

    fun dismissForHostShutdown() {
        if (closed) return
        dialog.dismiss()
        cleanup()
    }

    private fun cleanup() {
        if (closed) return
        closed = true
        requestClose = null
        runCatching { activity.unregisterComponentCallbacks(configCallbacks) }
        runCatching { activity.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks) }
        composeView?.disposeComposition()
        owner.state(Lifecycle.State.DESTROYED)
        composeView = null
        onClosed()
    }

    private fun configureWindow() {
        val p = palette.value
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(p.pageBackground) < 0.5
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(p.pageBackground))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            if (Build.VERSION.SDK_INT >= 28) {
                attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            }
            WindowCompat.setDecorFitsSystemWindows(this, false)
            @Suppress("DEPRECATION")
            statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    @Composable
    private fun EditorContent() {
        val p = palette.value
        val controller = rememberCodeEditorController()
        val scope = rememberCoroutineScope()
        val highlighter = remember { EpubMarkupHighlighter() }
        var snapshot by remember { mutableStateOf<EpubTextSnapshot?>(null) }
        var loading by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<String?>(null) }
        var confirmClose by remember { mutableStateOf(false) }
        var softWrap by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }

        LaunchedEffect(retry) {
            loading = true
            failure = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    EpubTextFiles.load(EpubTextFiles.resolve(root, relativePath)) { job.ensureActive() }
                }
                controller.setDocument(loaded.text)
                snapshot = loaded.snapshot
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error.message ?: "读取失败"
            } finally {
                loading = false
            }
        }
        fun close() {
            if (saving) return
            when {
                controller.isFindVisible -> controller.closeFind()
                controller.isGotoLineVisible -> controller.closeGotoLine()
                controller.isModified -> confirmClose = true
                else -> dialog.dismiss()
            }
        }
        SideEffect { requestClose = { close() } }
        fun save(closeAfter: Boolean = false) {
            val original = snapshot ?: return
            if (saving || loading) return
            if (controller.isComposing) {
                Toast.makeText(activity, "请先确认输入法候选词，再保存", Toast.LENGTH_SHORT).show()
                return
            }
            // Controller API is UI-thread-only. Take version and text without suspending.
            val version = controller.documentVersion
            val text = controller.getText(controller.lineEnding)
            saving = true
            confirmClose = false
            scope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        EpubTextFiles.save(EpubTextFiles.resolve(root, relativePath), text, original) {
                            job.ensureActive()
                        }
                    }
                    snapshot = updated
                    controller.markSaved(version)
                    // Cache refresh failure must not misreport a successful disk write as failed.
                    runCatching { withContext(Dispatchers.IO) { onSaved() } }.onFailure {
                        XposedBridge.log("ReaMicro Scripta cache refresh: ${it.message}")
                    }
                    Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                    if (closeAfter && !controller.isModified) dialog.dismiss()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Toast.makeText(activity, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
                } finally {
                    saving = false
                }
            }
        }

        val bg = Color(p.pageBackground)
        val textColor = Color(p.title)
        val muted = Color(p.body)
        val accent = Color(p.primary)
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(p.pageBackground) < 0.5
        val colors = remember(p) {
            (if (dark) EditorColors.Default else EditorColors.Light).copy(
                background = Color(p.rowBackground),
                foreground = textColor,
                gutterBackground = bg,
                gutterForeground = muted.copy(alpha = 0.65f),
                cursor = accent,
                handle = accent,
                selection = accent.copy(alpha = 0.22f),
                currentLine = accent.copy(alpha = 0.045f),
                symbolBarBackground = bg,
                symbolBarForeground = textColor,
                symbolBarPressed = accent.copy(alpha = 0.14f),
                findMatch = accent.copy(alpha = 0.16f),
                findMatchActive = accent.copy(alpha = 0.35f),
            )
        }
        Box(Modifier.fillMaxSize().background(bg).systemBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).semantics { contentDescription = "返回文件树" }
                            .clickable(enabled = !saving, role = Role.Button) { close() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            val stroke = 2.dp.toPx()
                            drawLine(textColor, Offset(size.width * .8f, size.height / 2),
                                Offset(size.width * .2f, size.height / 2), stroke, StrokeCap.Round)
                            drawLine(textColor, Offset(size.width * .2f, size.height / 2),
                                Offset(size.width * .48f, size.height * .22f), stroke, StrokeCap.Round)
                            drawLine(textColor, Offset(size.width * .2f, size.height / 2),
                                Offset(size.width * .48f, size.height * .78f), stroke, StrokeCap.Round)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        BasicText(relativePath.substringAfterLast('/') + if (controller.isModified) " ·" else "",
                            style = TextStyle(color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(relativePath, style = TextStyle(color = muted, fontSize = 11.sp),
                            maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Action(if (saving) "保存中" else "保存", accent,
                        !loading && !saving && snapshot != null && controller.isModified) { save() }
                }
                Rule(Color(p.border))
                if (loading || failure != null) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        BasicText(if (loading) "正在读取文件…" else failure.orEmpty(),
                            style = TextStyle(color = muted, fontSize = 14.sp))
                        if (failure != null) Action("重试", accent) { retry++ }
                    }
                } else {
                    CodeEditor(
                        controller = controller,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        colors = colors,
                        highlighter = highlighter,
                        softWrap = softWrap,
                        readOnly = saving,
                        autoClosePairs = false,
                    )
                }
                Rule(Color(p.border))
                Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicText("行 ${controller.caret.line + 1}  列 ${controller.caret.column + 1}",
                        style = TextStyle(color = muted, fontSize = 11.sp), modifier = Modifier.weight(1f))
                    BasicText("${snapshot?.format?.charset?.name().orEmpty()}  ${controller.lineEnding.name}",
                        style = TextStyle(color = muted, fontSize = 11.sp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Action("撤销", muted, !saving && controller.canUndo) { controller.undo() }
                    Action("重做", muted, !saving && controller.canRedo) { controller.redo() }
                    Action("查找", muted, !loading && !saving) { controller.openFind() }
                    Action("替换", muted, !loading && !saving) { controller.openReplace() }
                    Action("行号", muted, !loading) { controller.openGotoLine() }
                    Action("换行", if (softWrap) accent else muted, !loading) { softWrap = !softWrap }
                }
            }
            if (confirmClose) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f))
                    .clickable { confirmClose = false })
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(p.rowBackground), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .padding(20.dp),
                ) {
                    BasicText("保存修改？", style = TextStyle(color = textColor, fontSize = 18.sp,
                        fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(12.dp))
                    BasicText("返回后将丢弃 ${relativePath.substringAfterLast('/')} 的未保存修改。",
                        style = TextStyle(color = muted, fontSize = 14.sp))
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Action("继续编辑", muted) { confirmClose = false }
                        Action("不保存", Color(p.destructiveText)) { dialog.dismiss() }
                        Action("保存并返回", accent) { save(true) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Action(label: String, color: Color, enabled: Boolean = true, action: () -> Unit) {
        Box(Modifier.heightIn(min = 48.dp).widthIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
            BasicText(label, style = TextStyle(color = if (enabled) color else color.copy(alpha = .35f),
                fontSize = 13.sp), maxLines = 1)
        }
    }

    @Composable
    private fun Rule(color: Color) { Box(Modifier.fillMaxWidth().height(0.5.dp).background(color)) }

    private class EditorOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun create() {
            saved.performAttach()
            saved.performRestore(null)
            state(Lifecycle.State.CREATED)
        }
        fun state(value: Lifecycle.State) { registry.currentState = value }
    }
}
