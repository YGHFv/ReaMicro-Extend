package com.reamicro.fix.online.search

import com.reamicro.fix.association.model.AssociationPlatforms
import com.reamicro.fix.hook.webdav.OnlineBookSearchResult
import com.reamicro.fix.hook.webdav.cleanOnlineText

internal fun onlineSearchPlatformLabel(raw: String): String {
    val clean = raw.cleanOnlineText().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
    return when (AssociationPlatforms.normalizeSource(clean)?.id) {
        "qidian" -> "起点"
        "jinjiang" -> "晋江"
        "zongheng" -> "纵横"
        "xirang" -> "息壤"
        "youdu" -> "有毒"
        "faloo" -> "飞卢"
        "ciweimao" -> "刺猬猫"
        "fanqie" -> "番茄"
        "gongzicp" -> "长佩"
        "sfacg" -> "菠萝包"
        else -> clean
    }
}

internal fun onlineSearchTagLine(result: OnlineBookSearchResult): String {
    // 聚合书源名称不是原作平台；只有明确的平台来源才补到标签行。
    val platform = result.platformName.ifBlank {
        AssociationPlatforms.normalizeDisplayName(result.sourceName)
    }
    return listOfNotNull(
        onlineSearchPlatformLabel(platform).ifBlank { null },
        result.status.ifBlank { null },
        result.wordCount.ifBlank { null },
        result.chapterCount.takeIf { it > 0 }?.let { "${it}章" },
        result.updateTime.ifBlank { null },
    ).distinct().joinToString(" / ").ifBlank { "详情加载中" }
}
