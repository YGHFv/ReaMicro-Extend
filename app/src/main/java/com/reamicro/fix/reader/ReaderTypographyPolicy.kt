package com.reamicro.fix.reader

/** 宿主未设置时使用 18sp、1.5 倍行高；零值不是“跟随默认”，而会裁掉正文。 */
internal object ReaderTypographyPolicy {
    const val DEFAULT_TEXT_SIZE = 18f
    const val DEFAULT_LINE_HEIGHT = 1.5f

    fun textSize(value: Float?): Float =
        value?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_TEXT_SIZE

    fun lineHeight(value: Float?): Float =
        value?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_LINE_HEIGHT
}
