package com.reamicro.fix.epub.editor

internal fun epubPreviewSampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long = 4_194_304L): Int {
    require(width > 0 && height > 0) { "无法识别图片尺寸" }
    require(maxEdge > 0 && maxPixels > 0)
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge ||
        (width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
        check(sample <= Int.MAX_VALUE / 2) { "图片尺寸超出安全范围" }
        sample *= 2
    }
    return sample
}
