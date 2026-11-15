package com.reamicro.fix.hook

import java.util.concurrent.atomic.AtomicBoolean

internal object BookImageSheetActions {
    enum class Id { Online, Generate, Save, Fix }
    data class Action(val id: Id, val label: String, val icon: String)
    fun additions(cover: Boolean, hasSecondary: Boolean): List<Action> = buildList {
        add(Action(Id.Online, "在线图片", "Link"))
        add(Action(Id.Generate, "生成${if (cover) "封面" else "横幅"}", "AutoAwesome"))
        if (!cover && hasSecondary) add(Action(Id.Save, "保存横幅", "Download"))
        if (cover) add(Action(Id.Fix, "封面修复", "Edit"))
    }
}

internal class BookImageSheetActionGate {
    private val claimed = AtomicBoolean(false)
    fun tryStart(): Boolean = claimed.compareAndSet(false, true)
    fun hideFinished(hidden: Boolean) { if (!hidden) claimed.set(false) }
}
