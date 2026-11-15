package com.reamicro.fix.notification

import android.app.NotificationManager
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan

fun onlineCompletionDownloadTitle(progress: Int, status: String): CharSequence =
    "$DOWNLOAD_NOTIFICATION_PREFIX${onlineCompletionProgressLabel(progress, status)}"

fun onlineCompletionDownloadText(bookName: String, status: String): CharSequence {
    val displayName = bookName.ifBlank { ONLINE_COMPLETION_FALLBACK_TITLE }
    val detail = compactNotificationStatus(status)
    return SpannableString(if (detail.isBlank()) displayName else "$displayName · $detail").apply {
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
    return listOf(displayName, compactNotificationStatus(status)).filter(String::isNotBlank).joinToString("\n")
}

fun cancelOnlineCompletionNotificationIfDone(
    manager: NotificationManager, id: Int, done: Boolean, status: String,
) {
    if (!shouldDismissOnlineCompletionNotification(done, status)) return
    val version = OnlineNotificationEventOrder.version(id)
    com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper()).postDelayed({
        if (OnlineNotificationEventOrder.version(id) == version) manager.cancel(id)
    }, DONE_NOTIFICATION_VISIBLE_MS)
}

internal fun shouldDismissOnlineCompletionNotification(done: Boolean, status: String): Boolean =
    done && !compactNotificationStatus(status).startsWith("失败：") &&
        !compactNotificationStatus(status).contains("暂停") && failedCountLabel(status).isEmpty()

private fun onlineCompletionProgressLabel(progress: Int, status: String): String {
    val compactStatus = compactNotificationStatus(status)
    val percent = progress.coerceIn(0, 100)
    val failedSuffix = failedCountLabel(compactStatus)
    if (compactStatus.startsWith("失败：")) return STATUS_FAILED
    if (compactStatus.startsWith("已暂停") || compactStatus.startsWith("更新已暂停")) return "已暂停"
    if (compactStatus.contains("取消")) return "已取消"
    if (compactStatus.startsWith("已导入")) return "已导入$failedSuffix"
    if (percent == 100) return "已完成$failedSuffix"
    chapterProgressRegex.find(compactStatus)?.let { match ->
        return "${match.groupValues[1]}/${match.groupValues[2]} - $percent%$failedSuffix"
    }
    return when {
        compactStatus.contains(STATUS_DONE) -> STATUS_DONE
        compactStatus.contains(STATUS_FAILED) -> "$STATUS_FAILED$failedSuffix"
        compactStatus.contains(STATUS_IMPORTING) -> "$STATUS_IMPORT - $percent%$failedSuffix"
        compactStatus.contains(STATUS_RETRY) -> "$STATUS_RETRY - $percent%$failedSuffix"
        compactStatus.contains(STATUS_TOC) -> "$STATUS_TOC - $percent%$failedSuffix"
        compactStatus.contains(STATUS_DETAIL) -> "$STATUS_PREPARE - $percent%$failedSuffix"
        compactStatus.contains(STATUS_GENERATE) -> "$STATUS_GENERATE - $percent%$failedSuffix"
        compactStatus.isNotBlank() -> "${compactStatus.take(8)} - $percent%$failedSuffix"
        percent > 0 -> "$percent%"
        else -> STATUS_PREPARE
    }
}

private fun compactNotificationStatus(status: String): String =
    status.replace(whitespaceRegex, " ").trim()

private fun failedCountLabel(status: String): String {
    val count = failedCountRegex.find(status)?.groupValues?.drop(1)
        ?.firstNotNullOfOrNull(String::toIntOrNull) ?: 0
    return if (count > 0) " · \u5931\u8d25${count}\u7ae0" else ""
}

private val chapterProgressRegex = Regex("(?:\u4e0b\u8f7d|\u91cd\u8bd5)?(?:\u65b0\u589e)?\u7ae0\u8282\\s*(\\d+)\\s*/\\s*(\\d+)")
private val failedCountRegex = Regex("(?:失败\\s*(\\d+)\\s*章|(\\d+)\\s*章失败)")
private val whitespaceRegex = Regex("\\s+")

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
