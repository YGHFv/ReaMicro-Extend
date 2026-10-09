package com.reamicro.fix.hook

import org.json.JSONObject

internal data class ReaderMarginsSnapshot(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    val topOuter: Int,
    val bottomOuter: Int,
) {
    fun arguments(): Array<Any> = arrayOf(top, bottom, left, right, topOuter, bottomOuter)

    fun toJson(): JSONObject = JSONObject().apply {
        put("top", top)
        put("bottom", bottom)
        put("left", left)
        put("right", right)
        put("topOuter", topOuter)
        put("bottomOuter", bottomOuter)
    }

    companion object {
        // 与宿主 SessionKt.getReaderMargins 一致：旧 padding 用于四边，外边距默认为零。
        fun fromJson(json: JSONObject?, padding: Int): ReaderMarginsSnapshot = ReaderMarginsSnapshot(
            top = json?.optInt("top", padding) ?: padding,
            bottom = json?.optInt("bottom", padding) ?: padding,
            left = json?.optInt("left", padding) ?: padding,
            right = json?.optInt("right", padding) ?: padding,
            topOuter = json?.optInt("topOuter", 0) ?: 0,
            bottomOuter = json?.optInt("bottomOuter", 0) ?: 0,
        )

        fun fromHost(margins: Any): ReaderMarginsSnapshot {
            fun value(name: String): Int = (margins.javaClass.getMethod(name).invoke(margins) as Number).toInt()
            return ReaderMarginsSnapshot(
                value("getTop"), value("getBottom"), value("getLeft"), value("getRight"),
                value("getTopOuter"), value("getBottomOuter"),
            )
        }
    }
}
