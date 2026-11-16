package com.reamicro.fix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.integration.WindowPopupScope
import top.yukonga.miuix.kmp.integration.captureBackdrop
import com.reamicro.fix.ui.liquid.rememberCombinedBackdrop

internal val LocalModuleThemeConfig = compositionLocalOf { ModuleThemeConfig() }
internal val LocalModuleSceneBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalModuleWindowScaffold = staticCompositionLocalOf { false }
internal val LocalModuleWindowClosing = staticCompositionLocalOf { false }

@Composable
internal fun useModuleGlass(): Boolean {
    return LocalModuleThemeConfig.current.glassComponents
}

/**
 * Record the window body separately from its popup host. Children and menus receive page + panel,
 * while the panel itself keeps sampling the inherited page. Popups never enter their own input.
 */
@Composable
internal fun ModuleWindowPopupHost(content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        window?.let { DialogWindowStyle.apply(it) }
        onDispose { }
    }
    val capture = LocalModuleThemeConfig.current.needsSceneBackdrop(isRuntimeShaderSupported())
    val parentBackdrop = LocalModuleSceneBackdrop.current
    val bodyBackdrop = rememberLayerBackdrop()
    val windowBackdrop = if (!capture) null else if (parentBackdrop == null) bodyBackdrop
        else rememberCombinedBackdrop(parentBackdrop, bodyBackdrop)
    WindowPopupScope {
        CompositionLocalProvider(
            LocalModuleWindowScaffold provides true,
            LocalModuleSceneBackdrop provides windowBackdrop,
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.then(if (capture) Modifier.captureBackdrop(bodyBackdrop) else Modifier),
                ) {
                    content()
                }
            }
        }
    }
}
