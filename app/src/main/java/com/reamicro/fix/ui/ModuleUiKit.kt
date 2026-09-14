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

    /** 卡片容器；卡片内部再放若干行。 */
    fun card(rows: List<View>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.rowBackground, 12f)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(4), px(4), px(4), px(4))
            rows.forEach { addView(it) }
        })
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
                actions.forEach { (label, onClick) -> addView(button(label, onClick = onClick)) }
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
    fun button(label: String, textColor: Int = palette.primaryText, onClick: () -> Unit): TextView =
        textView(label, 14f, textColor).apply {
            gravity = Gravity.CENTER
            background = rounded(palette.pageBackground, 8f).apply {
                setStroke((1.2f * dp).toInt(), palette.border)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                px(38),
            ).apply {
                rightMargin = px(8)
                left = px(2)
            }
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
}
