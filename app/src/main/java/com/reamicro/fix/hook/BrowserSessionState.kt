package com.reamicro.fix.hook

/** A document result belongs to one navigation and may win against cancellation only once. */
internal class BrowserSessionState {
    private var terminal = false
    private var document = 0L
    private var cleanupClaimed = false

    @Synchronized fun active(): Boolean = !terminal
    @Synchronized fun navigated(): Long? = if (terminal) null else ++document
    @Synchronized fun currentDocument(): Long = document
    @Synchronized fun accepts(epoch: Long): Boolean = !terminal && document == epoch

    @Synchronized fun finish(epoch: Long? = null): Boolean {
        if (terminal || (epoch != null && document != epoch)) return false
        terminal = true
        return true
    }

    @Synchronized fun claimCleanup(): Boolean {
        if (cleanupClaimed) return false
        cleanupClaimed = true
        terminal = true
        return true
    }
}

/** Several native dialogs may cover one page; only the final release resumes it. */
internal class BrowserCoverState {
    private var depth = 0
    private var retired = false
    @Synchronized fun covered(): Boolean = depth > 0
    @Synchronized fun cover(): Boolean {
        if (retired) return false
        depth++
        return depth == 1
    }
    @Synchronized fun uncover(): Boolean {
        if (retired || depth == 0) return false
        depth--
        return depth == 0
    }
    @Synchronized fun retire() { retired = true; depth = 0 }
}
