package com.reamicro.fix.reload

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal

/** 取消信号覆盖提供方查询和游标读取，游标始终在同一调用内关闭。 */
internal inline fun <T> ContentResolver.cancellableQuery(
    uri: Uri,
    projection: Array<String>,
    read: (Cursor?) -> T,
): T {
    TaskCancellation.checkCurrent()
    val signal = CancellationSignal()
    val registration = TaskCancellation.current()?.register(signal::cancel)
    return try {
        query(uri, projection, null, null, null, signal).use { cursor ->
            TaskCancellation.checkCurrent()
            read(cursor).also { TaskCancellation.checkCurrent() }
        }
    } finally {
        registration?.close()
    }
}
