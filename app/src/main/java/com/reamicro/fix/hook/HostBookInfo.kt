package com.reamicro.fix.hook

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout

/** The host's book information card: labelled facts that flow across lines, then a synopsis that unfolds on a tap. */
internal class HostBookInfo(
    private val kit: HostFormKit,
    facts: List<Pair<String, String>>,
    synopsis: String,
) : LinearLayout(kit.context) {
    private val story = kit.text(synopsis, "BodyMedium", colorWithAlpha(kit.colors.text, SYNOPSIS_ALPHA))
    private var unfolded = false

    init {
        orientation = VERTICAL
        background = kit.shape(kit.colors.card, 8f, kit.colors.control, .4f)
        setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(14f))
        if (facts.isNotEmpty()) {
            val flow = HostFlow(kit.context, kit.dp(12f))
            facts.forEach { (label, value) -> flow.addView(fact(label, value)) }
            addView(flow, kit.params())
        }
        if (synopsis.isNotBlank()) addView(story, kit.params(top = if (facts.isEmpty()) 0f else 8f))
        story.background = kit.pressable()
        story.setOnClickListener {
            unfolded = !unfolded
            fold()
        }
        story.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (!host.isClickable) return
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, if (unfolded) "收起简介" else "展开简介",
                ))
            }
        }
        fold()
    }

    /** Only a synopsis that is cut short, or has been unfolded, answers a tap. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val lines = story.layout ?: return
        val cut = lines.lineCount > 0 && lines.getEllipsisCount(lines.lineCount - 1) > 0
        story.isClickable = unfolded || cut
        story.isFocusable = story.isClickable
    }

    private fun fold() {
        story.maxLines = if (unfolded) Int.MAX_VALUE else SYNOPSIS_LINES
        story.ellipsize = if (unfolded) null else TextUtils.TruncateAt.END
    }

    /** A label in the host's fixed label column, its value on the same baseline. */
    private fun fact(label: String, value: String) = kit.row().apply {
        gravity = Gravity.TOP
        val name = kit.text(label, "BodyMedium", kit.colors.secondary)
        name.minWidth = kit.dp(LABEL_WIDTH)
        name.setPaddingRelative(0, 0, kit.dp(LABEL_GAP), 0)
        addView(name)
        addView(kit.text(value, "LabelMedium", kit.colors.text))
    }

    private companion object {
        const val SYNOPSIS_LINES = 3
        const val SYNOPSIS_ALPHA = 179
        const val LABEL_WIDTH = 32f
        /** Keeps a label that outgrows its column, at a large font size, apart from its value. */
        const val LABEL_GAP = 4f
    }
}

/** Lays its children out in reading order, [gap] apart, and starts a new line when the next one does not fit. */
internal class HostFlow(context: Context, private val gap: Int) : ViewGroup(context) {
    private val spots = ArrayList<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val limit = if (bounded) MeasureSpec.getSize(widthMeasureSpec) else Int.MAX_VALUE
        val childWidth = if (bounded) MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST) else widthMeasureSpec
        val childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = 0
        var y = 0
        var line = 0
        var widest = 0
        spots.clear()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(childWidth, childHeight)
            if (x > 0 && child.measuredWidth > limit - x) {
                x = 0
                y += line
                line = 0
            }
            spots += x
            spots += y
            widest = maxOf(widest, x + child.measuredWidth)
            x += child.measuredWidth + gap
            line = maxOf(line, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), resolveSize(y + line, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val mirrored = layoutDirection == LAYOUT_DIRECTION_RTL
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val x = spots[index * 2]
            val y = spots[index * 2 + 1]
            val start = if (mirrored) width - x - child.measuredWidth else x
            child.layout(start, y, start + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
