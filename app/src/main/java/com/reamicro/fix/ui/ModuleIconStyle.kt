package com.reamicro.fix.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.reamicro.fix.R

internal enum class ModuleIconStyle(

    val key: String,

    @StringRes val labelRes: Int,

    val aliasClass: String,

    @DrawableRes val foreground: Int,

    @ColorRes val background: Int,
) {
    DEFAULT("default", R.string.icon_classic, ".ui.ModuleLauncherAlias", R.mipmap.ic_launcher_foreground, R.color.ic_launcher_background),
    MONO("mono", R.string.icon_mono, ".ui.ModuleIconAliasMono", R.mipmap.ic_launcher_02_foreground, R.color.ic_launcher_02_background),
    GOLD("gold", R.string.icon_gold, ".ui.ModuleIconAliasGold", R.mipmap.ic_launcher_03_foreground, R.color.ic_launcher_03_background),
    NIGHT("night", R.string.icon_night, ".ui.ModuleIconAliasNight", R.mipmap.ic_launcher_04_foreground, R.color.ic_launcher_04_background),
    SKY("sky", R.string.icon_bamboo, ".ui.ModuleIconAliasSky", R.mipmap.ic_launcher_05_foreground, R.color.ic_launcher_05_background),
    DARK("dark", R.string.icon_dark, ".ui.ModuleIconAliasDark", R.mipmap.ic_launcher_06_foreground, R.color.ic_launcher_06_background),
    ROSE("rose", R.string.icon_rose, ".ui.ModuleIconAliasRose", R.mipmap.ic_launcher_07_foreground, R.color.ic_launcher_07_background),
    BLUE("blue", R.string.icon_blue, ".ui.ModuleIconAliasBlue", R.mipmap.ic_launcher_08_foreground, R.color.ic_launcher_08_background),
    VIOLET("violet", R.string.icon_violet, ".ui.ModuleIconAliasViolet", R.mipmap.ic_launcher_09_foreground, R.color.ic_launcher_09_background),
    SLATE("slate", R.string.icon_slate, ".ui.ModuleIconAliasSlate", R.mipmap.ic_launcher_10_foreground, R.color.ic_launcher_10_background),
    ;

    companion object {

        fun fromKey(key: String?): ModuleIconStyle =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        fun aliases(): List<LauncherIconAlias> =
            entries.map { LauncherIconAlias(it.key, defaultEnabled = it == DEFAULT) }

        fun androidComponents(context: Context): LauncherIconComponents {
            val pm = context.packageManager
            val pkg = context.packageName
            fun component(key: String) = ComponentName(pkg, pkg + fromKey(key).aliasClass)
            return object : LauncherIconComponents {
                override fun read(key: String): LauncherComponentState =
                    when (pm.getComponentEnabledSetting(component(key))) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> LauncherComponentState.ENABLED
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> LauncherComponentState.DISABLED
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> LauncherComponentState.DISABLED_USER
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> LauncherComponentState.DISABLED_UNTIL_USED
                        else -> LauncherComponentState.DEFAULT
                    }

                override fun apply(changes: List<LauncherIconChange>) {
                    fun stateOf(enabled: Boolean) = if (enabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.setComponentEnabledSettings(
                            changes.map { change ->
                                PackageManager.ComponentEnabledSetting(
                                    component(change.key),
                                    stateOf(change.enabled),
                                    PackageManager.DONT_KILL_APP,
                                )
                            },
                        )
                    } else {
                        changes.forEach { change ->
                            pm.setComponentEnabledSetting(
                                component(change.key),
                                stateOf(change.enabled),
                                PackageManager.DONT_KILL_APP,
                            )
                        }
                    }
                }
            }
        }
    }
}
