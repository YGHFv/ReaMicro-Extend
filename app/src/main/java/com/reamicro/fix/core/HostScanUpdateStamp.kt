package com.reamicro.fix.core

/** 更新时间以不相等判定；APK 字段为路径、大小、mtime 的摘要，不替代扫描器的内容 SHA-256。 */
data class HostScanUpdateStamp(
    val hostUpdatedAt: Long,
    val moduleUpdatedAt: Long,
    val hostApk: String,
    val moduleApk: String,
) {
    init {
        require(hostUpdatedAt > 0 && moduleUpdatedAt > 0)
        require(listOf(hostApk, moduleApk).all { it.matches(Regex("[a-f0-9]{64}")) })
    }

    fun encode(): String = "$hostUpdatedAt|$moduleUpdatedAt|$hostApk|$moduleApk"

    fun changeReason(previous: HostScanUpdateStamp?): String = when {
        previous == null -> "首次启用更新检查"
        hostChanged(previous) && moduleChanged(previous) -> "检测到阅微和模块已更新"
        hostChanged(previous) -> "检测到阅微已更新"
        moduleChanged(previous) -> "检测到模块已更新"
        else -> "版本未变化"
    }

    fun needsScan(previous: HostScanUpdateStamp?, attempted: HostScanUpdateStamp?): Boolean =
        this != previous && this != attempted

    private fun hostChanged(previous: HostScanUpdateStamp): Boolean =
        hostUpdatedAt != previous.hostUpdatedAt || hostApk != previous.hostApk

    private fun moduleChanged(previous: HostScanUpdateStamp): Boolean =
        moduleUpdatedAt != previous.moduleUpdatedAt || moduleApk != previous.moduleApk

    companion object {
        fun decode(value: String?): HostScanUpdateStamp? = runCatching {
            val parts = requireNotNull(value).split('|')
            require(parts.size == 4)
            HostScanUpdateStamp(parts[0].toLong(), parts[1].toLong(), parts[2], parts[3])
        }.getOrNull()
    }
}
