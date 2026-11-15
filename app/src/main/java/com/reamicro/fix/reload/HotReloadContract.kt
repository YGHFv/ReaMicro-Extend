package com.reamicro.fix.reload

import com.reamicro.fix.core.HostPackages

internal object HotReloadContract {
    const val AUTHORITY = "com.reamicro.fix.hot-reload"
    const val REQUEST = "request"
    const val STATUS = "status"
    const val ID = "request_id"
    const val IN_PLACE = "in_place"
    const val PACKAGE_UPGRADE = "package_upgrade"
    const val MESSAGE = "message"
    const val STATE = "state"
    const val PENDING = "pending"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"

    fun authorized(packageName: String?, packagesForUid: List<String>): Boolean =
        HostPackages.contains(packageName) &&
            packageName in packagesForUid
}
