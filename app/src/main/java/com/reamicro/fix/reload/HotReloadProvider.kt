package com.reamicro.fix.reload

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

class HotReloadProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val caller = callingPackage
        check(HotReloadContract.authorized(caller,
            context.packageManager.getPackagesForUid(uid)?.toList().orEmpty())) { "Unauthorized reload caller" }
        return when (method) {
            HotReloadContract.REQUEST -> {
                check(extras?.getBoolean(HotReloadContract.IN_PLACE) == true) { "Update the host generation before reloading" }
                HotReloadService.request(uid, pid, requireNotNull(caller))
            }
            HotReloadContract.STATUS -> HotReloadService.status(arg.orEmpty(), uid, pid)
            else -> throw IllegalArgumentException("Unknown reload operation")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
