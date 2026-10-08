package com.reamicro.fix.epub.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RectF
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

internal fun hostSvgPreview(loader: ClassLoader, file: File, maxEdge: Int): Bitmap {
    require(maxEdge in 1..2048) { "SVG 预览尺寸超出安全范围" }
    require(file.length() <= 2L * 1024L * 1024L) { "SVG 文件超出安全预览范围" }
    val text = EpubTextFiles.load(file).text
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        "SVG 安全预览不支持文档实体"
    }
    require(!Regex("@import", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        "SVG 安全预览不支持外部样式"
    }
    val hrefs = Regex("""(?is)\b(?:xlink:)?href\s*=\s*["']([^"']*)["']""").findAll(text)
    require(hrefs.all { it.groupValues[1].trim().let { v -> v.isEmpty() || v.startsWith("#") } }) {
        "SVG 安全预览不支持外部资源引用"
    }
    val urls = Regex("""(?is)url\s*\(([^)]*)\)""").findAll(text)
    require(urls.all { it.groupValues[1].trim().trim('"', '\'').trim().startsWith("#") }) {
        "SVG 安全预览不支持外部资源引用"
    }
    val type = loader.loadClass("com.caverock.androidsvg.SVG")
    val svg = type.getMethod("getFromString", String::class.java).invoke(null, text)
    val viewBox = type.getMethod("getDocumentViewBox").invoke(svg) as? RectF
    var width = (type.getMethod("getDocumentWidth").invoke(svg) as Number).toFloat()
    var height = (type.getMethod("getDocumentHeight").invoke(svg) as Number).toFloat()
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
        width = viewBox?.width()?.takeIf { it.isFinite() && it > 0f } ?: 1f
        height = viewBox?.height()?.takeIf { it.isFinite() && it > 0f } ?: 1f
    }
    val factor = maxEdge / max(width, height)
    val targetWidth = (width * factor).roundToInt().coerceIn(1, maxEdge)
    val targetHeight = (height * factor).roundToInt().coerceIn(1, maxEdge)
    val picture = type.getMethod("renderToPicture", Integer.TYPE, Integer.TYPE)
        .invoke(svg, targetWidth, targetHeight) as Picture
    return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawPicture(picture)
    }
}
