package com.reamicro.fix.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Scrolls a form the way the host's own screens scroll: by touch alone, with no scroll bar drawn. */
internal class HostFormScroll(context: Context, content: View) : ScrollView(context) {
    init {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = false
        addView(content)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Keeps [header] in place over a scrolling form, as the host keeps its search pill over its contents list,
 * and holds the tallest height the two have taken, so that a list which gets shorter does not move the header.
 */
internal class HostPinnedForm(kit: HostFormKit, header: View, scroll: HostFormScroll) : LinearLayout(kit.context) {
    private var tallest = 0

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = kit.dp(GAP)
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        tallest = maxOf(tallest, measuredHeight)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            tallest
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        setMeasuredDimension(measuredWidth, maxOf(measuredHeight, minOf(tallest, limit)))
    }

    private companion object {
        /** With the header's own inset, the 12dp the host leaves between its search pill and its list. */
        const val GAP = 8f
    }
}
