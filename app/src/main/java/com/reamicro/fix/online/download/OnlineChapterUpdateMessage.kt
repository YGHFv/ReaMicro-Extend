package com.reamicro.fix.online.download

internal fun onlineChapterUpdateMessage(added: Int, failed: Int, retried: Int, styled: Boolean): String = when {
    added > 0 && retried > 0 && failed > 0 -> "已重试 $retried 章，已更新 $added 章，$failed 章失败"
    added > 0 && retried > 0 -> "已重试 $retried 章，已更新 $added 章"
    added > 0 && failed > 0 -> "已更新 $added 章，$failed 章失败"
    // 样式同步可以与新增章节同时发生，不能让样式提示覆盖更新数量。
    added > 0 -> "已更新 $added 章"
    failed > 0 && retried > 0 -> "已重试 $retried 章，$failed 章失败"
    failed > 0 -> "仍有 $failed 章失败"
    retried > 0 -> "已重试 $retried 章"
    styled -> "章节已是最新，默认样式已同步"
    else -> "已是最新章节"
}
