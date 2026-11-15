package com.reamicro.fix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ModuleAppTheme(config: ModuleThemeConfig, dark: Boolean, content: @Composable () -> Unit) {
    val seed = config.keyColor.argb?.let { Color(it) }
    val controller = remember(config.colorMode, seed, config.paletteStyle, config.colorSpec, dark) {
        ThemeController(
            colorSchemeMode = config.colorMode,
            keyColor = seed,
            paletteStyle = config.paletteStyle,
            colorSpec = config.colorSpec,
            isDark = dark,
        )
    }
    CompositionLocalProvider(
        LocalSquircleEnabled provides true,
        LocalModuleThemeConfig provides config,
    ) {
        MiuixTheme(controller = controller, content = content)
    }
}

@Composable
internal fun Modifier.modulePageScroll(scrollBehavior: ScrollBehavior? = null): Modifier {
    return this
        .scrollEndHaptic()
        .overScrollVertical()
        .then(
            if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            else Modifier,
        )
}
