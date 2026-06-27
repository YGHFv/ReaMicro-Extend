package com.reamicro.fix.notification

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan

fun onlineCompletionDownloadTitle(bookName: String): CharSequence {
    val displayName = bookName.ifBlank { ONLINE_COMPLETION_FALLBACK_TITLE }
    val text = "$DOWNLOAD_NOTIFICATION_PREFIX$displayName"
    return SpannableString(text).apply {
        setSpan(
            RelativeSizeSpan(0.82f),
            DOWNLOAD_NOTIFICATION_PREFIX.length,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private const val DOWNLOAD_NOTIFICATION_PREFIX = "\u4e0b\u8f7d\uff1a"
private const val ONLINE_COMPLETION_FALLBACK_TITLE = "\u5728\u7ebf\u8865\u5168"
