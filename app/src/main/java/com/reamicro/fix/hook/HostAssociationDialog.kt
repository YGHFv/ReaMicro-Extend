package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.Window
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.ui.ModuleTheme
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** Uses the host book-action sheet's geometry and live theme, including its custom UI font. */
internal class HostAssociationDialog(
    private val activity: Activity,
    private val actions: List<Action>,
    private val isCurrent: () -> Boolean,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    data class Action(
        val title: String,
        val description: String,
        val destructive: Boolean = false,
        val run: () -> Unit,
    )

    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private var dark by mutableStateOf(HostEmbeddedStyle.isDark(activity))
    private var compose: ComposeView? = null
    private var closed = false
    private var actionTaken = false
    private var configurationRegistered = false
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            dark = HostEmbeddedStyle.isDark(activity)
        }
        override fun onLowMemory() = Unit
    }

    override fun show() {
        if (isShowing || closed || activity.isFinishing || activity.isDestroyed) return
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setOnDismissListener { release() }
            owner.start()
            val view = ComposeView(context)
            compose = view
            view.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                owner.attach(this)
                setContent { this@HostAssociationDialog.Content() }
            }
            setContentView(view)
            window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            activity.registerComponentCallbacks(configuration)
            configurationRegistered = true
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            super.show()
            window?.let { com.reamicro.fix.ui.DialogWindowStyle.apply(it, activity.window) }
        } catch (error: Throwable) {
            runCatching { release() }.onFailure(error::addSuppressed)
            throw error
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
                if (configurationRegistered) {
                    configurationRegistered = false
                    activity.unregisterComponentCallbacks(configuration)
                }
            }
        }
    }

    private fun select(action: Action) {
        if (closed || actionTaken) return
        actionTaken = true
        val valid = isCurrent()
        dismiss()
        if (valid) action.run()
    }

    @Composable
    private fun Content() {
        val palette = HostEmbeddedStyle.palette(activity, dark)
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(scale, LocalConfiguration.current) { BookImageDialogStyle.typography(activity, scale) }
        ModuleTheme(colorScheme = palette.controlsScheme(), typography = typography) {
            WindowBottomSheet(
                show = true,
                onDismissRequest = { dismiss() },
                backgroundColor = palette.content,
                cornerRadius = (20 * scale).dp,
                insideMargin = DpSize.Zero,
                outsideMargin = DpSize.Zero,
                dragHandleColor = palette.surfaceHighest,
            ) {
                Column(Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = (20 * scale).dp).padding(bottom = (16 * scale).dp)) {
                    Text("关联操作", style = ModuleTheme.typography.labelLarge,
                        modifier = Modifier.semantics { heading() }.padding(bottom = (20 * scale).dp))
                    Surface(shape = RoundedCornerShape((8 * scale).dp), color = palette.bright,
                        border = BorderStroke((.8f * scale).dp, palette.borderVariant)) {
                        Column {
                            actions.forEachIndexed { index, action ->
                                if (index > 0) HorizontalDivider(color = palette.borderVariant,
                                    thickness = (.8f * scale).dp, modifier = Modifier.padding(horizontal = (18 * scale).dp))
                                ActionRow(action, palette, scale) { select(action) }
                            }
                        }
                    }
                    Spacer(Modifier.height((16 * scale).dp))
                    com.reamicro.fix.ui.DialogActionButton(onClick = { dismiss() },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("取消操作", style = ModuleTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionRow(action: Action, palette: HostEmbeddedStyle.Palette, scale: Float, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = action.title, onClick = onClick)
            .padding(horizontal = (18 * scale).dp, vertical = (16 * scale).dp)) {
            Text(action.title, color = if (action.destructive) palette.error else palette.text,
                style = ModuleTheme.typography.labelLarge)
            Text(action.description, color = palette.caption, style = ModuleTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = (2 * scale).dp))
        }
    }
}
