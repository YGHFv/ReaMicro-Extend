package com.reamicro.fix.reload

/** 先封住新任务入口，再判断是否空闲；拒绝时必须恢复旧代际。 */
internal class ReloadGate {
    enum class State { ACTIVE, CHECKING, RETIRED }
    @Volatile var state = State.ACTIVE
        private set
    private var tasks = 0

    @Synchronized fun enter(): Boolean {
        if (state != State.ACTIVE) return false
        tasks++
        return true
    }

    @Synchronized fun leave() {
        check(tasks > 0)
        tasks--
    }

    @Synchronized fun begin(): Boolean {
        if (state != State.ACTIVE || tasks != 0) return false
        state = State.CHECKING
        return true
    }

    @Synchronized fun reject() {
        check(state == State.CHECKING)
        state = State.ACTIVE
    }

    @Synchronized fun retire() {
        check(state == State.CHECKING && tasks == 0)
        state = State.RETIRED
    }
}
