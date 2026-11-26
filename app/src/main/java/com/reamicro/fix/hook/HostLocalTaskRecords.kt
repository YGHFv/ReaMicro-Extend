package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout

/** Run history as the host's record cards: what ran and when, then how it ended. */
internal class HostLocalTaskRecords(context: Context, colors: HostFormColors = HostFormColors.of(context)) {
    private val kit = HostFormKit(context, colors)
    val body: LinearLayout = kit.column()

    fun summary(message: String) {
        body.addView(kit.text(message, "BodyMedium", kit.colors.secondary), kit.params(top = 2f, bottom = 2f))
    }

    fun empty(message: String) {
        body.addView(kit.empty(message), kit.params(top = 24f, bottom = 24f))
    }

    fun record(title: String, time: String, result: String, message: String) {
        val outcome = outcome(result)
        val card = kit.column().apply {
            background = kit.shape(Color.TRANSPARENT, 12f, kit.colors.recordBorder, 1f)
            val inset = kit.dp(16f)
            setPadding(inset, inset, inset, inset)
            contentDescription = listOf(title, outcome.label, time, message).filter(String::isNotBlank).joinToString("，")
            if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true else isFocusable = true
        }
        val heading = kit.row()
        heading.addView(kit.glyph(outcome.glyph, outcome.tint, 18f))
        heading.addView(kit.text(title, "BodyLarge", kit.colors.text), kit.weighted().apply { marginStart = kit.dp(8f) })
        heading.addView(kit.text(time, "BodySmall", kit.colors.secondary), wrapped(8f))
        card.addView(heading)
        card.addView(kit.divider(kit.colors.recordDivider, start = 0f, gap = 10f))
        val detail = kit.row().apply { gravity = Gravity.TOP }
        detail.addView(kit.text(message, "BodyMedium", kit.colors.text), kit.weighted())
        detail.addView(kit.text(outcome.label, "LabelMedium", outcome.tint), wrapped(12f))
        card.addView(detail)
        body.addView(card, kit.params(top = 10f))
    }

    private fun wrapped(start: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginStart = kit.dp(start) }

    private fun outcome(result: String) = when (result) {
        "success" -> Outcome(HostGlyph.Success, "成功", kit.colors.accentText)
        "failed" -> Outcome(HostGlyph.Failed, "失败", kit.colors.error)
        "paused" -> Outcome(HostGlyph.Paused, "已暂停", kit.colors.secondary)
        "skipped" -> Outcome(HostGlyph.Skipped, "已跳过", kit.colors.secondary)
        else -> Outcome(HostGlyph.Skipped, result.ifBlank { "未知" }, kit.colors.secondary)
    }

    private class Outcome(val glyph: HostGlyph, val label: String, val tint: Int)
}
