package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.core.InjectedModuleContext

/** Uses the host book-action sheet's geometry and live theme, including its custom UI font. */
internal class HostAssociationDialog(
    private val activity: Activity,
    private val actions: List<Action>,
    private val isCurrent: () -> Boolean,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    data class Action(
        val title: String,
        val description: String,
        val icon: ImageVector,
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
        if (isShowing || closed || activity.isFinishing || activity.isDestroyed || !isCurrent()) return
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
            if (isShowing) runCatching { dismiss() }.onFailure(error::addSuppressed)
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
        val look = remember(palette, scale, typography) { HostDialogLook(palette, scale, typography) }
        HostDialogSheet(look, "关联操作", onDismiss = { dismiss() }, actions = {
            actions.forEachIndexed { index, action ->
                if (index > 0) HostDialogDivider(look, look.dp(54f))
                HostDialogAction(look, action.title, action.icon,
                    if (action.destructive) palette.error else palette.text, typography.labelLarge,
                    description = action.description) { select(action) }
            }
        })
    }
}
