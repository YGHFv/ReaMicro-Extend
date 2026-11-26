package com.reamicro.fix.notification

/** Only the module publishes. Buffer the latest event while its first receipt is pending. */
internal class OnlineNotificationDelivery {
    private class Event(val module: (((Boolean) -> Unit)?) -> Boolean)
    private var awaiting: Event? = null
    private var latest: Event? = null
    private var confirmed = false

    @Synchronized
    fun publish(module: (((Boolean) -> Unit)?) -> Boolean): Boolean {
        val event = Event(module)
        if (awaiting != null) {
            latest = event
            return true
        }
        if (confirmed) {
            val submitted = module(null)
            if (!submitted) confirmed = false
            return submitted
        }
        awaiting = event
        val submitted = module { posted -> confirm(event, posted) }
        if (!submitted) confirm(event, false)
        return submitted
    }

    @Synchronized
    private fun confirm(first: Event, posted: Boolean) {
        if (awaiting !== first) return
        awaiting = null
        confirmed = posted
        val event = latest
        latest = null
        if (event != null) publish(event.module)
    }
}
