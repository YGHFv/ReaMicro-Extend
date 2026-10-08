package com.reamicro.fix.hook

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
import android.widget.EditText
import java.util.WeakHashMap

internal object EmbeddedHostUi {
    const val PACKAGE = "app.zhendong.reamicro"
    const val FONT_ROOT = "composeResources/reamicro.composeapp.generated.resources/font/"
    data class TextMetrics(val bodyLarge: Float=16f,val bodySmall: Float=12f,val labelLarge: Float=14f,val titleSmall: Float=16f,val bodyLargePx:Float=0f,val labelLargePx:Float=0f,val bodyLinePx:Float=0f,val labelLinePx:Float=0f,val labelWeight:Int=500)
    @Volatile var textMetrics=TextMetrics()
        private set
    private var lastTypography: Any?=null
    private var lastDensity: Pair<Float,Float>?=null
    private val fontCache=WeakHashMap<ClassLoader,MutableMap<Boolean,Typeface>>()
    @Volatile private var uiFontResolver:(()->Typeface?)?=null
    fun bindUiFontResolver(resolver:()->Typeface?){uiFontResolver=resolver}
    fun uiTypeface(context:Context):Typeface? = if(hostContext(context)==null)null else
        uiFontResolver?.invoke() ?: nativeTypeface(context)
    fun hostContext(context: Context): Context? {
        var current=context
        repeat(16) {
            if(current is Activity) return current.takeIf { it.packageName==PACKAGE }
            val base=(current as? ContextWrapper)?.baseContext
            if(base==null || base===current) return current.takeIf { it.packageName==PACKAGE }
            current=base
        }
        return null
    }
    fun snapshot(context: Context): StructureHost232Colors.Snapshot? {
        val host=hostContext(context) ?: return null
        return StructureHost232Colors.snapshot(host,StructureHome130Style.isDark(host))
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

    fun captureTypography(loader: ClassLoader, composer: Any?) {
        if(composer==null)return
        runCatching {
            val composerType=loader.loadClass("androidx.compose.runtime.Composer")
            if(!composerType.isInstance(composer))return
            val material=loader.loadClass("androidx.compose.material3.MaterialTheme")
            val typography=material.getMethod("getTypography",composerType,Int::class.javaPrimitiveType)
                .invoke(material.getField("INSTANCE").get(null),composer,6)
            val local=loader.loadClass("androidx.compose.ui.platform.CompositionLocalsKt").getMethod("getLocalDensity").invoke(null)
            val density=composerType.getMethod("consume",loader.loadClass("androidx.compose.runtime.CompositionLocal")).invoke(composer,local)
            fun densityValue(name:String)=(density.javaClass.getMethod(name).apply {isAccessible=true}.invoke(density) as Number).toFloat()
            val densityKey=densityValue("getDensity") to densityValue("getFontScale")
            if(typography===lastTypography && lastDensity==densityKey)return
            fun pixels(styleName:String,property:String):Float = runCatching {
                val style=typography.javaClass.getMethod(styleName).invoke(typography)
                val packed=style.javaClass.methods.first {it.name.startsWith(property+"-")&&it.parameterCount==0}.invoke(style)
                val converter=density.javaClass.methods.first {it.name.startsWith("toPx-")&&it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))}.apply {isAccessible=true}
                (converter.invoke(density,packed) as Number).toFloat().takeIf {it.isFinite()&&it>0f}?:0f
            }.getOrDefault(0f)
            val unit=loader.loadClass("androidx.compose.ui.unit.TextUnit")
            val getValue=unit.getDeclaredMethod("getValue-impl",Long::class.javaPrimitiveType)
            fun size(name:String,fallback:Float):Float {
                val style=typography.javaClass.getMethod(name).invoke(typography)
                val getter=style.javaClass.methods.first {it.name.startsWith("getFontSize-")&&it.parameterCount==0}
                val value=(getValue.invoke(null,getter.invoke(style)) as Number).toFloat()
                return value.takeIf {it.isFinite()&&it in 8f..64f}?:fallback
            }
            val labelStyle=typography.javaClass.getMethod("getLabelLarge").invoke(typography)
            val weight=runCatching {val w=labelStyle.javaClass.getMethod("getFontWeight").invoke(labelStyle);(w.javaClass.getMethod("getWeight").invoke(w) as Number).toInt()}.getOrDefault(500)
            textMetrics=TextMetrics(size("getBodyLarge",16f),size("getBodySmall",12f),size("getLabelLarge",14f),size("getTitleSmall",16f),
                pixels("getBodyLarge","getFontSize"),pixels("getLabelLarge","getFontSize"),pixels("getBodyLarge","getLineHeight"),pixels("getLabelLarge","getLineHeight"),weight)
            lastTypography=typography;lastDensity=densityKey
        }
    }
    fun thoughtInput(input: EditText) {
        val context=input.context;val native=snapshot(context) ?: return
        input.includeFontPadding=false
        input.backgroundTintList=null
        if(textMetrics.bodyLargePx>0f) input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,textMetrics.bodyLargePx) else input.textSize=textMetrics.bodyLarge
        uiTypeface(context)?.let { input.typeface=it }
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
