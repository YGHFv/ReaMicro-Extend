package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.core.InjectedModuleContext

internal class ReaderThoughtSelectionEditor(
    private val activity: Activity, private val initialText: String,
    private val fonts: ReaderSelectionEditorFonts,
    private val onSave: (String, ReaderSelectionEditDialog) -> Unit,
) : Dialog(InjectedModuleContext.create(activity)) {
    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private var saving by mutableStateOf(false)
    private var themeRevision by mutableIntStateOf(0)
    private var compose: ComposeView? = null
    private var closed = false
    private var observingConfiguration = false
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { themeRevision++ }
        override fun onLowMemory() = Unit
    }
    val handle = ReaderSelectionEditDialog(this) { saving = it }

    override fun show() {
        if (isShowing || closed || activity.isFinishing || activity.isDestroyed) return
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            owner.start()
            activity.registerComponentCallbacks(configuration)
            observingConfiguration = true
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                owner.attach(this)
                setContent { this@ReaderThoughtSelectionEditor.EditorContent() }
            }
            compose = view
            val frame = android.widget.FrameLayout(context).apply {
                addView(view, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
                ))
                setOnClickListener { if (!saving) dismiss() }
            }
            view.isClickable = true
            setContentView(frame)
            window?.apply {
                decorView.setViewTreeLifecycleOwner(owner)
                decorView.setViewTreeSavedStateRegistryOwner(owner)
                setBackgroundDrawableResource(android.R.color.transparent)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.BOTTOM)
                decorView.setPadding(0, 0, 0, 0)

                WindowCompat.setDecorFitsSystemWindows(this, false)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            setOnDismissListener { release() }
            super.show()
            window?.let { com.reamicro.fix.ui.DialogWindowStyle.apply(
                it, activity.window, com.reamicro.fix.ui.DialogWindowStyle.SCRIM,
            ) }
        } catch (e: Throwable) {
            if (isShowing) runCatching { dismiss() }.onFailure(e::addSuppressed)
            runCatching { release() }.onFailure(e::addSuppressed)
            throw e
        }
    }

    private fun release() {
        if (closed) return
        closed = true
        val view = compose
        compose = null
        try {
            view?.disposeComposition()
        } finally {
            try {
                owner.close()
            } finally {
                if (observingConfiguration) {
                    observingConfiguration = false
                    activity.unregisterComponentCallbacks(configuration)
                }
            }
        }
    }

    @Composable private fun EditorContent() {

        val revision = themeRevision
        val p = HostEmbeddedStyle.palette(activity, HostEmbeddedStyle.isDark(activity))
        val typography = remember(revision, fonts) { HostEmbeddedStyle.typography(fonts.native, fonts.ui) }
        val scale = EmbeddedHostUi.scale(activity)
        var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
        }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        MaterialTheme(colorScheme = p.controlsScheme(), typography = typography) {

            Box(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding().imePadding()
                .padding((EmbeddedHostMetrics.THOUGHT_INSET * scale).dp)) {
                HostThoughtEditorInput(text, { text = it }, p,
                    enabled = !saving, inputModifier = Modifier.focusRequester(focus),
                    scale = scale, placeholder = "编辑选中的文本",
                    footer = {
                        Text(HostThoughtInputStyle.countLabel(text.text), Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = EmbeddedHostUi.textMetrics.bodySmall.sp),
                            color = p.caption, textAlign = TextAlign.End, maxLines = 1)
                        Spacer(Modifier.height((HostThoughtInputStyle.ACTION_GAP_UDP * scale).dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            com.reamicro.fix.ui.DialogActionButton(enabled = !saving, onClick = { dismiss() }) { Text("取消") }
                            Spacer(Modifier.weight(1f))
                            com.reamicro.fix.ui.DialogActionButton(enabled = !saving, onClick = { onSave(text.text, handle) },
                                modifier = Modifier
                                    .widthIn(min = (HostThoughtInputStyle.SAVE_MIN_WIDTH_UDP * scale).dp)) {
                                Text(if (saving) "正在保存…" else "保存")
                            }
                        }
                    })
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    }
}
