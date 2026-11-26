package com.reamicro.fix.hook

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.ui.MultiSelectOption

/** Lockable entries as a host option list, under a hint and an optional outlined action. */
internal class HostLockList(
    context: Context,
    hint: String,
    selected: Set<String>,
    colors: HostFormColors = HostFormColors.of(context),
) {
    private val kit = HostFormKit(context, colors)
    private val locked = selected.toMutableSet()
    private val list = kit.list()
    val body: LinearLayout = kit.column()
    val selection: Set<String> get() = locked.toSet()

    /** Asked before each tap; a closing dialog answers false. */
    var accepting: () -> Boolean = { true }

    init {
        if (hint.isNotBlank()) {
            body.addView(kit.text(hint, "BodyLarge", kit.colors.lead), kit.params(top = 2f))
        }
        body.addView(list, kit.params(top = 12f))
    }

    fun show(options: List<MultiSelectOption>) {
        list.removeAllViews()
        list.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
        options.forEach { option ->
            val row = HostLockRow(kit, option, option.value in locked)
            row.setOnClickListener {
                if (!accepting()) return@setOnClickListener
                if (!locked.add(option.value)) locked.remove(option.value)
                row.locked = option.value in locked
            }
            list.addView(row)
        }
    }

    /** Puts the host's outlined button between the hint and the list. */
    fun action(label: String, description: String, onClick: () -> Unit): TextView {
        val button = kit.outlinedButton(label, onClick)
        button.contentDescription = description
        // The button's 48dp touch target already insets its 40dp outline by 4dp.
        body.addView(button, body.indexOfChild(list), kit.params(top = 8f))
        list.layoutParams = kit.params(top = 8f)
        return button
    }
}

/** One entry: what it is, what it is for, and whether it is locked. */
private class HostLockRow(
    private val kit: HostFormKit,
    private val option: MultiSelectOption,
    locked: Boolean,
) : LinearLayout(kit.context) {
    private val mark = kit.glyph(HostGlyph.Check, kit.colors.accentText, MARK_SIZE)
    private val state = TextView(context)
    var locked = locked
        set(value) {
            field = value
            show()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(48f)
        setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(14f))
        background = kit.pressable()
        isFocusable = true
        accessibilityDelegate = HostRole("android.widget.CheckBox") { this.locked }
        val labels = kit.column()
        labels.addView(kit.text(option.label, "BodyLarge", option.color?.let(kit.colors::readable) ?: kit.colors.text))
        if (option.detail.isNotBlank()) {
            labels.addView(kit.text(option.detail, "BodyMedium", kit.colors.secondary), kit.params(top = 2f))
        }
        addView(labels, kit.weighted())
        addView(mark, LayoutParams(kit.dp(MARK_SIZE), kit.dp(MARK_SIZE)).apply { marginStart = kit.dp(12f) })
        addView(state, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = kit.dp(4f)
        })
        show()
    }

    private fun show() {
        isSelected = locked
        mark.visibility = if (locked) View.VISIBLE else View.GONE
        kit.style(state, if (locked) "LabelMedium" else "BodyMedium")
        state.setTextColor(if (locked) kit.colors.accentText else kit.colors.secondary)
        state.text = if (locked) "已锁定" else "可典当"
        val name = listOf(option.label, option.detail).filter(String::isNotBlank).joinToString("，")
        contentDescription = "$name，${if (locked) "已锁定，点按取消锁定" else "未锁定，点按锁定禁止典当"}"
    }

    private companion object {
        const val MARK_SIZE = 16f
    }
}
