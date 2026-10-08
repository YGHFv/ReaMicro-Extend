package com.reamicro.fix.reader

internal object HighlightImageLimits {
    const val MAX_EDGE = 8192
    const val MAX_PIXELS = 8L * 1024 * 1024

    fun accepts(width: Int, height: Int): Boolean =
        width in 1..MAX_EDGE && height in 1..MAX_EDGE && width.toLong() * height <= MAX_PIXELS

    fun requireValid(width: Int, height: Int) {
        require(accepts(width, height)) {
            "高亮图片尺寸无效或过大（${width}×${height}）；最长边不能超过 $MAX_EDGE，总像素不能超过 $MAX_PIXELS。请缩小图片后重试。"
        }
    }
}
