package com.reamicro.fix.hook

import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** One choice of a [HostOptionList]: its name, a quieter note after it, and whether it is the current one. */
internal class HostOption(
    val label: String,
    val note: String = "",
    val selected: Boolean = false,
    val onPick: () -> Unit,
)

/** The host's option list: a line to tap for each choice, the current one in the accent colour and ticked. */
internal class HostOptionList(private val kit: HostFormKit, empty: String = "") {
    private val list = kit.list()
    private val none = kit.empty(empty).apply { visibility = View.GONE }
    private var revealed = false
    val body: LinearLayout = kit.column().apply {
        addView(list, kit.params())
        addView(none, kit.params(top = 24f, bottom = 24f))
    }

    /** Replaces the choices; the first time, the current one is brought into view. */
    fun show(options: List<HostOption>) {
        list.removeAllViews()
        list.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
        none.visibility = if (options.isEmpty()) View.VISIBLE else View.GONE
        options.forEach { list.addView(row(it)) }
        if (revealed) return
        revealed = true
        options.indexOfFirst { it.selected }.takeIf { it > 0 }?.let { reveal(list.getChildAt(it)) }
    }

    private fun row(option: HostOption): View {
        val row = kit.row()
        row.minimumHeight = kit.dp(48f)
        row.setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(14f))
        row.background = kit.pressable()
        row.isFocusable = true
        row.accessibilityDelegate = HostRole("android.widget.RadioButton") { option.selected }
        row.setOnClickListener { option.onPick() }
        val tint = if (option.selected) kit.colors.accentText else kit.colors.text
        row.addView(kit.text(caption(option), "BodyLarge", tint), kit.weighted())
        if (option.selected) {
            val tick = kit.glyph(HostGlyph.Check, kit.colors.accentText, TICK)
            (tick.layoutParams as LinearLayout.LayoutParams).marginStart = kit.dp(12f)
            row.addView(tick)
        }
        return row
    }

    private fun caption(option: HostOption): CharSequence {
        if (option.note.isBlank()) return option.label
        return SpannableStringBuilder(option.label).append("  ").append(
            option.note, ForegroundColorSpan(kit.colors.secondary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** Scrolls the form, and nothing outside it, to [row] once the list has been laid out. */
    private fun reveal(row: View) {
        list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (row.parent === list && row.height == 0) return
                list.removeOnLayoutChangeListener(this)
                val scroll = generateSequence(row.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()
                val content = scroll?.getChildAt(0) as? ViewGroup ?: return
                val spot = Rect(0, 0, row.width, row.height)
                content.offsetDescendantRectToMyCoords(row, spot)
                scroll.requestChildRectangleOnScreen(content, spot, true)
            }
        })
    }

    private companion object {
        const val TICK = 16f
    }
}
