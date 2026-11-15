package io.legado.app.utils

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.platform.LegadoPlatform
import io.legado.app.utils.compress.LibArchiveUtils
import java.io.File

/**
 * Upstream archive dispatch and destination naming with the FileDoc application
 * boundary replaced by Android's File/ContentResolver descriptors.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ArchiveUtils {
    const val TEMP_FOLDER_NAME = "ArchiveTemp"
    val TEMP_PATH: String
        get() = File(LegadoPlatform.cacheDir, TEMP_FOLDER_NAME).apply { mkdirs() }.absolutePath

    fun deCompress(archiveUri: Uri, path: String = TEMP_PATH,
                   filter: ((String) -> Boolean)? = null): List<File> {
        val name = name(archiveUri)
        checkArchive(name)
        val destination = File(path, MD5Utils.md5Encode16(name)).apply { mkdirs() }
        return open(archiveUri).use { LibArchiveUtils.unArchive(it, destination, filter) }
    }

    fun deCompress(archivePath: String, path: String = TEMP_PATH,
                   filter: ((String) -> Boolean)? = null): List<File> =
        deCompress(Uri.parse(archivePath), path, filter)

    fun deCompress(archiveFile: File, path: String = TEMP_PATH,
                   filter: ((String) -> Boolean)? = null): List<File> =
        deCompress(Uri.fromFile(archiveFile), path, filter)

    fun deCompress(archiveDoc: DocumentFile, path: String = TEMP_PATH,
                   filter: ((String) -> Boolean)? = null): List<File> =
        deCompress(archiveDoc.uri, path, filter)

    fun getArchiveFilesName(fileUri: Uri, filter: ((String) -> Boolean)? = null): List<String> {
        checkArchive(name(fileUri))
        return open(fileUri).use { LibArchiveUtils.getFilesName(it, filter) }
    }

    fun isArchive(name: String): Boolean = archiveFileRegex.matches(name)

    private fun checkArchive(name: String) {
        require(isArchive(name)) { "Unexpected file suffix: Only 7z rar zip Accepted" }
    }

    private fun name(uri: Uri): String {
        if (uri.scheme == "content") {
            LegadoPlatform.context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment ?: throw IllegalArgumentException("Archive URI has no file name")
    }

    private fun open(uri: Uri): ParcelFileDescriptor =
        if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
            val file = File(uri.path ?: uri.toString())
            require(!file.isDirectory) { "Unexpected Folder input" }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            LegadoPlatform.context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open archive: $uri")
        }
}
