package com.reamicro.fix.reload

/** A refused old generation may retry; an unresolved submitted request must never overlap. */
internal class UpgradeReloadWindow(
    private val clock: () -> Long,
    private val durationMillis: Long = 60_000L,
    private val retryDelayMillis: Long = 2_000L,
) {
    private val started = clock()
    private var nextAttempt = started
    var inFlight = false
        private set
    var finished = false
        private set
    var attempts = 0
        private set

    fun alive(): Boolean = !finished && (inFlight || clock() - started < durationMillis)
    fun ready(): Boolean = alive() && !inFlight && clock() >= nextAttempt

    fun submit(): Boolean {
        if (!ready()) return false
        inFlight = true
        attempts++
        return true
    }

    fun defer() {
        check(!inFlight) { "Cannot defer a submitted upgrade" }
        if (!finished) nextAttempt = clock() + retryDelayMillis
    }

    fun refused() {
        check(inFlight) { "No upgrade submission to refuse" }
        inFlight = false
        defer()
    }

    fun finish() {
        inFlight = false
        finished = true
    }
}
