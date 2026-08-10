package com.reamicro.fix.hook.webdav

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.Toast
import android.webkit.JavascriptInterface
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.webdav.OnlineBodyMarkup
import com.reamicro.fix.settings.OnlineEpubHeaderScope
import de.robv.android.xposed.XposedBridge
import java.io.File
import com.reamicro.fix.hook.webdav.*

// 从 WebDavDriveHook 提升出来的嵌套类型。
//
// 拆分成同包扩展函数后，这些类型要在多个文件里出现；提升到子包顶层配合包级
// star import，引用点无需加限定名。inner class 需要外部实例，仍留在原类里。
internal data class OnlineSearchRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val charset: String = "UTF-8",
    val headers: Map<String, String> = emptyMap(),
)

/** 一张要嵌入 EPUB 的正文插图。 */
internal data class OnlineContentImage(
    val url: String,
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * 扫描所有章节里的插图标记，逐张下载并分配 EPUB 内文件名。下载失败的图片会被跳过（正文里回退成远程
 * `<img>`），不影响其余章节与图片，保证“下载时自动下载插图”尽量成功且不阻断整本导入。
 */

internal data class OnlineHttpResponse(
    val url: String,
    val body: String,
)

internal data class OnlineTocSnapshot(
    val detail: OnlineHttpResponse,
    val toc: OnlineHttpResponse,
    val chapters: List<OnlineChapter>,
)

internal data class OnlineSearchGroup(
    val source: OnlineSourceEntry,
    val query: String,
    val results: List<OnlineBookSearchResult>,
    val error: String,
)

internal data class OnlineBookSearchResult(
    val sourceName: String,
    val name: String,
    val author: String,
    val coverUrl: String,
    val detailUrl: String,
    val intro: String,
    val chapterCount: Int = 0,
    val status: String = "",
    val wordCount: String = "",
    val updateTime: String = "",
    val platformName: String = "",
)

internal data class OnlineResultMetadata(
    val status: String = "",
    val wordCount: String = "",
    val updateTime: String = "",
    val chapterCount: Int = 0,
    val platformName: String = "",
)

internal data class OnlineDownloadTarget(
    var source: OnlineSourceEntry,
    val query: String,
    var result: OnlineBookSearchResult,
)

internal data class OnlineImportedBookSourceInfo(
    val sourceId: String,
    val sourceName: String,
    val detailUrl: String,
    val source: OnlineSourceEntry?,
)

internal data class OnlineCompletionEpubMetadata(
    val sourceId: String = "",
    val sourceName: String = "",
    val detailUrl: String = "",
) {
    val hasMarker: Boolean
        get() = sourceId.isNotBlank() || sourceName.isNotBlank() || detailUrl.isNotBlank()
}

internal data class OnlineChapterUpdateResult(
    val added: Int,
    val failed: Int,
    val retried: Int = 0,
    val styled: Boolean = false,
)

internal data class OnlineFailedRetryResult(
    val retried: Int,
    val failed: Int,
    val changed: Boolean,
    val attempted: Int,
)

internal data class OnlineCompletionImportResult(
    val imported: Boolean,
    val bookDir: File?,
)

/** 一卷在目录中的位置：order 用于卷首页文件名，startIndex 为该卷首章下标。 */
internal data class OnlineVolumeSegment(
    val order: Int,
    val title: String,
    val startIndex: Int,
)

/**
 * 成书时随样式一起写入的装饰资源。
 *
 * 分割装饰图与头图都是全书一份，这里只带相对 href 与套用范围，避免把设置对象透到每个渲染函数。
 */

internal data class OnlineEpubDecor(
    val dividerImageHref: String? = null,
    val headerImageHref: String? = null,
    val headerScope: OnlineEpubHeaderScope = OnlineEpubHeaderScope.Off,
    /** 选中分割样式自带的正文结构，为空时用默认的 `p.te-divider-line`。 */
    val transitionMarkup: String = "",
) {
    fun headerHtml(isVolumePage: Boolean, isVolumeFirstChapter: Boolean): String {
        val href = headerImageHref ?: return ""
        val applies = when (headerScope) {
            OnlineEpubHeaderScope.Off -> false
            OnlineEpubHeaderScope.EveryChapter -> !isVolumePage
            OnlineEpubHeaderScope.VolumePage -> isVolumePage
            OnlineEpubHeaderScope.VolumeFirstChapter -> isVolumePage || isVolumeFirstChapter
        }
        return if (applies) OnlineBodyMarkup.header(href) else ""
    }
}

internal data class OnlineChapter(
    val title: String,
    val url: String,
    val volumeTitle: String = "",
    val level: Int = 0,
)

internal data class OnlineFailedChapter(
    val index: Int,
    val title: String,
    val url: String,
    val volumeTitle: String = "",
    val level: Int = 0,
    val attempts: Int = 0,
    val message: String = "",
    val updatedAt: Long = 0L,
    val sourceId: String = "",
    val detailUrl: String = "",
    val href: String = "",
)

internal data class OnlineBinaryPayload(
    val bytes: ByteArray,
    val mimeType: String,
    val url: String,
)

internal data class WebDavEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val updatedAt: Long,
    val rawHref: String = "",
)

internal data class LocalLibraryEntry(
    val name: String,
    val path: String,
    val treeUri: String,
    val documentId: String,
    val isDirectory: Boolean,
    val size: Long,
    val updatedAt: Long,
)

internal data class LocalLibraryPath(
    val treeUri: String,
    val documentId: String,
)

internal data class LocalLibraryListCache(
    val entries: List<LocalLibraryEntry>,
    val createdAtMs: Long,
)

internal data class LocalLibrarySearchIndex(
    val rootsKey: String,
    val entries: List<LocalLibraryEntry>,
    val createdAtMs: Long,
    val complete: Boolean,
)

internal class WebDavBackButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 38, 40)
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iconSize = dp(32).toFloat()
        val scale = iconSize / 24f
        val dx = (width - iconSize) / 2f
        val dy = (height - iconSize) / 2f
        path.reset()
        path.moveTo(20f, 11f)
        path.lineTo(7.83f, 11f)
        path.lineTo(13.42f, 5.41f)
        path.lineTo(12f, 4f)
        path.lineTo(4f, 12f)
        path.lineTo(12f, 20f)
        path.lineTo(13.42f, 18.59f)
        path.lineTo(7.83f, 13f)
        path.lineTo(20f, 13f)
        path.close()
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal class WebDavLogoView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(75, 175, 167)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)

        paint.color = Color.WHITE
        path.reset()
        path.moveTo(w * 0.28f, h * 0.55f)
        path.cubicTo(w * 0.28f, h * 0.45f, w * 0.38f, h * 0.38f, w * 0.49f, h * 0.41f)
        path.cubicTo(w * 0.54f, h * 0.31f, w * 0.68f, h * 0.28f, w * 0.77f, h * 0.38f)
        path.cubicTo(w * 0.87f, h * 0.39f, w * 0.94f, h * 0.47f, w * 0.94f, h * 0.57f)
        path.cubicTo(w * 0.94f, h * 0.68f, w * 0.85f, h * 0.76f, w * 0.74f, h * 0.76f)
        path.lineTo(w * 0.34f, h * 0.76f)
        path.cubicTo(w * 0.25f, h * 0.76f, w * 0.20f, h * 0.67f, w * 0.28f, h * 0.55f)
        canvas.drawPath(path, paint)

        paint.alpha = 214
        rect.set(w * 0.26f, h * 0.82f, w * 0.74f, h * 0.88f)
        canvas.drawRoundRect(rect, dp(2).toFloat(), dp(2).toFloat(), paint)
        paint.alpha = 255
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal class WebDavEmptyView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(243, 246, 247)
        rect.set(w * 0.17f, h * 0.27f, w * 0.83f, h * 0.86f)
        canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), paint)

        paint.color = Color.rgb(225, 232, 234)
        rect.set(w * 0.25f, h * 0.18f, w * 0.75f, h * 0.38f)
        canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)

        paint.color = Color.rgb(75, 175, 167)
        rect.set(w * 0.36f, h * 0.45f, w * 0.64f, h * 0.68f)
        canvas.drawRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), paint)

        paint.color = Color.WHITE
        path.reset()
        path.moveTo(w * 0.43f, h * 0.57f)
        path.cubicTo(w * 0.43f, h * 0.51f, w * 0.49f, h * 0.47f, w * 0.55f, h * 0.49f)
        path.cubicTo(w * 0.57f, h * 0.45f, w * 0.63f, h * 0.43f, w * 0.68f, h * 0.47f)
        path.cubicTo(w * 0.74f, h * 0.47f, w * 0.78f, h * 0.51f, w * 0.78f, h * 0.57f)
        path.cubicTo(w * 0.78f, h * 0.63f, w * 0.73f, h * 0.67f, w * 0.67f, h * 0.67f)
        path.lineTo(w * 0.47f, h * 0.67f)
        path.cubicTo(w * 0.44f, h * 0.67f, w * 0.40f, h * 0.63f, w * 0.43f, h * 0.57f)
        canvas.drawPath(path, paint)

        paint.color = Color.rgb(214, 222, 224)
        rect.set(w * 0.31f, h * 0.76f, w * 0.69f, h * 0.79f)
        canvas.drawRoundRect(rect, dp(2).toFloat(), dp(2).toFloat(), paint)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal class WebDavLoginBridge(
    private val activity: Activity,
    private val dialog: Dialog,
    private val prefs: android.content.SharedPreferences,
    private val closeWithSlide: () -> Unit,
) {
    @JavascriptInterface
    fun back() {
        activity.runOnUiThread {
            closeWithSlide()
        }
    }

    @JavascriptInterface
    fun closeNow() {
        activity.runOnUiThread {
            dialog.dismiss()
        }
    }

    @JavascriptInterface
    fun save(rawUrl: String?, rawUsername: String?, rawPassword: String?) {
        val url = normalizeServerUrl(rawUrl.orEmpty())
        val username = rawUsername.orEmpty().trim()
        val password = rawPassword.orEmpty()
        activity.runOnUiThread {
            when {
                url.isBlank() -> activity.toast("请输入服务器地址")
                username.isBlank() -> activity.toast("请输入账号")
                password.isBlank() -> activity.toast("请输入密码")
                else -> {
                    prefs.edit()
                        .putString(KEY_URL, url)
                        .putString(KEY_USERNAME, username)
                        .putString(KEY_PASSWORD, password)
                        .putString(KEY_BROWSE_DIR, DEFAULT_DIR)
                        .putBoolean(KEY_AUTHORIZED, true)
                        .apply()
                    XposedBridge.log("$LOG_PREFIX WebDAV login saved: ${url.redactWebDavUrl()}")
                    activity.toast("WebDAV 已保存")
                    dialog.dismiss()
                }
            }
        }
    }

    private fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun Activity.toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun String.redactWebDavUrl(): String =
        replace(Regex("""//([^/@]+)@"""), "//***@")
}
