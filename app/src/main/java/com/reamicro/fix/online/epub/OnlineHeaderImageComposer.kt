package com.reamicro.fix.online.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.io.File

internal object OnlineHeaderImageComposer {

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

    fun composePlaceholder(
        maskBitmap: Bitmap?,
        sampleWidth: Int,
        sampleHeight: Int,
    ): ByteArray? {
        val width = (if (sampleWidth > 0) sampleWidth else 1080).coerceAtLeast(320)
        val height = (if (sampleHeight > 0) sampleHeight else 608).coerceAtLeast(180)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(output)
            drawPlaceholder(canvas, width, height)
            maskBitmap?.takeIf { hasTransparency(it) }?.let { mask ->
                applyMask(canvas, mask, width, height)
            }
            ByteArrayOutputStream().use { stream ->
                output.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } finally {
            output.recycle()
        }
    }

    private fun drawPlaceholder(canvas: Canvas, width: Int, height: Int) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(0xFFB9AE95.toInt(), 0xFF8C8676.toInt(), 0xFF5C5B52.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val ridge = Path().apply {
            moveTo(0f, height * 0.78f)
            lineTo(width * 0.28f, height * 0.42f)
            lineTo(width * 0.47f, height * 0.66f)
            lineTo(width * 0.66f, height * 0.34f)
            lineTo(width.toFloat(), height * 0.72f)
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(ridge, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6E6A5C.toInt() })
        canvas.drawCircle(
            width * 0.78f,
            height * 0.24f,
            minOf(width, height) * 0.09f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDE6D6.toInt() },
        )
    }

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

    private fun toAlphaMask(mask: Bitmap, width: Int, height: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(mask, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== mask) scaled.recycle()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = pixel ushr 24

            val value = if (alpha == 0xFF) pixel and 0xFF else alpha
            pixels[index] = value shl 24
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

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

    private const val SAMPLE_LIMIT = 20_000
}
