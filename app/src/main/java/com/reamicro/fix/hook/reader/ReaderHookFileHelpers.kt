package com.reamicro.fix.hook.reader

import android.content.Context
import com.reamicro.fix.hook.reader.*

internal fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
