package com.reamicro.fix.ui

import androidx.compose.runtime.Immutable
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode

internal enum class ModuleUiStyle { OS3, OS4 }

internal enum class ModuleTopBarBlur { Gaussian, Progressive }

internal enum class ModuleKeyColor(val argb: Int?) {
    System(null),
    Blue(0xFF3482FF.toInt()),
    Green(0xFF36D167.toInt()),
    Purple(0xFF7C4DFF.toInt()),
    Yellow(0xFFFFB21D.toInt()),
    Orange(0xFFFF5722.toInt()),
    Pink(0xFFE91E63.toInt()),
    Teal(0xFF00BCD4.toInt()),
}

internal enum class ModuleNightMode { System, Light, Dark }

@Immutable
internal data class ModuleStyleSettings(
    val blurEnabled: Boolean = false,
    val floatingNavigation: Boolean = false,
    val glassEnabled: Boolean = true,
    val showNavigation: Boolean = true,
    val navigationMode: NavigationBarDisplayMode = NavigationBarDisplayMode.IconAndText,
    val topBarBlur: ModuleTopBarBlur = ModuleTopBarBlur.Gaussian,
    val pagerMode: PagerInterceptionMode = PagerInterceptionMode.CrossAxis,
) {
    companion object {
        val OS4 = ModuleStyleSettings(
            blurEnabled = true,
            floatingNavigation = true,
            topBarBlur = ModuleTopBarBlur.Progressive,
        )
    }
}

@Immutable
internal data class ModuleThemeConfig(
    val style: ModuleUiStyle = ModuleUiStyle.OS3,
    val colorMode: ColorSchemeMode = ColorSchemeMode.System,
    val keyColor: ModuleKeyColor = ModuleKeyColor.System,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2021,
    val os3: ModuleStyleSettings = ModuleStyleSettings(),
    val os4: ModuleStyleSettings = ModuleStyleSettings.OS4,
) {
    val active: ModuleStyleSettings get() = if (style == ModuleUiStyle.OS3) os3 else os4
    val glassComponents: Boolean get() = style == ModuleUiStyle.OS4 && active.glassEnabled

    fun needsSceneBackdrop(shaderSupported: Boolean): Boolean =
        shaderSupported && glassComponents && active.blurEnabled

    val dynamicColors: Boolean get() = colorMode in listOf(
        ColorSchemeMode.MonetSystem, ColorSchemeMode.MonetLight, ColorSchemeMode.MonetDark,
    )
    val customPalette: Boolean get() = dynamicColors && keyColor != ModuleKeyColor.System

    // These are the exact supported styles in upstream colorsFromSeed().
    val selectableColorSpec: Boolean get() = paletteStyle in listOf(
        ThemePaletteStyle.TonalSpot, ThemePaletteStyle.Neutral,
        ThemePaletteStyle.Vibrant, ThemePaletteStyle.Expressive,
    )
    val effectiveColorSpecYear: Int get() = when {
        paletteStyle == ThemePaletteStyle.Cmf -> 2026
        selectableColorSpec && colorSpec == ThemeColorSpec.Spec2025 -> 2025
        else -> 2021
    }

    val nightMode: ModuleNightMode get() = when (colorMode) {
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> ModuleNightMode.Light
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> ModuleNightMode.Dark
        else -> ModuleNightMode.System
    }

    fun isDark(systemDark: Boolean): Boolean = when (nightMode) {
        ModuleNightMode.Light -> false
        ModuleNightMode.Dark -> true
        ModuleNightMode.System -> systemDark
    }

    fun withActive(settings: ModuleStyleSettings): ModuleThemeConfig =
        if (style == ModuleUiStyle.OS3) copy(os3 = settings) else copy(os4 = settings)

    fun needsPageBackdrop(shaderSupported: Boolean): Boolean {
        if (!shaderSupported) return false
        val settings = active
        val topSamples = settings.blurEnabled
        val bottomSamples = settings.showNavigation && if (!settings.floatingNavigation) {
            settings.blurEnabled
        } else if (style == ModuleUiStyle.OS3) {
            settings.glassEnabled
        } else {
            settings.glassEnabled && settings.blurEnabled
        }
        return topSamples || bottomSamples || needsSceneBackdrop(shaderSupported)
    }
}

/** Persistence is kept independent from Android so old settings and style isolation can be tested. */
internal object ModuleThemeValues {
    const val STYLE = "moduleUiStyle"
    const val COLOR_MODE = "themeMode"
    const val KEY_COLOR = "themeKeyColor"
    const val PALETTE = "themePaletteStyle"
    const val COLOR_SPEC = "themeColorSpec"

    // Original OS3 storage keys remain authoritative; OS4 always uses its own namespace.
    private val os3LegacyKeys = mapOf(
        "blur" to "themeBlurBars",
        "floating" to "themeFloatingNavBar",
        "glass" to "themeLiquidGlass",
        "pagerMode" to "pagerGestureMode",
    )

    fun styleKey(style: ModuleUiStyle, option: String): String =
        if (style == ModuleUiStyle.OS3) os3LegacyKeys[option] ?: "themeOS3.$option" else "themeOS4.$option"

    fun read(values: Map<String, *>): ModuleThemeConfig {
        fun bool(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        fun number(key: String, default: Int): Int = (values[key] as? Number)?.toInt() ?: default
        fun settings(style: ModuleUiStyle, defaults: ModuleStyleSettings): ModuleStyleSettings {
            fun flag(option: String, default: Boolean) = bool(styleKey(style, option), default)
            fun index(option: String, default: Int) = number(styleKey(style, option), default)
            val topBarBlur = ModuleTopBarBlur.entries.firstOrNull {
                it.name == values[styleKey(style, "topBarBlur")]
            } ?: if (style == ModuleUiStyle.OS4) {
                // The previous progressive switch operated only on the OS4 Glass top bar.
                (values[styleKey(style, "progressiveBlur")] as? Boolean)?.let {
                    if (it) ModuleTopBarBlur.Progressive else ModuleTopBarBlur.Gaussian
                }
            } else null
            return defaults.copy(
                blurEnabled = flag("blur", defaults.blurEnabled),
                floatingNavigation = flag("floating", defaults.floatingNavigation),
                glassEnabled = flag("glass", defaults.glassEnabled),
                showNavigation = flag("navigation", defaults.showNavigation),
                navigationMode = NavigationBarDisplayMode.entries.getOrNull(index("navigationMode", defaults.navigationMode.ordinal))
                    ?: defaults.navigationMode,
                topBarBlur = topBarBlur ?: defaults.topBarBlur,
                pagerMode = PagerInterceptionMode.entries.getOrNull(index("pagerMode", defaults.pagerMode.ordinal))
                    ?: defaults.pagerMode,
            )
        }
        return ModuleThemeConfig(
            style = ModuleUiStyle.entries.firstOrNull { it.name == values[STYLE] } ?: ModuleUiStyle.OS3,
            colorMode = ColorSchemeMode.entries.getOrNull(number(COLOR_MODE, 0)) ?: ColorSchemeMode.System,
            keyColor = ModuleKeyColor.entries.getOrNull(number(KEY_COLOR, 0)) ?: ModuleKeyColor.System,
            paletteStyle = ThemePaletteStyle.entries.firstOrNull { it.name == values[PALETTE] } ?: ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.entries.firstOrNull { it.name == values[COLOR_SPEC] } ?: ThemeColorSpec.Spec2021,
            os3 = settings(ModuleUiStyle.OS3, ModuleStyleSettings()),
            os4 = settings(ModuleUiStyle.OS4, ModuleStyleSettings.OS4),
        )
    }

    fun write(config: ModuleThemeConfig): Map<String, Any> = buildMap {
        put(STYLE, config.style.name)
        put(COLOR_MODE, config.colorMode.ordinal)
        put(KEY_COLOR, config.keyColor.ordinal)
        put(PALETTE, config.paletteStyle.name)
        put(COLOR_SPEC, config.colorSpec.name)
        for (style in ModuleUiStyle.entries) {
            val settings = if (style == ModuleUiStyle.OS3) config.os3 else config.os4
            fun option(name: String, value: Any) = put(styleKey(style, name), value)
            option("blur", settings.blurEnabled)
            option("floating", settings.floatingNavigation)
            option("glass", settings.glassEnabled)
            option("navigation", settings.showNavigation)
            option("navigationMode", settings.navigationMode.ordinal)
            option("topBarBlur", settings.topBarBlur.name)
            option("pagerMode", settings.pagerMode.ordinal)
        }
    }
}
