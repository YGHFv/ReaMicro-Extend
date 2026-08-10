package com.reamicro.fix.webdav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 章节头图合成。
 *
 * 与 TEpub-Editor `buildProcessedHeaderFromAsset` 同一套算法：按样式的样板尺寸建画布，用户图
 * cover 缩放居中绘制，再用样板图的 alpha 做 `DST_IN` 裁切，得到贴边渐隐、撕边等效果。
 * 模块里的蒙版是样板图提取出来的灰度图（见 tools/gen-header-masks.mjs），灰度值即原 alpha。
 */
internal object OnlineHeaderImageComposer {
    /**
     * @param maskBitmap 蒙版灰度图，为 null 时只做 cover 裁切不套蒙版。
     * @return PNG 字节；源图无法解码时返回 null。
     */
    fun compose(
        sourceFile: File,
        maskBitmap: Bitmap?,
        sampleWidth: Int,
        sampleHeight: Int,
    ): ByteArray? {
        val source = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return null
        val width = (if (sampleWidth > 0) sampleWidth else source.width).coerceAtLeast(320)
        val height = (if (sampleHeight > 0) sampleHeight else source.height).coerceAtLeast(180)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(output)
            drawCover(canvas, source, width, height)
            maskBitmap?.takeIf { hasTransparency(it) }?.let { mask ->
                applyMask(canvas, mask, width, height)
            }
            ByteArrayOutputStream().use { stream ->
                output.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } finally {
            output.recycle()
            source.recycle()
        }
    }

    /** 按 max(w/iw, h/ih) 缩放并居中，等价于 CSS 的 object-fit: cover。 */
    private fun drawCover(canvas: Canvas, source: Bitmap, width: Int, height: Int) {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
    }

    /**
     * 用蒙版裁切已绘制的内容。
     *
     * 蒙版是灰度图，先按灰度值重建 alpha 再做 DST_IN；样板图本身若已带 alpha 通道也能直接用。
     */
    private fun applyMask(canvas: Canvas, mask: Bitmap, width: Int, height: Int) {
        val alphaMask = toAlphaMask(mask, width, height)
        try {
            canvas.drawBitmap(
                alphaMask,
                0f,
                0f,
                Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                },
            )
        } finally {
            alphaMask.recycle()
        }
    }

    /** 把蒙版拉伸到目标尺寸，并把灰度值转成 alpha。 */
    private fun toAlphaMask(mask: Bitmap, width: Int, height: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(mask, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== mask) scaled.recycle()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            // 灰度蒙版整幅不透明，此时取亮度当 alpha；本身带 alpha 的样板图则沿用其 alpha。
            val value = if (alpha == 0xFF) pixel and 0xFF else alpha
            pixels[index] = value shl 24
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 整幅完全不透明的蒙版没有裁切意义，直接跳过（与 TEpub 的 alpha < 250 判断一致）。 */
    private fun hasTransparency(mask: Bitmap): Boolean {
        val step = maxOf(1, mask.width * mask.height / SAMPLE_LIMIT)
        var index = 0
        val total = mask.width * mask.height
        while (index < total) {
            val pixel = mask.getPixel(index % mask.width, index / mask.width)
            val alpha = pixel ushr 24
            val value = if (alpha == 0xFF) pixel and 0xFF else alpha
            if (value < 250) return true
            index += step
        }
        return false
    }

    /** 判透明只需抽样，整幅逐像素读对上百万像素的样板图太慢。 */
    private const val SAMPLE_LIMIT = 20_000
}
