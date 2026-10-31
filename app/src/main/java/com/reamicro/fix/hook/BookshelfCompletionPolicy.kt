package com.reamicro.fix.hook

import com.reamicro.fix.settings.ModuleSettingsSnapshot

/** 按视觉顺序决定动作，不因隐藏同步而关闭其余独立开关。 */
internal object BookshelfCompletionPolicy {
    const val AUTO_UPDATE_INTERVAL_MS = 60 * 60 * 1000L

    enum class Action { SYNC, UPDATE, STYLE }

    fun actions(settings: ModuleSettingsSnapshot, shelfVisible: Boolean = true): List<Action> = buildList {
        if (shelfVisible && !settings.shelfHideSync) add(Action.SYNC)
        if (settings.shelfBatchUpdate) add(Action.UPDATE)
        if (shelfVisible && settings.shelfStyleButton) add(Action.STYLE)
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
