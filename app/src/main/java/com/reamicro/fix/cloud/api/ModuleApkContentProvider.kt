package com.reamicro.fix.cloud.api

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** 给系统 APK 安装器提供受限的 content:// APK URI。 */
class ModuleApkContentProvider : android.content.ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "只允许读取模块 APK" }
        val file = apkFile(uri)
        require(file.isFile) { "模块 APK 不存在" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = apkFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun apkFile(uri: Uri): File {
        val context = requireNotNull(context)
        require(uri.authority == AUTHORITY)
        val name = uri.lastPathSegment.orEmpty()
        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
        val root = File(context.cacheDir, MODULE_APK_DIR).canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root)
        return file
    }

    companion object {
        const val AUTHORITY = "com.reamicro.fix.module-apk"
        const val MODULE_APK_DIR = "module-updates"
    }
}
