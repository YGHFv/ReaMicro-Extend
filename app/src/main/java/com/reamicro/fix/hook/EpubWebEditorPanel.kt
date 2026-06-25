package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.WeakHashMap

internal class EpubWebEditorPanel(
    private val activity: Activity,
    private val root: File,
    private val bookTitle: String,
    private val book: Any?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    private val fontSettingsProvider: () -> FontSettingsSnapshot = { FontSettingsSnapshot() },
) {
    private val dialog = Dialog(activity)
    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private lateinit var loadingOverlay: View
    private val themeCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            syncThemeFromNative(
                (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
            )
        }

        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) = Unit
    }
    private var pendingImportGroupPath: String = ""
    private var pendingCoverPath: String = ""
    private var coverPickerActive: Boolean = false
    private var suppressCoverPickerUntilMs: Long = 0L
    private var stagedCoverFile: File? = null
    private var stagedCoverTargetPath: String = ""
    private var lastCoverResultSignature: String = ""
    private var lastCoverResultAtMs: Long = 0L
    @Volatile private var metadataChanged: Boolean = false
    private val globalUiFontFile: File? by lazy { resolveGlobalUiFontFile() }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        webView = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            alpha = 0f
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    activity.runOnUiThread { hideLoadingOverlay() }
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = globalUiFontFile != null
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
            loadDataWithBaseURL(
                editorBaseUrl(),
                editorHtml(),
                "text/html",
                "UTF-8",
                null,
            )
        }
        loadingOverlay = buildLoadingOverlay()
        container = FrameLayout(activity).apply {
            setBackgroundColor(pageBackground())
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(loadingOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            container.setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        dialog.setContentView(container)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) {
                return@setOnKeyListener false
            }
            webView.evaluateJavascript("window.FileEditorNative && window.FileEditorNative.handleBack()") { result ->
                if (result != "true") dialog.dismiss()
            }
            true
        }
        dialog.setOnDismissListener {
            synchronized(activePanels) {
                activePanels.remove(activity)
            }
            cleanupStagedCover()
            runCatching { activity.unregisterComponentCallbacks(themeCallbacks) }
            runCatching {
                webView.removeJavascriptInterface(BRIDGE_NAME)
                webView.destroy()
            }
            if (metadataChanged && !activity.isFinishing) {
                activity.window?.decorView?.postDelayed({
                    runCatching {
                        if (!activity.isFinishing) activity.recreate()
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX file editor metadata refresh failed: ${it.stackTraceToString()}")
                    }
                }, 250L)
            }
        }
        synchronized(activePanels) {
            activePanels[activity] = this
        }
        activity.registerComponentCallbacks(themeCallbacks)
        dialog.show()
        configureWindow()
        syncThemeFromNative()
    }

    private fun configureWindow() {
        val bg = pageBackground()
        val dark = isNightMode()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(bg))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = bg
                navigationBarColor = bg
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setDecorFitsSystemWindows(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                decorView.systemUiVisibility = systemUiFlags(dark)
            }
            applySystemBarAppearance(this, dark)
        }
    }

    private fun updateWindowTheme(dark: Boolean) {
        activity.runOnUiThread {
            val bg = if (dark) 0xFF111318.toInt() else 0xFFF2F3F8.toInt()
            if (::container.isInitialized) {
                container.setBackgroundColor(bg)
            }
            if (::loadingOverlay.isInitialized) {
                loadingOverlay.setBackgroundColor(bg)
            }
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(bg))
                decorView.setBackgroundColor(bg)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    statusBarColor = bg
                    navigationBarColor = bg
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    decorView.systemUiVisibility = systemUiFlags(dark)
                }
                applySystemBarAppearance(this, dark)
            }
        }
    }

    private fun applySystemBarAppearance(window: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else lightBars,
            lightBars,
        )
    }

    private fun syncThemeFromNative(darkOverride: Boolean? = null) {
        activity.runOnUiThread {
            val dark = darkOverride ?: isNightMode()
            updateWindowTheme(dark)
            if (::webView.isInitialized) {
                runCatching {
                    webView.evaluateJavascript(
                        "window.FileEditorNative && window.FileEditorNative.setTheme(${if (dark) "true" else "false"})",
                        null,
                    )
                }
            }
        }
    }

    private fun systemUiFlags(dark: Boolean): Int {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        return flags
    }

    private fun buildLoadingOverlay(): View {
        val dark = isNightMode()
        val textColor = if (dark) Color.parseColor("#A3ACBA") else Color.parseColor("#8F96A3")
        val cardColor = if (dark) Color.parseColor("#1C2128") else Color.parseColor("#F4F6FB")
        return FrameLayout(activity).apply {
            setBackgroundColor(pageBackground())
            isClickable = true
            isFocusable = true
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(
                        ProgressBar(activity).apply {
                            isIndeterminate = true
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        TextView(activity).apply {
                            text = "正在载入图书结构"
                            setTextColor(textColor)
                            textSize = 13f
                            setPadding(0, dp(14), 0, 0)
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }.also { layout ->
                    layout.setPadding(dp(28), dp(22), dp(28), dp(22))
                    layout.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(cardColor)
                        cornerRadius = dp(22).toFloat()
                    }
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER,
                ),
            )
        }
    }

    private fun hideLoadingOverlay() {
        if (!::loadingOverlay.isInitialized) return
        webView.animate().alpha(1f).setDuration(140L).start()
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
                loadingOverlay.alpha = 1f
            }
            .start()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun isNightMode(): Boolean =
        (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private inner class Bridge {
        @JavascriptInterface
        fun initialData(): String = safeJson {
            val files = allFiles()
            JSONObject()
                .put("title", bookTitle)
                .put("status", "已解包 ${files.size} 个文件")
                .put("metadata", metadataJson())
                .put("files", filesJson(files))
                .toString()
        }

        @JavascriptInterface
        fun listFiles(): String = safeJson { filesJson().toString() }

        @JavascriptInterface
        fun toast(message: String?) {
            val text = message.orEmpty().ifBlank { return }
            activity.toast(text)
        }

        @JavascriptInterface
        fun readText(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            require(isTextFile(file)) { "该文件不支持文本编辑" }
            require(file.length() <= MAX_TEXT_BYTES) { "文件过大，暂不支持编辑" }
            JSONObject()
                .put("ok", true)
                .put("content", String(file.readBytes(), StandardCharsets.UTF_8))
                .toString()
        }

        @JavascriptInterface
        fun writeText(path: String?, content: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            require(isTextFile(file)) { "该文件不支持文本编辑" }
            file.writeText(content.orEmpty(), StandardCharsets.UTF_8)
            JSONObject().put("ok", true).put("message", "已保存").toString()
        }

        @JavascriptInterface
        fun replaceText(path: String?, content: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            require(isTextFile(file)) { "该文件不支持文本编辑" }
            file.writeText(content.orEmpty(), StandardCharsets.UTF_8)
            JSONObject().put("ok", true).toString()
        }

        @JavascriptInterface
        fun readDataUrl(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            JSONObject()
                .put("ok", true)
                .put("dataUrl", previewDataUrl(file, PREVIEW_MAX_DIMENSION))
                .toString()
        }

        @JavascriptInterface
        fun readThumbnail(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            require(fileKind(file) == "image") { "不是图片文件" }
            JSONObject()
                .put("ok", true)
                .put("dataUrl", previewDataUrl(file, THUMB_MAX_DIMENSION))
                .toString()
        }

        @JavascriptInterface
        fun readDecoration(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            val kind = fileKind(file)
            JSONObject()
                .put("ok", true)
                .put("path", relativePath(file))
                .put("detail", detailedFileDetail(file, kind))
                .put("preview", if (kind == "image") previewDataUrl(file, THUMB_MAX_DIMENSION) else "")
                .toString()
        }

        @JavascriptInterface
        fun renameFile(path: String?, nextName: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            val cleanName = sanitizeFileName(nextName.orEmpty())
            require(file.isFile) { "文件不存在" }
            require(cleanName.isNotBlank()) { "文件名不能为空" }
            val target = File(file.parentFile ?: root, cleanName).canonicalFile
            requireInsideRoot(target)
            require(!target.exists()) { "同名文件已存在" }
            require(file.renameTo(target)) { "重命名失败" }
            JSONObject().put("ok", true).put("message", "已重命名").toString()
        }

        @JavascriptInterface
        fun deleteFile(path: String?): String = safeJson {
            val file = resolveChild(path.orEmpty())
            require(file.isFile) { "文件不存在" }
            require(file.delete()) { "删除失败" }
            JSONObject().put("ok", true).put("message", "已删除").toString()
        }

        @JavascriptInterface
        fun createTextFile(groupPath: String?, name: String?): String = safeJson {
            val dir = resolveDirectory(groupPath.orEmpty())
            val cleanName = sanitizeFileName(name.orEmpty())
            require(cleanName.isNotBlank()) { "文件名不能为空" }
            val file = File(dir, cleanName).canonicalFile
            requireInsideRoot(file)
            require(!file.exists()) { "同名文件已存在" }
            file.parentFile?.mkdirs()
            file.writeText("", StandardCharsets.UTF_8)
            JSONObject().put("ok", true).put("message", "已新建文件").toString()
        }

        @JavascriptInterface
        fun saveMetadata(payload: String?): String = safeJson {
            val data = JSONObject(payload.orEmpty().ifBlank { "{}" })
            val coverChanged = stagedCoverFile?.isFile == true && stagedCoverTargetPath.isNotBlank()
            saveMetadataJson(data)
            if (coverChanged) applyStagedCover()
            val metadata = metadataJson()
            syncBookMetadata(metadata, refreshCover = coverChanged)
            JSONObject()
                .put("ok", true)
                .put("message", "已保存")
                .put("metadata", metadata)
                .put("coverChanged", coverChanged)
                .toString()
        }

        @JavascriptInterface
        fun coverThumbnail(): String = safeJson {
            val staged = stagedCoverFile?.takeIf { it.isFile }
            if (staged != null) {
                return@safeJson JSONObject()
                    .put("ok", true)
                    .put("path", stagedCoverTargetPath)
                    .put("dataUrl", previewDataUrl(staged, THUMB_MAX_DIMENSION))
                    .toString()
            }
            val opf = opfFile()
            val cover = opf?.let { findCoverFile(it) }
            JSONObject()
                .put("ok", true)
                .put("path", cover?.let(::relativePath).orEmpty())
                .put("dataUrl", cover?.takeIf { it.isFile }?.let { previewDataUrl(it, THUMB_MAX_DIMENSION) }.orEmpty())
                .toString()
        }

        @JavascriptInterface
        fun pickCoverImage() {
            activity.runOnUiThread {
                val now = System.currentTimeMillis()
                if (coverPickerActive || now < suppressCoverPickerUntilMs || now < globalSuppressCoverPickerUntilMs) {
                    return@runOnUiThread
                }
                val opf = opfFile()
                val cover = opf?.let { findCoverFile(it) }
                pendingCoverPath = cover?.takeIf { it.isFile }?.let(::relativePath).orEmpty()
                coverPickerActive = true
                globalSuppressCoverPickerUntilMs = now + 6000L
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                runCatching {
                    activity.startActivityForResult(intent, REQUEST_COVER_IMAGE)
                }.onFailure {
                    pendingCoverPath = ""
                    coverPickerActive = false
                    activity.toast("无法打开图片选择器")
                    XposedBridge.log("$LOG_PREFIX file editor cover picker failed: ${it.stackTraceToString()}")
                }
            }
        }

        @JavascriptInterface
        fun setCover(path: String?): String = safeJson {
            val image = resolveChild(path.orEmpty())
            require(image.isFile) { "文件不存在" }
            require(fileKind(image) == "image") { "不是图片文件" }
            updateCoverHref(image)
            val metadata = metadataJson()
            syncBookMetadata(metadata, refreshCover = true)
            JSONObject()
                .put("ok", true)
                .put("message", "已设为封面")
                .put("metadata", metadata)
                .put("cover", coverThumbnail())
                .toString()
        }

        @JavascriptInterface
        fun copyText(value: String?) {
            activity.runOnUiThread {
                val text = value.orEmpty()
                if (text.isBlank()) {
                    activity.toast("没有可复制的内容")
                    return@runOnUiThread
                }
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("UUID", text))
                activity.toast("已复制")
            }
        }

        @JavascriptInterface
        fun pickFile(groupPath: String?) {
            activity.runOnUiThread {
                pendingImportGroupPath = groupPath.orEmpty()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                runCatching {
                    activity.startActivityForResult(intent, REQUEST_IMPORT_FILE)
                }.onFailure {
                    pendingImportGroupPath = ""
                    activity.toast("无法打开文件选择器")
                    XposedBridge.log("$LOG_PREFIX file editor import picker failed: ${it.stackTraceToString()}")
                }
            }
        }

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread { dialog.dismiss() }
        }

        @JavascriptInterface
        fun setTheme(dark: Boolean) {
            updateWindowTheme(dark)
        }

        @JavascriptInterface
        fun isDarkMode(): Boolean = isNightMode()

        private fun safeJson(block: () -> String): String =
            runCatching(block).getOrElse {
                XposedBridge.log("$LOG_PREFIX file editor bridge failed: ${it.stackTraceToString()}")
                JSONObject()
                    .put("ok", false)
                    .put("message", it.message.orEmpty().ifBlank { "操作失败" })
                    .toString()
            }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_COVER_IMAGE) {
            if (isDuplicateCoverResult(resultCode, data)) return
            handleCoverActivityResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_IMPORT_FILE) return
        if (resultCode != Activity.RESULT_OK) {
            pendingImportGroupPath = ""
            return
        }
        val uri = data?.data
        val result = runCatching {
            require(uri != null) { "未选择文件" }
            importUri(uri)
        }.fold(
            onSuccess = { JSONObject().put("ok", true).put("message", "已导入：$it").put("path", it) },
            onFailure = {
                XposedBridge.log("$LOG_PREFIX file editor import failed: ${it.stackTraceToString()}")
                JSONObject().put("ok", false).put("message", it.message.orEmpty().ifBlank { "导入失败" })
            },
        )
        pendingImportGroupPath = ""
        webView.evaluateJavascript(
            "window.FileEditorNative && window.FileEditorNative.onImportResult(${JSONObject.quote(result.toString())})",
            null,
        )
    }

    private fun isDuplicateCoverResult(resultCode: Int, data: Intent?): Boolean {
        val now = System.currentTimeMillis()
        val signature = buildString {
            append(resultCode)
            append('|')
            append(data?.dataString.orEmpty())
            append('|')
            append(data?.clipData?.itemCount ?: 0)
        }
        if (signature == lastCoverResultSignature && now - lastCoverResultAtMs < 4000L) {
            XposedBridge.log("$LOG_PREFIX ignored duplicate cover activity result")
            return true
        }
        lastCoverResultSignature = signature
        lastCoverResultAtMs = now
        return false
    }

    private fun handleCoverActivityResult(resultCode: Int, data: Intent?) {
        coverPickerActive = false
        suppressCoverPickerUntilMs = System.currentTimeMillis() + 6000L
        globalSuppressCoverPickerUntilMs = suppressCoverPickerUntilMs
        if (resultCode != Activity.RESULT_OK) {
            pendingCoverPath = ""
            return
        }
        val result = runCatching {
            val uri = data?.data
            require(uri != null) { "未选择图片" }
            stageCoverUri(uri)
        }.fold(
            onSuccess = { staged ->
                JSONObject()
                    .put("ok", true)
                    .put("message", "封面已选择，保存后生效")
                    .put("cover", JSONObject()
                        .put("ok", true)
                        .put("path", stagedCoverTargetPath)
                        .put("dataUrl", previewDataUrl(staged, THUMB_MAX_DIMENSION)))
            },
            onFailure = {
                XposedBridge.log("$LOG_PREFIX file editor cover replace failed: ${it.stackTraceToString()}")
                JSONObject().put("ok", false).put("message", it.message.orEmpty().ifBlank { "封面更新失败" })
            },
        )
        pendingCoverPath = ""
        webView.evaluateJavascript(
            "window.FileEditorNative && window.FileEditorNative.onCoverResult(${JSONObject.quote(result.toString())})",
            null,
        )
    }

    private fun stageCoverUri(uri: Uri): File {
        val currentCover = if (pendingCoverPath.isNotBlank()) {
            resolveChild(pendingCoverPath)
        } else {
            defaultCoverTarget(uri)
        }
        require(!currentCover.exists() || currentCover.isFile) { "封面文件不存在" }
        cleanupStagedCover()
        val dir = File(activity.cacheDir, "reamicro_cover_stage").canonicalFile
        dir.mkdirs()
        val target = File(dir, "${System.currentTimeMillis()}-${currentCover.name}").canonicalFile
        requireInsideDirectory(target, dir)
        activity.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { "无法读取图片" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        stagedCoverFile = target
        stagedCoverTargetPath = relativePath(currentCover)
        return target
    }

    private fun applyStagedCover() {
        val staged = stagedCoverFile?.takeIf { it.isFile } ?: return
        val target = resolveChild(stagedCoverTargetPath)
        target.parentFile?.mkdirs()
        if (!target.exists()) target.createNewFile()
        require(target.isFile) { "封面文件不存在" }
        staged.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setLastModified(System.currentTimeMillis())
        updateCoverHref(target)
        cleanupStagedCover()
    }

    private fun defaultCoverTarget(uri: Uri): File {
        val opf = opfFile()
        val opfDir = opf?.parentFile ?: root
        val imageDir = allFiles()
            .asSequence()
            .filter { fileKind(it) == "image" }
            .mapNotNull { it.parentFile }
            .firstOrNull()
            ?: File(opfDir, "Images")
        val sourceName = displayName(uri)
        val ext = sourceName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in IMAGE_EXTENSIONS }
            ?: "jpg"
        val target = uniqueFile(File(imageDir, "reamicro-cover.$ext").canonicalFile)
        requireInsideRoot(target)
        return target
    }

    private fun cleanupStagedCover() {
        stagedCoverFile?.let { runCatching { it.delete() } }
        stagedCoverFile = null
        stagedCoverTargetPath = ""
    }

    private fun importUri(uri: Uri): String {
        val dir = resolveDirectory(pendingImportGroupPath)
        val name = sanitizeFileName(displayName(uri).ifBlank { "imported-file" })
        require(name.isNotBlank()) { "文件名不能为空" }
        val target = uniqueFile(File(dir, name).canonicalFile)
        requireInsideRoot(target)
        target.parentFile?.mkdirs()
        if (!target.exists()) target.createNewFile()
        require(target.isFile) { "\u5c01\u9762\u6587\u4ef6\u4e0d\u5b58\u5728" }
        /*
        require(target.isFile) { "\u5c01\u9762\u6587\u4ef6\u4e0d\u5b58\u5728" }
        /*
        */
        */
        activity.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { "无法读取文件" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return relativePath(target)
    }

    private fun replaceCoverUri(uri: Uri): String {
        val currentCover = resolveChild(pendingCoverPath)
        require(currentCover.isFile) { "\u672a\u627e\u5230\u5c01\u9762\u6587\u4ef6" }
        activity.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { "\u65e0\u6cd5\u8bfb\u53d6\u56fe\u7247" }
            currentCover.outputStream().use { output -> input.copyTo(output) }
        }
        currentCover.setLastModified(System.currentTimeMillis())
        updateCoverHref(currentCover)
        return relativePath(currentCover)
    }

    /*
    private fun replaceCoverUri(uri: Uri): String {
        val currentCover = resolveChild(pendingCoverPath)
        require(currentCover.isFile) { "\u672a\u627e\u5230\u5c01\u9762\u6587\u4ef6" }
        /*
        require(currentCover.isFile) { "灏侀潰鏂囦欢涓嶅瓨鍦? }
        */
        val extension = currentCover.extension.ifBlank { "jpg" }
        val target = uniqueFile(
            File(
                currentCover.parentFile ?: root,
                "${currentCover.nameWithoutExtension}-reamicro-${System.currentTimeMillis()}.$extension",
            ).canonicalFile,
        )
        requireInsideRoot(target)
        target.parentFile?.mkdirs()
        if (!target.exists()) target.createNewFile()
        require(target.isFile) { "\u5c01\u9762\u6587\u4ef6\u4e0d\u5b58\u5728" }
        /*
        require(target.isFile) { "封面文件不存在" }
        activity.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { "无法读取图片" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        updateCoverHref(currentCover, target)
        return relativePath(target)
    }

    */
    */

    private fun updateCoverHref(newCover: File) {
        val opf = opfFile() ?: error("未找到 OPF 文件")
        val opfDir = opf.parentFile ?: root
        val newHref = newCover.relativeTo(opfDir).invariantSeparatorsPath
        val itemRegex = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
        var content = opf.readText(StandardCharsets.UTF_8)
        var targetId = itemRegex.findAll(content)
            .firstOrNull { hrefMatches(xmlAttr(it.value, "href"), newHref) }
            ?.value
            ?.let { xmlAttr(it, "id") }
            .orEmpty()
        if (targetId.isBlank()) targetId = uniqueManifestId(content, "reamicro-cover")

        var foundTarget = false
        content = itemRegex.replace(content) { match ->
            val tag = removeTokenAttr(match.value, "properties", "cover-image")
            if (!hrefMatches(xmlAttr(tag, "href"), newHref)) {
                tag
            } else {
                foundTarget = true
                addTokenAttr(setXmlAttr(tag, "id", targetId), "properties", "cover-image")
            }
        }
        if (!foundTarget) {
            val item = "\n    <item id=\"${escapeXml(targetId)}\" href=\"${escapeXml(newHref)}\" media-type=\"${mimeType(newCover)}\" properties=\"cover-image\"/>"
            content = insertIntoManifest(content, item)
        }
        content = upsertCoverMeta(content, targetId)
        opf.writeText(content, StandardCharsets.UTF_8)
    }

    private fun filesJson(files: List<File> = allFiles()): JSONArray =
        JSONArray().apply {
            files.forEach { file -> put(fileJson(file)) }
        }

    private fun fileJson(file: File): JSONObject {
        val relative = relativePath(file)
        val groupPath = relative.substringBeforeLast("/", "")
        val kind = fileKind(file)
        return JSONObject()
            .put("path", relative)
            .put("name", file.name)
            .put("stem", file.name.substringBeforeLast('.', file.name))
            .put("groupPath", groupPath)
            .put("group", if (groupPath.isBlank()) "ROOT" else groupPath.uppercase(Locale.ROOT))
            .put("kind", kind)
            .put("size", file.length())
            .put("sizeText", formatFileSize(file.length()))
            .put("detail", fileDetail(file, kind))
            .put("editable", isTextFile(file))
            .put("preview", "")
            .put("color", iconColor(kind, file.name))
    }

    private fun metadataJson(): JSONObject {
        val opf = opfFile()
        val content = opf?.let { runCatching { it.readText(StandardCharsets.UTF_8) }.getOrDefault("") }.orEmpty()
        val cover = opf?.let { findCoverFile(it) }
        return JSONObject()
            .put("title", tagText(content, "dc:title").ifBlank { bookTitle })
            .put("author", tagText(content, "dc:creator"))
            .put("subtitle", tagText(content, "dc:subtitle"))
            .put("publisher", tagText(content, "dc:publisher"))
            .put("maker", tagText(content, "meta", "name", "generator"))
            .put("series", tagText(content, "meta", "name", "calibre:series"))
            .put("tags", subjectsJson(content))
            .put("uuid", tagText(content, "dc:identifier").ifBlank { reamicroMd5Identifier(content) })
            .put("opfPath", opf?.let(::relativePath).orEmpty())
            .put("coverPath", cover?.let(::relativePath).orEmpty())
    }

    private fun opfFile(): File? =
        allFiles().firstOrNull { it.extension.equals("opf", ignoreCase = true) }

    private fun saveMetadataJson(data: JSONObject) {
        val opf = opfFile() ?: error("未找到 OPF 文件")
        var content = opf.readText(StandardCharsets.UTF_8)
        content = upsertTag(content, "dc:title", data.optString("title"))
        content = upsertTag(content, "dc:creator", data.optString("author"))
        content = upsertTag(content, "dc:subtitle", data.optString("subtitle"))
        content = upsertTag(content, "dc:publisher", data.optString("publisher"))
        content = upsertMetaContent(content, "generator", data.optString("maker"))
        content = upsertMetaContent(content, "calibre:series", data.optString("series"))
        opf.writeText(content, StandardCharsets.UTF_8)
    }

    private fun syncBookMetadata(metadata: JSONObject, refreshCover: Boolean = false) {
        val coverPath = metadata.optString("coverPath").takeIf { it.isNotBlank() }
        val bookshelfCover = coverPath
            ?.let { runCatching { createBookshelfCoverSnapshot(resolveChild(it), refreshCover) }.getOrNull() }
            ?: coverPath
        ReaMicroBookMetadataSync.syncBookMetadataAsync(
            repository = ReaMicroBookMetadataSync.currentBookshelfRepository(),
            book = book,
            patch = BookMetadataPatch(
                title = metadata.optString("title").takeIf { it.isNotBlank() },
                subtitle = metadata.optString("subtitle"),
                author = metadata.optString("author"),
                cover = bookshelfCover,
                publisher = metadata.optString("publisher"),
            ),
        )
        metadataChanged = true
    }

    private fun createBookshelfCoverSnapshot(source: File, refresh: Boolean): String {
        require(source.isFile) { "封面文件不存在" }
        val dir = File(activity.filesDir, "reamicro_cover_refresh").canonicalFile
        dir.mkdirs()
        val bookKey = bookStorageKey()
        val ext = source.extension.lowercase(Locale.ROOT).ifBlank { "jpg" }
        val sourceKey = Integer.toHexString(relativePath(source).hashCode())
        val baseName = "cover-$bookKey-$sourceKey-${source.lastModified()}-${source.length()}"
        val targetName = if (refresh) "$baseName-${System.currentTimeMillis()}.$ext" else "$baseName.$ext"
        val target = File(dir, targetName).canonicalFile
        if (!target.isFile || target.length() != source.length()) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cover-$bookKey-") && it.name != target.name }
            ?.forEach { runCatching { it.delete() } }
        return target.absolutePath
    }

    private fun bookStorageKey(): String {
        val raw = listOf("getUuid", "getId", "getUri")
            .asSequence()
            .map { name -> callBookString(name) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .ifBlank { bookTitle }
        return raw.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifBlank { "book" }
    }

    private fun callBookString(methodName: String): String =
        runCatching {
            book?.javaClass?.methods?.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.apply { isAccessible = true }
                ?.invoke(book)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")

    private fun subjectsJson(content: String): JSONArray =
        JSONArray().apply {
            Regex("<dc:subject\\b[^>]*>([\\s\\S]*?)</dc:subject>", RegexOption.IGNORE_CASE)
                .findAll(content)
                .map { decodeXmlText(it.groupValues.getOrNull(1).orEmpty()).trim() }
                .filter { it.isNotBlank() }
                .forEach { put(it) }
        }

    private fun replaceSubjects(content: String, tags: JSONArray?): String {
        val values = (0 until (tags?.length() ?: 0))
            .mapNotNull { index -> tags?.optString(index)?.trim()?.takeIf { it.isNotBlank() } }
            .distinct()
        var next = content.replace(
            Regex("\\s*<dc:subject\\b[^>]*>[\\s\\S]*?</dc:subject>", RegexOption.IGNORE_CASE),
            "",
        )
        if (values.isEmpty()) return next
        val insert = values.joinToString("") { "\n    <dc:subject>${escapeXml(it)}</dc:subject>" }
        return insertIntoMetadata(next, insert)
    }

    private fun upsertTag(content: String, tag: String, value: String): String {
        val clean = value.trim()
        val pattern = Regex("<$tag\\b([^>]*)>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE)
        if (clean.isBlank()) return content
        val replacement = "<$tag>${escapeXml(clean)}</$tag>"
        if (pattern.containsMatchIn(content)) return pattern.replaceFirst(content, replacement)
        return insertIntoMetadata(content, "\n    $replacement")
    }

    private fun upsertMetaContent(content: String, name: String, value: String): String {
        val clean = value.trim()
        val pattern = Regex("<meta\\b([^>]*\\bname=[\"']${Regex.escape(name)}[\"'][^>]*)>", RegexOption.IGNORE_CASE)
        if (clean.isBlank()) return content
        val replacement = "<meta name=\"$name\" content=\"${escapeXml(clean)}\"/>"
        if (pattern.containsMatchIn(content)) return pattern.replaceFirst(content, replacement)
        return insertIntoMetadata(content, "\n    $replacement")
    }

    private fun insertIntoMetadata(content: String, insert: String): String {
        val close = Regex("</metadata>", RegexOption.IGNORE_CASE)
        return if (close.containsMatchIn(content)) close.replaceFirst(content, "$insert\n  </metadata>") else content + insert
    }

    private fun insertIntoManifest(content: String, insert: String): String {
        val close = Regex("</manifest>", RegexOption.IGNORE_CASE).find(content)
            ?: error("未找到 OPF manifest")
        return content.substring(0, close.range.first) + insert + "\n  " + content.substring(close.range.first)
    }

    private fun upsertCoverMeta(content: String, coverId: String): String {
        var found = false
        val metaRegex = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        val next = metaRegex.replace(content) { match ->
            if (!xmlAttr(match.value, "name").equals("cover", ignoreCase = true)) {
                match.value
            } else {
                found = true
                setXmlAttr(match.value, "content", coverId)
            }
        }
        return if (found) next else insertIntoMetadata(next, "\n    <meta name=\"cover\" content=\"${escapeXml(coverId)}\"/>")
    }

    private fun xmlAttr(tag: String, name: String): String =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.groupValues
            ?.getOrNull(2)
            ?.let(::decodeXmlText)
            .orEmpty()

    private fun setXmlAttr(tag: String, name: String, value: String): String {
        val pattern = Regex("\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
        val match = pattern.find(tag)
        if (match != null) {
            return tag.replaceRange(match.range, "$name=\"${escapeXml(value)}\"")
        }
        val insertAt = tag.lastIndexOf("/>").takeIf { it >= 0 } ?: tag.lastIndexOf(">")
        if (insertAt < 0) return tag
        return tag.substring(0, insertAt).trimEnd() + " $name=\"${escapeXml(value)}\"" + tag.substring(insertAt)
    }

    private fun removeTokenAttr(tag: String, name: String, token: String): String {
        val pattern = Regex("\\s*\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
        return pattern.replace(tag) { match ->
            val quote = match.groupValues.getOrNull(1).orEmpty().ifBlank { "\"" }
            val values = match.groupValues.getOrNull(2).orEmpty()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() && !it.equals(token, ignoreCase = true) }
            if (values.isEmpty()) "" else " $name=$quote${escapeXml(values.joinToString(" "))}$quote"
        }
    }

    private fun addTokenAttr(tag: String, name: String, token: String): String {
        val values = xmlAttr(tag, name)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toMutableList()
        if (values.none { it.equals(token, ignoreCase = true) }) values += token
        return setXmlAttr(tag, name, values.joinToString(" "))
    }

    private fun hrefMatches(value: String, target: String): Boolean {
        val normalized = value.replace('\\', '/')
        return normalized == target || Uri.decode(normalized) == target
    }

    private fun uniqueManifestId(content: String, preferred: String): String {
        val base = preferred.replace(Regex("[^A-Za-z0-9_.-]"), "-").trim('-').ifBlank { "reamicro-cover" }
        val idRegex = { id: String -> Regex("\\bid\\s*=\\s*([\"'])${Regex.escape(id)}\\1", RegexOption.IGNORE_CASE) }
        if (!idRegex(base).containsMatchIn(content)) return base
        for (index in 1..999) {
            val candidate = "$base-$index"
            if (!idRegex(candidate).containsMatchIn(content)) return candidate
        }
        error("无法生成封面 ID")
    }

    private fun findCoverFile(opf: File): File? {
        val content = runCatching { opf.readText(StandardCharsets.UTF_8) }.getOrDefault("")
        val itemRegex = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
        val coverId = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(content)
            .firstOrNull { xmlAttr(it.value, "name").equals("cover", ignoreCase = true) }
            ?.let { xmlAttr(it.value, "content") }
            .orEmpty()
        val hrefByCoverId = if (coverId.isNotBlank()) {
            itemRegex.findAll(content)
                .firstOrNull { xmlAttr(it.value, "id") == coverId }
                ?.let { xmlAttr(it.value, "href") }
                .orEmpty()
        } else {
            ""
        }
        val coverImageHref = itemRegex.findAll(content)
            .firstOrNull { tag ->
                xmlAttr(tag.value, "properties")
                    .split(Regex("\\s+"))
                    .any { it.equals("cover-image", ignoreCase = true) }
            }
            ?.let { xmlAttr(it.value, "href") }
            .orEmpty()
        val href = hrefByCoverId.ifBlank { coverImageHref }
        val opfDir = opf.parentFile ?: root
        if (href.isNotBlank()) {
            val file = File(opfDir, Uri.decode(href)).canonicalFile
            if (file.isFile) return file
        }
        return allFiles().firstOrNull {
            fileKind(it) == "image" && it.name.contains("cover", ignoreCase = true)
        }
    }

    private fun manifestHrefById(content: String, id: String): String =
        Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(content)
            .firstOrNull { xmlAttr(it.value, "id") == id }
            ?.let { xmlAttr(it.value, "href") }
            .orEmpty()

    private fun tagText(content: String, tag: String, attrName: String, attrValue: String): String =
        Regex("<$tag\\b[^>]*\\b$attrName=[\"']${Regex.escape(attrValue)}[\"'][^>]*\\bcontent=[\"']([^\"']*)[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeXmlText)
            .orEmpty()

    private fun reamicroMd5Identifier(content: String): String =
        Regex("<meta\\b[^>]*\\bproperty=[\"']reamicro:md5[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .findAll(content)
            .map { xmlAttr(it.value, "content").ifBlank { xmlAttr(it.value, "value") }.trim() }
            .firstOrNull { REAMICRO_MD5_REGEX.matches(it) }
            .orEmpty()

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun decodeXmlText(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun allFiles(): List<File> =
        root.walkTopDown()
            .filter { it.isFile }
            .filterNot { relativePath(it).startsWith("META-INF/", ignoreCase = true) }
            .sortedWith(compareBy<File> { relativePath(it).substringBeforeLast("/", "") }
                .thenBy { naturalName(it.name) })
            .toList()

    private fun fileDetail(file: File, kind: String): String =
        when (kind) {
            "html" -> "章节内容"
            "css" -> "层叠样式表"
            "xml" -> if (file.name.contains("toc", ignoreCase = true) || file.extension.equals("ncx", true)) "目录结构" else "元数据"
            "font" -> "字体  ${formatFileSize(file.length())}"
            "image" -> "图片  ${formatFileSize(file.length())}"
            else -> formatFileSize(file.length())
        }

    private fun detailedFileDetail(file: File, kind: String): String =
        when (kind) {
            "html" -> titleHint(file).ifBlank { fileDetail(file, kind) }
            "image" -> {
                val size = imageSize(file)
                if (size.isBlank()) fileDetail(file, kind) else "图片  $size  ${formatFileSize(file.length())}"
            }
            else -> fileDetail(file, kind)
        }

    private fun titleHint(file: File): String =
        runCatching {
            val text = file.readText(StandardCharsets.UTF_8)
            tagText(text, "title")
                .ifBlank { Regex("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1).orEmpty() }
                .replace(Regex("<[^>]+>"), "")
                .trim()
        }.getOrDefault("")

    private fun imageSize(file: File): String =
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) "${options.outWidth} x ${options.outHeight}" else ""
        }.getOrDefault("")

    private fun previewDataUrl(file: File, maxDimension: Int): String {
        if (fileKind(file) != "image") {
            return rawDataUrl(file)
        }
        sampledImageDataUrl(file, maxDimension)?.let { return it }
        return rawDataUrl(file)
    }

    private fun rawDataUrl(file: File): String {
        val bytes = file.readBytes()
        return "data:${mimeType(file)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun sampledImageDataUrl(file: File, maxDimension: Int): String? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@runCatching null
            val sampleSize = computeInSampleSize(width, height, maxDimension)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
            val scaled = scaleBitmapIfNeeded(bitmap, maxDimension)
            val output = ByteArrayOutputStream()
            val format = if (scaled.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (format == Bitmap.CompressFormat.JPEG) 88 else 100
            scaled.compress(format, quality, output)
            if (scaled !== bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()
            val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            "data:$mime;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
        }.getOrNull()

    private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxDimension * 2 || currentHeight > maxDimension * 2) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0).orEmpty()
            } else {
                uri.lastPathSegment.orEmpty().substringAfterLast('/')
            }
        } finally {
            cursor?.close()
        }
    }

    private fun resolveChild(path: String): File =
        File(root, path.replace('\\', '/').trimStart('/')).canonicalFile.also(::requireInsideRoot)

    private fun resolveDirectory(path: String): File {
        val dir = if (path.isBlank()) root else resolveChild(path)
        require(dir == root || dir.isDirectory) { "目录不存在" }
        return dir
    }

    private fun requireInsideRoot(file: File) {
        val rootPath = root.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        require(file.canonicalFile.absolutePath == rootPath || file.canonicalFile.absolutePath.startsWith(rootPath + File.separator)) {
            "路径无效"
        }
    }

    private fun requireInsideDirectory(file: File, directory: File) {
        val dirPath = directory.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        require(file.canonicalFile.absolutePath == dirPath || file.canonicalFile.absolutePath.startsWith(dirPath + File.separator)) {
            "路径无效"
        }
    }

    private fun relativePath(file: File): String {
        val rootPath = root.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        return file.canonicalFile.absolutePath.removePrefix(rootPath).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: root
        val stem = file.name.substringBeforeLast('.', file.name)
        val ext = file.name.substringAfterLast('.', "")
        for (index in 1..999) {
            val nextName = if (ext.isBlank()) "$stem-$index" else "$stem-$index.$ext"
            val next = File(parent, nextName).canonicalFile
            if (!next.exists()) return next
        }
        error("无法生成唯一文件名")
    }

    private fun pageBackground(): Int =
        if ((activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) {
            0xFF111318.toInt()
        } else {
            0xFFF2F3F8.toInt()
        }

    private fun globalUiFontCss(): String {
        val selection = if (settingsProvider().canUseFontSettings) {
            fontSettingsProvider().globalFamily.trim()
        } else {
            ""
        }
        return when {
            selection.isBlank() || selection == GLOBAL_FONT_SYSTEM -> ""
            selection == GLOBAL_FONT_SERIF -> ":root{--ui-font-family:serif}"
            else -> globalUiFontFile?.let { file ->
                val url = cssString(file.toURI().toString())
                "@font-face{font-family:ReaMicroGlobalUiFont;src:url(\"$url\");font-style:normal;font-display:swap}:root{--ui-font-family:ReaMicroGlobalUiFont,sans-serif}"
            }.orEmpty()
        }
    }

    private fun resolveGlobalUiFontFile(): File? {
        if (!settingsProvider().canUseFontSettings) return null
        val selection = fontSettingsProvider().globalFamily.trim()
        if (selection.isBlank() || selection == GLOBAL_FONT_SYSTEM || selection == GLOBAL_FONT_SERIF) return null
        val direct = File(selection)
        if (direct.isFile && isGlobalFontFile(direct.name)) return direct
        val name = direct.name
        if (!isGlobalFontFile(name)) return null
        return fontDirectories(activity.filesDir)
            .asSequence()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull { it.isFile && it.name == name && isGlobalFontFile(it.name) }
    }

    private fun fontDirectories(filesDir: File): List<File> {
        val dirs = filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.map { File(it, "fonts") }
            ?.toMutableList()
            ?: mutableListOf()
        val defaultDir = File(File(filesDir, "0"), "fonts")
        if (dirs.none { it.absolutePath == defaultDir.absolutePath }) dirs.add(defaultDir)
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }

    private fun isGlobalFontFile(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)

    private fun cssString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "")

    private fun editorBaseUrl(): String =
        globalUiFontFile?.parentFile?.toURI()?.toString() ?: "https://reamicro-file-editor.local/"

    private fun editorHtml(): String =
        """
<!doctype html>
<html lang="zh-CN" data-theme="${if (isNightMode()) "dark" else "light"}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>EPUB元数据</title>
<style>
:root{--bg:#f2f3f8;--paper:#fff;--text:#191c24;--muted:#9a9ca6;--line:#e4e5ea;--accent:#ff5a1f;--green:#45cdb4;--blue:#2096ff;--shadow:0 1px 0 rgba(0,0,0,.04)}
${globalUiFontCss()}
html[data-theme="dark"]{--bg:#111318;--paper:#191c20;--text:#e5e6eb;--muted:#9397a1;--line:#282c33}
@media (prefers-color-scheme:dark){html{--bg:#111318;--paper:#191c20;--text:#e5e6eb;--muted:#9397a1;--line:#282c33}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font-family:var(--ui-font-family,sans-serif)}
button,input,textarea,select{font:inherit}
button{border:0;background:transparent;color:inherit;padding:0}
.app{min-height:100dvh;background:var(--bg);padding:0 0 max(8px,env(safe-area-inset-bottom));overflow:hidden}
.app.tree-mode{overflow:auto}
.app.editing{overflow:hidden;height:100dvh}
.topbar{height:52px;display:grid;grid-template-columns:44px minmax(0,1fr) 38px;align-items:center;padding:0 12px;background:var(--bg);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
.back{width:42px;height:42px;display:grid;place-items:center;font-size:34px;line-height:1}
.title{font-size:18px;font-weight:900;letter-spacing:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.tool{width:42px;height:42px;display:grid;place-items:center;color:#8d8f98;font-size:28px}
.status{display:none}
.app:not(.tree-mode) #tree{display:none}.app.tree-mode #meta{display:none}
.meta{display:block;padding:10px 14px 28px}
.meta-card{background:var(--paper);border:1px solid var(--line);border-radius:8px;padding:12px;box-shadow:none}
.meta-grid{display:grid;grid-template-columns:minmax(0,1fr) 112px;gap:12px;align-items:start}
.meta-cover{width:112px;aspect-ratio:3/4;border-radius:8px;background:#eef3f1;border:1px solid var(--line);overflow:hidden;display:grid;place-items:center;color:var(--muted);font-size:12px;font-weight:800;text-align:center}
.meta-cover img{width:100%;height:100%;object-fit:cover;display:block}
.meta-fields{display:grid;gap:10px}.meta-row{display:grid;gap:6px}.meta-row label{font-size:12px;font-weight:800;color:#626a78}
.meta-row input{width:100%;height:44px;border:1px solid var(--line);border-radius:8px;padding:0 10px;background:var(--paper);color:var(--text);font-size:14px;font-weight:700;line-height:1.45;outline:none;box-sizing:border-box}
.meta-row input.readonly{font-size:12px;font-weight:700;color:#414957;background:rgba(127,137,153,.06)}
.meta-two{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px}.meta-wide{grid-column:1/-1}
.meta-tags{height:44px;border:1px solid var(--line);border-radius:8px;padding:0 10px;background:var(--paper);color:var(--text);font-size:14px;box-sizing:border-box}
.meta-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:12px}
.meta-actions button{height:48px;border-radius:8px;font-size:14px;font-weight:900}
.meta-actions .secondary{background:#eef7f2;color:#3d8067;border:1px solid #cfe3d9}.meta-actions .primary{background:#3e8666;color:#fff}
.meta-hint{display:none}
.section{border-top:1px solid var(--line)}
.group-head{height:42px;display:grid;grid-template-columns:minmax(0,1fr) 36px 36px;align-items:center;padding:0 14px 0 18px;border-bottom:1px solid var(--line);color:var(--muted)}
.group-title{text-align:left;font-size:13px;letter-spacing:1.5px;text-transform:uppercase;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.group-icon{width:36px;height:36px;display:grid;place-items:center;font-size:24px;color:#a8aab1;line-height:1;text-align:center}
.group-icon svg{width:20px;height:20px;stroke:currentColor;stroke-width:2;fill:none;stroke-linecap:round;stroke-linejoin:round}
.chev{transition:transform .16s ease}.closed .chev{transform:rotate(-90deg)}
.file-row{height:64px;display:grid;grid-template-columns:48px minmax(0,1fr) 42px;align-items:center;margin-left:49px;border-bottom:1px solid var(--line);position:relative}
.file-row:active{background:rgba(0,0,0,.025)}
.thumb,.kind-icon{width:30px;height:30px;border-radius:4px;object-fit:cover;margin-left:0;display:grid;place-items:center;overflow:hidden}
.kind-icon{color:#fff;font-size:12px;font-weight:800}
.kind-html{background:#ff4a15}.kind-css{background:#20a1ff}.kind-xml{background:#45cdb4}.kind-font{background:#536273}.kind-other{background:#f59b24}
.file-copy{min-width:0;padding-right:8px;text-align:left}.file-name{font-size:15px;font-weight:900;line-height:1.16;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.file-detail{margin-top:5px;color:var(--muted);font-size:12px;line-height:1.2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.more{height:100%;font-size:22px;color:#85878f;display:grid;place-items:center;padding-right:8px}
.empty{margin:40px;text-align:center;color:var(--muted);font-size:16px}
.sheet-mask{position:fixed;inset:0;background:rgba(0,0,0,.24);z-index:19}
.sheet{position:fixed;left:0;right:0;bottom:0;background:#fff;border-radius:18px 18px 0 0;padding:18px 18px max(18px,env(safe-area-inset-bottom));z-index:20;box-shadow:0 -12px 40px rgba(0,0,0,.14)}
.sheet h3{margin:0 0 4px;font-size:18px}.sheet p{margin:0 0 12px;color:var(--muted);font-size:13px;word-break:break-all}.sheet button{width:100%;height:48px;border-radius:8px;text-align:center;font-weight:800}.sheet .primary{background:var(--accent);color:#fff}.sheet .plain{background:#f1f2f5;margin-top:8px}.sheet .danger{color:#d9362b;background:#fff0ee;margin-top:8px}.field{width:100%;height:44px;border:1px solid var(--line);border-radius:8px;padding:0 12px;margin:10px 0;background:#fff;color:var(--text)}
.editor{position:fixed;inset:0;background:var(--bg);z-index:10;display:none;grid-template-rows:64px minmax(0,1fr) auto;padding-bottom:max(8px,env(safe-area-inset-bottom))}
.app.editing .editor{display:grid}.editor-head{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px;align-items:center;padding:8px 14px 6px;border-bottom:1px solid rgba(228,229,234,.82);background:var(--bg);backdrop-filter:blur(14px)}
.editor-title{min-width:0;padding-left:0}.editor-title strong{display:block;font-size:15px;font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.editor-title small{display:block;margin-top:2px;color:#8e94a3;font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.editor-actions{display:grid;grid-auto-flow:column;grid-auto-columns:36px;gap:8px;align-items:center;justify-content:end}
.icon-btn{width:36px;height:36px;border-radius:12px;display:grid;place-items:center;font-size:24px;font-weight:400;box-shadow:0 8px 18px rgba(0,0,0,.05);min-width:0}
.icon-btn svg{width:20px;height:20px;stroke:currentColor;stroke-width:2;fill:none;stroke-linecap:round;stroke-linejoin:round}
.icon-search{background:#dff0ff;color:#2486cb}.icon-save{background:#e6f2eb;color:#93a79b}.icon-save.dirty{background:#d84b62;color:#fff;box-shadow:0 10px 24px rgba(216,75,98,.28)}.icon-close{background:#f7eaee;color:#a05262}.icon-btn.active{outline:2px solid rgba(36,134,203,.22)}
.code-wrap{min-height:0;display:grid;grid-template-columns:54px minmax(0,1fr);font-family:"Cascadia Mono","JetBrains Mono",monospace;background:#fafafa;overflow:hidden}
.lines{position:relative;padding:14px 8px calc(var(--editor-bottom, 88px) + 10px) 6px;color:#aab1be;background:#eef5fb;text-align:right;font-size:14px;line-height:1.62;overflow:hidden;user-select:none}
.line-canvas{position:relative;min-height:100%}
.line-row{position:absolute;right:0;left:0;padding-right:2px;height:1.62em;line-height:1.62;white-space:nowrap}
.editor-stack{position:relative;min-width:0;min-height:0;overflow:hidden;background:#fff}
.highlight,.code-input,.search-overlay{position:absolute;inset:0;margin:0;border:0;outline:0;padding:14px 14px calc(var(--editor-bottom, 88px) + 12px) 14px;font-family:"Cascadia Mono","JetBrains Mono",monospace;font-size:14px;line-height:1.62;white-space:pre-wrap;word-break:break-word;overflow:auto;tab-size:2}
.highlight{color:#20242d;background:transparent;pointer-events:none;z-index:1}
.highlight:empty::before{content:" "}
.search-overlay{background:transparent;pointer-events:none;z-index:2;scrollbar-width:none}
.search-overlay::-webkit-scrollbar{display:none}
.search-canvas{position:relative;min-width:100%;min-height:100%}
.search-measure{position:fixed;left:-99999px;top:0;visibility:hidden;pointer-events:none;white-space:pre-wrap;word-break:break-word;tab-size:2;box-sizing:border-box;overflow:visible}
.code-input{resize:none;background:transparent;color:transparent;caret-color:#20242d;-webkit-text-fill-color:transparent}
.code-input::selection{background:rgba(255,90,31,.25)}
.hl-tag{color:#0a7f46}.hl-name{color:#0a7f46}.hl-attr{color:#953800}.hl-string{color:#b42318}.hl-comment{color:#8d96a3}.hl-key{color:#953800}.hl-num{color:#1f7a8c}.hl-punc{color:#6b7280}
.search-hit{position:absolute;border-radius:4px;background:rgba(255,211,87,.46);box-shadow:0 0 0 1px rgba(255,193,7,.24)}
.search-hit.current{background:rgba(255,177,34,.54);box-shadow:0 0 0 1px rgba(255,149,0,.34)}
.preview{display:grid;place-items:center;background:#111;min-height:0;padding:18px}.preview img{max-width:100%;max-height:100%;object-fit:contain}.font-preview{padding:22px;font-size:28px;line-height:1.8;background:var(--paper);color:var(--text);height:100%;overflow:auto}
.replace{display:none;padding:0 10px 4px}.replace.open{display:block}
.replace-card{background:rgba(255,255,255,.96);border:1px solid rgba(228,229,234,.92);border-radius:20px;padding:11px 11px 9px;box-shadow:0 -6px 20px rgba(0,0,0,.06);backdrop-filter:blur(16px)}
.replace-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}.replace-head strong{font-size:13px}
.replace-meta{display:flex;gap:8px;align-items:center;justify-content:flex-end;min-width:0}
.replace-progress,.replace-count{color:#98a0ae;font-size:11px;white-space:nowrap}
.replace-row{display:grid;grid-template-columns:64px minmax(0,1fr);gap:8px;align-items:center;margin-bottom:8px}.replace-label{color:#677085;font-size:11px;font-weight:700}
.replace input,.replace select{height:38px;border:1px solid #dfe5ee;border-radius:12px;padding:0 12px;background:#fff;color:#1d2430}
.replace-actions{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin:6px 0 8px}.replace-actions button,.replace-batch button{height:38px;border-radius:12px;font-size:13px}
.replace-actions button{background:#edf3ed;color:#8ab6a7}.replace-batch{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:10px}.replace-batch button{background:#80b7a2;color:#fff}
.replace-batch button:disabled,.replace-actions button:disabled{opacity:.52}
.replace-options{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:8px;align-items:center;color:#667085;font-size:11px}
.replace-options label{display:inline-flex;gap:5px;align-items:center;white-space:nowrap}
.scope-switch{display:grid;grid-template-columns:repeat(3,1fr);gap:4px;padding:4px;border:1px solid #dfe5ee;border-radius:14px;background:#f5f8fb;min-width:0}
.scope-btn{height:34px;border-radius:10px;background:transparent;color:#8a93a3;font-size:12px;font-weight:700;padding:0 10px;white-space:nowrap}
.scope-btn.active{background:#fff;color:#1d2430;box-shadow:0 1px 3px rgba(15,23,42,.08)}
.check{width:18px;height:18px}
.confirm-mask{position:fixed;inset:0;background:rgba(15,23,42,.26);backdrop-filter:blur(10px);z-index:40}
.confirm-card{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);width:min(calc(100vw - 32px),460px);background:#fff;border-radius:28px;padding:26px 22px calc(20px + env(safe-area-inset-bottom));box-shadow:0 22px 60px rgba(15,23,42,.18);z-index:41}
.confirm-card h3{margin:0 0 12px;font-size:20px;font-weight:900}
.confirm-card p{margin:0;color:#667085;font-size:14px;line-height:1.7}
.confirm-card p strong{color:#344054}
.confirm-actions{display:grid;grid-template-columns:1fr;gap:10px;margin-top:16px}
.confirm-actions button{width:100%;height:56px;border-radius:10px;font-size:17px;font-weight:800}
.confirm-actions .cancel{background:#eef2f7;color:#667085}
.confirm-actions .plain{background:#f9ecee;color:#ba5567}
.confirm-actions .primary{background:#1f8a63;color:#fff}
.toast{position:fixed;left:50%;bottom:38px;transform:translateX(-50%);background:rgba(0,0,0,.78);color:#fff;padding:9px 14px;border-radius:999px;font-size:14px;z-index:30;opacity:0;transition:opacity .18s}.toast.show{opacity:1}
html[data-theme="dark"] .sheet{background:#191c20}html[data-theme="dark"] .sheet .plain{background:#252932}html[data-theme="dark"] .sheet .danger{background:#402126;color:#ffb4b4}html[data-theme="dark"] .field,html[data-theme="dark"] .highlight,html[data-theme="dark"] .editor-stack,html[data-theme="dark"] .code-wrap,html[data-theme="dark"] .font-preview{background:#15171c;color:#e5e6eb}html[data-theme="dark"] .lines{background:#111318;color:#7f8794}html[data-theme="dark"] .code-input{caret-color:#e5e6eb}html[data-theme="dark"] .editor-head,html[data-theme="dark"] .topbar{background:var(--bg);border-color:#2a2f38}html[data-theme="dark"] .title,html[data-theme="dark"] .editor-title strong{color:#f2f4f7}html[data-theme="dark"] .editor-title small,html[data-theme="dark"] .tool,html[data-theme="dark"] .back{color:#b5bac5}html[data-theme="dark"] .meta-card{background:#191c20;border-color:#2a2f38}html[data-theme="dark"] .meta-row label{color:#aeb5c2}html[data-theme="dark"] .meta-row input,html[data-theme="dark"] .meta-tags{background:#15171c;border-color:#303742;color:#e5e6eb}html[data-theme="dark"] .meta-row input.readonly{background:#12151a;color:#cdd4df}html[data-theme="dark"] .meta-cover{background:#151a20;border-color:#303742;color:#8d96a5}html[data-theme="dark"] .meta-actions .secondary{background:#1f2b26;color:#a9d2c1;border-color:#294137}html[data-theme="dark"] .replace-card{background:rgba(25,28,32,.96);border-color:#2a2f38}html[data-theme="dark"] .replace input,html[data-theme="dark"] .replace select{background:#171a1f;border-color:#303742;color:#e5e6eb}html[data-theme="dark"] .replace-label,html[data-theme="dark"] .replace-progress,html[data-theme="dark"] .replace-count,html[data-theme="dark"] .replace-options{color:#98a2b3}html[data-theme="dark"] .replace-actions button{background:#233027;color:#9dc8b9}html[data-theme="dark"] .replace-batch button{background:#5f927f}html[data-theme="dark"] .scope-switch{background:#171a1f;border-color:#303742}html[data-theme="dark"] .scope-btn{color:#9ca7b6}html[data-theme="dark"] .scope-btn.active{background:#222833;color:#e5e6eb}html[data-theme="dark"] .icon-search{background:#1f3340;color:#8ec9f0}html[data-theme="dark"] .icon-save{background:#21352b;color:#8ab9a6}html[data-theme="dark"] .icon-save.dirty{background:#c14961;color:#fff}html[data-theme="dark"] .icon-close{background:#3b252b;color:#f0b7c1}html[data-theme="dark"] .confirm-card{background:#191c20}html[data-theme="dark"] .confirm-card h3{color:#f2f4f7}html[data-theme="dark"] .confirm-card p{color:#98a2b3}html[data-theme="dark"] .confirm-card p strong{color:#f2f4f7}html[data-theme="dark"] .confirm-actions .cancel{background:#2a2f38;color:#c5cad4}html[data-theme="dark"] .confirm-actions .plain{background:#41272d;color:#ffb8c6}html[data-theme="dark"] .confirm-actions .primary{background:#216b52}html[data-theme="dark"] .hl-tag{color:#8db6ff}html[data-theme="dark"] .hl-name{color:#7bd3a8}html[data-theme="dark"] .hl-attr{color:#f4b37a}html[data-theme="dark"] .hl-string{color:#ff9f93}html[data-theme="dark"] .hl-comment{color:#7e8795}html[data-theme="dark"] .hl-key{color:#d5a3ff}html[data-theme="dark"] .hl-num{color:#7ad0e3}html[data-theme="dark"] .hl-punc{color:#aab2bf}
@media (prefers-color-scheme:dark){.sheet{background:#191c20}.sheet .plain{background:#252932}.sheet .danger{background:#402126;color:#ffb4b4}.field,.highlight,.editor-stack,.code-wrap,.font-preview{background:#15171c;color:#e5e6eb}.lines{background:#111318;color:#7f8794}.code-input{caret-color:#e5e6eb}.editor-head,.topbar{background:var(--bg);border-color:#2a2f38}.title,.editor-title strong{color:#f2f4f7}.editor-title small,.tool,.back{color:#b5bac5}.replace-card{background:rgba(25,28,32,.96);border-color:#2a2f38}.replace input,.replace select{background:#171a1f;border-color:#303742;color:#e5e6eb}.replace-label,.replace-progress,.replace-count,.replace-options{color:#98a2b3}.replace-actions button{background:#233027;color:#9dc8b9}.replace-batch button{background:#5f927f}.scope-switch{background:#171a1f;border-color:#303742}.scope-btn{color:#9ca7b6}.scope-btn.active{background:#222833;color:#e5e6eb}.icon-search{background:#1f3340;color:#8ec9f0}.icon-save{background:#21352b;color:#8ab9a6}.icon-save.dirty{background:#c14961;color:#fff}.icon-close{background:#3b252b;color:#f0b7c1}.confirm-card{background:#191c20}.confirm-card h3{color:#f2f4f7}.confirm-card p{color:#98a2b3}.confirm-card p strong{color:#f2f4f7}.confirm-actions .cancel{background:#2a2f38;color:#c5cad4}.confirm-actions .plain{background:#41272d;color:#ffb8c6}.confirm-actions .primary{background:#216b52}.hl-tag{color:#8db6ff}.hl-name{color:#7bd3a8}.hl-attr{color:#f4b37a}.hl-string{color:#ff9f93}.hl-comment{color:#7e8795}.hl-key{color:#d5a3ff}.hl-num{color:#7ad0e3}.hl-punc{color:#aab2bf}}
@media (max-width:420px){.meta{padding:10px 14px 24px}.meta-card{padding:12px;border-radius:8px}.meta-grid{grid-template-columns:minmax(0,1fr) 112px;gap:12px}.meta-cover{width:112px}.meta-two{grid-template-columns:1fr}.meta-actions{grid-template-columns:1fr 1fr;gap:10px}.meta-actions button{height:48px;font-size:14px}.editor-head{padding:8px 12px 6px}.editor-actions{grid-auto-columns:34px;gap:6px}.icon-btn{width:34px;height:34px;border-radius:10px}.icon-btn svg{width:18px;height:18px}.replace{padding:0 8px 4px}.replace-card{padding:10px 10px 8px;border-radius:18px}.replace-row{grid-template-columns:54px minmax(0,1fr);gap:7px}.replace input,.replace select{height:36px;padding:0 10px}.replace-actions{gap:5px}.replace-actions button,.replace-batch button{height:36px;font-size:12px}.replace-options{grid-template-columns:1fr 1fr;grid-template-areas:"scope scope" "regex text";gap:7px}.scope-switch{grid-area:scope}.replace-options label:nth-of-type(1){grid-area:regex}.replace-options label:nth-of-type(2){grid-area:text;justify-self:end}.scope-btn{font-size:11px;padding:0 6px}}
</style>
</head>
<body>
<main id="app" class="app">
  <header class="topbar"><button class="back" onclick="FileEditorNative.closeOrBack()">‹</button><div id="title" class="title" style="padding-top:8px;line-height:1"></div><button class="tool" onclick="FileEditorNative.refresh()">↻</button></header>
  <section id="meta" class="meta"></section>
  <p id="status" class="status"></p>
  <section id="tree"></section>
  <section id="editor" class="editor">
    <div class="editor-head"><div class="editor-title"><strong id="editName"></strong><small id="editPath"></small></div><div class="editor-actions"><button id="searchBtn" class="icon-btn icon-search" onclick="FileEditorNative.toggleReplace()"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5"></path></svg></button><button id="saveBtn" class="icon-btn icon-save" onclick="FileEditorNative.save()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h11l3 3v13H5z"></path><path d="M8 4v6h8V4"></path><path d="M9 20v-6h6v6"></path></svg></button><button class="icon-btn icon-close" onclick="FileEditorNative.closeEditor()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12"></path><path d="M18 6L6 18"></path></svg></button></div></div>
    <div id="editorBody" class="code-wrap"><pre id="lines" class="lines">1</pre><textarea id="text"></textarea></div>
    <div id="replace" class="replace"><div class="replace-card"><div class="replace-head"><strong>查找替换</strong><div class="replace-meta"><span id="replaceStatus" class="replace-progress"></span><span id="scopeCount" class="replace-count"></span></div></div><div class="replace-row"><span class="replace-label">查找</span><input id="find" placeholder="输入查找内容"></div><div class="replace-row"><span class="replace-label">替换为</span><input id="repl" placeholder="留空则删除"></div><div class="replace-actions"><button onclick="FileEditorNative.findPrev()">上一个</button><button onclick="FileEditorNative.count()">计数</button><button onclick="FileEditorNative.findNext()">下一个</button></div><div class="replace-batch"><button onclick="FileEditorNative.replaceCurrent()">替换当前</button><button onclick="FileEditorNative.replaceAll()">替换全部</button></div><div class="replace-options"><input id="scope" type="hidden" value="current"><div class="scope-switch"><button type="button" id="scopeCurrent" class="scope-btn active" onclick="FileEditorNative.setScope('current')">当前文件</button><button type="button" id="scopeHtml" class="scope-btn" onclick="FileEditorNative.setScope('html')">HTML章节</button><button type="button" id="scopeAll" class="scope-btn" onclick="FileEditorNative.setScope('all')">所有文本</button></div><label><input id="regex" class="check" type="checkbox"> 正则</label><label><input id="textOnly" class="check" type="checkbox"> 仅文本</label></div></div></div>
  </section>
  <div id="toast" class="toast"></div>
  <div id="searchMeasure" class="search-measure"></div>
</main>
<script>
const api=window.ReaMicroFileEditor;
const state={title:"",status:"",metadata:{},files:[],groups:{},open:{},editing:null,content:"",dirty:false,matches:[],matchIndex:-1,actionFile:null,renameFile:null,searchResults:[],searchResultIndex:-1,decorating:false,searchDirty:true,renderVersion:0,layoutVersion:-1,layoutNodes:[],layoutLength:0,layoutFrame:0,editorSyncFrame:0,openSearchToken:0,scopeJobToken:0,searchSessionToken:0,treeScrollTop:0,lineOffsetCacheText:null,lineOffsetCache:[0],page:"meta",coverPickerArmed:0,coverPicking:false,coverPickerBlockedUntil:0};
const byId=(id)=>document.getElementById(id);
const esc=(s)=>String(s==null?"":s).replace(/[&<>"']/g,(c)=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
function callJson(fn){try{const raw=fn();return JSON.parse(raw||"{}");}catch(e){return {ok:false,message:String(e)}}}
function toast(msg){try{api.toast(String(msg||""));return}catch(e){}const el=byId("toast");el.textContent=msg;el.classList.add("show");setTimeout(()=>el.classList.remove("show"),1600)}
function updateSaveState(){const btn=byId("saveBtn");if(btn)btn.classList.toggle("dirty",!!state.dirty)}
function nativeDarkMode(){try{return !!api.isDarkMode()}catch(e){return null}}
function applyTheme(dark){document.documentElement.dataset.theme=dark?"dark":"light";try{api.setTheme(!!dark)}catch(e){}}
function watchTheme(){const query=window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)"):null;let last=null;const update=()=>{const native=nativeDarkMode();const dark=native===null?(query?!!query.matches:document.documentElement.dataset.theme==="dark"):native;if(dark===last)return;last=dark;applyTheme(dark)};update();if(query){if(query.addEventListener)query.addEventListener("change",update);else if(query.addListener)query.addListener(update)}setInterval(update,1000)}
function rememberTreeScroll(){if(!state.editing)state.treeScrollTop=window.scrollY||document.documentElement.scrollTop||document.body.scrollTop||0}
function restoreTreeScroll(){requestAnimationFrame(()=>window.scrollTo(0,state.treeScrollTop||0))}
function groupFiles(){const map={};state.files.forEach(f=>{const k=f.group||"ROOT";if(!map[k])map[k]={name:k,path:f.groupPath||"",files:[]};map[k].files.push(f);if(state.open[k]===undefined)state.open[k]=true});state.groups=map}
function mergeDecorations(nextFiles,invalidatePaths=[]){const invalid=new Set(invalidatePaths||[]);const previous=new Map((state.files||[]).map(f=>[f.path,f]));return nextFiles.map(file=>{const old=previous.get(file.path);if(!old||invalid.has(file.path))return file;if(!file.preview&&old.preview)file.preview=old.preview;if((!file.detail||file.detail==="章节内容")&&old.detail&&old.detail!=="章节内容")file.detail=old.detail;return file})}
function refresh(invalidatePaths=[]){const arr=callJson(()=>api.listFiles());if(Array.isArray(arr)){state.files=mergeDecorations(arr,invalidatePaths);state.decorating=false;groupFiles();renderTree();restoreTreeScroll();byId("status").textContent="已解包 "+state.files.length+" 个文件";queueDecorations()}}
function renderAll(){byId("title").textContent=state.page==="tree"?"EPUB 文件树":"EPUB元数据";byId("status").textContent=state.status||"";document.getElementById("app").classList.toggle("tree-mode",state.page==="tree");renderMetaPage();groupFiles();renderTree();queueDecorations();loadCoverPreview()}
function renderMetaPage(){const m=state.metadata||{};byId("meta").innerHTML='<div class="meta-card"><div class="meta-grid"><div class="meta-fields"><div class="meta-row"><label>书名</label><input id="metaTitle" value="'+esc(m.title||state.title||"")+'"></div><div class="meta-row"><label>作者</label><input id="metaAuthor" value="'+esc(m.author||"")+'"></div></div><button class="meta-cover" id="metaCover" ontouchstart="FileEditorNative.armCoverPicker()" onmousedown="FileEditorNative.armCoverPicker()" onclick="FileEditorNative.pickCoverImage()"><span>点击选取封面</span></button></div><div class="meta-two" style="margin-top:14px"><div class="meta-row"><label>副标题</label><input id="metaSubtitle" value="'+esc(m.subtitle||"")+'"></div><div class="meta-row"><label>出版社</label><input id="metaPublisher" value="'+esc(m.publisher||"")+'"></div><div class="meta-row"><label>制作信息</label><input id="metaMaker" value="'+esc(m.maker||"")+'"></div><div class="meta-row"><label>制作系列</label><input id="metaSeries" value="'+esc(m.series||"")+'"></div><div class="meta-row meta-wide"><label>UUID / 标识符</label><input class="readonly" id="metaUuid" readonly onclick="FileEditorNative.copyUuid()" value="'+esc(m.uuid||"")+'"></div></div><p class="meta-hint">UUID 使用阅微/EPUB 当前生成值，仅可点击复制，不允许直接修改。</p><div class="meta-actions"><button class="secondary" onclick="FileEditorNative.openTreePage()">编辑 EPUB 文件</button><button class="primary" onclick="FileEditorNative.saveMeta()">保存</button></div></div>'}
function collectMetaForm(){return{title:byId("metaTitle")?.value||"",author:byId("metaAuthor")?.value||"",subtitle:byId("metaSubtitle")?.value||"",publisher:byId("metaPublisher")?.value||"",maker:byId("metaMaker")?.value||"",series:byId("metaSeries")?.value||""}}
function saveMeta(){const r=callJson(()=>api.saveMetadata(JSON.stringify(collectMetaForm())));toast(r.message||"已保存");if(r.ok&&r.metadata){state.metadata=r.metadata;renderMetaPage();loadCoverPreview();if(r.coverChanged)refresh([state.metadata.coverPath])}}
function openTreePage(){state.page="tree";renderAll();window.scrollTo(0,0)}
function copyUuid(){const value=state.metadata?.uuid||byId("metaUuid")?.value||"";api.copyText(value)}
function armCoverPicker(){state.coverPickerArmed=Date.now()}
function pickCoverImage(){const now=Date.now();if(state.coverPicking||now<state.coverPickerBlockedUntil||now-(state.coverPickerArmed||0)>1200)return;state.coverPicking=true;api.pickCoverImage();setTimeout(()=>{state.coverPicking=false},5000)}
function loadCoverPreview(){if(state.page!=="meta")return;const r=callJson(()=>api.coverThumbnail());if(r.ok&&r.dataUrl){const el=byId("metaCover");if(el)el.innerHTML='<img src="'+r.dataUrl+'" alt="cover">'}}
function onCoverResult(raw){state.coverPicking=false;state.coverPickerArmed=0;state.coverPickerBlockedUntil=Date.now()+6000;const r=JSON.parse(raw||"{}");toast(r.message||"封面已选择");if(r.ok&&r.cover&&r.cover.dataUrl){const el=byId("metaCover");if(el)el.innerHTML='<img src="'+r.cover.dataUrl+'" alt="cover">'}}
function invalidateDecorations(paths){const invalid=new Set(changedPathsForRefresh(paths));if(!invalid.size)return;state.files.forEach(file=>{if(!invalid.has(file.path))return;if(file.kind==="html")file.detail="章节内容";if(file.kind==="image")file.preview=""})}
function renderTree(){const root=byId("tree");const keys=Object.keys(state.groups).filter(k=>!/^META-INF$/i.test(k)).sort();if(!keys.length){root.innerHTML='<div class="empty">这个图书目录暂时没有文件</div>';return}root.innerHTML=keys.map(k=>{const g=state.groups[k];const closed=!state.open[k];return '<section class="section '+(closed?'closed':'')+'"><div class="group-head"><button class="group-title" onclick="FileEditorNative.toggleGroup(\''+escAttr(k)+'\')">'+esc(g.name)+'</button><button class="group-icon" onclick="FileEditorNative.openAddSheet(\''+escAttr(g.path||'')+'\')">＋</button><button class="group-icon chev" onclick="FileEditorNative.toggleGroup(\''+escAttr(k)+'\')"><svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M6 9l6 6 6-6\"></path></svg></button></div>'+(closed?'':g.files.map(fileRow).join(''))+'</section>'}).join("")}
function fileRow(f){const icon=f.kind==="image"&&f.preview?'<img class="thumb" src="'+f.preview+'">':'<div class="kind-icon kind-'+(f.kind||"other")+'" style="background:'+esc(f.color||"")+'">'+iconFor(f)+'</div>';return '<div class="file-row" data-path="'+escAttr(f.path)+'"><button onclick="FileEditorNative.openFile(\''+escAttr(f.path)+'\')">'+icon+'</button><button class="file-copy" onclick="FileEditorNative.openFile(\''+escAttr(f.path)+'\')"><div class="file-name">'+esc(f.stem||f.name)+'</div><div class="file-detail">'+esc(f.detail||f.sizeText)+'</div></button><button class="more" onclick="FileEditorNative.openActionSheet(\''+escAttr(f.path)+'\')">⋮</button></div>'}
function iconFor(f){if(f.kind==="html")return "&lt;/&gt;";if(f.kind==="css")return "{}";if(f.kind==="xml")return f.name&&f.name.toLowerCase().includes("content")?"▤":"≡";if(f.kind==="font")return "T";return "•"}
function escAttr(s){return String(s||"").replace(/\\/g,"\\\\").replace(/'/g,"\\'").replace(/\n/g," ")}
function updateScopeCount(){const scope=byId("scope")?.value||"current";const count=scopeFiles(scope).length;byId("scopeCount").textContent=count+" 个文件"}
function updateScopeUi(){const scope=byId("scope")?.value||"current";["current","html","all"].forEach(key=>{const el=byId("scope"+key.charAt(0).toUpperCase()+key.slice(1));if(el)el.classList.toggle("active",scope===key)})}
function clearVisibleMatches(){state.matches=[];state.matchIndex=-1;syncSearchHits();updateSearchStatus()}
function setScope(scope){const input=byId("scope");if(!input||input.value===scope)return;input.value=scope;updateScopeUi();state.searchDirty=true;updateScopeCount();clearVisibleMatches();const token=++state.scopeJobToken;if(byId("find")?.value){updateSearchStatus("搜索中...");setTimeout(()=>{if(token!==state.scopeJobToken||input.value!==scope)return;buildMatches(true)},0)}else updateSearchStatus()}
function updateEditorBottomInset(){const replace=byId("replace");const open=replace&&replace.classList.contains("open");const bottom=open?(replace.offsetHeight||0):0;document.documentElement.style.setProperty("--editor-bottom",bottom+"px");if(state.editing&&byId("text"))scheduleLayoutSync()}
function updateMatchLine(){}
function queueDecorations(){if(state.decorating)return;state.decorating=true;const jobs=state.files.filter(f=>(f.kind==="image"&&!f.preview)||(f.kind==="html"&&f.detail==="章节内容"));let index=0;function step(){const file=jobs[index++];if(!file){state.decorating=false;return}const res=callJson(()=>api.readDecoration(file.path));if(res.ok){const target=state.files.find(x=>x.path===res.path);if(target){if(res.detail)target.detail=res.detail;if(res.preview)target.preview=res.preview;const row=document.querySelector('[data-path="'+CSS.escape(res.path)+'"]');if(row){const copy=row.querySelector('.file-copy');if(copy){const detail=copy.querySelector('.file-detail');if(detail&&res.detail)detail.textContent=res.detail}if(res.preview){const first=row.querySelector('button');if(first)first.innerHTML='<img class="thumb" src="'+res.preview+'">'}}}}if(index<jobs.length)setTimeout(step,0);else state.decorating=false}setTimeout(step,0)}
async function openFile(path,preserveSearch=false,invalidateSearchToken=true){if(state.editing&&state.editing.path!==path){if(!(await confirmLeaveEditor()))return}const f=state.files.find(x=>x.path===path);if(!f)return;rememberTreeScroll();if(invalidateSearchToken)state.openSearchToken++;if(f.kind==="image"){const r=callJson(()=>api.readDataUrl(path));if(r.ok){showPreview(f,'<div class="preview"><img src="'+r.dataUrl+'"></div>')}else toast(r.message);return}if(f.kind==="font"){const r=callJson(()=>api.readDataUrl(path));if(r.ok){showPreview(f,'<div class="font-preview" style="font-family:previewFont"><style>@font-face{font-family:previewFont;src:url('+r.dataUrl+')}</style><p>字体预览 ABC abc 12345</p><p>风起云涌，江湖夜雨十年灯。</p><p>我加载了怪谈游戏</p></div>')}else toast(r.message);return}if(!f.editable){toast("该文件类型暂不支持编辑");return}const r=callJson(()=>api.readText(path));if(!r.ok){toast(r.message);return}state.editing=f;state.content=r.content||"";state.dirty=false;state.renderVersion=0;state.layoutVersion=-1;state.layoutNodes=[];state.layoutLength=0;state.lineOffsetCacheText=null;state.lineOffsetCache=[0];if(!preserveSearch){state.matches=[];state.matchIndex=-1;state.searchResults=[];state.searchResultIndex=-1;state.searchDirty=true}else{state.matches=[];state.matchIndex=-1}byId("app").classList.add("editing");byId("editName").textContent=f.name;byId("editPath").textContent=f.path;byId("editorBody").className="code-wrap";byId("editorBody").innerHTML='<div id="lines" class="lines"><div id="lineCanvas" class="line-canvas"></div></div><div class="editor-stack"><pre id="highlight" class="highlight"></pre><div id="searchOverlay" class="search-overlay"><div id="searchCanvas" class="search-canvas"></div></div><textarea id="text" class="code-input" spellcheck="false" autocapitalize="off" autocomplete="off" autocorrect="off"></textarea></div>';const text=byId("text");text.value=state.content;text.oninput=()=>{state.dirty=true;updateSaveState();state.matches=[];state.matchIndex=-1;state.searchDirty=true;state.lineOffsetCacheText=null;if(!preserveSearch){state.searchResults=[];state.searchResultIndex=-1}requestEditorSync()};text.onscroll=syncScroll;byId("searchBtn").disabled=false;wireSearchInputs();syncEditor();updateScopeCount();updateEditorBottomInset();updateSaveState()}
function showPreview(f,html){state.editing=f;state.dirty=false;byId("app").classList.add("editing");byId("editName").textContent=f.name;byId("editPath").textContent=f.path;byId("editorBody").className="";byId("editorBody").innerHTML=html;byId("replace").classList.remove("open");byId("searchBtn").disabled=true;updateScopeCount();updateEditorBottomInset();updateSaveState()}
function syncEditor(){syncHighlight();scheduleLayoutSync()}
function requestEditorSync(){if(state.editorSyncFrame)return;state.editorSyncFrame=requestAnimationFrame(()=>{state.editorSyncFrame=0;syncEditor();updateSearchStatus()})}
function scheduleLayoutSync(after){if(state.layoutFrame)cancelAnimationFrame(state.layoutFrame);const version=state.renderVersion;state.layoutFrame=requestAnimationFrame(()=>{state.layoutFrame=0;if(version!==state.renderVersion)return;syncLines();syncSearchHits();syncScroll();if(typeof after==="function")after()})}
function getBoxPadding(el){const style=getComputedStyle(el);return{top:parseFloat(style.paddingTop)||0,right:parseFloat(style.paddingRight)||0,bottom:parseFloat(style.paddingBottom)||0,left:parseFloat(style.paddingLeft)||0,lineHeight:parseFloat(style.lineHeight)||22}}
function contentMetrics(el){const pad=getBoxPadding(el);return{pad,width:Math.max(0,el.scrollWidth-pad.left-pad.right),height:Math.max(0,el.scrollHeight-pad.top-pad.bottom),viewportWidth:Math.max(0,el.clientWidth-pad.left-pad.right),viewportHeight:Math.max(0,el.clientHeight-pad.top-pad.bottom)}}
function ensureLineOffsets(textValue){if(state.lineOffsetCacheText===textValue)return state.lineOffsetCache;const offsets=[0];let idx=textValue.indexOf("\n");while(idx!==-1){offsets.push(idx+1);idx=textValue.indexOf("\n",idx+1)}state.lineOffsetCacheText=textValue;state.lineOffsetCache=offsets;return offsets}
function syncLines(){const text=byId("text"),lineCanvas=byId("lineCanvas"),hi=byId("highlight");if(!text||!lineCanvas||!hi)return;const metrics=contentMetrics(text);lineCanvas.style.height=Math.max(metrics.height,metrics.viewportHeight,getBoxPadding(text).lineHeight)+"px";const offsets=ensureLineOffsets(text.value);const rows=[];for(let i=0;i<offsets.length;i++){const start=offsets[i];const end=i+1<offsets.length?Math.max(start,offsets[i+1]-1):text.value.length;const rects=measureOffsetRects(start,end);if(rects.length){rects.forEach((rect,index)=>{rows.push('<div class="line-row" style="top:'+rect.top+'px;height:'+Math.max(rect.height,metrics.pad.lineHeight)+'px">'+(index===0?String(i+1):"")+'</div>')})}else{const top=measureCaretTop(start);rows.push('<div class="line-row" style="top:'+top+'px;height:'+metrics.pad.lineHeight+'px">'+String(i+1)+'</div>')}}lineCanvas.innerHTML=rows.join("")}
function syncScroll(){const text=byId("text"),hi=byId("highlight"),overlay=byId("searchOverlay"),lines=byId("lines"),lineCanvas=byId("lineCanvas");if(!text)return;if(hi){hi.scrollTop=text.scrollTop;hi.scrollLeft=text.scrollLeft}if(overlay){overlay.scrollTop=text.scrollTop;overlay.scrollLeft=text.scrollLeft}if(lines&&lineCanvas){lineCanvas.style.transform='translateY('+-text.scrollTop+'px)'}updateMatchLine()}
function syncHighlight(){const text=byId("text"),hi=byId("highlight");if(!text||!hi)return;hi.innerHTML=highlightCode(text.value,state.editing||{});state.renderVersion++;state.layoutVersion=-1;state.layoutNodes=[];state.layoutLength=0}
function highlightCode(code,file){const kind=(file.kind||"").toLowerCase();if(kind==="xml"||kind==="html")return highlightMarkup(code);if(kind==="css")return highlightCss(code);return highlightScript(code)}
function escapeCode(code){return String(code||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")}
function restorePlaceholders(result,placeholders){placeholders.forEach((value,index)=>{result=result.replace(new RegExp("___PH"+index+"___","g"),value)});return result}
function highlightMarkup(code){let result=escapeCode(code);const placeholders=[];let placeholderIndex=0;result=result.replace(/(&lt;!--[\s\S]*?--&gt;)/g,match=>{const key="___PH"+placeholderIndex+"___";placeholders[placeholderIndex++]='<span class="hl-comment">'+match+'</span>';return key});result=result.replace(/(&lt;\?)([\w:-]+)|(&lt;\/?)([\w:-]+)/g,(match,piOpen,piName,tagOpen,tagName)=>{const key="___PH"+placeholderIndex+"___";const open=piOpen||tagOpen||"";const name=piName||tagName||"";placeholders[placeholderIndex++]='<span class="hl-tag">'+open+'</span><span class="hl-name">'+name+'</span>';return key});result=result.replace(/(\s)([\w:-]+)(=)/g,(match,space,name,equals)=>{const key="___PH"+placeholderIndex+"___";placeholders[placeholderIndex++]=space+'<span class="hl-attr">'+name+'</span><span class="hl-punc">'+equals+'</span>';return key});result=result.replace(/(&quot;[^&]*?&quot;|'[^']*?')/g,match=>{const key="___PH"+placeholderIndex+"___";placeholders[placeholderIndex++]='<span class="hl-string">'+match+'</span>';return key});result=result.replace(/(&lt;!DOCTYPE)([\s\S]*?)(&gt;)/gi,(match,open,body,close)=>'<span class="hl-tag">'+open+'</span><span class="hl-punc">'+body+'</span><span class="hl-tag">'+close+'</span>');result=result.replace(/(\/?&gt;|\?&gt;)/g,'<span class="hl-tag">$1</span>');return restorePlaceholders(result,placeholders)}
function highlightCss(code){let result=escapeCode(code);const placeholders=[];let placeholderIndex=0;result=result.replace(/(\/\*[\s\S]*?\*\/)/g,match=>{const key="___PH"+placeholderIndex+"___";placeholders[placeholderIndex++]='<span class="hl-comment">'+match+'</span>';return key});result=result.replace(/([\w-]+)(\s*)(:)/g,(match,name,space,colon)=>{const key="___PH"+placeholderIndex+"___";placeholders[placeholderIndex++]='<span class="hl-attr">'+name+'</span>'+space+'<span class="hl-punc">'+colon+'</span>';return key});result=result.replace(/(&quot;.*?&quot;|'[^']*?')/g,match=>{const key="___PH"+placeholderIndex+"___";placeholders[placeholderIndex++]='<span class="hl-string">'+match+'</span>';return key});result=result.replace(/([{};(),])/g,'<span class="hl-punc">$1</span>');result=result.replace(/(#[0-9a-fA-F]{3,8}\b)/g,'<span class="hl-num">$1</span>');return restorePlaceholders(result,placeholders)}
function highlightScript(code){return escapeCode(code).replace(/(&quot;.*?&quot;|'[^']*?')/g,'<span class="hl-string">$1</span>').replace(/\b(function|const|let|var|return|if|else|for|while|true|false|null)\b/g,'<span class="hl-key">$1</span>').replace(/\b(\d+(?:\.\d+)?)\b/g,'<span class="hl-num">$1</span>')}
function ensureLayoutNodes(){if(state.layoutVersion===state.renderVersion)return state.layoutNodes;const hi=byId("highlight");if(!hi){state.layoutNodes=[];state.layoutLength=0;return state.layoutNodes}const nodes=[];const walker=document.createTreeWalker(hi,NodeFilter.SHOW_TEXT);let pos=0,node;while((node=walker.nextNode())){const value=node.nodeValue||"";if(!value.length)continue;nodes.push({node,start:pos,end:pos+value.length});pos+=value.length}state.layoutVersion=state.renderVersion;state.layoutNodes=nodes;state.layoutLength=pos;return nodes}
function locateTextOffset(offset){const nodes=ensureLayoutNodes();if(!nodes.length)return null;const safe=Math.max(0,Math.min(offset,state.layoutLength));let low=0,high=nodes.length-1,match=high;while(low<=high){const mid=(low+high)>>1;const item=nodes[mid];if(safe<item.start){high=mid-1}else if(safe>item.end){low=mid+1}else{match=mid;break}}const entry=nodes[match];if(safe===entry.end&&match+1<nodes.length)return{node:nodes[match+1].node,offset:0};return{node:entry.node,offset:Math.max(0,Math.min(safe-entry.start,(entry.node.nodeValue||"").length))}}
function buildDomRange(start,end){const s=locateTextOffset(start);const e=locateTextOffset(end);if(!s||!e)return null;const range=document.createRange();range.setStart(s.node,s.offset);range.setEnd(e.node,e.offset);return range}
function measureCaretTop(offset){const text=byId("text"),hi=byId("highlight");if(!text||!hi)return 0;const metrics=contentMetrics(text);const range=buildDomRange(offset,offset);if(!range)return Math.max(0,Math.min(text.scrollHeight,ensureLineOffsets(text.value).indexOf(offset)*metrics.pad.lineHeight));const rect=range.getBoundingClientRect();const hiRect=hi.getBoundingClientRect();return Math.max(0,rect.top-hiRect.top-metrics.pad.top+hi.scrollTop)}
function measureOffsetRects(start,end){const text=byId("text"),hi=byId("highlight");if(!text||!hi)return[];const metrics=contentMetrics(text);const range=buildDomRange(start,end);if(!range)return[];const hiRect=hi.getBoundingClientRect();const rects=Array.from(range.getClientRects()).filter(rect=>rect.width>0||rect.height>0);if(rects.length)return rects.map(rect=>({top:Math.max(0,rect.top-hiRect.top-metrics.pad.top+hi.scrollTop),left:Math.max(0,rect.left-hiRect.left-metrics.pad.left+hi.scrollLeft),width:Math.max(2,rect.width),height:Math.max(metrics.pad.lineHeight,rect.height)}));const top=measureCaretTop(start);return[{top,left:0,width:2,height:metrics.pad.lineHeight}]}
function configureSearchMeasure(){const text=byId("text"),measure=byId("searchMeasure");if(!text||!measure)return null;const style=getComputedStyle(text);measure.style.width=text.clientWidth+"px";measure.style.fontFamily=style.fontFamily;measure.style.fontSize=style.fontSize;measure.style.fontWeight=style.fontWeight;measure.style.lineHeight=style.lineHeight;measure.style.letterSpacing=style.letterSpacing;measure.style.padding=style.padding;measure.style.border="0";measure.style.whiteSpace=style.whiteSpace;measure.style.wordBreak=style.wordBreak;measure.style.wordWrap=style.wordWrap;measure.style.tabSize=style.tabSize||"2";measure.style.webkitTextSizeAdjust="100%";return measure}
function measureSearchRects(start,end){const text=byId("text"),measure=configureSearchMeasure();if(!text||!measure)return[];const safeStart=Math.max(0,Math.min(start,text.value.length));const safeEnd=Math.max(safeStart,Math.min(end,text.value.length));const before=esc(text.value.slice(0,safeStart));const hitRaw=text.value.slice(safeStart,safeEnd)||" ";const after=esc(text.value.slice(safeEnd))||" ";measure.innerHTML=before+'<span id="searchMeasureHit">'+esc(hitRaw)+'</span>'+after;const mark=byId("searchMeasureHit");if(!mark)return[];const measureRect=measure.getBoundingClientRect();const pad=getBoxPadding(text);const rects=Array.from(mark.getClientRects()).filter(rect=>rect.width>0||rect.height>0);if(rects.length)return rects.map(rect=>({top:Math.max(0,rect.top-measureRect.top-pad.top),left:Math.max(0,rect.left-measureRect.left-pad.left),width:Math.max(2,rect.width),height:Math.max(pad.lineHeight,rect.height)}));return[{top:measureCaretTop(safeStart),left:0,width:2,height:pad.lineHeight}]}
function syncSearchHits(){const canvas=byId("searchCanvas"),text=byId("text");if(!canvas||!text)return;const metrics=contentMetrics(text);canvas.style.width=Math.max(metrics.width,metrics.viewportWidth,2)+"px";canvas.style.height=Math.max(metrics.height,metrics.viewportHeight,metrics.pad.lineHeight)+"px";const current=state.matches[state.matchIndex];if(!current){canvas.innerHTML="";return}const html=measureSearchRects(current[0],current[1]).map(rect=>'<span class="search-hit current" style="top:'+rect.top+'px;left:'+rect.left+'px;width:'+rect.width+'px;height:'+rect.height+'px"></span>');canvas.innerHTML=html.join("")}
function changedPathsForRefresh(paths){return Array.from(new Set((paths||[]).filter(Boolean)))}
function persistCurrentFile(showToast=true){if(!state.editing||!byId("text"))return false;const currentPath=state.editing.path;const r=callJson(()=>api.writeText(currentPath,byId("text").value));if(showToast)toast(r.message||"已保存");if(!r.ok)return false;state.dirty=false;updateSaveState();const refreshPaths=changedPathsForRefresh([currentPath]);invalidateDecorations(refreshPaths);refresh(refreshPaths);return true}
async function save(){if(!state.editing)return;if(!byId("text")){toast("预览文件无需保存");return}persistCurrentFile(true)}
function showUnsavedConfirm(){const file=state.editing;if(!file)return Promise.resolve("discard");return new Promise(resolve=>{hideConfirm();const mask=document.createElement("div");mask.className="confirm-mask";mask.id="confirmMask";const card=document.createElement("div");card.className="confirm-card";card.id="confirmCard";card.innerHTML='<h3>保存当前文件修改？</h3><p><strong>'+esc(file.name)+'</strong> 还有未保存的内容。保存后会写回当前 EPUB 缓存，不保存会丢失这次修改。</p><div class="confirm-actions"><button class="cancel" id="confirmCancel">继续编辑</button><button class="plain" id="confirmDiscard">不保存</button><button class="primary" id="confirmSave">保存并返回</button></div>';document.body.appendChild(mask);document.body.appendChild(card);byId("confirmCancel").onclick=()=>{hideConfirm();resolve("cancel")};byId("confirmDiscard").onclick=()=>{hideConfirm();resolve("discard")};byId("confirmSave").onclick=()=>{hideConfirm();resolve("save")}})}
function hideConfirm(){const mask=byId("confirmMask"),card=byId("confirmCard");if(mask)mask.remove();if(card)card.remove()}
async function confirmLeaveEditor(){if(!(state.dirty&&byId("text")))return true;const action=await showUnsavedConfirm();if(action==="cancel")return false;if(action==="save")return persistCurrentFile(true);return true}
function finishCloseEditor(){state.openSearchToken++;state.editing=null;state.dirty=false;updateSaveState();state.matches=[];state.matchIndex=-1;state.searchResults=[];state.searchResultIndex=-1;state.searchDirty=true;byId("app").classList.remove("editing");byId("replace").classList.remove("open");hideConfirm();updateEditorBottomInset();restoreTreeScroll()}
async function closeEditor(){if(!(await confirmLeaveEditor()))return;finishCloseEditor()}
function closeOrBack(){if(state.editing)closeEditor();else if(state.page==="tree"){state.page="meta";renderAll();window.scrollTo(0,0)}else api.close()}
function handleBack(){if(state.editing){closeEditor();return true}if(state.page==="tree"){state.page="meta";renderAll();window.scrollTo(0,0);return true}return false}
function toggleGroup(k){state.open[k]=!state.open[k];renderTree()}
function toggleReplace(){const panel=byId("replace");panel.classList.toggle("open");byId("searchBtn").classList.toggle("active",panel.classList.contains("open"));wireSearchInputs();updateScopeCount();updateEditorBottomInset();if(!panel.classList.contains("open")){state.matches=[];state.matchIndex=-1;state.searchResults=[];state.searchResultIndex=-1;state.searchDirty=true;updateSearchStatus();const line=byId("matchLine");if(line)line.style.display="none"}}
function wireSearchInputs(){["find","regex","textOnly"].forEach(id=>{const el=byId(id);if(el&&el.dataset.wired!=="1"){el.dataset.wired="1";el.oninput=()=>{state.searchDirty=true;updateSearchStatus()};el.onchange=()=>{state.searchDirty=true;updateSearchStatus()}}});updateScopeUi()}
function activePattern(){const q=byId("find").value;if(!q)return null;const flags="gi";if(byId("regex").checked){try{return new RegExp(q,flags)}catch(e){updateSearchStatus("正则无效");return null}}return {text:q,textOnly:byId("textOnly").checked}}
function findRanges(value,pattern){const ranges=[];if(!pattern)return ranges;if(pattern instanceof RegExp){let m;while((m=pattern.exec(value))){if(!m[0]){pattern.lastIndex++;continue}ranges.push([m.index,m.index+m[0].length])}return ranges}const source=value.toLowerCase();const needle=pattern.text.toLowerCase();let p=0;while((p=source.indexOf(needle,p))!==-1){ranges.push([p,p+needle.length]);p+=needle.length||1}return ranges}
function normalizeEditorText(content){return String(content||"").replace(/\r\n/g,"\n").replace(/\r/g,"\n")}
function buildSearchSource(content,textOnly){if(!textOnly)return{text:content,map:null};let out="",map=[];let inTag=false,spacePending=false;for(let i=0;i<content.length;i++){const ch=content[i];if(ch==="<"){inTag=true;spacePending=out.length>0&&out[out.length-1]!==" "&&out[out.length-1]!=="\n";continue}if(inTag){if(ch===">"){inTag=false;if(spacePending){out+=" ";map.push(i);spacePending=false}}else if(ch==="\n"){out+="\n";map.push(i);spacePending=false}continue}out+=ch;map.push(i)}return{text:out,map}}
function mapRangesToContent(ranges,map,contentLength){if(!map)return ranges.slice();return ranges.map(range=>{const startIndex=Math.max(0,Math.min(range[0],map.length-1));const endIndex=Math.max(range[0],range[1]-1);const start=map[startIndex]??0;const end=Math.min(contentLength,(map[Math.min(endIndex,map.length-1)]??contentLength-1)+1);return[start,Math.max(start,end)]})}
function buildMatches(selectFirst=true){const q=activePattern();const session=++state.searchSessionToken;state.matches=[];state.matchIndex=-1;state.searchResults=[];state.searchResultIndex=-1;syncSearchHits();if(!q){state.searchDirty=false;updateSearchStatus();return}const scope=byId("scope").value;if(scope==="current"){const text=byId("text");if(!text){state.searchDirty=false;updateSearchStatus();return}const normalized=normalizeEditorText(text.value);const source=buildSearchSource(normalized,!!q.textOnly);state.matches=mapRangesToContent(findRanges(source.text,q),source.map,normalized.length);state.searchDirty=false;if(state.matches.length){state.matchIndex=0;if(selectFirst)selectMatch();else updateSearchStatus()}else updateSearchStatus();return}scopeFiles(scope).forEach(f=>{const r=callJson(()=>api.readText(f.path));if(!r.ok)return;const normalized=normalizeEditorText(r.content||"");const source=buildSearchSource(normalized,!!q.textOnly);const rawMatches=findRanges(source.text,q);const matches=mapRangesToContent(rawMatches,source.map,normalized.length);if(matches.length)state.searchResults.push({path:f.path,name:f.name,matches})});state.searchDirty=false;if(state.searchResults.length){state.searchResultIndex=0;if(selectFirst)openSearchResult(0,0,session);else updateSearchStatus()}else updateSearchStatus()}
function currentGlobalMatchIndex(){if(state.searchResultIndex<0||!state.searchResults.length)return 0;let count=0;for(let i=0;i<state.searchResults.length;i++){const item=state.searchResults[i];if(i<state.searchResultIndex){count+=item.matches.length;continue}if(i===state.searchResultIndex){return count+Math.max(1,state.matchIndex+1)}break}return 0}
function updateSearchStatus(message){const el=byId("replaceStatus");if(!el)return;const scope=byId("scope")?.value||"current";if(message){el.textContent=message;return}if(state.searchDirty){el.textContent="0 / 0";return}if(scope==="current"){el.textContent=!state.matches.length?"0 / 0":((state.matchIndex<0?0:state.matchIndex+1)+" / "+state.matches.length)}else{const total=state.searchResults.reduce((sum,item)=>sum+item.matches.length,0);const files=state.searchResults.length;const current=total?currentGlobalMatchIndex():0;el.textContent=!total?"0 / 0":(current+" / "+total+" 处 · "+Math.max(0,state.searchResultIndex+1)+" / "+files+" 文件")}}
function scrollSelectionIntoView(text,start,end){const rects=measureSearchRects(start,end);const rect=rects[0];if(!rect){const offsets=ensureLineOffsets(text.value);const lineIndex=Math.max(0,offsets.findIndex(v=>v>start)-1);const lineHeight=getBoxPadding(text).lineHeight;text.scrollTop=Math.max(0,lineIndex*lineHeight);syncScroll();return}const overlayHeight=byId("replace")?.classList.contains("open")?(byId("replace").offsetHeight||0):0;const viewportTop=text.scrollTop;const viewportBottom=text.scrollTop+Math.max(140,text.clientHeight-overlayHeight-16);const targetTop=Math.max(0,rect.top-12);const targetBottom=rect.top+rect.height+12;if(targetTop<viewportTop){text.scrollTop=targetTop}else if(targetBottom>viewportBottom){text.scrollTop=Math.max(0,targetBottom-(text.clientHeight-overlayHeight)*0.62)}syncScroll()}
function selectMatch(){const text=byId("text");const m=state.matches[state.matchIndex];if(!text||!m)return;text.setSelectionRange(m[0],m[1]);syncHighlight();scheduleLayoutSync(()=>{scrollSelectionIntoView(text,m[0],m[1]);updateSearchStatus()})}
function verifyCurrentFileMatches(path){const text=byId("text");const q=activePattern();if(!text||!q||!state.editing||state.editing.path!==path)return[];const normalized=normalizeEditorText(text.value);const source=buildSearchSource(normalized,!!q.textOnly);return mapRangesToContent(findRanges(source.text,q),source.map,normalized.length)}
function pruneSearchResult(fileIndex,session){if(session!==state.searchSessionToken)return;state.searchResults.splice(fileIndex,1);if(!state.searchResults.length){clearVisibleMatches();state.searchResultIndex=-1;state.searchDirty=false;updateSearchStatus();return}const nextIndex=Math.min(fileIndex,state.searchResults.length-1);state.searchResultIndex=nextIndex;openSearchResult(nextIndex,0,session)}
function openSearchResult(fileIndex,matchIndex,session){const currentSession=session??state.searchSessionToken;const item=state.searchResults[fileIndex];if(!item||currentSession!==state.searchSessionToken)return;const token=++state.openSearchToken;openFile(item.path,true,false);setTimeout(()=>{if(token!==state.openSearchToken||currentSession!==state.searchSessionToken)return;const verified=verifyCurrentFileMatches(item.path);if(!verified.length){pruneSearchResult(fileIndex,currentSession);return}state.searchResults[fileIndex].matches=verified;state.matches=verified;state.matchIndex=Math.min(matchIndex,verified.length-1);state.searchResultIndex=fileIndex;selectMatch();updateSearchStatus()},80)}
function findNext(){const scope=byId("scope").value;if(state.searchDirty)buildMatches(false);if(scope==="current"){if(!state.matches.length)return;state.matchIndex=(state.matchIndex+1)%state.matches.length;selectMatch();return}if(!state.searchResults.length)return;const current=state.searchResults[state.searchResultIndex];const currentMatch=state.matches===current?.matches?state.matchIndex:-1;if(current&&currentMatch+1<current.matches.length){state.matches=current.matches;state.matchIndex=currentMatch+1;selectMatch();return}const nextFile=(state.searchResultIndex+1)%state.searchResults.length;openSearchResult(nextFile,0)}
function findPrev(){const scope=byId("scope").value;if(state.searchDirty)buildMatches(false);if(scope==="current"){if(!state.matches.length)return;state.matchIndex=(state.matchIndex-1+state.matches.length)%state.matches.length;selectMatch();return}if(!state.searchResults.length)return;const current=state.searchResults[state.searchResultIndex];const currentMatch=state.matches===current?.matches?state.matchIndex:current?.matches.length||0;if(current&&currentMatch>0){state.matches=current.matches;state.matchIndex=currentMatch-1;selectMatch();return}const prevFile=(state.searchResultIndex-1+state.searchResults.length)%state.searchResults.length;const prevItem=state.searchResults[prevFile];openSearchResult(prevFile,prevItem.matches.length-1)}
function count(){if(state.searchDirty)buildMatches(false);const scope=byId("scope").value;const total=scope==="current"?state.matches.length:state.searchResults.reduce((sum,item)=>sum+item.matches.length,0);toast("共 "+total+" 处")}
function replaceCurrent(){const text=byId("text");if(!text)return;if(state.searchDirty)buildMatches(false);const m=state.matches[state.matchIndex];if(!m)return;const rep=byId("repl").value;text.value=text.value.slice(0,m[0])+rep+text.value.slice(m[1]);state.dirty=true;updateSaveState();state.searchDirty=true;syncEditor();buildMatches(false)}
function replaceInValue(value,pattern,rep){const ranges=findRanges(value,pattern);if(!ranges.length)return {value,count:0};let out="",last=0;ranges.forEach(r=>{out+=value.slice(last,r[0])+rep;last=r[1]});out+=value.slice(last);return {value:out,count:ranges.length}}
function scopeFiles(scopeValue){const scope=scopeValue||byId("scope").value;if(scope==="current")return state.editing?[state.editing]:[];return state.files.filter(f=>f.editable&&(scope==="all"||f.kind==="html"))}
function replaceAll(){const pattern=activePattern();if(!pattern)return;const rep=byId("repl").value;const scope=byId("scope").value;const files=scopeFiles(scope);if(!files.length)return;if(pattern.textOnly){toast("仅文本模式暂只支持查找定位");return}let changed=0,total=0;const changedPaths=[];files.forEach(f=>{let content;if(state.editing&&f.path===state.editing.path&&byId("text"))content=byId("text").value;else{const r=callJson(()=>api.readText(f.path));if(!r.ok)return;content=r.content||""}const next=replaceInValue(content,pattern,rep);if(!next.count)return;total+=next.count;changed++;changedPaths.push(f.path);if(state.editing&&f.path===state.editing.path&&byId("text")){byId("text").value=next.value;state.dirty=true;updateSaveState();state.searchDirty=true;syncEditor();buildMatches(false)}else callJson(()=>api.replaceText(f.path,next.value))});toast("已替换 "+total+" 处，涉及 "+changed+" 个文件");const refreshPaths=changedPathsForRefresh(changedPaths);invalidateDecorations(refreshPaths);refresh(refreshPaths)}
function openActionSheet(path){const f=state.files.find(x=>x.path===path);if(!f)return;state.actionFile=f;const coverButton=f.kind==="image"?'<button class="plain" onclick="FileEditorNative.setCover(state.actionFile.path)">设为封面</button>':'';showSheet('<h3>'+esc(f.name)+'</h3><p>'+esc(f.path)+'</p><button class="primary" onclick="FileEditorNative.openFile(state.actionFile.path);hideSheet()">打开</button>'+coverButton+'<button class="plain" onclick="FileEditorNative.openRenameSheet()">重命名</button><button class="danger" onclick="FileEditorNative.deleteActionFile()">删除文件</button><button class="plain" onclick="FileEditorNative.hideSheet()">取消</button>')}
function openRenameSheet(){const f=state.actionFile;if(!f)return;showSheet('<h3>重命名文件</h3><p>'+esc(f.path)+'</p><input id="renameInput" class="field" value="'+esc(f.name)+'"><button class="primary" onclick="FileEditorNative.submitRename()">保存名称</button><button class="plain" onclick="FileEditorNative.hideSheet()">取消</button>');setTimeout(()=>byId("renameInput").focus(),80)}
function submitRename(){const f=state.actionFile;const name=byId("renameInput").value;const r=callJson(()=>api.renameFile(f.path,name));toast(r.message);hideSheet();refresh()}
function deleteActionFile(){const f=state.actionFile;if(!f)return;if(!confirm("删除 "+f.name+"？"))return;const r=callJson(()=>api.deleteFile(f.path));toast(r.message);hideSheet();refresh()}
function setCover(path){const r=callJson(()=>api.setCover(path));toast(r.message||"已设为封面");hideSheet();if(r.ok){if(r.metadata)state.metadata=r.metadata;const coverPath=state.metadata?.coverPath||path;refresh([path,coverPath]);if(state.page==="meta"){renderMetaPage();loadCoverPreview()}}}
function openAddSheet(groupPath){state.addGroup=groupPath||"";showSheet('<h3>添加文件</h3><p>'+(groupPath?esc(groupPath):"ROOT")+'</p><button class="primary" onclick="FileEditorNative.pickFile()">导入文件</button><button class="plain" onclick="FileEditorNative.openNewTextSheet()">新建文本文件</button><button class="plain" onclick="FileEditorNative.hideSheet()">取消</button>')}
function openNewTextSheet(){showSheet('<h3>新建文本文件</h3><p>建议使用 .xhtml、.html、.css 或 .xml</p><input id="newTextName" class="field" placeholder="chapter.xhtml"><button class="primary" onclick="FileEditorNative.createText()">创建</button><button class="plain" onclick="FileEditorNative.hideSheet()">取消</button>');setTimeout(()=>byId("newTextName").focus(),80)}
function createText(){const r=callJson(()=>api.createTextFile(state.addGroup,byId("newTextName").value));toast(r.message);hideSheet();refresh()}
function pickFile(){api.pickFile(state.addGroup||"");hideSheet();toast("请选择要导入的文件")}
function onImportResult(raw){const r=JSON.parse(raw);toast(r.message||"导入完成");refresh()}
function showSheet(html){hideSheet();const mask=document.createElement("div");mask.className="sheet-mask";mask.id="sheetMask";mask.onclick=hideSheet;const sheet=document.createElement("div");sheet.className="sheet";sheet.id="sheet";sheet.innerHTML=html;document.body.appendChild(mask);document.body.appendChild(sheet)}
function hideSheet(){const a=byId("sheetMask"),b=byId("sheet");if(a)a.remove();if(b)b.remove()}
window.FileEditorNative={refresh,openFile,closeEditor,closeOrBack,handleBack,toggleGroup,toggleReplace,findNext,findPrev,count,replaceCurrent,replaceAll,openActionSheet,openRenameSheet,submitRename,deleteActionFile,setCover,openAddSheet,openNewTextSheet,createText,pickFile,onImportResult,hideSheet,save,setScope,saveMeta,openTreePage,copyUuid,armCoverPicker,pickCoverImage,onCoverResult};
(function init(){watchTheme();const data=JSON.parse(api.initialData());state.title=data.title;state.status=data.status;state.metadata=data.metadata||{};state.files=data.files||[];renderAll()})();
</script>
</body>
</html>
        """.trimIndent()

    private fun Activity.toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val LOG_PREFIX = "ReaMicro LSP"
        private const val BRIDGE_NAME = "ReaMicroFileEditor"
        const val REQUEST_IMPORT_FILE = 0x524D46E1.toInt()
        const val REQUEST_COVER_IMAGE = 0x524D46E2.toInt()
        private const val MAX_TEXT_BYTES = 5L * 1024L * 1024L
        private const val MAX_THUMB_BYTES = 2L * 1024L * 1024L
        private const val THUMB_MAX_DIMENSION = 320
        private const val PREVIEW_MAX_DIMENSION = 2048
        private const val GLOBAL_FONT_SYSTEM = "system"
        private const val GLOBAL_FONT_SERIF = "serif"
        private val activePanels = WeakHashMap<Activity, EpubWebEditorPanel>()
        @Volatile
        private var globalSuppressCoverPickerUntilMs: Long = 0L
        private val REAMICRO_MD5_REGEX = Regex("^[0-9a-fA-F]{32}$")
        private val TEXT_EXTENSIONS = setOf("html", "htm", "xhtml", "xml", "opf", "ncx", "css", "txt", "js", "json", "svg", "md")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

        fun dispatchActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            synchronized(activePanels) {
                activePanels[activity]
            }?.onActivityResult(requestCode, resultCode, data)
        }

        private fun isTextFile(file: File): Boolean =
            file.extension.lowercase(Locale.ROOT) in TEXT_EXTENSIONS

        private fun fileKind(file: File): String {
            val ext = file.extension.lowercase(Locale.ROOT)
            return when {
                ext in IMAGE_EXTENSIONS -> "image"
                ext in FONT_EXTENSIONS -> "font"
                ext in setOf("html", "htm", "xhtml") -> "html"
                ext == "css" -> "css"
                ext in setOf("xml", "opf", "ncx", "svg") -> "xml"
                else -> "other"
            }
        }

        private fun iconColor(kind: String, name: String): String =
            when (kind) {
                "html" -> "#ff4a15"
                "css" -> "#2096ff"
                "xml" -> if (name.contains("toc", true)) "#45cdb4" else "#ff4a15"
                "font" -> "#536273"
                "image" -> "#20a8b8"
                else -> "#f59b24"
            }

        private fun mimeType(file: File): String =
            when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "image/jpeg"
            }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.0fKB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1fMB", mb)
        }

        private fun sanitizeFileName(name: String): String =
            name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)

        private fun naturalName(name: String): String =
            name.lowercase(Locale.ROOT).replace(Regex("(\\d+)")) { it.value.padStart(8, '0') }

        private fun tagText(content: String, tag: String): String =
            Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&amp;", "&")
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")
                ?.trim()
                .orEmpty()
    }
}
