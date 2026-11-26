package com.reamicro.fix.hook

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

/** Host colour roles, paired the way the host pairs them in its settings cards and dialogs. */
internal data class HostFormColors(
    val dialog: Int,
    val card: Int,
    val surface: Int,
    val text: Int,
    val fieldText: Int,
    val secondary: Int,
    val hairline: Int,
    val primary: Int,
    val onPrimary: Int,
    val accentText: Int,
    val outline: Int,
    val placeholder: Int,
    val error: Int,
    val control: Int,
    /** Sunken fill: the track of a switch that is off, the search pill. */
    val recess: Int,
) {
    /** Text under a dialog's section label. */
    val lead: Int get() = colorWithAlpha(text, 184)
    /** Second line of a settings row. */
    val supporting: Int get() = colorWithAlpha(text, 168)
    val pressed: Int get() = colorWithAlpha(text, 31)
    val recordBorder: Int get() = colorWithAlpha(text, 26)
    val recordDivider: Int get() = colorWithAlpha(text, 20)

    /**
     * Keeps [color]'s hue and nudges it towards the body colour only as far as [minimum] contrast needs,
     * against [recess] as well when [sunken] text uses it.
     */
    fun readable(color: Int, minimum: Double = TEXT_CONTRAST, sunken: Boolean = false): Int {
        val grounds = listOfNotNull(dialog, card, surface, recess.takeIf { sunken })
        for (step in 0..BLEND_STEPS) {
            val candidate = ColorUtils.blendARGB(color, text, step / BLEND_STEPS.toFloat())
            if (grounds.all { contrast(candidate, it) >= minimum }) return candidate
        }
        return text
    }

    // onPrimary stays as the host pairs it with primary on its own selected options and buttons.
    private fun legible() = copy(
        secondary = readable(secondary, sunken = true),
        primary = readable(primary, MARK_CONTRAST),
        accentText = readable(accentText),
        placeholder = readable(placeholder),
        error = readable(error),
    )

    companion object {
        private const val TEXT_CONTRAST = 4.5
        /** Fills and outlines that mark a state only need to stand out from what surrounds them. */
        private const val MARK_CONTRAST = 3.0
        private const val BLEND_STEPS = 20

        private fun contrast(foreground: Int, background: Int): Double {
            val ground = ColorUtils.setAlphaComponent(background, 255)
            return ColorUtils.calculateContrast(ColorUtils.compositeColors(foreground, ground), ground)
        }

        fun of(context: Context): HostFormColors {
            val host = EmbeddedHostUi.snapshot(context) ?: return of(ModuleDialogTheme.palette(context))
            fun role(name: String, fallback: Int) = host.roles[name] ?: fallback
            val text = role("OnBackground", Color.BLACK)
            val primary = role("Primary", text)
            return HostFormColors(
                dialog = host.brightArgb,
                card = host.pageArgb,
                surface = role("Surface", host.brightArgb),
                text = text,
                fieldText = role("OnSurface", text),
                secondary = host.captionArgb,
                hairline = host.borderVariantArgb,
                primary = primary,
                onPrimary = role("OnPrimary", host.pageArgb),
                accentText = primary,
                outline = role("Outline", host.captionArgb),
                placeholder = role("OnSurfaceVariant", host.captionArgb),
                error = role("Error", text),
                control = role("SurfaceContainerHighest", host.borderArgb),
                recess = host.contentArgb,
            ).legible()
        }

        fun of(palette: ModuleDialogTheme.Palette) = HostFormColors(
            dialog = palette.rowBackground,
            card = palette.pageBackground,
            surface = palette.rowBackground,
            text = palette.title,
            fieldText = palette.title,
            secondary = palette.body,
            hairline = palette.border,
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            accentText = palette.primaryText,
            outline = palette.body,
            placeholder = palette.body,
            error = palette.destructiveText,
            control = palette.border,
            recess = palette.pageBackground,
        ).legible()
    }
}

/** Android View counterparts of the host's settings and dialog components, in host units. */
internal class HostFormKit(val context: Context, val colors: HostFormColors = HostFormColors.of(context)) {
    fun px(dp: Float): Float = EmbeddedHostUi.dp(context, dp)
    fun dp(dp: Float): Int = px(dp).roundToInt()
    fun stroke(dp: Float): Int = px(dp).roundToInt().coerceAtLeast(1)

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun params(top: Float = 0f, bottom: Float = 0f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    fun text(value: CharSequence, role: String, color: Int, weight: Int? = null) = TextView(context).apply {
        text = value
        style(this, role, weight)
        setTextColor(color)
    }

    /** Applies a host text role; [weight] overrides the role's weight the way the host does for section titles. */
    fun style(view: TextView, role: String, weight: Int? = null) {
        EmbeddedHostUi.text(view, role)
        view.includeFontPadding = false
        val wanted = weight ?: EmbeddedHostUi.typographyStyles[role]?.fontWeight ?: return
        val face = EmbeddedHostUi.uiTypeface(context) ?: view.typeface ?: return
        val heavy = wanted >= BOLD_WEIGHT
        // The host sets its heavy roles in its own bold cut instead of emboldening the regular one.
        val bold = if (heavy && face === EmbeddedHostUi.nativeTypeface(context)) {
            EmbeddedHostUi.nativeBoldTypeface(context)
        } else {
            null
        }
        view.typeface = when {
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(bold ?: face, wanted, false)
            bold != null -> bold
            else -> Typeface.create(face, if (heavy) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    fun shape(fill: Int, radius: Float, border: Int = Color.TRANSPARENT, width: Float = 0f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = px(radius)
        if (width > 0f) setStroke(stroke(width), border)
    }

    /** Press feedback bounded by [content], or by the view itself when there is no content. */
    fun pressable(content: Drawable? = null) = RippleDrawable(
        ColorStateList.valueOf(colors.pressed), content, if (content == null) ColorDrawable(Color.WHITE) else null,
    )

    fun glyph(glyph: HostGlyph, color: Int, size: Float) = ImageView(context).apply {
        setImageDrawable(HostGlyphDrawable(glyph, color))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun divider(color: Int = colors.hairline, start: Float = 16f, gap: Float = 0f) = View(context).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stroke(.8f)).apply {
            marginStart = dp(start)
            topMargin = dp(gap)
            bottomMargin = dp(gap)
        }
    }

    /** The host's empty state: one centred line in its caption colour. */
    fun empty(message: String) = text(message, "BodyLarge", colors.secondary).apply { gravity = Gravity.CENTER }

    /** Caret, selection and its handles in the host's primary colour. */
    fun caret(input: EditText) {
        input.highlightColor = colorWithAlpha(colors.primary, SELECTION_ALPHA)
        if (Build.VERSION.SDK_INT < 29) return
        input.textCursorDrawable?.mutate()?.setTint(colors.primary)
        input.textSelectHandle?.mutate()?.setTint(colors.primary)
        input.textSelectHandleLeft?.mutate()?.setTint(colors.primary)
        input.textSelectHandleRight?.mutate()?.setTint(colors.primary)
    }

    /** Host settings card: 12dp corners and a hairline border around rows split by inset dividers. */
    fun card() = column().apply {
        background = shape(colors.card, 12f, colors.hairline, .8f)
        clipToOutline = true
        val edge = stroke(.8f)
        setPadding(edge, edge, edge, edge)
    }

    /** Host option list: rows on the surface colour inside 8dp corners. */
    fun list() = column().apply {
        background = shape(colors.surface, 8f, colors.hairline, 1f)
        clipToOutline = true
        val edge = stroke(1f)
        setPadding(edge, edge, edge, edge)
    }

    /** Host outlined button: 40dp tall inside a 48dp touch target. */
    fun outlinedButton(label: String, onClick: () -> Unit) = text(label, "LabelLarge", colors.text).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48f)
        background = InsetDrawable(
            pressable(shape(colors.dialog, 8f, colors.hairline, .8f)), 0, dp(4f), 0, dp(4f),
        )
        // After the background: an inset drawable imposes its insets as the view's padding.
        setPadding(dp(16f), dp(4f), dp(16f), dp(4f))
        isFocusable = true
        setOnClickListener { onClick() }
        accessibilityDelegate = HostRole("android.widget.Button")
    }

    private companion object {
        const val BOLD_WEIGHT = 600
        const val SELECTION_ALPHA = 64
    }
}

/** Reports a plain view under the widget role it plays. */
internal class HostRole(private val className: String, private val checked: (() -> Boolean)? = null) :
    View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = className
        val state = checked ?: return
        info.isCheckable = true
        if (Build.VERSION.SDK_INT >= TRI_STATE_SDK) {
            info.setChecked(
                if (state()) AccessibilityNodeInfo.CHECKED_STATE_TRUE else AccessibilityNodeInfo.CHECKED_STATE_FALSE,
            )
        } else {
            @Suppress("DEPRECATION")
            info.isChecked = state()
        }
    }

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(host, event)
        event.className = className
        checked?.let { event.isChecked = it() }
    }

    private companion object {
        /** Android 16 replaced the boolean checked state with a tri-state one. */
        const val TRI_STATE_SDK = 36
    }
}

/** Outlines from the host's icon set, plus ringed status marks on the same 24-unit grid. */
internal enum class HostGlyph(val outline: String? = null) {
    Forward(
        "M10,19a1,1 0 0,1 -0.64,-0.23a1,1 0 0,1 -0.13,-1.41L13.71,12L9.39,6.63a1,1 0 0,1 0.15,-1.41" +
            "A1,1 0 0,1 11,5.37l4.83,6a1,1 0 0,1 0,1.27l-5,6A1,1 0 0,1 10,19Z",
    ),
    Down(
        "M12,16a1,1 0 0,1 -0.64,-0.23l-6,-5A1,1 0 1,1 6.64,9.23L12,13.71l5.36,-4.32a1,1 0 0,1 1.41,0.15" +
            "A1,1 0 0,1 18.63,11l-6,4.83A1,1 0 0,1 12,16Z",
    ),
    Check("M9,16.2L4.8,12l-1.4,1.4L9,19L21,7l-1.4,-1.4L9,16.2Z"),
    Search(
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5C16,5.91 13.09,3 9.5,3S3,5.91 3,9.5" +
            "S5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5Z" +
            "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5S14,7.01 14,9.5S11.99,14 9.5,14Z",
    ),
    Close("M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12Z"),
    Success,
    Failed,
    Paused,
    Skipped,
}

internal class HostGlyphDrawable(private val glyph: HostGlyph, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path: Path = glyph.outline?.let(PathParser::createPathFromPathData) ?: Path().apply {
        addCircle(12f, 12f, 9f, Path.Direction.CW)
        when (glyph) {
            HostGlyph.Success -> line(7.8f, 12.3f, 10.7f, 15.1f, 16.2f, 9.3f)
            HostGlyph.Failed -> {
                line(12f, 7.6f, 12f, 12.8f)
                line(12f, 16.2f, 12f, 16.3f)
            }
            HostGlyph.Paused -> line(12f, 7.6f, 12f, 12f, 15f, 13.8f)
            else -> line(8f, 12f, 16f, 12f)
        }
    }
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val unit = minOf(bounds.width(), bounds.height()) / GRID
        paint.style = if (glyph.outline == null) Paint.Style.STROKE else Paint.Style.FILL
        paint.color = color
        paint.alpha = Color.alpha(color) * drawableAlpha / 255
        canvas.save()
        canvas.translate(bounds.exactCenterX() - GRID * unit / 2f, bounds.exactCenterY() - GRID * unit / 2f)
        canvas.scale(unit, unit)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val GRID = 24f

        fun Path.line(vararg points: Float) {
            moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
        }
    }
}

/**
 * The host's settings switch: a Material switch drawn at the host's 0.62 scale inside its
 * unscaled 52dp slot, with the host's own off-state colours.
 */
internal class HostSwitchView(private val kit: HostFormKit) : View(kit.context) {
    private val unit = kit.px(SCALE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = 0f
    private var animator: ValueAnimator? = null
    var checked = false
        private set

    fun setChecked(value: Boolean, animate: Boolean) {
        checked = value
        animator?.cancel()
        animator = null
        val target = if (value) 1f else 0f
        if (!animate || !isAttachedToWindow || !isLaidOut || position == target) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = TRAVEL_MILLIS
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(kit.dp(TRACK_WIDTH), kit.dp(TRACK_HEIGHT))

    override fun onDraw(canvas: Canvas) {
        val trackWidth = TRACK_WIDTH * unit
        val trackHeight = TRACK_HEIGHT * unit
        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        val colors = kit.colors
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(colors.recess, colors.primary, position)
        canvas.drawRoundRect(left, top, left + trackWidth, top + trackHeight, trackHeight / 2f, trackHeight / 2f, paint)
        val border = BORDER * unit
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border
        paint.color = colors.control
        paint.alpha = (Color.alpha(colors.control) * (1f - position)).roundToInt()
        val radius = (trackHeight - border) / 2f
        canvas.drawRoundRect(
            left + border / 2f, top + border / 2f, left + trackWidth - border / 2f, top + trackHeight - border / 2f,
            radius, radius, paint,
        )
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(colors.control, colors.onPrimary, position)
        val thumb = (THUMB_OFF + (THUMB_ON - THUMB_OFF) * position) * unit / 2f
        val travel = (CENTRE_OFF + (CENTRE_ON - CENTRE_OFF) * position) * unit
        val centre = if (layoutDirection == LAYOUT_DIRECTION_RTL) left + trackWidth - travel else left + travel
        canvas.drawCircle(centre, top + trackHeight / 2f, thumb, paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        position = if (checked) 1f else 0f
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SCALE = .62f
        const val TRACK_WIDTH = 52f
        const val TRACK_HEIGHT = 32f
        const val BORDER = 2f
        const val THUMB_OFF = 16f
        const val THUMB_ON = 24f
        const val CENTRE_OFF = 16f
        const val CENTRE_ON = 36f
        const val TRAVEL_MILLIS = 100L
    }
}
