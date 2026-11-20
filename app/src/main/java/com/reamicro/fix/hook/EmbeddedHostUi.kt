package com.reamicro.fix.hook

import com.reamicro.fix.core.HostTypographyMetrics
import com.reamicro.fix.core.HostPackages

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.widget.EditText
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.reamicro.fix.reload.ModuleResources
import java.util.WeakHashMap

internal object EmbeddedHostUi {
    const val FONT_ROOT = "composeResources/reamicro.composeapp.generated.resources/font/"
    data class TextMetrics(val bodyLarge: Float=16f,val bodySmall: Float=12f,val labelLarge: Float=14f,val titleSmall: Float=16f,val bodyLargePx:Float=0f,val labelLargePx:Float=0f,val bodyLinePx:Float=0f,val labelLinePx:Float=0f,val labelWeight:Int=500)
    var textMetrics by mutableStateOf(TextMetrics())
        private set
    var typographyStyles by mutableStateOf<Map<String, HostTypographyMetrics.Style>>(emptyMap())
        private set
    private var lastTypography: Any?=null
    private var lastDensity: Pair<Float,Float>?=null
    private val fontCache=WeakHashMap<ClassLoader,MutableMap<Boolean,Typeface>>()
    @Volatile private var uiFontResolver:(()->Typeface?)?=null
    @Volatile private var uiFontFamilyResolver:((Context)->FontFamily)?=null
    @Volatile private var materialFontFamilyResolver:((Context, FontFamily)->FontFamily)?=null
    private var typographyWarningReported = false

    init {
        ModuleResources.cleanup {
            uiFontResolver = null
            uiFontFamilyResolver = null
            materialFontFamilyResolver = null
            synchronized(fontCache) { fontCache.clear() }
            typographyStyles = emptyMap()
            textMetrics = TextMetrics()
            lastTypography = null
            lastDensity = null
            typographyWarningReported = false
        }
    }
    fun bindUiFontResolver(resolver:()->Typeface?){uiFontResolver=resolver}
    fun bindUiFontFamilyResolver(resolver:(Context)->FontFamily){uiFontFamilyResolver=resolver}
    fun bindMaterialFontFamilyResolver(resolver:(Context,FontFamily)->FontFamily){materialFontFamilyResolver=resolver}
    fun materialFontFamily(context:Context,nativeFamily:FontFamily):FontFamily {
        val host=hostContext(context) ?: return nativeFamily
        return materialFontFamilyResolver?.invoke(host,nativeFamily) ?: nativeFamily
    }
    fun uiFontFamily(context:Context):FontFamily {
        val host=hostContext(context) ?: return FontFamily.Default
        return uiFontFamilyResolver?.invoke(host) ?: HostEmbeddedStyle.fonts(host).serif
    }
    fun uiTypeface(context:Context):Typeface? = if(hostContext(context)==null)null else
        uiFontResolver?.invoke() ?: nativeTypeface(context)
    fun hostContext(context: Context): Context? {
        var current=context
        repeat(16) {
            if(current is Activity) return current.takeIf { HostPackages.contains(it.packageName) }
            val base=(current as? ContextWrapper)?.baseContext
            if(base==null || base===current) return current.takeIf { HostPackages.contains(it.packageName) }
            current=base
        }
        return null
    }
    fun snapshot(context: Context): HostThemeColors.Snapshot? {
        val host=hostContext(context) ?: return null
        return HostThemeColors.snapshot(host,HostEmbeddedStyle.isDark(host))
    }
    fun scale(context: Context): Float = hostContext(context)?.let { StructureBookDetailDividerStyle.scale(it.classLoader) } ?: 1f
    fun dp(context: Context, value: Float): Float = value * scale(context) * context.resources.displayMetrics.density
    fun nativeTypeface(context: Context): Typeface? = nativeFace(context,false)
    fun nativeBoldTypeface(context: Context): Typeface? = nativeFace(context,true)
    private fun nativeFace(context: Context, bold:Boolean):Typeface? {
        val host=hostContext(context) ?: return null
        synchronized(fontCache) {
            val faces=fontCache.getOrPut(host.classLoader){mutableMapOf()}
            faces[bold]?.let {return it}
            val name=if(bold) "serif_bold.ttf" else "serif_medium.ttf"
            return runCatching {Typeface.createFromAsset(host.assets,FONT_ROOT+name)}.getOrNull()?.also {faces[bold]=it}
        }
    }

    fun captureTypography(composer: Any?) {
        if (composer == null) return
        runCatching {
            val metrics = HostTypographyMetrics(composer)
            if (metrics.typography === lastTypography && metrics.densityKey == lastDensity) return
            val styles = listOf(
                "DisplayLarge", "DisplayMedium", "DisplaySmall", "HeadlineLarge", "HeadlineMedium", "HeadlineSmall",
                "TitleLarge", "TitleMedium", "TitleSmall", "BodyLarge", "BodyMedium", "BodySmall",
                "LabelLarge", "LabelMedium", "LabelSmall",
            ).associateWith(metrics::style)
            val body = styles.getValue("BodyLarge")
            val small = styles.getValue("BodySmall")
            val label = styles.getValue("LabelLarge")
            val title = styles.getValue("TitleSmall")
            typographyStyles = styles
            fun size(value: HostTypographyMetrics.UnitValue?, fallback: Float) =
                value?.value?.takeIf { !value.em && it in 8f..64f } ?: fallback
            textMetrics = TextMetrics(size(body.fontSize, 16f), size(small.fontSize, 12f),
                size(label.fontSize, 14f), size(title.fontSize, 16f),
                body.fontSizePx, label.fontSizePx, body.lineHeightPx, label.lineHeightPx, label.fontWeight ?: 500)
            lastTypography = metrics.typography
            lastDensity = metrics.densityKey
            typographyWarningReported = false
        }.onFailure {
            if (!typographyWarningReported) {
                typographyWarningReported = true
                com.reamicro.fix.xposed.HookRuntime.logError("ReaMicro embedded typography unavailable", it)
            }
        }
    }

    /** Only primitive text metrics cross the host/module Compose ClassLoader boundary. */
    fun textStyle(role: String, fallback: TextStyle): TextStyle {
        val native = typographyStyles[role] ?: return fallback
        fun HostTypographyMetrics.UnitValue.local(): TextUnit = if (em) value.em else value.sp
        return fallback.copy(
            fontSize = native.fontSize?.local() ?: fallback.fontSize,
            lineHeight = native.lineHeight?.local() ?: fallback.lineHeight,
            letterSpacing = native.letterSpacing?.local() ?: fallback.letterSpacing,
            fontWeight = native.fontWeight?.let(::FontWeight) ?: fallback.fontWeight,
        )
    }
    fun text(view: TextView, role: String) {
        val context = view.context
        if (hostContext(context) == null) return
        val style = typographyStyles[role]
        val fallback = when (role) {
            "TitleLarge" -> 22f
            "TitleSmall" -> textMetrics.titleSmall
            "LabelLarge" -> textMetrics.labelLarge
            "BodySmall" -> textMetrics.bodySmall
            "BodyMedium" -> 14f
            else -> textMetrics.bodyLarge
        }
        if (style != null && style.fontSizePx > 0f) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontSizePx)
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fallback)
        }
        view.includeFontPadding = false
        uiTypeface(context)?.let { face ->
            val weight = style?.fontWeight ?: if (role.startsWith("Title") || role.startsWith("Label")) 500 else 400
            view.typeface = if (Build.VERSION.SDK_INT >= 28) {
                Typeface.create(face, weight, false)
            } else {
                Typeface.create(face, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        style?.letterSpacing?.let { spacing ->
            view.letterSpacing = if (spacing.em) spacing.value else {
                val size = style.fontSize?.takeIf { !it.em }?.value
                if (size != null && size > 0f) spacing.value / size else 0f
            }
        }
        style?.lineHeightPx?.takeIf { it > 0f }?.let { line ->
            view.setLineSpacing(line - view.paint.fontSpacing, 1f)
        }
    }
    fun thoughtInput(input: EditText) {
        val context=input.context;val native=snapshot(context) ?: return
        text(input, "BodyLarge")
        input.backgroundTintList=null
        input.setTextColor(native.roles.getValue("OnBackground"))
        input.setHintTextColor(native.captionArgb)
        val inset=dp(context,16f).toInt()
        input.setPadding(inset,inset,inset,inset)
        input.minHeight=dp(context,56f).toInt()
        input.background=HostSurfaceDrawable(native.brightArgb,native.borderVariantArgb,dp(context,16f),context.resources.displayMetrics.density)
        val primary=native.roles.getValue("Primary")
        input.highlightColor=(primary and 0x00ffffff) or (0x40 shl 24)
        if(android.os.Build.VERSION.SDK_INT>=29){
            input.textCursorDrawable?.mutate()?.setTint(primary)
            input.textSelectHandle?.mutate()?.setTint(primary)
            input.textSelectHandleLeft?.mutate()?.setTint(primary)
            input.textSelectHandleRight?.mutate()?.setTint(primary)
        }
    }
}

internal class HostSurfaceDrawable(private val fill:Int,private val border:Int,private val radius:Float,private val stroke:Float):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha=255
    override fun draw(canvas:Canvas) {
        val r=RectF(bounds);paint.style=Paint.Style.FILL;paint.color=fill;paint.alpha=android.graphics.Color.alpha(fill)*drawableAlpha/255
        canvas.drawRoundRect(r,radius,radius,paint)
        if(stroke>0f){r.inset(stroke/2,stroke/2);paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.color=border;paint.alpha=android.graphics.Color.alpha(border)*drawableAlpha/255;canvas.drawRoundRect(r,radius,radius,paint)}
    }
    override fun setAlpha(alpha:Int){drawableAlpha=alpha.coerceIn(0,255);invalidateSelf()}
    override fun getOutline(outline:android.graphics.Outline){outline.setRoundRect(bounds,radius);outline.alpha=drawableAlpha/255f}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Drawable API") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
}

internal object EmbeddedHostMetrics {
    const val SEARCH_HEIGHT=38f
    const val SEARCH_BORDER=.4f
    const val THOUGHT_INSET=16f
    const val THOUGHT_CORNER=16f
    const val RESULT_CORNER=8f
    const val RESULT_INSET=16f
    const val RESULT_BORDER=.5f
    const val COVER_WIDTH=64
    const val COVER_HEIGHT=90
    const val COVER_HEIGHT_RATIO=1.41f
}
