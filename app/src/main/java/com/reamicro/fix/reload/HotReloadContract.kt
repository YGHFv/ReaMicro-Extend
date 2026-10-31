package com.reamicro.fix.reload

internal object HotReloadContract {
    const val AUTHORITY = "com.reamicro.fix.hot-reload"
    const val REQUEST = "request"
    const val STATUS = "status"
    const val ID = "request_id"
    const val IN_PLACE = "in_place"
    const val MESSAGE = "message"
    const val STATE = "state"
    const val PENDING = "pending"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"

    fun authorized(packageName: String?, packagesForUid: List<String>): Boolean =
        packageName in setOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix") &&
            packageName in packagesForUid
}
