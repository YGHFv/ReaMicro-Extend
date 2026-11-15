package com.reamicro.fix.association

/** Native Book identity. A reused VM or the same title does not identify its owner. */
internal data class AssociationBookIdentity(val uid: Long, val id: Long, val uuid: String) {
    init { require(uid >= 0L && id > 0L && uuid.isNotBlank()) { "关联图书身份无效" } }
}

/** The host's MVI get/reduce/CAS loop, with a short owner gate only around publication. */
internal object AssociationOwnedState {
    fun <S : Any> update(
        isCurrent: () -> Boolean,
        read: () -> S?,
        matches: (S) -> Boolean,
        reduce: (S) -> S,
        compareAndSet: (S, S) -> Boolean,
        commit: (() -> Boolean) -> Boolean,
    ): Boolean {
        while (isCurrent()) {
            val before = read() ?: return false
            if (!isCurrent() || !matches(before)) return false
            val after = reduce(before)
            if (!isCurrent()) return false
            if (after === before) return true
            var entered = false
            val published = try {
                commit {
                    check(!entered) { "关联状态重复提交" }
                    entered = true
                    if (!isCurrent() || !matches(before)) false else compareAndSet(before, after)
                }
            } catch (error: Exception) {
                if (!isCurrent()) return false
                throw error
            }
            check(entered) { "关联状态尚未提交" }
            if (published) return isCurrent()
        }
        return false
    }
}
