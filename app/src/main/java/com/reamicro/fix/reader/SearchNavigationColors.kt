package com.reamicro.fix.reader

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class SearchNavigationColors(val background: Int, val foreground: Int, val caption: Int,
                                  val disabled: Int, val border: Int) {
    companion object {
        private const val WHITE = -1
        private const val BLACK = -16777216

        fun resolve(background: Int, foreground: Int, caption: Int, border: Int): SearchNavigationColors {
            val bg = background or BLACK
            val fallback = if (contrast(WHITE, bg) >= contrast(BLACK, bg)) WHITE else BLACK
            fun readable(candidate: Int, minimum: Double): Int {
                val opaque = blend(candidate, bg, ((candidate ushr 24) and 255) / 255.0)
                return if (contrast(opaque, bg) >= minimum) opaque else fallback
            }
            val fg = readable(foreground, 4.5)
            val secondary = readable(caption, 4.5)
            val muted = blend(fg, bg, .52)
            val disabled = if (contrast(muted, bg) >= 3.0) muted else fg
            return SearchNavigationColors(bg, fg, secondary, disabled, readable(border, 1.5))
        }

        fun contrast(first: Int, second: Int): Double {
            fun luminance(color: Int): Double {
                fun linear(shift: Int): Double {
                    val s = ((color ushr shift) and 255) / 255.0
                    return if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)
                }
                return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
            }
            val a = luminance(first); val b = luminance(second)
            return (max(a, b) + .05) / (min(a, b) + .05)
        }

        private fun blend(fg: Int, bg: Int, alpha: Double): Int {
            fun channel(shift: Int) = ((((fg ushr shift) and 255) * alpha +
                ((bg ushr shift) and 255) * (1 - alpha)).roundToInt().coerceIn(0, 255) shl shift)
            return BLACK or channel(16) or channel(8) or channel(0)
        }
    }
}
