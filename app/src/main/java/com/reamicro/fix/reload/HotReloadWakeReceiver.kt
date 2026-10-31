package com.reamicro.fix.reload

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 宿主未声明模块包 queries 时，显式广播只唤醒桥接并授予包可见性，不直接发起重载。 */
class HotReloadWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val uri = Uri.parse("content://${HotReloadContract.AUTHORITY}/visibility")
        for (pkg in listOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix")) {
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        resultCode = Activity.RESULT_OK
    }
}
