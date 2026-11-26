package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A dialog's form, laid out with the host's dialog, settings-card and option components. */
internal class HostForm(context: Context, colors: HostFormColors = HostFormColors.of(context)) {
    val kit = HostFormKit(context, colors)
    val body: LinearLayout = kit.column()
    private val fields = HashMap<EditText, HostOutlinedField>()
    private val reveals = HashMap<View, () -> Unit>()
    private var failed: HostOutlinedField? = null
    private val error = kit.text("", "BodyMedium", kit.colors.error).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** Opens the form the way the host's dialogs open: an accent label, over a line of body text if there is one. */
    fun lead(label: String, description: String = "") {
        body.addView(kit.text(label, "LabelMedium", kit.colors.accentText), kit.params(top = 2f))
        if (description.isBlank()) return
        body.addView(kit.text(description, "BodyLarge", kit.colors.lead), kit.params(top = 6f))
    }

    fun card(parent: LinearLayout = body, top: Float = 16f): LinearLayout =
        kit.card().also { parent.addView(it, kit.params(top = top)) }

    fun divider(parent: LinearLayout) = parent.addView(kit.divider())

    fun switchRow(parent: LinearLayout, title: String, subtitle: String, checked: Boolean): HostSwitchRow =
        HostSwitchRow(kit, title, subtitle, checked).also { parent.addView(it) }

    /** An entry row that opens something else; returns the view holding its second line. */
    fun entryRow(parent: LinearLayout, title: String, summary: String, onClick: () -> Unit): TextView {
        val row = HostEntryRow(kit, title, summary)
        row.setOnClickListener { onClick() }
        parent.addView(row)
        return row.summaryView
    }

    /** An entry row that shows what it is set to and opens the choices for it. */
    fun valueRow(parent: LinearLayout, title: String, value: String, onClick: () -> Unit) {
        val row = HostEntryRow(kit, title, "", value = value)
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    /** A row that unfolds its content beneath it, inside the same card. */
    fun disclosure(parent: LinearLayout, title: String, summary: String, expanded: Boolean): LinearLayout {
        val content = kit.column().apply { setPadding(kit.dp(16f), 0, kit.dp(16f), kit.dp(16f)) }
        val row = HostEntryRow(kit, title, summary)
        fun unfold(open: Boolean) {
            content.visibility = if (open) View.VISIBLE else View.GONE
            row.setGlyph(if (open) HostGlyph.Down else HostGlyph.Forward)
            val state = if (open) "已展开" else "已收起"
            if (Build.VERSION.SDK_INT >= 30) {
                row.stateDescription = state
            } else {
                row.contentDescription = listOf(title, summary, state).filter(String::isNotBlank).joinToString("，")
            }
        }
        row.setOnClickListener { unfold(content.visibility != View.VISIBLE) }
        reveals[content] = { unfold(true) }
        unfold(expanded)
        parent.addView(row)
        parent.addView(content)
        return content
    }

    /** The host's section title, [bottom] above what it heads. */
    fun heading(text: String, parent: LinearLayout = body, bottom: Float = 12f) {
        val title = kit.text(text, "TitleSmall", kit.colors.text, SECTION_WEIGHT)
        if (Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
        parent.addView(title, kit.params(top = 20f, bottom = bottom))
    }

    /** A host section title over one row of option blocks. */
    fun choices(
        title: String,
        options: List<Pair<String, String>>,
        selected: String,
        helper: String = "",
        parent: LinearLayout = body,
        onSelected: (String) -> Unit,
    ) {
        heading(title, parent, 12f - HostChoiceRow.TOUCH_INSET)
        val row = HostChoiceRow(kit)
        options.forEach { (value, label) -> row.add(label, value == selected) { onSelected(value) } }
        parent.addView(row, kit.params())
        if (helper.isNotBlank()) helper(helper, parent, 8f - HostChoiceRow.TOUCH_INSET)
    }

    fun field(
        title: String,
        value: String,
        hint: String,
        helper: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
        parent: LinearLayout = body,
    ): EditText {
        val field = HostOutlinedField(kit, title, value, hint, helper, numeric, multiline)
        parent.addView(field, kit.params(top = if (opensDisclosure(parent)) 4f else 16f))
        fields[field.input] = field
        return field.input
    }

    fun helper(message: String, parent: LinearLayout = body, top: Float = 8f) {
        parent.addView(
            kit.text(message, "BodyMedium", kit.colors.secondary),
            kit.params(top = if (opensDisclosure(parent)) 0f else top),
        )
    }

    fun section(visible: Boolean = true, parent: LinearLayout = body): LinearLayout = kit.column().also {
        it.visibility = if (visible) View.VISIBLE else View.GONE
        parent.addView(it)
    }

    /** The host's bordered tip box. */
    fun notice(lines: List<String>, parent: LinearLayout = body) {
        val box = kit.column().apply {
            background = kit.shape(Color.TRANSPARENT, 4f, kit.colors.hairline, 1f)
            val inset = kit.dp(12f)
            setPadding(inset, inset, inset, inset)
        }
        lines.forEachIndexed { index, line ->
            box.addView(kit.text(line, "BodyMedium", kit.colors.secondary), kit.params(top = if (index == 0) 0f else 12f))
        }
        parent.addView(box, kit.params(top = 16f))
    }

    /** What a part of the form says when it has nothing to offer. */
    fun empty(message: String, parent: LinearLayout = body) {
        parent.addView(kit.empty(message), kit.params(top = 24f, bottom = 24f))
    }

    fun finish() = body.addView(error, kit.params(top = 12f))

    /** Reports [message] on [field] when the user can be taken to it, otherwise beneath the form. */
    fun showError(message: String, field: EditText? = null) {
        clearError()
        val target = field?.let(fields::get)?.takeIf(::reveal)
        if (target == null) {
            error.text = message
            error.visibility = View.VISIBLE
            error.post { error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height), false) }
            return
        }
        failed = target
        target.fail(message)
        target.input.requestFocus()
        target.post { target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), false) }
    }

    fun clearError() {
        error.text = ""
        error.visibility = View.GONE
        failed?.clear()
        failed = null
    }

    /** Unfolds whatever hides [view]; false when it sits in a part of the form that does not apply. */
    private fun reveal(view: View): Boolean {
        var current: View? = view
        while (current != null && current !== body) {
            reveals[current]?.invoke()
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return current === body
    }

    private fun opensDisclosure(parent: LinearLayout) = parent.childCount == 0 && reveals.containsKey(parent)

    private companion object {
        /** The host draws section titles in its title face at regular weight. */
        const val SECTION_WEIGHT = 400
    }
}
