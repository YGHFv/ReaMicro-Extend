package com.reamicro.fix.hook

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Material outlined text field as the host's dialogs show it, with the label resting on the border. */
internal class HostOutlinedField(
    private val kit: HostFormKit,
    label: String,
    value: String,
    placeholder: String,
    private val helper: String,
    numeric: Boolean,
    multiline: Boolean,
) : LinearLayout(kit.context) {
    private val colors = kit.colors
    private val caption = kit.text(label, "BodySmall", colors.placeholder)
    private val support = kit.text(helper, "BodyMedium", colors.secondary).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(kit.dp(16f), 0, kit.dp(16f), 0)
    }
    private val box = Box()
    private var failure: String? = null

    val input = EditText(context).apply {
        id = View.generateViewId()
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        setSingleLine(!multiline)
        if (multiline) minLines = MULTILINE_ROWS
        gravity = (if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL) or Gravity.START
        kit.style(this, "BodyLarge")
        background = null
        setText(value)
        hint = placeholder
        setTextColor(colors.fieldText)
        setHintTextColor(colors.placeholder)
        minHeight = kit.dp(56f)
        setPadding(kit.dp(16f), kit.dp(16f), kit.dp(16f), kit.dp(16f))
        kit.caret(this)
        setOnFocusChangeListener { _, _ -> refresh() }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) = clear()
        })
    }

    init {
        orientation = VERTICAL
        caption.labelFor = input.id
        caption.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        box.addView(input, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = caption.measuredHeight / 2 })
        box.addView(caption, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { marginStart = kit.dp(16f) })
        addView(box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(support, kit.params(top = 4f))
        refresh()
    }

    /** Marks the field invalid and replaces its supporting text with [message]. */
    fun fail(message: String) {
        failure = message
        refresh()
    }

    fun clear() {
        if (failure == null) return
        failure = null
        refresh()
    }

    private fun refresh() {
        val failed = failure != null
        caption.setTextColor(when {
            failed -> colors.error
            input.isFocused -> colors.accentText
            else -> colors.placeholder
        })
        support.text = failure ?: helper
        support.setTextColor(if (failed) colors.error else colors.secondary)
        support.visibility = if (support.text.isNullOrBlank()) View.GONE else View.VISIBLE
        box.invalidate()
    }

    /** Draws the outline behind the input and leaves it open where the label sits. */
    private inner class Box : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val radius = kit.px(4f)
        private val gap = kit.dp(4f)

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            val focused = input.isFocused
            val width = kit.px(if (focused) 2f else 1f).coerceAtLeast(1f)
            paint.strokeWidth = width
            paint.color = when {
                failure != null -> colors.error
                focused -> colors.primary
                else -> colors.outline
            }
            val half = width / 2f
            canvas.save()
            canvas.clipOutRect(caption.left - gap, caption.top, caption.right + gap, caption.bottom)
            canvas.drawRoundRect(
                half, input.top + half, this.width - half, input.bottom - half, radius, radius, paint,
            )
            canvas.restore()
        }
    }

    private companion object {
        const val MULTILINE_ROWS = 3
    }
}

/** Host option blocks: equal shares of one row, stacked when a label would not fit its share. */
internal class HostChoiceRow(private val kit: HostFormKit) : ViewGroup(kit.context) {
    private val gap = kit.dp(10f)
    private val touchInset = kit.dp(TOUCH_INSET)
    private var stacked = false

    /** Adds an option; [onPick] runs when the user moves the selection onto it. */
    fun add(label: String, selected: Boolean, onPick: () -> Unit) {
        val colors = kit.colors
        val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
        val block = kit.text(label, "LabelMedium", colors.text).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = kit.dp(48f)
            setTextColor(ColorStateList(states, intArrayOf(colors.onPrimary, colors.text)))
            val fill = GradientDrawable().apply {
                cornerRadius = kit.px(8f)
                color = ColorStateList(states, intArrayOf(colors.primary, colors.surface))
                setStroke(kit.stroke(1f), colors.hairline)
            }
            background = InsetDrawable(kit.pressable(fill), 0, touchInset, 0, touchInset)
            // After the background: an inset drawable imposes its insets as the view's padding.
            val vertical = kit.dp(8f) + touchInset
            setPadding(kit.dp(SIDE), vertical, kit.dp(SIDE), vertical)
            isFocusable = true
            isSelected = selected
            accessibilityDelegate = HostRole("android.widget.RadioButton") { isSelected }
        }
        block.setOnClickListener {
            if (block.isSelected) return@setOnClickListener
            for (index in 0 until childCount) getChildAt(index).isSelected = false
            block.isSelected = true
            onPick()
        }
        addView(block)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val count = childCount
        var widest = 0
        for (index in 0 until count) {
            val child = getChildAt(index)
            child.measure(free, free)
            widest = maxOf(widest, child.measuredWidth)
        }
        val gaps = gap * (count - 1).coerceAtLeast(0)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            widest * count + gaps
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val share = if (count == 0) 0 else ((width - gaps) / count).coerceAtLeast(0)
        stacked = count > 1 && widest > share
        var height = 0
        if (stacked) {
            val full = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            for (index in 0 until count) {
                val child = getChildAt(index)
                child.measure(full, free)
                height += child.measuredHeight
            }
        } else {
            val exact = MeasureSpec.makeMeasureSpec(share, MeasureSpec.EXACTLY)
            for (index in 0 until count) {
                val child = getChildAt(index)
                child.measure(exact, free)
                height = maxOf(height, child.measuredHeight)
            }
            val tall = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            for (index in 0 until count) getChildAt(index).measure(exact, tall)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val mirrored = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var offset = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (stacked) {
                child.layout(0, offset, child.measuredWidth, offset + child.measuredHeight)
                offset += child.measuredHeight
            } else {
                val start = if (mirrored) width - offset - child.measuredWidth else offset
                child.layout(start, 0, start + child.measuredWidth, child.measuredHeight)
                offset += child.measuredWidth + gap
            }
        }
    }

    companion object {
        /** Transparent band above and below each 34dp block that completes its 48dp touch target. */
        const val TOUCH_INSET = 7f
        private const val SIDE = 12f
    }
}

/** Host switch row: 56dp alone, 76dp with a second line; the whole row toggles. */
internal class HostSwitchRow(
    kit: HostFormKit,
    title: String,
    subtitle: String,
    checked: Boolean,
) : LinearLayout(kit.context) {
    private val control = HostSwitchView(kit)
    private var listener: ((Boolean) -> Unit)? = null

    var isChecked: Boolean
        get() = control.checked
        set(value) {
            if (value == control.checked) return
            control.setChecked(value, animate = true)
            listener?.invoke(value)
        }

    fun setOnCheckedChangeListener(listener: (Boolean) -> Unit) {
        this.listener = listener
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val detailed = subtitle.isNotBlank()
        minimumHeight = kit.dp(if (detailed) 76f else 56f)
        val vertical = kit.dp(if (detailed) 12f else 8f)
        setPadding(kit.dp(16f), vertical, kit.dp(16f), vertical)
        val texts = kit.column()
        texts.addView(kit.text(title, "LabelLarge", kit.colors.text))
        if (detailed) texts.addView(kit.text(subtitle, "BodyMedium", kit.colors.supporting))
        addView(texts, kit.weighted())
        control.setChecked(checked, animate = false)
        addView(control, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = kit.dp(16f) })
        background = kit.pressable()
        isFocusable = true
        setOnClickListener { isChecked = !isChecked }
        accessibilityDelegate = HostRole("android.widget.Switch") { control.checked }
    }
}

/** Host settings entry row: title, optional second line, optional current value and the host's trailing arrow. */
internal class HostEntryRow(
    private val kit: HostFormKit,
    title: String,
    summary: String,
    glyph: HostGlyph = HostGlyph.Forward,
    value: String? = null,
) : LinearLayout(kit.context) {
    val summaryView: TextView = kit.text(summary, "BodyMedium", kit.colors.supporting)
    /** What the entry is set to, beside the arrow as the host's selector rows show it. */
    val valueView: TextView = kit.text(value.orEmpty(), "BodyLarge", kit.colors.fieldText)
    private val titleView = kit.text(title, "LabelLarge", kit.colors.text)
    private val arrow = kit.glyph(glyph, kit.colors.control, ARROW_SIZE)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val detailed = summary.isNotBlank()
        minimumHeight = kit.dp(if (detailed) 76f else 56f)
        val vertical = kit.dp(if (detailed) 12f else 8f)
        setPadding(kit.dp(SIDE), vertical, kit.dp(SIDE), vertical)
        val texts = kit.column()
        texts.addView(titleView)
        summaryView.visibility = if (detailed) View.VISIBLE else View.GONE
        texts.addView(summaryView)
        addView(texts, kit.weighted())
        valueView.visibility = if (value == null) View.GONE else View.VISIBLE
        valueView.gravity = Gravity.END
        valueView.maxLines = VALUE_LINES
        valueView.ellipsize = TextUtils.TruncateAt.END
        addView(valueView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = kit.dp(TEXT_GAP) })
        addView(arrow, LayoutParams(kit.dp(ARROW_SIZE), kit.dp(ARROW_SIZE)).apply {
            marginStart = kit.dp(if (value == null) TEXT_GAP else VALUE_GAP)
            marginEnd = kit.dp(ARROW_END)
        })
        background = kit.pressable()
        isFocusable = true
        accessibilityDelegate = HostRole("android.widget.Button")
    }

    fun setGlyph(glyph: HostGlyph) = arrow.setImageDrawable(HostGlyphDrawable(glyph, kit.colors.control))

    /** A short title leaves its room to the value; a long one shares the row evenly with it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (valueView.visibility != View.GONE && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val taken = paddingLeft + paddingRight + kit.dp(TEXT_GAP + VALUE_GAP + ARROW_SIZE + ARROW_END)
            val room = (MeasureSpec.getSize(widthMeasureSpec) - taken).coerceAtLeast(0)
            val wanted = listOf(titleView, summaryView).filter { it.visibility != View.GONE }
                .maxOf { ceil(Layout.getDesiredWidth(it.text, it.paint)).toInt() }
            val limit = maxOf(room / 2, room - wanted)
            if (valueView.maxWidth != limit) valueView.maxWidth = limit
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val SIDE = 16f
        const val TEXT_GAP = 16f
        const val VALUE_GAP = 6f
        const val VALUE_LINES = 2
        const val ARROW_SIZE = 20f
        const val ARROW_END = 4f
    }
}

/** The search pill the host keeps over its contents list: an icon, the query and a way to clear it. */
internal class HostSearchField(private val kit: HostFormKit, hint: String) : LinearLayout(kit.context) {
    private val input = EditText(kit.context)
    private var query = ""
    private var listener: (String) -> Unit = {}

    /** Called with the trimmed query each time it changes. */
    fun onQuery(listener: (String) -> Unit) {
        this.listener = listener
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(TOUCH)
        val inset = kit.dp((TOUCH - HEIGHT) / 2f)
        background = InsetDrawable(kit.shape(kit.colors.recess, HEIGHT / 2f), 0, inset, 0, inset)
        // After the background: an inset drawable imposes its insets as the view's padding.
        setPaddingRelative(kit.dp(START), 0, kit.dp(END), 0)
        addView(kit.glyph(HostGlyph.Search, kit.colors.secondary, ICON))
        val placeholder = kit.text(hint, "BodyMedium", kit.colors.secondary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPaddingRelative(kit.dp(GAP), 0, kit.dp(GAP), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val clear = clearButton(inset)
        input.apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            kit.style(this, "LabelLarge")
            background = null
            setTextColor(kit.colors.text)
            setPaddingRelative(kit.dp(GAP), 0, kit.dp(GAP), 0)
            kit.caret(this)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = hint
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    val blank = text.isNullOrEmpty()
                    placeholder.visibility = if (blank) View.VISIBLE else View.GONE
                    clear.visibility = if (blank) View.GONE else View.VISIBLE
                    val keyword = text?.toString().orEmpty().trim()
                    if (keyword == query) return
                    query = keyword
                    listener(keyword)
                }
            })
        }
        val entry = FrameLayout(kit.context)
        entry.addView(placeholder, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
        ))
        entry.addView(input, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        addView(entry, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(clear, LayoutParams(kit.dp(TOUCH), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun clearButton(inset: Int) = kit.glyph(HostGlyph.Close, kit.colors.secondary, ICON).apply {
        background = RippleDrawable(
            ColorStateList.valueOf(kit.colors.pressed), null,
            InsetDrawable(kit.shape(Color.WHITE, HEIGHT / 2f), 0, inset, 0, inset),
        )
        // After the background, as above.
        val edge = kit.dp((TOUCH - ICON) / 2f)
        setPadding(edge, edge, edge, edge)
        visibility = View.GONE
        contentDescription = CLEAR
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        accessibilityDelegate = HostRole("android.widget.Button")
        setOnClickListener { input.setText("") }
    }

    private companion object {
        const val TOUCH = 48f
        const val HEIGHT = 40f
        const val START = 14f
        const val END = 2f
        const val ICON = 22f
        const val GAP = 12f
        const val CLEAR = "清除筛选"
    }
}
