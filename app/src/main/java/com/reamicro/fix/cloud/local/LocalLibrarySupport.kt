package com.reamicro.fix.cloud.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.R
import com.reamicro.fix.webdav.ImportLocalLibraryRowContext
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.online.download.importCacheFile
import com.reamicro.fix.online.download.enqueueNativeImport

// 本地书库（SAF 文档树）的纯逻辑部分。
//
// 路径编解码、条目排序、可读路径展示、书籍文件类型判定。
//
// 这些函数不依赖 hook 实例——由 tools/find-pure-functions.mjs 编译验证。
internal fun syntheticLocalLibraryBookEntry(path: String): LocalLibraryEntry =
    LocalLibraryEntry(
        name = path.substringAfterLast(':').decodeLocalPathPart().substringAfterLast('/').ifBlank { LOCAL_LIBRARY_TITLE },
        path = path,
        treeUri = "",
        documentId = "",
        isDirectory = false,
        size = 0L,
        updatedAt = System.currentTimeMillis(),
    )

internal fun localLibraryBookUrl(path: String): String =
    LOCAL_LIBRARY_SOURCE_PREFIX + path

internal fun localLibraryPrefs(context: Context?): android.content.SharedPreferences? {
    val appContext = context ?: runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()?.applicationContext
    return appContext?.getSharedPreferences(LOCAL_LIBRARY_PREFS, Context.MODE_PRIVATE)
}

internal fun localLibraryPathArg(path: String): String =
    path.ifBlank { LOCAL_LIBRARY_ROOT_PATH }.let {
        if (it == "root" || it == "/") LOCAL_LIBRARY_ROOT_PATH else it
    }

internal fun localLibraryReadablePath(entry: LocalLibraryEntry): String =
    localLibraryReadablePath(entry.treeUri, entry.documentId).ifBlank { entry.name }

internal fun localLibraryReadablePath(treeUri: String, documentId: String): String {
    val cleanId = documentId.replace('\\', '/')
    return when {
        cleanId.startsWith("primary:", ignoreCase = true) ->
            "0/" + cleanId.substringAfter(':').trim('/').ifBlank { "" }
        cleanId.startsWith("home:", ignoreCase = true) ->
            "/Documents/" + cleanId.substringAfter(':').trim('/').ifBlank { "" }
        cleanId.contains(':') ->
            "/" + cleanId.substringAfter(':').trim('/').ifBlank { cleanId.substringBefore(':') }
        cleanId.isNotBlank() -> "/$cleanId"
        else -> Uri.parse(treeUri).lastPathSegment.orEmpty()
    }.replace("//", "/").trimEnd('/').let { path ->
        if (path == "0") "0/" else path.ifBlank { "/" }
    }
}

internal fun queryDocumentEntry(context: Context, documentUri: Uri, treeUri: String, documentId: String): LocalLibraryEntry? {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    return runCatching {
        context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val mime = cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
            LocalLibraryEntry(
                name = name.ifBlank { LOCAL_LIBRARY_TITLE },
                path = encodeLocalLibraryPath(treeUri, documentId),
                treeUri = treeUri,
                documentId = documentId,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                size = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)),
                updatedAt = cursor.longAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    .takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }
    }.getOrNull()
}

internal fun queryDocumentName(context: Context, documentUri: Uri): String =
    runCatching {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.stringAt(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) else ""
        }.orEmpty()
    }.getOrDefault("")

internal fun encodeLocalLibraryPath(treeUri: String, documentId: String): String =
    "$LOCAL_LIBRARY_PATH_PREFIX${treeUri.localPathPart()}:${documentId.localPathPart()}"

internal fun decodeLocalLibraryPath(path: String): LocalLibraryPath? {
    if (!path.startsWith(LOCAL_LIBRARY_PATH_PREFIX)) return null
    val body = path.removePrefix(LOCAL_LIBRARY_PATH_PREFIX)
    val parts = body.split(':', limit = 2)
    if (parts.size != 2) return null
    return LocalLibraryPath(
        treeUri = parts[0].decodeLocalPathPart(),
        documentId = parts[1].decodeLocalPathPart(),
    )
}

internal fun localDocumentUri(entry: LocalLibraryEntry): Uri =
    DocumentsContract.buildDocumentUriUsingTree(Uri.parse(entry.treeUri), entry.documentId)
