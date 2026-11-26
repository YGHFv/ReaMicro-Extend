package com.reamicro.fix.hook

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask

/** Task settings in the host's own components; optional details stay folded inside their card. */
internal class HostLocalTaskEditor(
    context: Context,
    spec: CloudAutomationTaskSpec,
    task: LocalTask?,
    account: String,
    pawnSummary: String,
    onPawnClick: () -> Unit,
    colors: HostFormColors = HostFormColors.of(context),
) {
    val form = HostForm(context, colors)
    val body: LinearLayout get() = form.body
    var duration: EditText? = null; private set
    var drawLimit: EditText? = null; private set
    var books: EditText? = null; private set
    var readOncePerDay: HostSwitchRow? = null; private set
    var merchantAutoComplete: HostSwitchRow? = null; private set
    var merchantCity: EditText? = null; private set
    var merchantPrincipal: EditText? = null; private set
    var merchantTransport: EditText? = null; private set
    var pawnSummaryView: TextView? = null; private set
    var usesRecentBooks = task?.books.isNullOrEmpty(); private set
    var blessingChoice = spec.resolveBlessingChoice(task?.blessingType); private set

    init {
        form.lead(account, when {
            spec.autoRead -> "打开阅微时上报阅读时长。"
            spec.merchant -> "打开阅微时检查行商进度，记录事件与收益。"
            spec.rewardTriggered -> "领取轶闻奖励后，使用彩筹自动祈愿。"
            spec.taskType == "pawn" -> "按当日期物典当换铜，跳过你锁定的期物。"
            else -> "打开阅微时获取每日轶闻，结束后领取奖励。"
        })
        if (spec.autoRead) reading(task)
        if (spec.rewardTriggered) {
            drawLimit = form.field("每日祈愿上限", (task?.dailyDrawLimit ?: 3).toString(), "0–20 次",
                "填 0 会用完所有彩筹；填 1–20 可限制每日次数。", numeric = true)
        }
        if (spec.merchant) merchant(spec, task)
        else if (spec.blessingOptions.isNotEmpty()) blessing(spec)
        if (spec.taskType == "pawn") {
            pawnSummaryView = form.entryRow(form.card(), "禁当期物", pawnSummary, onPawnClick)
            form.helper("锁定的期物不会典当；一项都不锁定时，任何期物都可被典当。")
        }
        form.notice(listOfNotNull(
            "离开阅微后不会定时检查；再次打开时继续处理。".takeIf { spec.merchant },
            "保存仅更新配置；任务是否启用由列表开关控制。",
        ))
        form.finish()
    }

    private fun reading(task: LocalTask?) {
        val card = form.card()
        readOncePerDay = form.switchRow(card, "每日仅运行一次",
            "按北京时间，每个账号每天只执行一批阅读。关闭后，每次冷启动都可再次执行。", task?.hostReadOncePerDay ?: true)
        form.divider(card)
        form.helper("每日仅运行一次开启时，首次上报后即记录当天已执行。部分成功或结果不明时，当天不会再开一批；没有可读图书时不占次数。",
            form.disclosure(card, "阅读执行说明", "", false))
        duration = form.field("每本阅读时长", (task?.durationMinutes ?: 30).toString(), "1–720 分钟",
            "每本 1–720 分钟，仍受时间锁与每日上限限制。", numeric = true)
        lateinit var customBooks: LinearLayout
        form.choices("阅读图书", listOf("recent" to "最近阅读", "custom" to "指定图书"),
            if (usesRecentBooks) "recent" else "custom") { choice ->
            usesRecentBooks = choice == "recent"
            customBooks.visibility = if (usesRecentBooks) View.GONE else View.VISIBLE
        }
        customBooks = form.section(!usesRecentBooks)
        books = form.field("图书清单", task?.books?.joinToString("\n") { "${it.bookId}|${it.name}" }.orEmpty(),
            "图书 ID | 书名", "每行一本；书名可省略。", multiline = true, parent = customBooks)
    }

    private fun merchant(spec: CloudAutomationTaskSpec, task: LocalTask?) {
        val automatic = task?.merchantAutoComplete == true
        val card = form.card()
        val switch = form.switchRow(card, "自动完成行商", "开启后结算已完成的行商，再按配置开启下一趟；关闭时只检查并通知。", automatic)
        merchantAutoComplete = switch
        val nextTrip = form.section(automatic, card)
        form.divider(nextTrip)
        val custom = form.disclosure(nextTrip, "下一趟行商", "城池、本金与车马留空时，沿用上次行商。",
            task?.merchantCityCode?.isNotBlank() == true || (task?.merchantPrincipal ?: 0L) > 0L ||
                (task?.merchantTransportId ?: 0L) > 0L)
        merchantCity = form.field("目的城池", task?.merchantCityCode.orEmpty(), "沿用上次城池",
            "填写城池代码。", parent = custom)
        merchantPrincipal = form.field("携带本金", task?.merchantPrincipal?.takeIf { it > 0 }?.toString().orEmpty(),
            "沿用上次本金", "单位：铜；填 0 也会沿用上次。", numeric = true, parent = custom)
        merchantTransport = form.field("行商车马", task?.merchantTransportId?.takeIf { it > 0 }?.toString().orEmpty(),
            "沿用上次车马", "填写车马 ID；填 0 也会沿用上次。", numeric = true, parent = custom)
        val blessings = form.section(automatic)
        blessing(spec, blessings)
        switch.setOnCheckedChangeListener { enabled ->
            val visibility = if (enabled) View.VISIBLE else View.GONE
            nextTrip.visibility = visibility
            blessings.visibility = visibility
        }
    }

    private fun blessing(spec: CloudAutomationTaskSpec, parent: LinearLayout = body) {
        form.choices("无签时自动祈求", spec.blessingOptions.map { value ->
            value to when (value) {
                CloudTaskLocalRunner.BLESSING_LUCK -> "求运 · 轶闻"
                CloudTaskLocalRunner.BLESSING_SAFETY -> "求安 · 行商"
                CloudTaskLocalRunner.BLESSING_WEALTH -> "求财 · 行商"
                else -> "不自动祈签"
            }
        }, blessingChoice, (if (spec.merchant) "已有安签或财签直接沿用，其他签保留。"
            else "已有运签直接沿用，其他签保留。") + "查询不明或祈签结果待确认时跳过。", parent) { blessingChoice = it }
    }
}
