package com.reamicro.fix.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

internal enum class ModuleThemePreferenceRow {
    Mode, Style, KeyColor, Palette, ColorSpec, Blur, Glass, TopBarBlur, Navigation, Floating, NavigationMode, Liquid,
}

internal fun moduleThemePreferenceRows(config: ModuleThemeConfig): List<ModuleThemePreferenceRow> = buildList {
    add(ModuleThemePreferenceRow.Mode)
    add(ModuleThemePreferenceRow.Style)
    if (config.dynamicColors) add(ModuleThemePreferenceRow.KeyColor)
    if (config.customPalette) {
        add(ModuleThemePreferenceRow.Palette)
        add(ModuleThemePreferenceRow.ColorSpec)
    }
    add(ModuleThemePreferenceRow.Blur)
    if (config.style == ModuleUiStyle.OS4) add(ModuleThemePreferenceRow.Glass)
    add(ModuleThemePreferenceRow.TopBarBlur)
    add(ModuleThemePreferenceRow.Navigation)
    if (config.active.showNavigation) {
        add(ModuleThemePreferenceRow.Floating)
        if (!config.active.floatingNavigation) add(ModuleThemePreferenceRow.NavigationMode)
        else if (config.style == ModuleUiStyle.OS3) add(ModuleThemePreferenceRow.Liquid)
    }
}

internal fun LazyListScope.moduleThemeSettings(config: ModuleThemeConfig, onChange: (ModuleThemeConfig) -> Unit) {
    item(key = "interface.title", contentType = "group-title") {
        GroupTitle(stringResource(R.string.section_interface))
    }
    modulePreferenceGroup(
        groupKey = "interface",
        rows = moduleThemePreferenceRows(config),
        rowKey = { it.name },
    ) { row -> ModuleThemePreference(row, config, onChange) }
}

@Composable
private fun ModuleThemePreference(
    row: ModuleThemePreferenceRow,
    config: ModuleThemeConfig,
    onChange: (ModuleThemeConfig) -> Unit,
) {
    val settings = config.active
    val supported = isRuntimeShaderSupported()
    fun update(value: ModuleStyleSettings) = onChange(config.withActive(value))
    when (row) {
        ModuleThemePreferenceRow.Mode -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_theme_title),
            items = listOf(
                stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark),
                stringResource(R.string.theme_monet_system), stringResource(R.string.theme_monet_light), stringResource(R.string.theme_monet_dark),
            ),
            selectedIndex = config.colorMode.ordinal,
            onSelectedIndexChange = { onChange(config.copy(colorMode = ColorSchemeMode.entries[it])) },
        )
        ModuleThemePreferenceRow.Style -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_ui_style_title),
            items = listOf(stringResource(R.string.ui_style_os3), stringResource(R.string.ui_style_os4)),
            selectedIndex = config.style.ordinal,
            onSelectedIndexChange = { onChange(config.copy(style = ModuleUiStyle.entries[it])) },
        )
        ModuleThemePreferenceRow.KeyColor -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_key_color_title),
            items = listOf(
                stringResource(R.string.key_color_system), stringResource(R.string.key_color_blue),
                stringResource(R.string.key_color_green), stringResource(R.string.key_color_purple),
                stringResource(R.string.key_color_yellow), stringResource(R.string.key_color_orange),
                stringResource(R.string.key_color_pink), stringResource(R.string.key_color_teal),
            ),
            selectedIndex = config.keyColor.ordinal,
            onSelectedIndexChange = { onChange(config.copy(keyColor = ModuleKeyColor.entries[it])) },
        )
        ModuleThemePreferenceRow.Palette -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_palette_style_title),
            items = listOf(
                stringResource(R.string.palette_tonal_spot), stringResource(R.string.palette_neutral),
                stringResource(R.string.palette_vibrant), stringResource(R.string.palette_expressive),
                stringResource(R.string.palette_rainbow), stringResource(R.string.palette_fruit_salad),
                stringResource(R.string.palette_monochrome), stringResource(R.string.palette_fidelity),
                stringResource(R.string.palette_content), stringResource(R.string.palette_cmf),
            ),
            selectedIndex = config.paletteStyle.ordinal,
            onSelectedIndexChange = { onChange(config.copy(paletteStyle = ThemePaletteStyle.entries[it])) },
        )
        ModuleThemePreferenceRow.ColorSpec -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_color_spec_title),
            summary = stringResource(
                when {
                    config.effectiveColorSpecYear == 2026 -> R.string.pref_color_spec_cmf
                    !config.selectableColorSpec -> R.string.pref_color_spec_2021_only
                    else -> R.string.pref_color_spec_summary
                },
            ),
            enabled = config.selectableColorSpec,
            items = if (config.selectableColorSpec) listOf("2021", "2025") else listOf(config.effectiveColorSpecYear.toString()),
            selectedIndex = if (config.selectableColorSpec) config.colorSpec.ordinal else 0,
            onSelectedIndexChange = { onChange(config.copy(colorSpec = ThemeColorSpec.entries[it])) },
        )
        ModuleThemePreferenceRow.Blur -> SwitchPreference(
            title = stringResource(R.string.pref_blur_title),
            summary = stringResource(R.string.pref_blur_summary),
            enabled = supported,
            checked = settings.blurEnabled,
            onCheckedChange = { update(settings.copy(blurEnabled = it)) },
        )
        ModuleThemePreferenceRow.Glass -> SwitchPreference(
            title = stringResource(R.string.pref_os4_glass_title),
            summary = stringResource(if (supported) R.string.pref_os4_glass_summary else R.string.pref_glass_unsupported),
            checked = settings.glassEnabled,
            onCheckedChange = { update(settings.copy(glassEnabled = it)) },
        )
        ModuleThemePreferenceRow.TopBarBlur -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_top_bar_blur_title),
            summary = if (supported) null else stringResource(R.string.pref_glass_unsupported),
            enabled = supported && settings.blurEnabled,
            items = listOf(stringResource(R.string.top_bar_blur_gaussian), stringResource(R.string.top_bar_blur_progressive)),
            selectedIndex = settings.topBarBlur.ordinal,
            onSelectedIndexChange = { update(settings.copy(topBarBlur = ModuleTopBarBlur.entries[it])) },
        )
        ModuleThemePreferenceRow.Navigation -> SwitchPreference(
            title = stringResource(R.string.pref_show_navigation_title),
            checked = settings.showNavigation,
            onCheckedChange = { update(settings.copy(showNavigation = it)) },
        )
        ModuleThemePreferenceRow.Floating -> SwitchPreference(
            title = stringResource(R.string.pref_floating_bar_title),
            summary = stringResource(R.string.pref_floating_bar_summary),
            checked = settings.floatingNavigation,
            onCheckedChange = { update(settings.copy(floatingNavigation = it)) },
        )
        ModuleThemePreferenceRow.NavigationMode -> ModuleDropdownPreference(
            title = stringResource(R.string.pref_navigation_mode_title),
            items = listOf(
                stringResource(R.string.navigation_icon_and_text),
                stringResource(R.string.navigation_icon_only),
                stringResource(R.string.navigation_selected_text),
            ),
            selectedIndex = settings.navigationMode.ordinal,
            onSelectedIndexChange = { update(settings.copy(navigationMode = NavigationBarDisplayMode.entries[it])) },
        )
        ModuleThemePreferenceRow.Liquid -> SwitchPreference(
            title = stringResource(R.string.pref_liquid_glass_title),
            summary = stringResource(if (supported) R.string.pref_liquid_glass_summary else R.string.pref_glass_unsupported),
            enabled = supported,
            checked = settings.glassEnabled,
            onCheckedChange = { update(settings.copy(glassEnabled = it)) },
        )
    }
}
