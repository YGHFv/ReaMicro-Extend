package com.reamicro.fix.reload

/** 连接重试与框架回执共用一个状态；超时提示不能放开重复提交。 */
internal class ReloadRequestState {
    enum class Phase { RESERVED, SUBMITTED, UNCONFIRMED, SUCCEEDED, FAILED }

    @Volatile var phase = Phase.RESERVED
        private set
    fun submit(): Boolean {
        if (phase != Phase.RESERVED) return false
        phase = Phase.SUBMITTED
        return true
    }

    fun complete(success: Boolean): Boolean {
        if (phase != Phase.RESERVED && phase != Phase.SUBMITTED && phase != Phase.UNCONFIRMED) return false
        phase = if (success) Phase.SUCCEEDED else Phase.FAILED
        return true
    }

    fun loseConnection(): Boolean {
        if (phase != Phase.SUBMITTED) return false
        phase = Phase.UNCONFIRMED
        return true
    }
}
