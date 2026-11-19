package com.reamicro.fix.notification

import java.net.URLEncoder

internal fun taskRecordsUri(accountId: String): String =
    "reamicro://task-records/" + URLEncoder.encode(accountId.ifBlank { "all" }, "UTF-8")

internal fun taskProgressTag(accountId: String, taskType: String): String =
    "task-progress:${accountId.length}:$accountId:${taskType.length}:$taskType"

internal fun taskProgressPhaseRank(phase: String): Int? = when (phase) {
    "waking" -> 0
    "running" -> 1
    "finished" -> 2
    else -> null
}

internal fun taskProgressEventAccepted(previousAt: Long, previousRank: Int, at: Long, rank: Int): Boolean =
    at > previousAt || (at == previousAt && rank >= previousRank)

internal fun taskNotificationTitle(title: String, accountId: String, accountLabel: String): String =
    if (accountId.isBlank()) title else "${accountLabel.ifBlank { accountId }} · $title"
