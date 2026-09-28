package com.reamicro.fix.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.reamicro.fix.R

/**
 * 桌面图标配色。
 *
 * 十套配色各自对应一个 `activity-alias`（见 AndroidManifest.xml），切换即启用目标别名、
 * 禁用其余。这套机制和「隐藏桌面图标」共用同一批组件，所以两件事必须在这里一起管：
 * 隐藏 = 十个全禁用，显示 = 只启用当选的那个。若各自单独 setComponentEnabledSetting，
 * 隐藏后再切配色就会把图标重新点亮，反之切完配色也会漏掉没被隐藏的别名。
 *
 * 默认那套沿用既有的 `ModuleLauncherAlias`：旧版本用户的隐藏状态记在这个组件名上，
 * 改名会让升级后的隐藏开关对不上已被禁用的组件。
 */
internal enum class ModuleIconStyle(
    /** 落库值。写进偏好文件，不要改。 */
    val key: String,
    /** 设置页展示名。 */
    @StringRes val labelRes: Int,
    /** 别名类名（相对包名）。默认那套是历史名字。 */
    val aliasClass: String,
    /**
     * 选择器预览用的**前景位图**（adaptive-icon 的 foreground 层，是 PNG）。
     * 不直接用整张 adaptive-icon（mipmap）——它在 API 26+ 是 XML，光栅化到 Compose 时
     * 部分 ROM（HyperOS 等）画不出前景，预览会空白。改由选择器用「背景色 + 前景位图」自行叠合。
     */
    @DrawableRes val foreground: Int,
    /** 选择器预览用的**背景色**（adaptive-icon 的 background 层）。 */
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

        /** 找不到就回默认，避免偏好文件被改坏后没有任何图标可用。 */
        fun fromKey(key: String?): ModuleIconStyle =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        /** 默认别名沿用历史 [DEFAULT]，其 manifest 默认 enabled=true，其余默认 false。 */
        fun aliases(): List<LauncherIconAlias> =
            entries.map { LauncherIconAlias(it.key, defaultEnabled = it == DEFAULT) }

        /**
         * PackageManager 适配：切换与探测都走这里，逻辑判定交给 [LauncherIconController]。
         *
         * - 读单个组件失败直接抛出，让控制器把整次观测标记为 UNKNOWN，而不是漏掉某个别名。
         * - 应用变更：API 33+ 用 setComponentEnabledSettings 一次原子提交，旧版本按传入顺序
         *   逐个提交（控制器保证「先启用目标，再禁用其余」，避免中途没有任何 LAUNCHER 入口）。
         * - DONT_KILL_APP：别把正在操作的界面自身杀掉；它不保证所有 ROM 都不重建桌面，
         *   所以持久化与恢复由控制器负责。
         */
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