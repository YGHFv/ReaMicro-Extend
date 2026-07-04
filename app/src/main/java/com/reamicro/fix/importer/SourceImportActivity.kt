package com.reamicro.fix.importer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.widget.Toast

/**
 * 接管 .json 文件的「打开方式」入口。
 *
 * 模块自身进程无法直接写入阅微私有存储，因此这里只读取被打开的 JSON 内容，
 * 然后拉起阅微主进程并通过自定义 extra 传递 payload，真正的导入由阅微进程内的
 * hook（ReaMicroSettingsHook.hookExternalSourceImportIntent）完成。
 */
class SourceImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleImportIntent(intent)
        finishWithoutAnimation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
        finishWithoutAnimation()
    }

    private fun handleImportIntent(intent: Intent?) {
        val uri = resolveUri(intent)
        if (uri == null) {
            toast("未找到要导入的文件")
            return
        }
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            toast("文件读取失败或为空")
            Log.w(LOG_TAG, "failed to read import uri=$uri")
            return
        }
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "imported.json"
        launchHostImport(bytes, displayName)
    }

    private fun resolveUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
    }

    private fun launchHostImport(bytes: ByteArray, displayName: String) {
        val launch = packageManager.getLaunchIntentForPackage(HOST_PACKAGE)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(HOST_PACKAGE)
            }.let { probe ->
                packageManager.resolveActivity(probe, 0)?.activityInfo?.let { info ->
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setClassName(info.packageName, info.name)
                    }
                }
            }
        if (launch == null) {
            toast("未找到阅微，无法导入")
            Log.w(LOG_TAG, "host launch intent not found")
            return
        }
        launch.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_IMPORT_PAYLOAD, Base64.encodeToString(bytes, Base64.NO_WRAP))
            putExtra(EXTRA_IMPORT_NAME, displayName)
        }
        runCatching { startActivity(launch) }
            .onFailure {
                toast("无法启动阅微")
                Log.w(LOG_TAG, "start host failed: ${it.message}")
            }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val LOG_TAG = "ReaMicroImport"
        const val HOST_PACKAGE = "app.zhendong.reamicro"
        const val EXTRA_IMPORT_PAYLOAD = "com.reamicro.fix.import.PAYLOAD"
        const val EXTRA_IMPORT_NAME = "com.reamicro.fix.import.NAME"
    }
}
