package com.reamicro.fix.hook

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.WindowManager
import com.reamicro.fix.xposed.HookRuntime
import kotlin.math.roundToInt

/** A bounded, in-memory copy of the covered host window; never captures this popup itself. */
internal class HostWindowBackdrop(
    private val activity: Activity,
    private val main: Handler,
    private val isCurrent: () -> Boolean,
    private val onImage: (Bitmap?) -> Unit,
) {
    private var closed = false
    private var copying = false
    private var revision = 0L
    private var warned = false

    fun refresh() {
        if (closed) return
        revision++
        if (!copying) copy()
    }

    fun close() {
        closed = true
        revision++
    }

    private fun copy() {
        if (closed || !isCurrent() || activity.isFinishing || activity.isDestroyed) return
        if (Build.VERSION.SDK_INT < 31) { onImage(null); return }
        val source = HostSettingsDialogs.backgroundWindow(activity)
        val decor = source.decorView
        val width = decor.width
        val height = decor.height
        if (!decor.isAttachedToWindow || width <= 0 || height <= 0 ||
            source.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            onImage(null)
            return
        }
        val scale = minOf(1f, MAX_EDGE / maxOf(width, height).toFloat())
        val image = runCatching {
            Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }.getOrElse { error -> warn(error); onImage(null); return }
        val requested = revision
        copying = true
        try {
            PixelCopy.request(source, image, { result ->
                copying = false
                if (closed || !isCurrent() || activity.isFinishing || activity.isDestroyed) {
                    image.recycle()
                } else if (requested != revision) {
                    image.recycle()
                    copy()
                } else if (result == PixelCopy.SUCCESS) {
                    onImage(image)
                } else {
                    image.recycle()
                    onImage(null)
                    warn(IllegalStateException("PixelCopy result=$result"))
                }
            }, main)
        } catch (error: Throwable) {
            copying = false
            image.recycle()
            onImage(null)
            warn(error)
        }
    }

    private fun warn(error: Throwable) {
        if (warned) return
        warned = true
        HookRuntime.logError("ReaMicro host backdrop capture unavailable", error)
    }

    private companion object {
        const val MAX_EDGE = 720
    }
}
