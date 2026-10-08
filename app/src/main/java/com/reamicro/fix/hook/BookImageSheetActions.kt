package com.reamicro.fix.hook

import java.util.concurrent.atomic.AtomicBoolean

internal object BookImageSheetActions {
    enum class Id { Pick, Online, Generate, Save, Restore, Remove, Fix, Cancel }
    data class Action(val id: Id, val label: String, val icon: String)
    fun items(cover: Boolean, hasSecondary: Boolean): List<Action> = buildList {
        val label = if (cover) "封面" else "横幅"
        add(Action(Id.Pick, "选取图片", "Image"))
        add(Action(Id.Online, "在线图片", "Link"))
        add(Action(Id.Generate, "生成$label", "AutoAwesome"))
        if (cover || hasSecondary) add(Action(Id.Save, "保存$label", "Download"))
        if (hasSecondary) add(if (cover) Action(Id.Restore, "恢复封面", "RestartAlt")
            else Action(Id.Remove, "删除横幅", "DeleteOutline"))
        if (cover) add(Action(Id.Fix, "封面修复", "Build"))
        add(Action(Id.Cancel, "取消", "Cancel"))
    }
}

internal class BookImageSheetActionGate {
    private val claimed = AtomicBoolean(false)
    fun tryStart(): Boolean = claimed.compareAndSet(false, true)
    fun hideFinished(hidden: Boolean) { if (!hidden) claimed.set(false) }
}
