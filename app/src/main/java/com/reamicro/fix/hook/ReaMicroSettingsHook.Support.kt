package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.association.provider.YouShuLoginCookies
import com.reamicro.fix.association.provider.YouShuLoginState
import com.reamicro.fix.association.provider.YouShuLoginStatus
import com.reamicro.fix.association.provider.YouShuLoginPolicy
import com.reamicro.fix.association.provider.YouShuWebSearchBridge
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.readerHighlightBookIdentityMatches
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.core.hostClass
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors

internal fun ReaMicroSettingsHook.readerSheetWindowInsets(composer: Any): Any =
    requireNotNull(com.reamicro.fix.core.HostHighlightUiBindings.current).sheetInsets(composer)
internal fun ReaMicroSettingsHook.closeImageVector(): Any =
    requireNotNull(requireNotNull(com.reamicro.fix.core.HostHighlightUiBindings.current).method("sheet.close").invoke(null))
internal fun ReaMicroSettingsHook.exportModuleLog() {
    val activity = activityProvider() ?: return
    runCatching {
        val fileName = "reamicro_log_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        val logText = collectModuleLog()
        writeBytesToDownloads(activity, fileName, "text/plain", logText.toByteArray(Charsets.UTF_8))
        showToast("\u5df2\u5bfc\u51fa\u65e5\u5fd7\uff1a$fileName")
    }.onFailure {
        showToast("\u5bfc\u51fa\u65e5\u5fd7\u5931\u8d25")
        HookRuntime.log("$LOG_PREFIX export module log failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.openProjectUrl() {
    val activity = activityProvider() ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
        activity.startActivity(intent)
    }.onFailure {
        showToast("\u65e0\u6cd5\u6253\u5f00\u9879\u76ee\u5730\u5740")
        HookRuntime.log("$LOG_PREFIX open project url failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.collectModuleLog(): String {
    val builder = StringBuilder()
    runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.contains("ReaMicro", ignoreCase = true)) {
                    builder.append(line).append('\n')
                }
            }
        }
        process.destroy()
    }.onFailure {
        builder.append("collect log failed: ${it.stackTraceToString()}")
    }
    if (builder.isEmpty()) {
        builder.append("\u6682\u65e0\u65e5\u5fd7\uff08logcat \u672a\u6355\u83b7\u5230\u76f8\u5173\u8bb0\u5f55\uff09")
    }
    return builder.toString()
}

internal fun ReaMicroSettingsHook.schedulePreviewRefresh(preview: WebView, refresh: () -> Unit) {
    onlineEpubPreviewHandler.removeCallbacks(pendingOnlineEpubPreviewRefresh)
    pendingOnlineEpubPreviewRefresh = object : Runnable {
        override fun run() {
            if (pendingOnlineEpubPreviewRefresh !== this) return
            pendingOnlineEpubPreviewRefresh = Runnable {}
            if (preview.isAttachedToWindow) runCatching(refresh)
        }
    }
    onlineEpubPreviewHandler.postDelayed(pendingOnlineEpubPreviewRefresh, ONLINE_EPUB_PREVIEW_DEBOUNCE_MS)
}

internal fun ReaMicroSettingsHook.onlineDownloadStyleSummary(): String {
    val settings = onlineEpubStyleSettings()
    return OnlineEpubStyleKind.entries.joinToString(" · ") { kind ->
        settings.selected(kind)?.name.orEmpty().ifBlank { kind.title }
    }
}

internal fun ReaMicroSettingsHook.parsePreviewNineSlice(value: String, bitmapWidth: Int, bitmapHeight: Int): PreviewNineSlice? {
    if (value.isBlank()) return null
    val numbers = Regex("""-?\d+(?:\.\d+)?""").findAll(value).map { it.value.toFloat() }.toList()
    if (numbers.size < 4) return null
    val scaleX = if (numbers.size >= 6 && numbers[4] > 0f) bitmapWidth / numbers[4] else 1f
    val scaleY = if (numbers.size >= 6 && numbers[5] > 0f) bitmapHeight / numbers[5] else 1f
    val left = (numbers[0] * scaleX).toInt().coerceIn(0, bitmapWidth)
    val top = (numbers[1] * scaleY).toInt().coerceIn(0, bitmapHeight)
    val right = (numbers[2] * scaleX).toInt().coerceIn(left, bitmapWidth)
    val bottom = (numbers[3] * scaleY).toInt().coerceIn(top, bitmapHeight)
    if (right <= left || bottom <= top) return null
    return PreviewNineSlice(left, top, right, bottom)
}

internal fun ReaMicroSettingsHook.drawPreviewNineSlice(canvas: Canvas, bitmap: Bitmap, slice: PreviewNineSlice, dst: Rect) {
    val srcX = intArrayOf(0, slice.left, slice.right, bitmap.width)
    val srcY = intArrayOf(0, slice.top, slice.bottom, bitmap.height)
    val leftWidth = minOf(slice.left, dst.width() / 2)
    val rightWidth = minOf(bitmap.width - slice.right, dst.width() - leftWidth)
    val topHeight = minOf(slice.top, dst.height() / 2)
    val bottomHeight = minOf(bitmap.height - slice.bottom, dst.height() - topHeight)
    val dstX = intArrayOf(dst.left, dst.left + leftWidth, dst.right - rightWidth, dst.right)
    val dstY = intArrayOf(dst.top, dst.top + topHeight, dst.bottom - bottomHeight, dst.bottom)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            val source = Rect(srcX[col], srcY[row], srcX[col + 1], srcY[row + 1])
            val target = Rect(dstX[col], dstY[row], dstX[col + 1], dstY[row + 1])
            if (source.width() > 0 && source.height() > 0 && target.width() > 0 && target.height() > 0) {
                canvas.drawBitmap(bitmap, source, target, null)
            }
        }
    }
}

internal fun ReaMicroSettingsHook.drawPreviewCoverBitmap(canvas: Canvas, bitmap: Bitmap, dst: Rect) {
    if (dst.width() <= 0 || dst.height() <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
    val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val dstAspect = dst.width().toFloat() / dst.height().toFloat()
    val src = if (srcAspect > dstAspect) {
        val width = (bitmap.height * dstAspect).toInt().coerceIn(1, bitmap.width)
        val left = (bitmap.width - width) / 2
        Rect(left, 0, left + width, bitmap.height)
    } else {
        val height = (bitmap.width / dstAspect).toInt().coerceIn(1, bitmap.height)
        val top = (bitmap.height - height) / 2
        Rect(0, top, bitmap.width, top + height)
    }
    canvas.drawBitmap(bitmap, src, dst, null)
}

internal fun ReaMicroSettingsHook.drawPreviewCssBorder(canvas: Canvas, rect: Rect, box: PreviewBoxStyle) {
    if (box.borderWidthPx <= 0f || box.borderColor == null || rect.width() <= 0 || rect.height() <= 0) return
    val inset = box.borderWidthPx / 2f
    val borderRect = RectF(rect).apply { inset(inset, inset) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = box.borderWidthPx
        color = box.borderColor
    }
    if (box.radiusPx > 0f) {
        canvas.drawRoundRect(borderRect, box.radiusPx, box.radiusPx, paint)
    } else {
        canvas.drawRect(borderRect, paint)
    }
}

internal fun ReaMicroSettingsHook.previewBoxStyle(css: String, density: Float): PreviewBoxStyle {
    val values = readerHighlightCssProperties(css)
    val padding = previewCssBoxEdges(values, "padding", density).scale(PREVIEW_REEDEN_BOX_EDGE_SCALE)
    val margin = previewCssBoxEdges(values, "margin", density).scale(PREVIEW_REEDEN_BOX_EDGE_SCALE)
    val border = values["border"].orEmpty()
    val borderWidth = values["border-width"]?.let { previewCssSizePx(it, density) }
        ?: Regex("""(\d+(?:\.\d+)?)px""").find(border)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { it * density }
        ?: 0f
    val borderColor = parseSettingsColor(values["border-color"].orEmpty())
        ?: Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""").find(border)?.value?.let(::parseSettingsColor)
    return PreviewBoxStyle(
        paddingLeftPx = padding.left,
        paddingTopPx = padding.top,
        paddingRightPx = padding.right,
        paddingBottomPx = padding.bottom,
        marginLeftPx = margin.left,
        marginTopPx = margin.top,
        marginRightPx = margin.right,
        marginBottomPx = margin.bottom,
        borderWidthPx = borderWidth,
        borderColor = borderColor,
        radiusPx = values["border-radius"]?.let { previewCssSizePx(it, density) } ?: 0f,
        backgroundSize = values["background-size"].orEmpty(),
    )
}

internal fun ReaMicroSettingsHook.previewCssBoxEdges(values: Map<String, String>, prefix: String, density: Float): PreviewBoxEdges {
    val shorthand = values[prefix].orEmpty()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { previewCssSizePx(it, density) }
    val top = values["$prefix-top"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(0) ?: 0f
    val right = values["$prefix-right"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: 0f
    val bottom = values["$prefix-bottom"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(2) ?: shorthand.getOrNull(0) ?: 0f
    val left = values["$prefix-left"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(3) ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: 0f
    return PreviewBoxEdges(left = left, top = top, right = right, bottom = bottom)
}

internal fun ReaMicroSettingsHook.previewCssSizePx(value: String, density: Float): Float {
    val trimmed = value.trim().lowercase()
    val number = Regex("""-?\d+(?:\.\d+)?""").find(trimmed)?.value?.toFloatOrNull() ?: return 0f
    return when {
        trimmed.endsWith("px") -> number * density
        trimmed.endsWith("dp") -> number * density
        trimmed.endsWith("em") -> number * 16f * density
        trimmed.endsWith("rem") -> number * 16f * density
        else -> number * density
    }.coerceAtLeast(0f)
}

internal fun ReaMicroSettingsHook.parseSettingsColor(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    runCatching { Color.parseColor(trimmed) }.getOrNull()?.let { return it }
    val groups = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)""")
        .find(trimmed)
        ?.groupValues
        ?: return null
    val alpha = groups.getOrNull(4)
        ?.takeIf { it.isNotBlank() }
        ?.toFloatOrNull()
        ?.let { if (it <= 1f) (it * 255).toInt() else it.toInt() }
        ?: 255
    return Color.argb(
        alpha.coerceIn(0, 255),
        groups[1].toInt().coerceIn(0, 255),
        groups[2].toInt().coerceIn(0, 255),
        groups[3].toInt().coerceIn(0, 255),
    )
}

internal fun ReaMicroSettingsHook.sanitizeNinePatchFileName(name: String): String {
    val cleaned = safeDownloadName(name)
        .removeSuffix(".png")
        .removeSuffix(".9")
        .trim('.', ' ')
        .ifBlank { "highlight" }
    return "$cleaned.9.png"
}

internal fun ReaMicroSettingsHook.md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5")
        .digest(bytes)
        .joinToString("") { "%02X".format(it) }

internal fun ReaMicroSettingsHook.reedenNineSlice(css: String): String =
    Regex("""(?:--)?reeden-background-nine-slice\s*:\s*([^;]+)""")
        .findAll(css)
        .lastOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()

internal fun ReaMicroSettingsHook.colorFromReedenCss(css: String): String {
    val value = Regex("""color\s*:\s*([^;]+)""").find(css)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val rgba = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""")
        .find(value)
        ?.groupValues
    if (rgba != null && rgba.size >= 4) {
        return "#%02X%02X%02X".format(
            rgba[1].toInt().coerceIn(0, 255),
            rgba[2].toInt().coerceIn(0, 255),
            rgba[3].toInt().coerceIn(0, 255),
        )
    }
    val hex = Regex("""#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})""").find(value)?.value
    return hex?.let {
        if (it.length == 9) "#${it.takeLast(6)}" else it
    }?.uppercase() ?: ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR
}

internal fun ReaMicroSettingsHook.safeDownloadName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(80)
        .ifBlank { "style" }

internal fun ReaMicroSettingsHook.exportCurrentAccountCredential() {
    val activity = activityProvider() ?: return
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching { accountController.exportCurrentCredential() }
            .onSuccess { exported ->
                activity.runOnModuleUiThread {
                    runCatching {
                        bumpAccountListVersion()
                        copyTextToClipboard(activity, "\u9605\u5fae\u8d26\u53f7\u51ed\u8bc1", exported.credential)
                        showToast("\u5df2\u590d\u5236\u8d26\u53f7 ${exported.account.accountId} \u7684\u51ed\u8bc1")
                    }.onFailure {
                        HookRuntime.log("$LOG_PREFIX account credential copy failed: ${it.stackTraceToString()}")
                        showToast(it.message ?: "\u590d\u5236\u51ed\u8bc1\u5931\u8d25")
                    }
                }
            }
            .onFailure {
                HookRuntime.log("$LOG_PREFIX account export failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u51fa\u51ed\u8bc1\u5931\u8d25")
            }
    }.start()
}

internal fun ReaMicroSettingsHook.exportAccountData(accountId: Long) {
    val activity = activityProvider() ?: return
    val progress = showExportProgress(
        activity = activity,
        title = "\u6b63\u5728\u5bfc\u51fa\u5168\u90e8\u6570\u636e",
        message = "\u6b63\u5728\u6574\u7406\u8d26\u53f7\u6570\u636e\uff0c\u8bf7\u7a0d\u5019",
    )
    com.reamicro.fix.reload.ModuleResources.thread {
        var exportedFile: File? = null
        runCatching {
            if (progress.isCancelled) error("export canceled")
            val exported = accountController.exportAccountDataBundleFile(accountId)
            exportedFile = exported.file
            if (progress.isCancelled) error("export canceled")
            progress.update("\u6b63\u5728\u5199\u5165\u4e0b\u8f7d\u76ee\u5f55")
            writeFileToDownloads(activity, exported.fileName, "application/zip", exported.file)
            if (progress.isCancelled) error("export canceled")
            exported
        }.onSuccess { exported ->
            exported.file.delete()
            activity.runOnModuleUiThread {
                progress.dismiss()
                bumpAccountListVersion()
                showToast(
                    "\u5df2\u5bfc\u51fa\u8d26\u53f7 ${exported.account.accountId} \u7684\u5168\u90e8\u6570\u636e\u5230\u4e0b\u8f7d\u76ee\u5f55\uff1a${exported.fileName}",
                )
            }
        }.onFailure {
            exportedFile?.delete()
            progress.dismiss()
            if (!progress.isCancelled) {
                HookRuntime.log("$LOG_PREFIX account data export failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u51fa\u8d26\u53f7\u6570\u636e\u5931\u8d25")
            }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.exportAllAccountData() {
    val activity = activityProvider() ?: return
    val progress = showExportProgress(
        activity = activity,
        title = "\u6b63\u5728\u5bfc\u51fa\u6240\u6709\u8d26\u53f7\u6570\u636e",
        message = "\u6b63\u5728\u9010\u4e2a\u6574\u7406\u8d26\u53f7\u6570\u636e\uff0c\u8bf7\u7a0d\u5019",
    )
    com.reamicro.fix.reload.ModuleResources.thread {
        val exportedFiles = mutableListOf<File>()
        runCatching {
            if (progress.isCancelled) error("export canceled")
            val exported = accountController.exportAllAccountDataBundleFiles()
            exportedFiles.addAll(exported.map { it.file })
            exported.forEachIndexed { index, bundle ->
                if (progress.isCancelled) error("export canceled")
                progress.update("\u6b63\u5728\u5199\u5165\u4e0b\u8f7d\u76ee\u5f55 ${index + 1}/${exported.size}")
                writeFileToDownloads(activity, bundle.fileName, "application/zip", bundle.file)
            }
            if (progress.isCancelled) error("export canceled")
            exported
        }.onSuccess { exported ->
            exported.forEach { it.file.delete() }
            activity.runOnModuleUiThread {
                progress.dismiss()
                bumpAccountListVersion()
                showToast("\u5df2\u9010\u4e2a\u5bfc\u51fa ${exported.size} \u4e2a\u8d26\u53f7\u7684\u6570\u636e\u5305")
            }
        }.onFailure {
            exportedFiles.forEach { it.delete() }
            progress.dismiss()
            if (!progress.isCancelled) {
                HookRuntime.log("$LOG_PREFIX all account data export failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u51fa\u5168\u90e8\u8d26\u53f7\u6570\u636e\u5931\u8d25")
            }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.exportAccountBooks(accountId: Long) {
    val activity = activityProvider() ?: return
    val progress = showExportProgress(
        activity = activity,
        title = "\u6b63\u5728\u5bfc\u51fa\u5168\u90e8\u56fe\u4e66",
        message = "\u6b63\u5728\u91cd\u65b0\u6253\u5305 EPUB\uff0c\u8bf7\u7a0d\u5019",
    )
    com.reamicro.fix.reload.ModuleResources.thread {
        var exportedFile: File? = null
        runCatching {
            if (progress.isCancelled) error("export canceled")
            val exported = accountController.exportAccountBooksBundleFile(accountId)
            exportedFile = exported.file
            if (progress.isCancelled) error("export canceled")
            progress.update("\u6b63\u5728\u5199\u5165\u4e0b\u8f7d\u76ee\u5f55")
            writeFileToDownloads(activity, exported.fileName, "application/zip", exported.file)
            if (progress.isCancelled) error("export canceled")
            exported
        }.onSuccess { exported ->
            exported.file.delete()
            activity.runOnModuleUiThread {
                progress.dismiss()
                showToast(
                    "\u5df2\u5bfc\u51fa\u8d26\u53f7 ${exported.account.accountId} \u7684\u5168\u90e8\u56fe\u4e66\u5230\u4e0b\u8f7d\u76ee\u5f55\uff1a${exported.fileName}",
                )
            }
        }.onFailure {
            exportedFile?.delete()
            progress.dismiss()
            if (!progress.isCancelled) {
                HookRuntime.log("$LOG_PREFIX account books export failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u51fa\u8d26\u53f7\u5168\u90e8\u56fe\u4e66\u5931\u8d25")
            }
        }
    }.start()
}

internal fun ReaMicroSettingsHook.importAccountDataFromUri(activity: Activity, uri: Uri) {
    val progress = showExportProgress(
        activity = activity,
        title = "\u6b63\u5728\u8bfb\u53d6\u6570\u636e\u5305",
        message = "\u6b63\u5728\u590d\u5236\u6570\u636e\u5305\uff0c\u8bf7\u7a0d\u5019",
    )
    com.reamicro.fix.reload.ModuleResources.thread {
        var spooled: File? = null
        runCatching {
            val target = File(activity.cacheDir, "reamicro_import_${System.currentTimeMillis()}.zip")
            spooled = target
            var copied = 0L
            activity.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    copied = input.copyTo(output)
                }
            } ?: error("\u65e0\u6cd5\u8bfb\u53d6\u8d26\u53f7\u6570\u636e\u5305")
            if (copied <= 0L) error("\u8d26\u53f7\u6570\u636e\u5305\u4e3a\u7a7a")
            if (progress.isCancelled) error("import canceled")
            progress.update("\u6b63\u5728\u6821\u9a8c\u6570\u636e\u5305")
            val displayName = queryDisplayName(activity, uri).orEmpty()
            accountController.previewAccountDataBundleFile(target, displayName)
        }.onSuccess { preview ->
            progress.dismiss()
            val spooledFile = spooled
            if (spooledFile == null || !spooledFile.isFile) {
                showToast("\u6570\u636e\u5305\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u9009\u62e9")
                return@onSuccess
            }
            activity.runOnModuleUiThread {
                confirmAccountDataImport(activity, preview, spooledFile) { confirmedFile ->
                    startAccountDataImport(activity, confirmedFile)
                }
            }
        }.onFailure {
            spooled?.delete()
            progress.dismiss()
            if (!progress.isCancelled) {
                HookRuntime.log("$LOG_PREFIX account data import failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u5165\u8d26\u53f7\u6570\u636e\u5931\u8d25")
            }
        }
    }.start()
}

// 导入会覆盖本机同一账号的数据，先展示清单让用户确认。
internal fun ReaMicroSettingsHook.confirmAccountDataImport(
    activity: Activity,
    preview: AccountCompletionController.AccountDataBundlePreview,
    file: File,
    onConfirm: (File) -> Unit,
) {
    runCatching {
        if (!file.isFile) {
            showToast("\u6570\u636e\u5305\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u9009\u62e9")
            return
        }
        val dialog = settingsDialog(activity)
        val colors = SettingsDialogColors(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "\u786e\u8ba4\u5bfc\u5165\u8d26\u53f7\u6570\u636e", colors))
        card.addView(
            settingsDialogHint(
                activity,
                buildString {
                    append("\u8d26\u53f7\uff1a")
                    append(preview.accountId)
                    if (preview.nickname.isNotBlank()) {
                        append(" ")
                        append(preview.nickname)
                    }
                    append('\n')
                    append("\u6570\u636e\u5305\uff1a")
                    append(preview.fileName)
                    append('\n')
                    append("\u56fe\u4e66\u6587\u4ef6\uff1a")
                    append(preview.fileCount)
                    append(" \u4e2a\uff0c\u89e3\u538b\u540e ")
                    append(formatDataBundleSize(preview.uncompressedBytes))
                    append('\n')
                    append("\u8bfb\u53d6\u65f6\u957f\uff1a")
                    append(
                        if (preview.exportedAt > 0L) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(preview.exportedAt))
                        } else {
                            "\u672a\u8bb0\u5f55"
                        },
                    )
                    append('\n')
                    append("\u5bfc\u5165\u5c06\u8986\u76d6\u672c\u673a\u540c\u4e00\u8d26\u53f7\u7684\u4e66\u5e93\u4e0e\u9605\u8bfb\u8bb0\u5f55\uff0c\u8bf7\u786e\u8ba4\u3002")
                    if (preview.hasLocalTasks) append("\n同时恢复此账号的本地任务配置与记录。")
                },
                colors,
            ),
        )
        val actions = settingsDialogActions(activity)
        actions.addView(
            settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral).apply {
                setOnClickListener {
                    dialog.dismiss()
                    file.delete()
                    showToast("\u5df2\u53d6\u6d88\u5bfc\u5165")
                }
            },
            settingsDialogButtonParams(activity),
        )
        actions.addView(
            settingsDialogButton(activity, "\u786e\u8ba4\u5bfc\u5165", colors, SettingsDialogButtonRole.Primary).apply {
                setOnClickListener {
                    dialog.dismiss()
                    onConfirm(file)
                }
            },
            settingsDialogButtonParams(activity),
        )
        card.addView(actions)
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX account data import confirm failed: ${it.stackTraceToString()}")
        showToast(it.message ?: "\u65e0\u6cd5\u786e\u8ba4\u5bfc\u5165")
    }
}

internal fun ReaMicroSettingsHook.startAccountDataImport(activity: Activity, file: File) {
    val progress = showExportProgress(
        activity = activity,
        title = "\u6b63\u5728\u5bfc\u5165\u8d26\u53f7\u6570\u636e",
        message = "\u6b63\u5728\u6062\u590d\u6570\u636e\uff0c\u8bf7\u7a0d\u5019",
    )
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching {
            accountController.importAccountDataBundle(file) { message -> progress.update(message) }
        }.onSuccess { imported ->
            file.delete()
            activity.runOnModuleUiThread {
                progress.dismiss()
                bumpAccountListVersion()
                restartHostAfterAccountSwitch(activity, imported.account.accountId)
            }
        }.onFailure {
            file.delete()
            progress.dismiss()
            if (!progress.isCancelled) {
                HookRuntime.log("$LOG_PREFIX account data import failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u5165\u8d26\u53f7\u6570\u636e\u5931\u8d25")
            }
        }
    }.start()
}

internal fun formatDataBundleSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${bytes} ${units[index]}"
    } else {
        String.format(Locale.US, "%.2f %s", value, units[index])
    }
}

internal fun ReaMicroSettingsHook.exportAllAccountCredentials() {
    val activity = activityProvider() ?: return
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching {
            val exported = accountController.exportAllCredentialsBundle()
            val fileName = buildAccountCredentialBundleFileName()
            writeTextToDownloads(activity, fileName, exported.content)
            fileName to exported.count
        }.onSuccess { (fileName, count) ->
            activity.runOnModuleUiThread {
                bumpAccountListVersion()
                showToast("\u5df2\u5bfc\u51fa $count \u4e2a\u8d26\u53f7\u51ed\u8bc1\u5230\u4e0b\u8f7d\u76ee\u5f55\uff1a$fileName")
            }
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX account bundle export failed: ${it.stackTraceToString()}")
            showToast(it.message ?: "\u5bfc\u51fa\u5168\u90e8\u8d26\u53f7\u51ed\u8bc1\u5931\u8d25")
        }
    }.start()
}

internal fun ReaMicroSettingsHook.importCredentialFromClipboard() {
    val activity = activityProvider() ?: return
    val raw = readClipboardText(activity).trim()
    if (raw.isBlank()) {
        showToast("\u526a\u8d34\u677f\u4e2d\u6ca1\u6709\u8d26\u53f7\u51ed\u8bc1")
        return
    }
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching { accountController.importCredentials(raw) }
            .onSuccess { result ->
                activity.runOnModuleUiThread {
                    bumpAccountListVersion()
                    val firstAccountId = result.accounts.firstOrNull()?.accountId
                    val message = if (result.count <= 1 && firstAccountId != null) {
                        "\u5df2\u5bfc\u5165\u8d26\u53f7 $firstAccountId"
                    } else {
                        "\u5df2\u5bfc\u5165 ${result.count} \u4e2a\u8d26\u53f7"
                    }
                    showToast(message)
                }
            }
            .onFailure {
                showToast(it.message ?: "\u5bfc\u5165\u51ed\u8bc1\u5931\u8d25")
            }
    }.start()
}

internal fun ReaMicroSettingsHook.importCredentialFromUri(activity: Activity, uri: Uri) {
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching {
            val raw = activity.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?.trim()
                .orEmpty()
            if (raw.isBlank()) {
                error("\u9009\u62e9\u7684\u6587\u4ef6\u4e2d\u6ca1\u6709\u8d26\u53f7\u51ed\u8bc1")
            }
            val displayName = queryDisplayName(activity, uri)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "\u51ed\u8bc1\u6587\u4ef6"
            val result = accountController.importCredentials(raw)
            displayName to result
        }.onSuccess { (displayName, result) ->
            activity.runOnModuleUiThread {
                bumpAccountListVersion()
                val firstAccountId = result.accounts.firstOrNull()?.accountId
                val message = if (result.count <= 1 && firstAccountId != null) {
                    "\u5df2\u4ece $displayName \u5bfc\u5165\u8d26\u53f7 $firstAccountId"
                } else {
                    "\u5df2\u4ece $displayName \u5bfc\u5165 ${result.count} \u4e2a\u8d26\u53f7"
                }
                showToast(message)
            }
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX account credential file import failed: ${it.stackTraceToString()}")
            showToast(it.message ?: "\u4ece\u6587\u4ef6\u5bfc\u5165\u51ed\u8bc1\u5931\u8d25")
        }
    }.start()
}

internal fun ReaMicroSettingsHook.switchToStoredAccount(accountId: Long) {
    val activity = activityProvider() ?: return
    com.reamicro.fix.reload.ModuleResources.thread {
        runCatching { accountController.switchToAccount(accountId) }
            .onSuccess { account ->
                activity.runOnModuleUiThread {
                    restartHostAfterAccountSwitch(activity, account.accountId)
                }
            }
            .onFailure {
                showToast(it.message ?: "\u5207\u6362\u8d26\u53f7\u5931\u8d25")
            }
    }.start()
}

internal fun ReaMicroSettingsHook.copyTextToClipboard(activity: Activity, label: String, text: String) {
    val clipboard = activity.getSystemService(ClipboardManager::class.java)
        ?: error("\u65e0\u6cd5\u83b7\u53d6\u526a\u8d34\u677f")
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun ReaMicroSettingsHook.readClipboardText(activity: Activity): String {
    val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return ""
    val clip = clipboard.primaryClip ?: return ""
    return clip.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
}

internal fun ReaMicroSettingsHook.restartHostAfterAccountSwitch(activity: Activity, accountId: Long) {
    showToast("\u5df2\u5207\u6362\u5230\u8d26\u53f7 $accountId\uff0c\u6b63\u5728\u5237\u65b0")
    val launchIntent = activity.packageManager?.getLaunchIntentForPackage(activity.packageName)
    if (launchIntent == null) {
        runCatching { activity.recreate() }
            .onFailure {
                HookRuntime.log("$LOG_PREFIX fallback recreate failed: ${it.stackTraceToString()}")
            }
        return
    }
    val restartIntent = launchIntent.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra("reamicro_account_switched", accountId)
    }
    runCatching {
        activity.startActivity(restartIntent)
        activity.overridePendingTransition(0, 0)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX host refresh after account switch failed: ${it.stackTraceToString()}")
        runCatching {
            activity.recreate()
        }.onFailure { fallbackError ->
            HookRuntime.log("$LOG_PREFIX fallback host refresh failed: ${fallbackError.stackTraceToString()}")
            showToast("\u8d26\u53f7\u5df2\u5207\u6362\uff0c\u8bf7\u624b\u52a8\u5237\u65b0\u6216\u91cd\u65b0\u8fdb\u5165\u9605\u5fae")
        }
    }
}

internal fun ReaMicroSettingsHook.buildAccountCredentialBundleFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    return "\u9605\u5fae\u8865\u5168\u8ba1\u5212_$timestamp.json"
}

internal fun ReaMicroSettingsHook.writeTextToDownloads(activity: Activity, displayName: String, text: String): Uri {
    return writeBytesToDownloads(activity, displayName, "application/json", text.toByteArray(Charsets.UTF_8))
}

internal fun ReaMicroSettingsHook.writeFileToDownloads(activity: Activity, displayName: String, mimeType: String, inputFile: File): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u6587\u4ef6")
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                inputFile.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: error("\u65e0\u6cd5\u5199\u5165\u4e0b\u8f7d\u6587\u4ef6")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        }.getOrElse { error ->
            resolver.delete(uri, null, null)
            throw error
        }
        return uri
    }
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
        error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u76ee\u5f55")
    }
    val outputFile = File(downloadsDir, displayName)
    inputFile.inputStream().buffered().use { input ->
        outputFile.outputStream().buffered().use { output -> input.copyTo(output) }
    }
    return Uri.fromFile(outputFile)
}

internal fun ReaMicroSettingsHook.writeBytesToDownloads(activity: Activity, displayName: String, mimeType: String, bytes: ByteArray): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u6587\u4ef6")
        runCatching {
            resolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                ?: error("\u65e0\u6cd5\u5199\u5165\u4e0b\u8f7d\u6587\u4ef6")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        }.getOrElse { error ->
            resolver.delete(uri, null, null)
            throw error
        }
        return uri
    }
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
        error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u76ee\u5f55")
    }
    val outputFile = File(downloadsDir, displayName)
    outputFile.outputStream().use { output -> output.write(bytes) }
    return Uri.fromFile(outputFile)
}

internal fun ReaMicroSettingsHook.showToast(message: String) {
    val activity = activityProvider() ?: return
    activity.runOnModuleUiThread {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}

internal fun ReaMicroSettingsHook.showExportProgress(activity: Activity, title: String, message: String): ExportProgress {
    val progress = ExportProgress()
    activity.runOnModuleUiThread {
        runCatching {
            val dp = activity.resources.displayMetrics.density
            val colors = SettingsDialogColors(activity)
            val messageView = TextView(activity).apply {
                text = message
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(colors.body)
                includeFontPadding = false
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((26 * dp).toInt(), (24 * dp).toInt(), (26 * dp).toInt(), (22 * dp).toInt())
                background = roundedRect(colors.card, 8 * dp, colors.border)
                addView(
                    ProgressBar(activity).apply {
                        isIndeterminate = true
                        ModuleDialogTheme.tintProgress(this, colors.primary)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = (16 * dp).toInt() },
                )
                addView(
                    TextView(activity).apply {
                        text = title
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setTextColor(colors.title)
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = (8 * dp).toInt() },
                )
                addView(
                    messageView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            val content = FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(
                    card,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            val dialog = Dialog(activity).apply {
                setCancelable(false)
                setContentView(content)
            }
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    progress.handleBack(activity)
                    true
                } else {
                    false
                }
            }
            dialog.window?.let { inheritScanSystemBars(it, activity) }
            dialog.show()
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            progress.attach(dialog, messageView)
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX failed to show export progress: ${it.stackTraceToString()}")
        }
    }
    return progress
}

internal fun ReaMicroSettingsHook.roundedRect(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

internal fun ReaMicroSettingsHook.roundedRect(color: Int, radius: Float, strokeColor: Int): GradientDrawable =
    roundedRect(color, radius).apply {
        setStroke(1, strokeColor)
    }

internal fun ReaMicroSettingsHook.dictionarySettingsSummary(): String {
    val preset = dictionaryPresetSelectionSummary()
    val api = dictionaryApiSelectionSummary()
    return "$preset / $api"
}

internal fun ReaMicroSettingsHook.dictionaryApiSelectionSummary(): String {
    val context = activityProvider()?.applicationContext
    val dictionarySettings = AiApiStore.dictionarySettings(context)
    val config = AiApiStore.dictionaryApi(context)
    return when {
        config == null -> "\u672a\u9009\u62e9 API"
        dictionarySettings.apiId.isBlank() -> "\u8ddf\u968f API \u914d\u7f6e\uff1a${config.displayName}"
        else -> config.displayName
    }
}

internal fun ReaMicroSettingsHook.dictionaryPresetSelectionSummary(): String {
    val context = activityProvider()?.applicationContext
    val dictionarySettings = AiApiStore.dictionarySettings(context)
    return AiApiStore.dictionaryPreset(context, dictionarySettings.presetId).name
}

internal fun ReaMicroSettingsHook.imageSettingsSummary(): String {
    val cover = imagePresetSelectionSummary(AiImagePresetTarget.Cover)
    val banner = imagePresetSelectionSummary(AiImagePresetTarget.Banner)
    val api = imageApiSelectionSummary()
    return "$cover / $banner / $api"
}

internal fun ReaMicroSettingsHook.imageApiSelectionSummary(): String {
    val context = activityProvider()?.applicationContext
    val imageSettings = AiApiStore.imageSettings(context)
    val config = AiApiStore.imageApi(context)
    return when {
        config == null -> "\u672a\u9009\u62e9 API"
        imageSettings.apiId.isBlank() -> "\u8ddf\u968f API \u914d\u7f6e\uff1a${config.displayName}"
        else -> config.displayName
    }
}

internal fun ReaMicroSettingsHook.imagePresetSelectionSummary(target: AiImagePresetTarget): String {
    val context = activityProvider()?.applicationContext
    val imageSettings = AiApiStore.imageSettings(context)
    return AiApiStore.imagePreset(context, target, imagePresetId(imageSettings, target)).name
}

internal fun ReaMicroSettingsHook.imagePresetId(settings: com.reamicro.fix.ai.AiImageSettings, target: AiImagePresetTarget): String =
    when (target) {
        AiImagePresetTarget.Cover -> settings.coverPresetId
        AiImagePresetTarget.Banner -> settings.bannerPresetId
    }

internal fun ReaMicroSettingsHook.settingsRoundedRect(fill: Int, radiusPx: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusPx.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
    }

internal fun ReaMicroSettingsHook.settingsDp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

internal fun ReaMicroSettingsHook.presetPromptPreview(prompt: String): String =
    prompt.compactOnlineSourceLine().let { line ->
        if (line.length > PRESET_PROMPT_PREVIEW_MAX_CHARS) {
            line.take(PRESET_PROMPT_PREVIEW_MAX_CHARS).trimEnd() + "..."
        } else {
            line
        }
    }

internal fun ReaMicroSettingsHook.settingsColorWithAlpha(color: Int, alpha: Float): Int =
    Color.argb(
        (255 * alpha.coerceIn(0f, 1f)).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

internal fun ReaMicroSettingsHook.setRotationEnabled(
    enabled: Boolean,
    currentState: RotationUiState,
    updateRow: (String, Boolean) -> Unit,
): Boolean {
    settings.setRotationEnabled(enabled)
    updateRow(ModuleSettings.KEY_ROTATION_ENABLED, enabled)
    if (enabled) {
        val nextState = if (
            currentState.autoEnabled ||
            currentState.portraitLockEnabled ||
            currentState.landscapeLockEnabled
        ) {
            currentState
        } else {
            currentState.copy(autoEnabled = true)
        }
        rotationUiState = nextState
        if (nextState.autoEnabled && !currentState.autoEnabled) {
            settings.setRotationAutoEnabled(true)
        }
    }
    applyCurrentRotation()
    return enabled
}

internal fun ReaMicroSettingsHook.setRotationBaseEnabled(
    key: String,
    enabled: Boolean,
    updateRow: (String, Boolean) -> Unit,
): Boolean {
    suppressRotationSnapshotSync()
    val nextState = currentRotationUiState().withBase(key, enabled)
    rotationUiState = nextState
    when (key) {
        ModuleSettings.KEY_ROTATION_AUTO_ENABLED -> settings.setRotationAutoEnabled(enabled)
        ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED -> settings.setRotationPortraitLockEnabled(enabled)
        ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED -> settings.setRotationLandscapeLockEnabled(enabled)
        else -> return false
    }
    ModuleSettings.ROTATION_BASE_KEYS.forEach { baseKey ->
        updateRow(baseKey, nextState.isEnabled(baseKey))
    }
    applyCurrentRotation()
    return nextState.isEnabled(key)
}

internal fun ReaMicroSettingsHook.suppressRotationSnapshotSync() {
    suppressRotationSnapshotSyncUntilMs = System.currentTimeMillis() + ROTATION_SNAPSHOT_SYNC_SUPPRESS_MS
}

internal fun ReaMicroSettingsHook.currentRotationUiState(): RotationUiState =
    rotationUiState ?: RotationUiState.from(settings.snapshot())

internal fun ReaMicroSettingsHook.currentRotationDisplayState(): RotationUiState {
    val snapshot = settings.snapshot()
    return RotationUiState.fromActivityOrientation(
        activityProvider()?.requestedOrientation,
        snapshot.rotationReverseEnabled,
    ) ?: rotationUiState ?: RotationUiState.from(snapshot)
}

internal fun ReaMicroSettingsHook.applyCurrentRotation() {
    val activity = activityProvider() ?: return
    RotationOrientationController.apply(activity, settings.snapshot())
}

internal fun ReaMicroSettingsHook.hasManualEditFeature(): Boolean =
    com.reamicro.fix.core.HostManualEditBindings.publisher != null

internal fun ReaMicroSettingsHook.applyYouShuSourceEnabled(groupId: String, updateChecked: (Boolean) -> Unit) {
    settings.setAssociationSearchSourceEnabled(groupId, true)
    updateChecked(true)
    activityProvider()?.let { activity ->
        activity.runOnModuleUiThread {
            Toast.makeText(activity, "优书网已登录并启用", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun ReaMicroSettingsHook.verifyYouShuLoginAsync(
    attempts: Int = YOUSHU_LOGIN_VERIFY_ATTEMPTS,
    allowWebView: Boolean = true,
    cancellation: com.reamicro.fix.reload.TaskCancellation = com.reamicro.fix.reload.TaskCancellation(),
    isCurrent: () -> Boolean = { true },
    onResult: (YouShuLoginStatus) -> Unit,
) {
    val activity = activityProvider() ?: run { onResult(YouShuLoginStatus.UNKNOWN); return }
    val identity = runCatching { AccountHostRuntime.storageSnapshot(activity) }.getOrNull()
    val revision = YouShuWebSearchBridge.executionRevision
    val current = {
        !cancellation.isCancelled && YouShuWebSearchBridge.isExecutionCurrent(revision) &&
            ReaMicroSettingsHook.activeInstanceOrNull() === this && activityProvider() === activity &&
            !activity.isFinishing && !activity.isDestroyed && settings.snapshot().moduleEnabled &&
            runCatching {
                identity?.let { AccountHostRuntime.isCurrentStorage(it, activity) }
                    ?: (AccountHostRuntime.storageSnapshot(activity) == null)
            }.getOrDefault(false) && isCurrent()
    }
    com.reamicro.fix.reload.ModuleResources.thread {
        var status = YouShuLoginStatus.UNKNOWN
        val worker = Thread.currentThread()
        val threadCancellation = cancellation.register(worker::interrupt)
        runCatching {
            cancellation.run {
                val maxAttempts = attempts.coerceAtLeast(1)
                for (attempt in 0 until maxAttempts) {
                    if (!current()) return@run
                    YouShuLoginCookies.persistSession()
                    val cookieState = YouShuLoginState.checkWithCookie()
                    status = if (allowWebView && cookieState != YouShuLoginStatus.VALID) {
                        YouShuLoginPolicy.combine(listOf(cookieState, YouShuLoginState.checkWithWebView()))
                    } else cookieState
                    if (status != YouShuLoginStatus.UNKNOWN) break
                    if (attempt < maxAttempts - 1) Thread.sleep(YOUSHU_LOGIN_VERIFY_DELAY_MS)
                }
            }
        }.onFailure {
            if (!cancellation.isCancelled && !Thread.currentThread().isInterrupted) {
                HookRuntime.log("$LOG_PREFIX YouShu login verify unavailable: ${it.javaClass.simpleName}")
            }
            status = YouShuLoginStatus.UNKNOWN
        }.also {
            threadCancellation.close()
        }
        if (current()) {
            HookRuntime.log("$LOG_PREFIX YouShu login verify result=$status")
            activity.runOnModuleUiThread { if (current()) onResult(status) }
        }
    }.apply {
        name = "ReaMicroYouShuLoginVerify"
        isDaemon = true
        start()
    }
}

internal fun ReaMicroSettingsHook.openYouShuLoginPage(onLoginStateResolved: (Boolean) -> Unit) {
    val activity = activityProvider()
    if (activity == null) {
        onLoginStateResolved(false)
        return
    }
    activity.runOnModuleUiThread {
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        val resolve: (Boolean) -> Unit = { value ->
            if (resolved.compareAndSet(false, true)) onLoginStateResolved(value)
        }
        runCatching {
            HostYouShuLoginDialog(activity,
                isCurrent = { activityProvider() === activity && settings.snapshot().moduleEnabled },
                onResult = resolve).show()
        }.onFailure {
            HookRuntime.log("$LOG_PREFIX failed to open YouShu login WebView: ${it.stackTraceToString()}")
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YOUSHU_LOGIN_URL)))
            }.onFailure { error ->
                HookRuntime.log("$LOG_PREFIX failed to open YouShu login page: ${error.stackTraceToString()}")
            }
            resolve(false)
        }
    }
}

internal fun ReaMicroSettingsHook.readerSelectionMenuSettingsSummary(snapshot: ModuleSettingsSnapshot): String {
    val enabled = listOf(
        snapshot.readerCompactSelectionMenuEnabled,
        snapshot.readerEditOverwriteEnabled,
        snapshot.readerDictionaryEnabled,
        snapshot.readerSelectionHighlightEnabled,
    ).count { it }
    return "$enabled / 4 \u9879\u5df2\u542f\u7528"
}

internal fun ReaMicroSettingsHook.displayReaderBookTitle(bookKey: String, storedTitle: String): String {
    val currentBookKey = ReaderHighlightBookContext.bookKey
    val currentBookTitle = ReaderHighlightBookContext.bookTitle
    val currentMatches = readerHighlightBookIdentityMatches(
        storedBookKey = bookKey,
        storedBookTitle = storedTitle,
        currentBookKey = currentBookKey,
        currentBookTitle = currentBookTitle,
    )
    val candidates = buildList {
        if (currentMatches) add(currentBookTitle)
        add(storedTitle)
        addAll(bookKey.split('|').asReversed())
    }
    return candidates
        .asSequence()
        .map(::cleanReaderBookTitle)
        .firstOrNull { it.isNotBlank() && !isGenericReaderBookTitle(it) && !isInternalReaderBookTitle(it) }
        ?: "\u672c\u4e66"
}

internal fun ReaMicroSettingsHook.cleanReaderBookTitle(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return trimmed
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .removeSuffix(".epub")
        .removeSuffix(".EPUB")
        .trim()
}

internal fun ReaMicroSettingsHook.isInternalReaderBookTitle(value: String): Boolean =
    value.contains('|') ||
        value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
        value.matches(Regex("^[0-9a-fA-F]{16,}$"))

internal fun ReaMicroSettingsHook.isGenericReaderBookTitle(value: String): Boolean =
    value == "\u672c\u4e66" || value == "\u56fe\u4e66"

internal fun ReaMicroSettingsHook.consumeExternalSourceImportIntent(activity: Activity, intent: Intent?) {
    intent ?: return
    val payload = intent.getStringExtra(EXTRA_IMPORT_PAYLOAD) ?: return

    intent.removeExtra(EXTRA_IMPORT_PAYLOAD)
    val displayName = intent.getStringExtra(EXTRA_IMPORT_NAME) ?: "imported.json"
    val bytes = runCatching { Base64.decode(payload, Base64.NO_WRAP) }.getOrNull()
    if (bytes == null || bytes.isEmpty()) {
        showToast("导入内容为空")
        return
    }
    val token = "external:$displayName:${bytes.size}"
    if (token == lastExternalImportToken &&
        System.currentTimeMillis() - lastExternalImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS
    ) {
        return
    }
    lastExternalImportToken = token
    lastExternalImportAtMs = System.currentTimeMillis()
    importExternalJson(activity, bytes, displayName)
}

internal fun ReaMicroSettingsHook.importExternalJson(activity: Activity, bytes: ByteArray, displayName: String) {
    when (detectExternalImportKind(bytes)) {
        ExternalImportKind.HIGHLIGHT -> importExternalHighlight(activity, bytes)
        ExternalImportKind.ONLINE_SOURCE -> importExternalOnlineSource(activity, bytes, displayName)
    }
}

internal fun ReaMicroSettingsHook.detectExternalImportKind(bytes: ByteArray): ExternalImportKind {

    if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 1.toByte()
    ) {
        return ExternalImportKind.HIGHLIGHT
    }
    val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
    val json = runCatching {
        when {
            text.startsWith("[") -> JSONArray(text).optJSONObject(0)
            text.startsWith("{") -> JSONObject(text)
            else -> null
        }
    }.getOrNull() ?: return ExternalImportKind.ONLINE_SOURCE
    val isSource = json.has("bookSourceName") || json.has("bookSourceUrl") ||
        json.has("searchUrl") || json.has("ruleSearch") || json.has("ruleToc")
    if (isSource) return ExternalImportKind.ONLINE_SOURCE
    val isHighlight = json.optString("type") == "reader_highlight_style" ||
        json.has("style") || json.has("styles") || json.has("color")
    return if (isHighlight) ExternalImportKind.HIGHLIGHT else ExternalImportKind.ONLINE_SOURCE
}

internal fun ReaMicroSettingsHook.queryDisplayName(activity: Activity, uri: Uri): String? =
    activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

internal fun ReaMicroSettingsHook.pageModifier(innerPaddings: Any, extendBottom: Boolean = false): Any {
    val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    val base = nativeUi.fillSize(modifierInstance())
    val padded = if (extendBottom) nativeUi.padding(base, 0f,
        (ui.instance("paddingTop", innerPaddings) as Number).toFloat(), 0f, 0f)
    else nativeUi.padding(base, innerPaddings)
    return nativeUi.padding(padded, udp(16), 0f, udp(16), 0f)
}
internal fun ReaMicroSettingsHook.clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
    nativeUi.clickable(baseModifier, functionProxy(name, FUNCTION0_CLASS) { onClick(); targetUnit() })
internal fun ReaMicroSettingsHook.combinedClickableModifier(
    baseModifier: Any, name: String, onClick: () -> Unit, onLongClick: () -> Unit,
): Any = nativeUi.combinedClickable(baseModifier,
    functionProxy("${name}LongClick", FUNCTION0_CLASS) { onLongClick(); targetUnit() },
    functionProxy("${name}Click", FUNCTION0_CLASS) { onClick(); targetUnit() })
internal fun ReaMicroSettingsHook.listItemDefaultMask(hasSupporting: Boolean, hasTrailing: Boolean): Int {
    var mask = 4 or 16 or 128 or 256
    if (!hasSupporting) mask = mask or 8
    if (!hasTrailing) mask = mask or 32
    return mask
}

internal fun ReaMicroSettingsHook.switchModifier(): Any = nativeUi.height(modifierInstance(), udp(44))
internal fun ReaMicroSettingsHook.dividerModifier(): Any = nativeUi.padding(modifierInstance(), udp(16), 0f, 0f, 0f)
internal fun ReaMicroSettingsHook.transparentListItemColors(composer: Any): Any = nativeUi.listColors(composer)
internal fun ReaMicroSettingsHook.switchColors(composer: Any): Any {
    val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    val scheme = colorScheme(composer)
    val thumb = ui.field("color.surfaceContainerHighest").getLong(scheme)
    return nativeUi.switchColors(thumb, ui.call("backgroundDim", scheme) as Long, thumb, composer)
}
internal fun ReaMicroSettingsHook.rememberBooleanState(composer: Any, initialValue: Boolean): Any {
    val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    val remembered = ui.instance("remember", composer)
    if (remembered !== ui.value("emptyRemembered")) return requireNotNull(remembered)
    return mutableBooleanState(initialValue).also { ui.instance("updateRemember", composer, it) }
}
internal fun ReaMicroSettingsHook.associationExpandedState(initialValue: Boolean): Any {
    associationExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { associationExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.readerExpandedState(initialValue: Boolean): Any {
    readerExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { readerExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.accountExpandedState(initialValue: Boolean): Any {
    accountExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { accountExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.cloudExpandedState(initialValue: Boolean): Any {
    cloudExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { cloudExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.accountSwitchExpandedState(initialValue: Boolean): Any {
    accountSwitchExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { accountSwitchExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.accountDataExportExpandedState(initialValue: Boolean): Any {
    accountDataExportExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { accountDataExportExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.rotationExpandedState(initialValue: Boolean): Any {
    rotationExpandedUiState?.let { return it }
    return mutableBooleanState(initialValue).also { rotationExpandedUiState = it }
}

internal fun ReaMicroSettingsHook.accountListVersionState(): Any {
    accountListVersionUiState?.let { return it }
    return mutableState(0).also { accountListVersionUiState = it }
}

internal fun ReaMicroSettingsHook.accountListVersionValue(): Int =
    (stateValue(accountListVersionState()) as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpAccountListVersion() {
    val state = accountListVersionState()
    val value = (stateValue(state) as? Number)?.toInt() ?: 0
    setStateValue(state, value + 1)
}

internal fun ReaMicroSettingsHook.aboutVersionState(): Any {
    aboutVersionUiState?.let { return it }
    return mutableState(0).also { aboutVersionUiState = it }
}

internal fun ReaMicroSettingsHook.aboutVersionValue(): Int =
    (stateValue(aboutVersionState()) as? Number)?.toInt() ?: 0

internal fun ReaMicroSettingsHook.bumpAboutVersion() {
    val state = aboutVersionState()
    val value = (stateValue(state) as? Number)?.toInt() ?: 0
    setStateValue(state, value + 1)
}

internal fun ReaMicroSettingsHook.moduleBuildVersionLine(): String {
    val (versionName, buildTime) = moduleBuildInfo()
    return "v$versionName${if (buildTime.isNotBlank()) " · $buildTime" else ""}"
}

internal fun ReaMicroSettingsHook.registerAboutVersionTap() {
    aboutVersionTapCount += 1
    if (aboutVersionTapCount >= 6) {
        aboutVersionTapCount = 0
        confirmApiDebugUnlock()
    }
}

internal fun ReaMicroSettingsHook.mutableBooleanState(initialValue: Boolean): Any = nativeUi.mutableState(initialValue)
internal fun ReaMicroSettingsHook.mutableState(initialValue: Any?): Any = nativeUi.mutableState(initialValue)
internal fun ReaMicroSettingsHook.booleanStateValue(state: Any): Boolean = stateValue(state) as? Boolean ?: false
internal fun ReaMicroSettingsHook.setBooleanState(state: Any, value: Boolean) { setStateValue(state, value) }
internal fun ReaMicroSettingsHook.stateValue(state: Any): Any? = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).stateValue(state)
internal fun ReaMicroSettingsHook.setStateValue(state: Any, value: Any?) { requireNotNull(com.reamicro.fix.core.HostUiBindings.current).setState(state, value) }
internal fun ReaMicroSettingsHook.colorScheme(composer: Any): Any = nativeUi.theme(composer, "getColorScheme")
internal fun ReaMicroSettingsHook.typography(composer: Any): Any = nativeUi.theme(composer, "getTypography")
internal fun ReaMicroSettingsHook.spacedBy(value: Int): Any = nativeUi.spacedBy(udp(value))
internal fun ReaMicroSettingsHook.arrangementStart(): Any = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).value("rowStart")
internal fun ReaMicroSettingsHook.arrangementTop(): Any = nativeUi.top()
internal fun ReaMicroSettingsHook.alignmentStart(): Any = nativeUi.start()
internal fun ReaMicroSettingsHook.alignmentCenter(): Any = nativeUi.center()
internal fun ReaMicroSettingsHook.alignmentCenterVertically(): Any = nativeUi.centerVertically()
internal fun ReaMicroSettingsHook.modifierInstance(): Any = nativeUi.modifier()
internal fun ReaMicroSettingsHook.composerEmpty(): Any = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).value("emptyRemembered")
internal fun ReaMicroSettingsHook.udp(value: Int): Float = nativeUi.dp(value)
internal fun ReaMicroSettingsHook.udp(value: Double): Float = nativeUi.dp(value)
internal fun ReaMicroSettingsHook.composableLambda(key: Int, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    nativeUi.lambda(key, functionProxy("Composable$key", functionClassName, block))
internal fun ReaMicroSettingsHook.functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
    composeInterop.functionProxy(name, functionClassName, block)

internal fun ReaMicroSettingsHook.cls(className: String): Class<*> =
    classLoader.hostClass(className)

internal fun ReaMicroSettingsHook.composeColorToArgb(color: Long): Int =
    composeInterop.colorToArgb(color)

internal fun ReaMicroSettingsHook.targetUnit(): Any = com.reamicro.fix.core.HostCoroutines.unit(classLoader)
