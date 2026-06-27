package com.reamicro.fix.notification

import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan

fun onlineCompletionDownloadTitle(progress: Int, status: String): CharSequence =
    "$DOWNLOAD_NOTIFICATION_PREFIX${onlineCompletionProgressLabel(progress, status)}"

fun onlineCompletionDownloadText(bookName: String, status: String): CharSequence {
    val displayName = bookName.ifBlank { ONLINE_COMPLETION_FALLBACK_TITLE }
    return SpannableString(displayName).apply {
        setSpan(
            RelativeSizeSpan(0.82f),
            0,
            displayName.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

fun onlineCompletionDownloadBigText(bookName: String, status: String, progress: Int): CharSequence {
    val displayName = bookName.ifBlank { ONLINE_COMPLETION_FALLBACK_TITLE }
    return displayName
}

fun cancelOnlineCompletionNotificationIfDone(manager: NotificationManager, id: Int, done: Boolean) {
    if (!done) return
    Handler(Looper.getMainLooper()).postDelayed({ manager.cancel(id) }, DONE_NOTIFICATION_VISIBLE_MS)
}

private fun onlineCompletionProgressLabel(progress: Int, status: String): String {
    val compactStatus = compactNotificationStatus(status)
    val percent = progress.coerceIn(0, 100)
    chapterProgressRegex.find(compactStatus)?.let { match ->
        val current = match.groupValues[1].toIntOrNull() ?: 0
        val total = match.groupValues[2].toIntOrNull() ?: 0
        val chapterPercent = chapterPercent(current, total).takeIf { total > 0 } ?: percent
        return "${match.groupValues[1]}/${match.groupValues[2]} - $chapterPercent%"
    }
    return when {
        compactStatus.contains(STATUS_DONE) -> STATUS_DONE
        compactStatus.contains(STATUS_FAILED) -> STATUS_FAILED
        compactStatus.contains(STATUS_IMPORTING) -> "$STATUS_IMPORT - $percent%"
        compactStatus.contains(STATUS_RETRY) -> "$STATUS_RETRY - $percent%"
        compactStatus.contains(STATUS_TOC) -> "$STATUS_TOC - $percent%"
        compactStatus.contains(STATUS_DETAIL) -> "$STATUS_PREPARE - $percent%"
        compactStatus.contains(STATUS_GENERATE) -> "$STATUS_GENERATE - $percent%"
        compactStatus.isNotBlank() -> "${compactStatus.take(8)} - $percent%"
        percent > 0 -> "$percent%"
        else -> STATUS_PREPARE
    }
}

private fun compactNotificationStatus(status: String): String =
    status.replace(Regex("\\s+"), " ").trim()

private val chapterProgressRegex = Regex("(?:\u4e0b\u8f7d|\u91cd\u8bd5)?\u7ae0\u8282\\s*(\\d+)\\s*/\\s*(\\d+)")

private fun chapterPercent(current: Int, total: Int): Int {
    if (total <= 0) return 0
    return ((current.coerceAtLeast(0) * 100L) / total).toInt().coerceIn(0, 100)
}

private const val DOWNLOAD_NOTIFICATION_PREFIX = "\u4e0b\u8f7d\uff1a"
private const val ONLINE_COMPLETION_FALLBACK_TITLE = "\u5728\u7ebf\u8865\u5168"
private const val STATUS_DETAIL = "\u8bfb\u53d6\u8be6\u60c5"
private const val STATUS_DONE = "\u5df2\u5bfc\u5165\u9605\u5fae"
private const val STATUS_FAILED = "\u5931\u8d25"
private const val STATUS_GENERATE = "\u751f\u6210"
private const val STATUS_IMPORT = "\u5bfc\u5165"
private const val STATUS_IMPORTING = "\u5bfc\u5165"
private const val STATUS_PREPARE = "\u51c6\u5907"
private const val STATUS_RETRY = "\u91cd\u8bd5"
private const val STATUS_TOC = "\u76ee\u5f55"
private const val DONE_NOTIFICATION_VISIBLE_MS = 3500L
