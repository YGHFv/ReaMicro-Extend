package com.reamicro.fix.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.hook.ModuleDialogTheme

internal data class MultiSelectOption(val value: String, val label: String, val color: Int? = null)

internal fun multiSelectSummary(options: List<MultiSelectOption>, selected: Set<String>): String {
    val locked = options.count { it.value in selected }
    return when {
        options.isEmpty() -> "暂无可选期物"
        locked == 0 -> "未锁定任何项（全部可典当）"
        else -> "已锁定 $locked/${options.size} 项"
    }
}

internal class ModuleUiKit(private val context: Context) {
    private val dp = context.resources.displayMetrics.density
    val palette = ModuleDialogTheme.palette(context)

    fun page(children: List<View>): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(28))
            setBackgroundColor(palette.pageBackground)
        }
        children.forEach { column.addView(it) }
        return ScrollView(context).apply {
            setBackgroundColor(palette.pageBackground)
            addView(column)

            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                column.setPadding(px(16), px(16) + top, px(16), px(28) + bottom)
                insets
            }
        }
    }

    fun pageTitle(text: String, subtitle: String = ""): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(6), px(6), px(6), px(10))
        addView(textView(text, 24f, palette.title, bold = true))
        if (subtitle.isNotBlank()) {
            addView(textView(subtitle, 13f, palette.body).apply { setPadding(0, px(4), 0, 0) })
        }
    }

    fun sectionTitle(text: String): View = textView(text, 13f, palette.body, bold = true).apply {
        setPadding(px(8), px(18), px(8), px(6))
    }

    fun card(rows: List<View>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.rowBackground, 12f)
        setPadding(px(4), px(4), px(4), px(4))
        rows.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(10) }
    }

    fun row(
        title: String,
        subtitle: String = "",
        actions: List<Pair<String, () -> Unit>> = emptyList(),
        titleColor: Int = palette.title,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(12), px(14), px(12))
        addView(textView(title, 16f, titleColor))
        if (subtitle.isNotBlank()) {
            addView(textView(subtitle, 12f, palette.body).apply { setPadding(0, px(4), 0, 0) })
        }
        if (actions.isNotEmpty()) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(10), 0, 0)
                actions.forEach { (label, onClick) ->
                    addView(button(label, role = buttonRoleOf(label), onClick = onClick))
                }
            })
        }
    }

    enum class Role { Primary, Neutral, Danger }

    fun button(label: String, role: Role = Role.Primary, onClick: () -> Unit): TextView =
        textView(
            label,
            14f,
            when (role) {
                Role.Primary -> palette.primaryText
                Role.Neutral -> palette.neutralText
                Role.Danger -> palette.destructiveText
            },
        ).apply {
            gravity = Gravity.CENTER

            setPadding(px(16), 0, px(16), 0)
            background = rounded(palette.pageBackground, 8f).apply {
                setStroke((1.2f * dp).toInt(), palette.border)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                px(36),
            ).apply { rightMargin = px(8) }
        }

    private fun buttonRoleOf(label: String): Role = when (label) {
        "清空", "停用", "取消" -> Role.Danger
        "关闭", "刷新", "重算下次时刻" -> Role.Neutral
        else -> Role.Primary
    }

    fun info(text: String, color: Int = palette.body): TextView =
        textView(text, 12f, color).apply { setPadding(px(14), px(4), px(14), px(10)) }

    fun textView(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    fun rounded(color: Int, radiusDp: Float = CARD_CORNER_DP): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * dp
        setColor(color)
    }

    fun contentDialog(title: String, content: CharSequence, actions: List<Pair<String, () -> Unit>>, bodySizeSp: Float = 12f): Dialog {
        val dialog = Dialog(context)
        val metrics = context.resources.displayMetrics
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.rowBackground, 12f)
            setPadding(px(18), px(18), px(18), px(14))
        }
        card.addView(textView(title, 18f, palette.title, bold = true).apply {
            setPadding(0, 0, 0, px(10))
        })
        card.addView(
            ScrollView(context).apply {
                addView(
                    TextView(context).apply {
                        text = content
                        textSize = bodySizeSp
                        setTextColor(palette.body)
                        includeFontPadding = false
                        setTextIsSelectable(true)
                    },
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (metrics.heightPixels * 0.55f).toInt(),
                )
            },
        )
        if (actions.isNotEmpty()) {
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(12), 0, 0)
                actions.forEach { (label, onClick) -> addView(button(label, onClick = onClick)) }
            })
        }
        dialog.setContentView(card)
        dialog.setOnShowListener {

            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout(
                (metrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    fun toast(message: String) {
        android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun scaffold(content: View, tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): View {
        val bar = bottomBar(tabs, selectedIndex, onSelect)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.pageBackground)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)

            setOnApplyWindowInsetsListener { _, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                (bar.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    params.bottomMargin = px(BOTTOM_BAR_MARGIN_DP) + bottom
                    bar.layoutParams = params
                }
                insets
            }
        }
    }

    private fun bottomBar(tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(palette.rowBackground, 14f)
            setPadding(px(6), px(6), px(6), px(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = px(12)
                rightMargin = px(12)
                bottomMargin = px(BOTTOM_BAR_MARGIN_DP)
            }
            tabs.forEachIndexed { index, title ->
                addView(
                    textView(title, 14f, if (index == selectedIndex) palette.primaryText else palette.body, bold = index == selectedIndex).apply {
                        gravity = Gravity.CENTER
                        isClickable = true
                        setOnClickListener { onSelect(index) }
                        if (index == selectedIndex) {
                            background = rounded(palette.pageBackground, 10f).apply {
                                setStroke((1.2f * dp).toInt(), palette.border)
                            }
                        }
                        layoutParams = LinearLayout.LayoutParams(0, px(42), 1f).apply {
                            leftMargin = px(3)
                            rightMargin = px(3)
                        }
                    },
                )
            }
        }

    fun listItem(
        title: String,
        body: String,
        meta: String,
        accent: Boolean,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.rowBackground, 12f)
        setPadding(px(14), px(12), px(14), px(12))
        isClickable = true
        setOnClickListener { onClick() }

        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = px(10) }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    textView(title, 15f, if (accent) palette.title else palette.destructiveText, bold = true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(textView(meta, 11f, palette.body))
            },
        )
        if (body.isNotBlank()) {
            addView(textView(body, 13f, palette.body).apply { setPadding(0, px(6), 0, 0) })
        }
    }

    fun editDialog(
        title: String,
        build: (
            add: (label: String, hint: String, value: String) -> Unit,
            choose: (label: String, options: List<Pair<String, String>>, value: String) -> Unit,
            multi: (
                label: String,
                hint: String,
                options: List<MultiSelectOption>,
                selected: Set<String>,
                refresh: (() -> List<MultiSelectOption>?)?,
            ) -> Unit,
        ) -> Unit,
        register: (label: String, value: () -> String) -> Unit,
        onSave: () -> Boolean,
    ): Dialog {
        val dialog = Dialog(context)
        val metrics = context.resources.displayMetrics
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.rowBackground, 12f)
            setPadding(px(18), px(18), px(18), px(14))
        }
        card.addView(textView(title, 18f, palette.title, bold = true).apply { setPadding(0, 0, 0, px(10)) })

        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        build(
            { label, hint, value ->
                form.addView(fieldRow(label, hint))
                val edit = editText(value)
                form.addView(edit)
                register(label) { edit.text.toString().trim() }
            },
            { label, options, value ->
                form.addView(fieldRow(label, options.joinToString("/") { it.second }))
                var selected = options.firstOrNull { it.first == value } ?: options.firstOrNull()
                val picker = button(selected?.second ?: "未选择", role = Role.Neutral) {}
                picker.setOnClickListener {
                    if (options.size > 1) {
                        val next = (options.indexOfFirst { it.first == selected?.first } + 1) % options.size
                        selected = options[next]

                        picker.text = selected?.second ?: "未选择"
                    }
                }
                form.addView(picker)
                register(label) { selected?.first.orEmpty() }
            },
            { label, hint, options, selected, refresh ->
                form.addView(fieldRow(label, hint))
                var picked = selected

                var shown = options
                val summary = button(multiSelectSummary(shown, picked), role = Role.Neutral) {}

                val refreshAndTrack = refresh?.let { load ->
                    { load()?.also { shown = it } }
                }
                summary.setOnClickListener {
                    multiSelectDialog(
                        label,
                        hint,
                        shown,
                        picked,
                        refresh = refreshAndTrack,
                        refreshLabel = "刷新期物清单",
                    ) { confirmed ->
                        picked = confirmed
                        summary.text = multiSelectSummary(shown, picked)
                    }
                }
                form.addView(summary)

                register(label) { picked.sortedWith(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it })).joinToString(",") }
            },
        )
        val scroll = ScrollView(context).apply {
            addView(form)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (metrics.heightPixels * 0.5f).toInt(),
            )
        }
        card.addView(scroll)
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(12), 0, 0)
                addView(button("保存", onClick = { if (onSave()) dialog.dismiss() }))
                addView(button("取消", onClick = { dialog.dismiss() }))
            },
        )
        dialog.setContentView(card)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout((metrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    fun multiSelectDialog(
        title: String,
        hint: String,
        options: List<MultiSelectOption>,
        selected: Set<String>,
        refresh: (() -> List<MultiSelectOption>?)? = null,
        refreshLabel: String = "刷新清单",
        onConfirm: (Set<String>) -> Unit,
    ): Dialog {
        val dialog = Dialog(context)
        val metrics = context.resources.displayMetrics
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.rowBackground, 12f)
            setPadding(px(18), px(18), px(18), px(14))
        }
        card.addView(textView(title, 18f, palette.title, bold = true))
        if (hint.isNotBlank()) {
            card.addView(textView(hint, 12f, palette.body).apply { setPadding(0, px(6), 0, px(10)) })
        }
        val picked = selected.toMutableSet()
        var shown = options
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun renderRows() {
            list.removeAllViews()
            shown.forEach { option ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(px(12), px(10), px(12), px(10))
                }
                val name = textView(option.label, 14f, option.color ?: palette.title)
                name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val state = textView("", 12f, palette.body)
                row.addView(name)
                row.addView(state)

                fun apply() {
                    val locked = option.value in picked
                    state.text = if (locked) "已锁定" else "可典当"
                    state.setTextColor(if (locked) palette.primaryText else palette.body)
                    row.background = rounded(if (locked) palette.primarySoft else palette.pageBackground, 8f).apply {
                        setStroke((1.2f * dp).toInt(), palette.border)
                    }
                }
                apply()
                row.isClickable = true
                row.setOnClickListener {
                    if (!picked.add(option.value)) picked.remove(option.value)
                    apply()
                }
                list.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = px(6) })
            }
        }
        renderRows()
        if (refresh != null) {
            var refreshing = false
            val refreshButton = button(refreshLabel, role = Role.Neutral) {}
            refreshButton.setOnClickListener {
                if (refreshing) return@setOnClickListener
                refreshing = true
                refreshButton.text = "正在读取…"
                background(

                    work = { runCatching { refresh() } },
                    then = { result ->
                        refreshing = false
                        refreshButton.text = refreshLabel
                        val updated = result.getOrNull()
                        if (result.isFailure) {
                            toast(result.exceptionOrNull()?.message ?: "刷新清单失败")
                        } else if (updated == null) {
                            toast("没有读到清单，稍后再试")
                        } else {
                            shown = updated

                            renderRows()
                            toast("清单已更新，共 ${updated.size} 项")
                        }
                    },
                )
            }
            card.addView(refreshButton)
        }
        card.addView(
            ScrollView(context).apply {
                addView(list)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (metrics.heightPixels * 0.5f).toInt(),
                )
            },
        )
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(12), 0, 0)
                addView(button("完成", onClick = { onConfirm(picked.toSet()); dialog.dismiss() }))
                addView(button("取消", onClick = { dialog.dismiss() }))
            },
        )
        dialog.setContentView(card)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout((metrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    private fun fieldRow(label: String, hint: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(2), px(6), px(2), px(2))
        addView(textView(label, 13f, palette.title, bold = true))
        if (hint.isNotBlank()) addView(textView(hint, 11f, palette.body).apply { setPadding(0, px(2), 0, 0) })
    }

    private fun editText(value: String): android.widget.EditText =
        android.widget.EditText(context).apply {
            setText(value)
            textSize = 14f
            setTextColor(palette.title)
            background = rounded(palette.pageBackground, 8f).apply {
                setStroke((1.2f * dp).toInt(), palette.border)
            }
            setPadding(px(10), px(8), px(10), px(8))
            maxLines = 6
        }

    val isDarkPage: Boolean
        get() {
            val red = android.graphics.Color.red(palette.pageBackground) / 255.0
            val green = android.graphics.Color.green(palette.pageBackground) / 255.0
            val blue = android.graphics.Color.blue(palette.pageBackground) / 255.0
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 0.45
        }

    fun <T> background(work: () -> T, then: (T) -> Unit) {
        Thread {
            runCatching(work)
                .onSuccess { value -> onMain { then(value) } }
                .onFailure { error -> onMain { toast(error.message ?: error.javaClass.simpleName) } }
        }.apply { isDaemon = true }.start()
    }

    private fun onMain(block: () -> Unit) {
        val activity = context as? Activity
        if (activity != null) activity.runOnUiThread(block) else block()
    }

    private fun px(value: Int): Int = (value * dp).toInt()

    private companion object {
        const val BOTTOM_BAR_MARGIN_DP = 12
        const val CARD_CORNER_DP = 12f
    }
}
