package com.reamicro.fix.hook

internal data class AccountAdvancedPreferences(
    val dynamicColor: Boolean,
    val smartRecognition: Boolean,
    val illustrationOptimization: Boolean,
    val relateDownloadCover: Boolean,
    val callingCardColor: Boolean,
)

/** 权限来自本次宿主原判断，切号代次与 DataStore token 在实际事务内一起核验。 */
internal class AccountAdvancedRestoreState {
    data class Target(val accountId: Long, val token: String)
    data class Origin(val generation: Long, val target: Target, var check: Long = 0)
    data class Pending(val origin: Origin, val preferences: AccountAdvancedPreferences)
    private var generation = 0L
    private var target: Target? = null
    private var pending: Pending? = null
    private var ready = false
    private var permitted = false
    private var checkSequence = 0L
    private var verdictSequence = 0L
    private val departed = LinkedHashMap<Target, Origin>()

    @Synchronized fun begin(value: Target, preferences: AccountAdvancedPreferences?): Long {
        target?.let { departed[it] = Origin(generation, it) }
        if (departed.size > 16) departed.remove(departed.keys.first())
        generation++
        target = value
        pending = preferences?.let { Pending(Origin(generation, value), it) }
        ready = false
        permitted = false
        verdictSequence = 0L
        return generation
    }
    @Synchronized fun origin(accountId: Long, token: String?): Origin? = target
        ?.takeIf { it.accountId == accountId && (token == null || token == it.token) }
        ?.let { Origin(generation, it) }
        ?: departed.entries.lastOrNull { it.key.accountId == accountId && (token == null || token == it.key.token) }?.value
    @Synchronized fun observeCurrent(value: Target?) {
        if (value != null && (target == null || ready && target != value)) {
            generation++
            target = value
            pending = null
            permitted = false
            ready = true
            verdictSequence = 0L
            departed.clear()
        }
    }
    @Synchronized fun markReady(ticket: Long) { if (generation == ticket) ready = true }
    @Synchronized fun cancel(ticket: Long) { if (generation == ticket) { pending = null; permitted = false; ready = true } }
    @Synchronized fun nativeIdentityChange() {
        generation++
        target = null
        pending = null
        departed.clear()
        permitted = false
        ready = true
    }
    @Synchronized fun verdict(origin: Origin?, allowed: Boolean) {
        if (origin != null && origin.generation == generation && origin.target == target) {
            origin.check = ++checkSequence
            verdictSequence = origin.check
            permitted = allowed
        }
    }
    @Synchronized fun failed(origin: Origin?) {
        if (origin != null && origin.generation == generation && origin.target == target && origin.check >= verdictSequence)
            permitted = false
    }
    @Synchronized fun preserved(accountId: Long): AccountAdvancedPreferences? =
        pending?.takeIf { it.origin.target.accountId == accountId }?.preferences
    @Synchronized fun candidate(): Pending? = pending?.takeIf { ready && permitted }
    @Synchronized fun commit(ticket: Pending, token: String?, write: () -> Unit): Boolean {
        if (pending !== ticket || !ready || !permitted || token != target?.token) return false
        write()
        return true
    }
    @Synchronized fun committed(ticket: Pending) { if (pending === ticket && permitted) pending = null }
    @Synchronized fun nativeWrite(origin: Origin?, token: String?, write: () -> Any?): Any? {
        // 不能证明来源的宿主清理保留原样，绝不能放过当前非会员的门禁。
        if (origin == null) return write()
        if (origin.generation != generation || origin.target != target) return null
        // 切号正在发布身份时，目标账号原生清理可能先于 token 事务入队；该清理必须照常执行。
        if (ready && token != target?.token) return null
        if (permitted && origin.check < verdictSequence) return null
        return write()
    }
}
