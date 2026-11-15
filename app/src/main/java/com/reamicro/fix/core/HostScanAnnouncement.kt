package com.reamicro.fix.core

/** Completing an update does not acknowledge its result before the user dismisses it. */
internal object HostScanAnnouncement {
    fun pending(current: HostScanUpdateStamp, completed: HostScanUpdateStamp?,
        unacknowledged: HostScanUpdateStamp?): Boolean =
        current != completed || current == unacknowledged
}
