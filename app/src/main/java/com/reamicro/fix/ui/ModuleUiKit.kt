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

/**
 * 模块主界面的轻量视图工具。
 *
 * 为什么不复用设置页那套 `settingsDialog*`：它们是 `ReaMicroSettingsHook` 的成员扩展函数，
 * 必须绑定宿主（阅微）的 Activity 实例——而这里是模块自己的进程、自己的 Activity。所以只共享
 * 真正独立可用的部分（配色来自 [ModuleDialogTheme.palette]），视图构建在这里重写一套等价实现，
 * 保持与设置页一致的观感。
 */
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
            // targetSdk 35 起系统强制 edge-to-edge，内容会画到状态栏/导航栏底下。
            // 之前没避让，标题被状态栏压掉一截。这里把系统栏高度加成内边距。
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

    /**
     * 卡片容器；卡片内部再放若干行。
     *
     * 卡片自己带下外边距与左右边距：此前没有外边距，多张卡在页面上直接贴在一起（实机上看起来
     * 就是"卡片互相堆叠重合"）。间距统一收在这里，调用方不用每处都写 layoutParams。
     */
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

    /**
     * 一行：标题 + 副标题，右侧可选按钮。
     *
     * 副标题承载"当前状态/为什么"——自检项光显示一行状态用户不知道该怎么办。
     */
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

    /**
     * 操作按钮。
     *
     * 填充用**页面底色**而不是卡片底色：`primarySoft` 在深色配色里就等于卡片底色，按钮会和卡片
     * 糊在一起，完全看不出是个可点的控件（实机见过）。页面底色与卡片底色在两种配色下都不同，
     * 再加一圈描边，按钮在任何配色下都能看出来。
     */
    /** 按钮配色角色。尺寸与间距对所有角色一致，只有文字颜色不同。 */
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
            // 文字左右必须留内边距：只给固定宽高的话，"立即执行"这种四字标签会顶到边框上，
            // 一排按钮的宽度还各不相同，看起来就"丑且不统一"。
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

    /**
     * 按按钮文案推断配色角色。
     *
     * 调用方只给「文案 to 回调」，不必每处都标角色；危险操作（清空/停用/取消）统一走红色，
     * 这样同一个动作在哪个页面都是同一个颜色。
     */
    private fun buttonRoleOf(label: String): Role = when (label) {
        "清空", "停用", "取消" -> Role.Danger
        "关闭", "刷新", "重排闹钟", "重算下次时刻" -> Role.Neutral
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

    fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * dp
        setColor(color)
    }

    /**
     * 详情弹窗：标题 + 可滚动正文。
     *
     * 正文用等宽感的分行文本即可——记录与日志都是「一行一条」，不需要列表控件。
     * 正文高度按屏幕比例给定值：用 weight 填充的话，外层 card 是 wrap_content，
     * 高度为 0 的子项会被压成不可见。
     */
    fun contentDialog(title: String, content: String, actions: List<Pair<String, () -> Unit>>): Dialog {
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
                        textSize = 12f
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

    /**
     * 页面骨架：可滚动内容 + 底部页签栏。
     *
     * 底栏固定在窗口底部，内容区自己滚动——页签切换就是换内容，不重建底栏，
     * 所以切页不会闪。
     */
    fun scaffold(content: View, tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): View {
        val bar = bottomBar(tabs, selectedIndex, onSelect)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.pageBackground)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
            // 内容区的顶部避让由 page() 自己处理；底栏要单独避让手势导航条，否则会被压在下面点不到。
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

    /**
     * 一条「通知样式」的列表项：标题 / 正文 / 时间，整块可点。
     *
     * 任务记录用它而不是普通行，是为了和系统通知的长相接近——用户已经在通知栏见过这些内容，
     * 样式一致时更容易对上"哪条通知对应哪次执行"。
     */
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
        ).apply {
            leftMargin = px(16)
            rightMargin = px(16)
            bottomMargin = px(8)
        }
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

    /**
     * 编辑弹窗：一行一个输入框（标签 + 提示 + 初值）。
     *
     * [register] 把每个输入框按标签交回调用方读取——标签是唯一键，调用方按同一套标签取值，
     * 不用维护两份下标。
     */
    fun editDialog(
        title: String,
        build: (add: (label: String, hint: String, value: String) -> Unit) -> Unit,
        register: (label: String, edit: android.widget.EditText) -> Unit,
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
        // 注意：form 只能挂到一个父容器上（下面挂进 ScrollView），
        // 先 card.addView(form) 再 scroll.addView(form) 会直接抛
        // "The specified child already has a parent" 崩掉。
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        build { label, hint, value ->
            form.addView(fieldRow(label, hint))
            val edit = editText(value)
            form.addView(edit)
            register(label, edit)
        }
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

    /** 当前配色是不是深色底。用于把状态栏图标调成相反色，否则深底上的黑图标看不见。 */
    val isDarkPage: Boolean
        get() {
            val red = android.graphics.Color.red(palette.pageBackground) / 255.0
            val green = android.graphics.Color.green(palette.pageBackground) / 255.0
            val blue = android.graphics.Color.blue(palette.pageBackground) / 255.0
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 0.45
        }

    /**
     * 在后台线程跑一段事，回到主线程更新。
     *
     * root 探测（要起 su 进程）与任务执行（要走网络）都不能卡 UI，所以统一走这里。
     * 结果是任意类型，调用方直接拿到对象——不要为了传值把多个字段拼成字符串再拆开，
     * 那样两端的字段顺序/数量一旦不一致就会渲染出互相矛盾的内容。
     */
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
    }
}
