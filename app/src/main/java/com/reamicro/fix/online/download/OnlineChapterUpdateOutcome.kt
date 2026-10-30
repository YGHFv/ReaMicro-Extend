package com.reamicro.fix.online.download

/** 单书更新的实际结果，供批量汇总使用，不从Toast文案反推章节数。 */
internal data class OnlineChapterUpdateOutcome(
    val title: String,
    val added: Int = 0,
    val failed: Int = 0,
    val retried: Int = 0,
    val error: String? = null,
    val skipped: Boolean = false,
) {
    val success: Boolean get() = error == null && failed == 0
}
