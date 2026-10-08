package com.reamicro.fix.hook

internal data class LocalAutomationRequest(val accountId: String, val generation: Long)
internal class LocalAutomationRequestGate {
    private var generation = 0L
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun begin(accountId: String) = LocalAutomationRequest(accountId, ++generation)
    @Synchronized fun accepts(request: LocalAutomationRequest, currentAccount: String): Boolean =
        request.generation == generation && request.accountId == currentAccount
}
