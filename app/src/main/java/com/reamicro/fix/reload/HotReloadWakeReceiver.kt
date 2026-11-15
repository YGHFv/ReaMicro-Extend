package com.reamicro.fix.reload

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.reamicro.fix.core.HostPackages

/** 宿主未声明模块包 queries 时，显式广播只唤醒桥接并授予包可见性，不直接发起重载。 */
class HotReloadWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val uri = Uri.parse("content://${HotReloadContract.AUTHORITY}/visibility")
        val granted = HostPackages.all.map { packageName ->
            runCatching {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure {
                android.util.Log.e("ReaMicroHotReload", "Cannot grant visibility to $packageName", it)
            }.isSuccess
        }.any { it }
        resultCode = if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED
    }
}
