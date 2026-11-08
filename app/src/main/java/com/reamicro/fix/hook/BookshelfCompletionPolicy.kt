package com.reamicro.fix.hook

import com.reamicro.fix.settings.ModuleSettingsSnapshot

/** 同步显示与书架专属动作分开；多选和正在阅读均不注入书架动作。 */
internal object BookshelfCompletionPolicy {
    const val AUTO_UPDATE_INTERVAL_MS = 60 * 60 * 1000L

    enum class Action { UPDATE, STYLE }

    fun hideSync(settings: ModuleSettingsSnapshot): Boolean = settings.moduleEnabled && settings.shelfHideSync

    fun actions(
        settings: ModuleSettingsSnapshot,
        shelfVisible: Boolean = true,
        selecting: Boolean = false,
    ): List<Action> = buildList {
        if (!settings.moduleEnabled || !shelfVisible || selecting) return@buildList
        if (settings.shelfBatchUpdate) add(Action.UPDATE)
        if (settings.shelfStyleButton) add(Action.STYLE)
    }

    fun autoUpdate(settings: ModuleSettingsSnapshot): Boolean =
        settings.moduleEnabled && settings.shelfBatchUpdate && settings.shelfAutoUpdate

    fun canStartAutomatic(
        settings: ModuleSettingsSnapshot,
        now: Long,
        lastCompletedAt: Long,
        dexKitScanning: Boolean,
    ): Boolean =
        autoUpdate(settings) &&
            !dexKitScanning &&
            (lastCompletedAt <= 0L || now - lastCompletedAt >= AUTO_UPDATE_INTERVAL_MS)
}
