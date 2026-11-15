package com.reamicro.fix.hook

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * BookCover geometry verified through MT on both supported APKs. Network source covers retain
 * the bounded, cancellable downloader because the native image request omits source auth headers.
 */
internal class HostSearchCoverView(context: Context) : FrameLayout(context) {
    private var theme: Pair<HostThemeColors.Snapshot, Float>? = null
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        EmbeddedHostUi.text(this, "BodySmall")
    }
    init {
        val inset = pixels(4f)
        setPadding(inset, inset, inset, inset)
        addView(image, LayoutParams(pixels(64f), pixels(89.6f), Gravity.CENTER))
        addView(status, LayoutParams(pixels(64f), pixels(89.6f), Gravity.CENTER))
        applyTheme()
    }
    private fun pixels(value: Float) = (EmbeddedHostUi.dp(context, value) + .5f).toInt()
    fun applyTheme() {
        val native = HostThemeColors.snapshot(context, HostEmbeddedStyle.isDark(context))
        val next = native to EmbeddedHostUi.dp(context, 1f)
        if (theme == next) return
        theme = next
        background = HostSurfaceDrawable(native.pageArgb, native.borderVariantArgb,
            EmbeddedHostUi.dp(context, 8f), EmbeddedHostUi.dp(context, .8f))
        image.background = HostSurfaceDrawable(native.roles.getValue("SurfaceContainerHighest"), 0,
            EmbeddedHostUi.dp(context, 4f), 0f)
        status.setTextColor(native.captionArgb)
        EmbeddedHostUi.text(status, "BodySmall")
    }
    fun state(message: String) {
        image.setImageDrawable(null)
        status.text = message
        status.visibility = View.VISIBLE
        contentDescription = message
    }
    fun loaded(bitmap: Bitmap) {
        image.setImageBitmap(bitmap)
        status.visibility = View.GONE
        contentDescription = "封面"
    }
}
