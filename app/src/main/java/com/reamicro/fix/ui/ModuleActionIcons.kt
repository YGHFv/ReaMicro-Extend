package com.reamicro.fix.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.icon.glass.Sort
import top.yukonga.miuix.kmp.icon.glass.SelectAll
import top.yukonga.miuix.kmp.icon.glass.Contacts
import top.yukonga.miuix.kmp.icon.glass.Close

internal enum class ModuleActionIcon { Play, Settings, Info, Delete, Sort, SelectAll, Close, Accounts }

@Composable
internal fun moduleActionIcon(icon: ModuleActionIcon): ImageVector =
    if (LocalModuleThemeConfig.current.style == ModuleUiStyle.OS4) {
        when (icon) {
            ModuleActionIcon.Play -> MiuixIcons.Glass.Play
            ModuleActionIcon.Settings -> MiuixIcons.Glass.SettingsOutline
            ModuleActionIcon.Info -> MiuixIcons.Glass.Info
            ModuleActionIcon.Delete -> MiuixIcons.Glass.Delete
            ModuleActionIcon.Sort -> MiuixIcons.Glass.Sort
            ModuleActionIcon.SelectAll -> MiuixIcons.Glass.SelectAll
            ModuleActionIcon.Close -> MiuixIcons.Glass.Close
            ModuleActionIcon.Accounts -> MiuixIcons.Glass.Contacts
        }
    } else {
        when (icon) {
            ModuleActionIcon.Play -> MiuixIcons.Play
            ModuleActionIcon.Settings -> MiuixIcons.Settings
            ModuleActionIcon.Info -> MiuixIcons.Info
            ModuleActionIcon.Delete -> MiuixIcons.Delete
            ModuleActionIcon.Sort -> MiuixIcons.Sort
            ModuleActionIcon.SelectAll -> MiuixIcons.SelectAll
            ModuleActionIcon.Close -> MiuixIcons.Close
            ModuleActionIcon.Accounts -> MiuixIcons.Contacts
        }
    }
