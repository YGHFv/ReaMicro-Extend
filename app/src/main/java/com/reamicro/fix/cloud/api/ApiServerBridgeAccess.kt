package com.reamicro.fix.cloud.api

import android.content.Context
import android.content.Intent
import android.os.Process

internal object ApiServerBridgeAccess {
    val allowedPackages = com.reamicro.fix.core.HostPackages.all + "com.reamicro.fix"

    fun authorized(uid: Int, ownUid: Int, packages: Collection<String>): Boolean =
        uid >= 0 && (uid == ownUid || packages.any { it in allowedPackages })

    fun trustedUid(context: Context, uid: Int): Boolean {
        if (uid < 0) return false
        val packages = runCatching { context.packageManager.getPackagesForUid(uid)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        return authorized(uid, Process.myUid(), packages)
    }

    fun grantVisibility(context: Context) {
        val uri = ApiServerSettingsBridgeProvider.URI.buildUpon().appendPath("bridge").build()
        for (pkg in allowedPackages - context.packageName) {
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}
