package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiApiConfig
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiImagePresetTarget
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class BookOverviewImageSelectionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val requestCoverFix: () -> Boolean = { false },
) {
    private val contextStack = ThreadLocal.withInitial { mutableListOf<BookImageContext>() }
    private val activeSourceDialogs = Collections.synchronizedSet(mutableSetOf<String>())

    fun install() {
        hookBookCoverBannerItem()
        hookBottomSheet(COVER_BOTTOM_SHEET_METHOD, ImageTarget.Cover)
        hookBottomSheet(BANNER_BOTTOM_SHEET_METHOD, ImageTarget.Banner)
    }

    private fun hookBookCoverBannerItem() {
        runCatching {
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val methods = itemsClass.declaredMethods.filter { method ->
                method.name == BOOK_COVER_BANNER_ITEM_METHOD &&
                    method.parameterTypes.size == 5 &&
                    method.parameterTypes.getOrNull(1)?.name == BOOK_CLASS &&
                    method.parameterTypes.getOrNull(2)?.name == FUNCTION1_CLASS
            }
            if (methods.isEmpty()) error("BookCoverBannerItem composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val book = param.args?.getOrNull(1) ?: return
                        val onUpdateCover = param.args?.getOrNull(2) ?: return
                        stack().add(BookImageContext(book, onUpdateCover))
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stack = stack()
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book overview image context hook installed (${methods.size})")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BookCoverBannerItem: ${it.stackTraceToString()}")
        }
    }

    private fun hookBottomSheet(methodName: String, target: ImageTarget) {
        runCatching {
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val methods = itemsClass.declaredMethods.filter { method ->
                method.name == methodName &&
                    method.parameterTypes.size == 6 &&
                    method.parameterTypes.getOrNull(1)?.name == FUNCTION0_CLASS
            }
            if (methods.isEmpty()) error("$methodName not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = currentBookContext() ?: return
                        val activity = activityProvider() ?: return
                        val hasSecondaryAction = param.args?.getOrNull(0) as? Boolean ?: false
                        val originalOnPick = param.args?.getOrNull(1) ?: return
                        val secondaryAction = param.args?.getOrNull(2)
                        val onDismiss = param.args?.getOrNull(3)
                        param.result = null
                        val key = sourceDialogKey(context.book, target)
                        if (!activeSourceDialogs.add(key)) return
                        activity.runOnUiThread {
                            activity.window?.decorView?.post {
                                invokeFunction0(onDismiss)
                                showImageSourceDialog(
                                    context = context,
                                    target = target,
                                    originalOnPick = originalOnPick,
                                    hasSecondaryAction = hasSecondaryAction,
                                    secondaryAction = secondaryAction,
                                    dialogKey = key,
                                )
                            }
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX $methodName image source hook installed (${methods.size})")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook $methodName image source: ${it.stackTraceToString()}")
        }
    }

    private fun showImageSourceDialog(
        context: BookImageContext,
        target: ImageTarget,
        originalOnPick: Any,
        hasSecondaryAction: Boolean,
        secondaryAction: Any?,
        dialogKey: String,
    ) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            val colors = DialogColors(activity)
            val dialog = imageDialog(activity)
            val card = dialogCard(activity, colors)
            card.addView(dialogTitle(activity, "${target.label}图片", colors))
            card.addView(actionRow(activity, "选取图片", "使用阅微原来的本地图片选择", colors) {
                dialog.dismiss()
                invokeFunction0(originalOnPick)
            })
            card.addView(actionRow(activity, "在线图片", "输入图片链接，下载后预览并应用", colors) {
                dialog.dismiss()
                showOnlineImageDialog(context, target)
            })
            card.addView(actionRow(activity, "生成${target.label}", "使用生图管理里的 API 和${target.label}预设", colors) {
                dialog.dismiss()
                showAiImageDialog(context, target)
            })
            if (hasSecondaryAction && secondaryAction != null) {
                val label = when (target) {
                    ImageTarget.Cover -> "重置封面"
                    ImageTarget.Banner -> "删除横幅"
                }
                card.addView(actionRow(activity, label, "", colors) {
                    dialog.dismiss()
                    invokeFunction0(secondaryAction)
                })
            }
            if (target == ImageTarget.Cover) {
                card.addView(actionRow(activity, "封面修复", "上传当前关联封面到书库", colors) {
                    dialog.dismiss()
                    if (!requestCoverFix()) {
                        activity.toast("当前页面无法执行封面修复")
                    }
                })
            }
            card.addView(actionRow(activity, "取消", "", colors) {
                dialog.dismiss()
            })
            dialog.setOnDismissListener {
                activeSourceDialogs.remove(dialogKey)
            }
            showDialog(dialog, card, activity, 0.9f)
        }
    }

    private fun showOnlineImageDialog(context: BookImageContext, target: ImageTarget) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            val colors = DialogColors(activity)
            val dialog = imageDialog(activity)
            val card = dialogCard(activity, colors)
            var previewBytes: ByteArray? = null

            card.addView(dialogTitle(activity, "在线图片", colors))
            val input = editText(activity, "图片链接", singleLine = false).apply {
                minLines = 2
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            card.addView(input)
            val imageView = previewImageView(activity, target)
            card.addView(imageView)
            val status = statusText(activity, colors)
            val progress = ProgressBar(activity).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            card.addView(progress, centeredWrapParams(activity))
            card.addView(status)

            val buttons = horizontalActions(activity)
            val preview = compactButton(activity, "预览", colors)
            val apply = compactButton(activity, "应用", colors)
            val cancel = compactButton(activity, "取消", colors, neutral = true)
            buttons.addView(preview, actionWeightParams())
            buttons.addView(apply, actionWeightParams())
            buttons.addView(cancel, actionWeightParams())
            card.addView(buttons)

            preview.setOnClickListener {
                val url = input.text?.toString().orEmpty().trim()
                if (url.isBlank()) {
                    activity.toast("请输入图片链接")
                    return@setOnClickListener
                }
                runUiTask(
                    activity = activity,
                    status = status,
                    progress = progress,
                    controls = listOf(preview, apply, cancel),
                    message = "正在下载图片",
                    block = { downloadImageBytes(url) },
                ) { bytes ->
                    previewBytes = bytes
                    updatePreview(imageView, bytes)
                    status.text = "预览已加载"
                }
            }
            apply.setOnClickListener {
                val url = input.text?.toString().orEmpty().trim()
                if (url.isBlank() && previewBytes == null) {
                    activity.toast("请输入图片链接")
                    return@setOnClickListener
                }
                runUiTask(
                    activity = activity,
                    status = status,
                    progress = progress,
                    controls = listOf(preview, apply, cancel),
                    message = "正在应用图片",
                    block = {
                        val bytes = previewBytes ?: downloadImageBytes(url)
                        val applied = saveBookImage(context, target, bytes)
                        bytes to applied
                    },
                ) { (bytes, applied) ->
                    previewBytes = bytes
                    updatePreview(imageView, bytes)
                    finishApply(activity, context, target, applied)
                    dialog.dismiss()
                }
            }
            cancel.setOnClickListener { dialog.dismiss() }

            showDialog(dialog, scroll(card, activity), activity, 0.94f)
        }
    }

    private fun showAiImageDialog(context: BookImageContext, target: ImageTarget) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            val appContext = activity.applicationContext ?: activity
            val config = AiApiStore.imageApi(appContext)
            val settings = AiApiStore.imageSettings(appContext)
            val presetTarget = target.aiTarget
            val selectedPresetId = when (target) {
                ImageTarget.Cover -> settings.coverPresetId
                ImageTarget.Banner -> settings.bannerPresetId
            }
            val preset = AiApiStore.imagePreset(appContext, presetTarget, selectedPresetId)
            val colors = DialogColors(activity)
            val dialog = imageDialog(activity)
            val card = dialogCard(activity, colors)
            var generatedBytes: ByteArray? = null

            card.addView(dialogTitle(activity, "生成${target.label}", colors))
            card.addView(dialogMessage(activity, "当前模型：${config?.displayName ?: "未配置"}", colors))
            card.addView(dialogMessage(activity, "参考图片：生成时自动读取当前封面并上传", colors))

            val prompt = editText(activity, "提示词内容", singleLine = false).apply {
                minLines = 4
                setText(preset.prompt)
                gravity = Gravity.TOP or Gravity.START
            }
            card.addView(prompt)

            val size = editText(activity, "图片尺寸", singleLine = true).apply {
                setText(DEFAULT_IMAGE_SIZE)
            }
            card.addView(size)

            val imageView = previewImageView(activity, target)
            card.addView(imageView)
            val status = statusText(activity, colors)
            val progress = ProgressBar(activity).apply {
                visibility = View.GONE
                isIndeterminate = true
            }
            card.addView(progress, centeredWrapParams(activity))
            card.addView(status)

            val buttons = horizontalActions(activity)
            val generate = compactButton(activity, "生图", colors)
            val apply = compactButton(activity, "应用", colors)
            val save = compactButton(activity, "保存", colors)
            val cancel = compactButton(activity, "取消", colors, neutral = true)
            buttons.addView(generate, actionWeightParams())
            buttons.addView(apply, actionWeightParams())
            buttons.addView(save, actionWeightParams())
            buttons.addView(cancel, actionWeightParams())
            card.addView(buttons)

            generate.setOnClickListener {
                val api = config
                if (api == null) {
                    activity.toast("请先在 API 配置里设置生图 API")
                    return@setOnClickListener
                }
                val template = prompt.text?.toString().orEmpty().trim()
                if (template.isBlank()) {
                    activity.toast("请输入提示词")
                    return@setOnClickListener
                }
                runUiTask(
                    activity = activity,
                    status = status,
                    progress = progress,
                    controls = listOf(generate, apply, save, cancel),
                    message = "正在生成图片",
                    block = {
                        generateImage(
                            config = api,
                            prompt = renderImagePrompt(template, context.book),
                            referenceDataUrl = loadReferenceImageDataUrl(context.book),
                            requestedSize = size.text?.toString().orEmpty().trim(),
                            target = target,
                        )
                    },
                ) { bytes ->
                    generatedBytes = bytes
                    updatePreview(imageView, bytes)
                    status.text = "生成完成，点击应用才会写入${target.label}"
                }
            }
            apply.setOnClickListener {
                val bytes = generatedBytes
                if (bytes == null) {
                    activity.toast("请先生成图片")
                    return@setOnClickListener
                }
                runUiTask(
                    activity = activity,
                    status = status,
                    progress = progress,
                    controls = listOf(generate, apply, save, cancel),
                    message = "正在应用图片",
                    block = { saveBookImage(context, target, bytes) },
                ) { applied ->
                    finishApply(activity, context, target, applied)
                    dialog.dismiss()
                }
            }
            save.setOnClickListener {
                val bytes = generatedBytes
                if (bytes == null) {
                    activity.toast("请先生成图片")
                    return@setOnClickListener
                }
                runUiTask(
                    activity = activity,
                    status = status,
                    progress = progress,
                    controls = listOf(generate, apply, save, cancel),
                    message = "正在保存到相册",
                    block = { saveToGallery(activity, bytes, target) },
                ) {
                    status.text = "已保存到相册"
                    activity.toast("已保存到相册")
                }
            }
            cancel.setOnClickListener { dialog.dismiss() }

            showDialog(dialog, scroll(card, activity), activity, 0.94f)
        }
    }

    private fun saveBookImage(context: BookImageContext, target: ImageTarget, bytes: ByteArray): AppliedImage {
        validateImageBytes(bytes)
        val activity = activityProvider() ?: error("Activity unavailable")
        val bookDir = resolveBookDir(activity, context.book) ?: error("书籍目录不可用")
        val relative = when (target) {
            ImageTarget.Cover -> coverRelativeForWrite(context.book)
            ImageTarget.Banner -> bannerRelativeForWrite(context.book)
        }
        val targetFile = childFile(bookDir, relative)
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(bytes)
        return AppliedImage(relative, targetFile)
    }

    private fun finishApply(
        activity: Activity,
        context: BookImageContext,
        target: ImageTarget,
        applied: AppliedImage,
    ) {
        if (target == ImageTarget.Cover) {
            invokeFunction1(context.onUpdateCover, applied.relativePath)
        }
        activity.toast("${target.label}已应用")
        activity.window?.decorView?.postDelayed({
            runCatching {
                if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to refresh book overview image: ${it.stackTraceToString()}")
            }
        }, REFRESH_DELAY_MS)
    }

    private fun generateImage(
        config: AiApiConfig,
        prompt: String,
        referenceDataUrl: String?,
        requestedSize: String,
        target: ImageTarget,
    ): ByteArray {
        val sizes = imageSizeCandidates(requestedSize, target)
        var lastError: Throwable? = null
        for (size in sizes) {
            imageRequestBodies(config.model, prompt, size, referenceDataUrl).forEach { body ->
                val bytes = runCatching {
                    requestImageGeneration(config, body)
                }.onFailure { lastError = it }.getOrNull()
                if (bytes != null) return bytes
            }
        }
        throw lastError ?: IllegalStateException("未获取到生图结果")
    }

    private fun imageRequestBodies(
        model: String,
        prompt: String,
        size: String,
        referenceDataUrl: String?,
    ): List<JSONObject> {
        val normalizedSize = normalizeImageSize(size)
        fun base(): JSONObject =
            JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("n", 1)
                .also { body ->
                    if (normalizedSize.isNotBlank()) body.put("size", normalizedSize)
                }

        val bodies = mutableListOf<JSONObject>()
        if (!referenceDataUrl.isNullOrBlank()) {
            bodies += base()
                .put("image", JSONArray().put(referenceDataUrl))
                .put("response_format", "b64_json")
            bodies += base()
                .put("image", referenceDataUrl)
                .put("response_format", "b64_json")
            bodies += base()
                .put("image_urls", JSONArray().put(referenceDataUrl))
                .put("response_format", "b64_json")
            bodies += base()
                .put("image", JSONArray().put(referenceDataUrl))
            bodies += base()
                .put("image", referenceDataUrl)
            bodies += base()
                .put("image_urls", JSONArray().put(referenceDataUrl))
        }
        bodies += base().put("response_format", "b64_json")
        bodies += base()
        return bodies.distinctBy { it.toString() }
    }

    private fun requestImageGeneration(config: AiApiConfig, body: JSONObject): ByteArray {
        val connection = (URL(imageGenerationUrl(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = IMAGE_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json,image/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.use { readLimited(it, MAX_AI_RESPONSE_BYTES) } ?: ByteArray(0)
            if (code !in 200..299) {
                val text = payload.toString(Charsets.UTF_8)
                error("HTTP $code: ${extractApiError(text).ifBlank { text.take(180) }}")
            }
            val contentType = connection.contentType.orEmpty()
            if (contentType.startsWith("image/", ignoreCase = true) && isImageBytes(payload)) {
                return payload
            }
            val text = payload.toString(Charsets.UTF_8)
            val json = runCatching { JSONObject(text) }.getOrElse {
                error("生图接口未返回有效 JSON")
            }
            extractImageBytes(json) ?: error("生图接口返回中没有图片数据")
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadImageBytes(url: String): ByteArray {
        val normalized = url.trim()
        if (normalized.startsWith("data:image/", ignoreCase = true)) {
            return decodeDataUrl(normalized) ?: error("图片 Data URL 无效")
        }
        require(isRemoteUrl(normalized)) { "请输入 http 或 https 图片链接" }
        val connection = (URL(normalized).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载失败：HTTP $code")
            val bytes = connection.inputStream.use { readLimited(it, MAX_IMAGE_BYTES) }
            validateImageBytes(bytes)
            bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun extractImageBytes(value: Any?): ByteArray? {
        return when (value) {
            is JSONObject -> extractImageBytes(value)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    extractImageBytes(value.opt(index))?.let { return it }
                }
                null
            }
            is String -> decodeDataUrl(value)
            else -> null
        }
    }

    private fun extractImageBytes(json: JSONObject): ByteArray? {
        BASE64_KEYS.forEach { key ->
            val candidate = json.optString(key).trim()
            if (candidate.isNotBlank()) {
                decodeBase64Image(candidate)?.let { return it }
            }
        }
        URL_KEYS.forEach { key ->
            val candidate = json.optString(key).trim()
            if (isRemoteUrl(candidate) || candidate.startsWith("data:image/", ignoreCase = true)) {
                runCatching { downloadImageBytes(candidate) }
                    .onSuccess { return it }
                    .onFailure {
                        XposedBridge.log("$LOG_PREFIX failed to fetch generated image url: ${it.stackTraceToString()}")
                    }
            }
            extractImageBytes(json.opt(key))?.let { return it }
        }
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in BASE64_KEYS || key in URL_KEYS) continue
            extractImageBytes(json.opt(key))?.let { return it }
        }
        return null
    }

    private fun renderImagePrompt(template: String, book: Any): String {
        val title = callString(book, "getTitle").trim().ifBlank { "当前书籍" }
        val bookText = buildList {
            title.takeIf { it.isNotBlank() }?.let { add(it) }
            callString(book, "getAuthor").trim().takeIf { it.isNotBlank() }?.let { add("作者：$it") }
            callString(book, "getPublisher").trim().takeIf { it.isNotBlank() }?.let { add("出版/关联：$it") }
        }.joinToString("，").ifBlank { "当前书籍" }
        val prompt = template.trim()
        val replaced = prompt
            .replace("{title}", title)
            .replace("{{title}}", title)
            .replace("{{text}}", bookText)
        return if (
            prompt.contains("{title}") ||
            prompt.contains("{{title}}") ||
            prompt.contains("{{text}}")
        ) {
            replaced
        } else {
            "$replaced\n\n书籍信息：$bookText"
        }
    }

    private fun imageSizeCandidates(requested: String, target: ImageTarget): List<String> {
        val normalized = requested.trim().ifBlank { DEFAULT_IMAGE_SIZE }
        val fallback = when (target) {
            ImageTarget.Cover -> "1024x1365"
            ImageTarget.Banner -> "1536x768"
        }
        return listOf(normalized, fallback, "").distinct()
    }

    private fun normalizeImageSize(value: String): String =
        when (val normalized = value.trim()) {
            "1k", "1K" -> "1K"
            "2k", "2K" -> "2K"
            "4k", "4K" -> "4K"
            else -> normalized
        }

    private fun imageGenerationUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/images/generations", ignoreCase = true) -> base
            base.endsWith("/chat/completions", ignoreCase = true) ->
                base.removeSuffix("/chat/completions") + "/images/generations"
            base.endsWith("/v1", ignoreCase = true) || isVersionedApiBaseUrl(base) -> "$base/images/generations"
            base.isBlank() -> error("Base URL 为空")
            else -> "$base/v1/images/generations"
        }
    }

    private fun isVersionedApiBaseUrl(baseUrl: String): Boolean =
        Regex(""".*/api/v\d+$""", RegexOption.IGNORE_CASE).matches(baseUrl)

    private fun saveToGallery(activity: Activity, bytes: ByteArray, target: ImageTarget): Uri {
        validateImageBytes(bytes)
        val (extension, mime) = imageExtensionAndMime(bytes)
        val resolver = activity.contentResolver
        val name = "reamicro-${target.id}-${System.currentTimeMillis()}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ReaMicro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册文件")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入相册文件")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun updatePreview(imageView: ImageView, bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("图片无法预览")
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
    }

    private fun validateImageBytes(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "图片为空" }
        require(bytes.size <= MAX_IMAGE_BYTES) { "图片过大" }
        require(isImageBytes(bytes)) { "无法识别图片内容" }
    }

    private fun isImageBytes(bytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun decodeDataUrl(value: String): ByteArray? {
        if (!value.startsWith("data:image/", ignoreCase = true)) return null
        val encoded = value.substringAfter(',', missingDelimiterValue = "").trim()
        if (encoded.isBlank()) return null
        return decodeBase64Image(encoded)
    }

    private fun decodeBase64Image(value: String): ByteArray? {
        val encoded = value.substringAfter(',', value).trim()
        if (encoded.length < 80) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (isImageBytes(bytes)) bytes else null
        }.getOrNull()
    }

    private fun imageExtensionAndMime(bytes: ByteArray): Pair<String, String> {
        return when {
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "png" to "image/png"
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() &&
                bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() &&
                bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() &&
                bytes[11] == 0x50.toByte() -> "webp" to "image/webp"
            else -> "jpg" to "image/jpeg"
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) error("响应过大")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun loadReferenceImageDataUrl(book: Any): String? =
        loadReferenceImageBytes(book)?.let { bytes ->
            val mime = imageExtensionAndMime(bytes).second
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

    private fun loadReferenceImageBytes(book: Any): ByteArray? {
        val activity = activityProvider() ?: return null
        val cover = callString(book, "getCover").trim()
        val direct = when {
            cover.startsWith("data:image/", ignoreCase = true) ->
                runCatching { decodeDataUrl(cover)?.also(::validateImageBytes) }.getOrNull()
            isRemoteUrl(cover) ->
                runCatching { downloadImageBytes(cover) }.getOrNull()
            cover.startsWith("content://", ignoreCase = true) ->
                runCatching { readImageUri(activity, Uri.parse(cover)) }.getOrNull()
            else -> null
        }
        if (direct != null) return direct

        val bookDir = resolveBookDir(activity, book) ?: return null
        val relatives = listOf(
            safeRelativePath(cover),
            DEFAULT_COVER_FILE,
        ).filter { it.isNotBlank() }.distinct()
        relatives.forEach { relative ->
            runCatching { readImageFile(childFile(bookDir, relative)) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun readImageFile(file: File): ByteArray? {
        if (!file.isFile) return null
        val bytes = file.inputStream().use { readLimited(it, MAX_IMAGE_BYTES) }
        validateImageBytes(bytes)
        return bytes
    }

    private fun readImageUri(activity: Activity, uri: Uri): ByteArray? {
        val bytes = activity.contentResolver.openInputStream(uri)
            ?.use { readLimited(it, MAX_IMAGE_BYTES) }
            ?: return null
        validateImageBytes(bytes)
        return bytes
    }

    private fun coverRelativeForWrite(book: Any): String {
        val current = safeRelativePath(callString(book, "getCover"))
        return current.ifBlank { DEFAULT_COVER_FILE }
    }

    private fun bannerRelativeForWrite(book: Any): String {
        val cover = safeRelativePath(callString(book, "getCover"))
        require(cover.isNotBlank()) { "请先设置封面后再设置横幅" }
        val slash = cover.lastIndexOf('/')
        val dot = cover.lastIndexOf('.')
        return if (dot > slash) {
            cover.take(dot) + "~banner" + cover.substring(dot)
        } else {
            "$cover~banner"
        }
    }

    private fun safeRelativePath(value: String): String {
        val normalized = value.trim().replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return ""
        if (isRemoteUrl(normalized) || normalized.contains(':')) return ""
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "." || it == ".." }) return ""
        return segments.joinToString("/")
    }

    private fun childFile(root: File, relativePath: String): File {
        val rootFile = root.canonicalFile
        val child = File(rootFile, relativePath.replace('/', File.separatorChar)).canonicalFile
        require(child.path == rootFile.path || child.path.startsWith(rootFile.path + File.separator)) {
            "图片路径越界"
        }
        return child
    }

    private fun resolveBookDir(activity: Activity, book: Any): File? {
        val filesDir = activity.filesDir ?: return null
        val uid = callLong(book, "getUid")
        val uuid = callString(book, "getUuid").trim()
        if (uuid.isBlank()) return null
        val preferred = if (uid >= 0L) File(File(File(filesDir, uid.toString()), "books"), uuid) else null
        val candidates = buildList {
            preferred?.let { add(it) }
            filesDir.listFiles()
                ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
                ?.mapTo(this) { File(File(it, "books"), uuid) }
            uriFile(callString(book, "getUri"))?.let { uri ->
                add(if (uri.isDirectory) uri else uri.parentFile ?: uri)
            }
        }
        val existing = candidates
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .firstOrNull { it.isDirectory }
        if (existing != null) return existing
        val target = preferred?.canonicalFile ?: return null
        target.mkdirs()
        return target.takeIf { it.isDirectory }
    }

    private fun uriFile(value: String): File? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return when {
            trimmed.startsWith("file://") -> File(Uri.parse(trimmed).path ?: return null)
            trimmed.startsWith("/") -> File(trimmed)
            else -> null
        }
    }

    private fun extractApiError(text: String): String =
        runCatching {
            val json = JSONObject(text)
            val error = json.optJSONObject("error") ?: return@runCatching ""
            error.optString("message")
        }.getOrDefault("")

    private fun <T> runUiTask(
        activity: Activity,
        status: TextView,
        progress: ProgressBar,
        controls: List<View>,
        message: String,
        block: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        controls.forEach { it.isEnabled = false; it.alpha = 0.55f }
        progress.visibility = View.VISIBLE
        status.text = message
        Thread {
            val result = runCatching { block() }
            activity.runOnUiThread {
                controls.forEach { it.isEnabled = true; it.alpha = 1f }
                progress.visibility = View.GONE
                result
                    .onSuccess { value -> onSuccess(value) }
                    .onFailure {
                        val text = it.message ?: it.javaClass.simpleName
                        status.text = text
                        activity.toast(text)
                        XposedBridge.log("$LOG_PREFIX image task failed: ${it.stackTraceToString()}")
                    }
            }
        }.apply { name = "ReaMicro-BookImageTask" }.start()
    }

    private fun currentBookContext(): BookImageContext? =
        stack().lastOrNull()

    private fun stack(): MutableList<BookImageContext> {
        val existing = contextStack.get()
        if (existing != null) return existing
        val created = mutableListOf<BookImageContext>()
        contextStack.set(created)
        return created
    }

    private fun invokeFunction0(callback: Any?) {
        runCatching { XposedHelpers.callMethod(callback, "invoke") }
            .onFailure { XposedBridge.log("$LOG_PREFIX failed to invoke Function0: ${it.stackTraceToString()}") }
    }

    private fun invokeFunction1(callback: Any?, value: Any?) {
        runCatching { XposedHelpers.callMethod(callback, "invoke", value) }
            .onFailure { XposedBridge.log("$LOG_PREFIX failed to invoke Function1: ${it.stackTraceToString()}") }
    }

    private fun function0Proxy(name: String, block: () -> Any?): Any {
        val functionClass = cls(FUNCTION0_CLASS)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> block()
                "toString" -> "ReaMicro-$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === (args as? Array<*>)?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun targetUnit(): Any? =
        runCatching { cls(KOTLIN_UNIT_CLASS).getField("INSTANCE").get(null) }.getOrNull()

    private fun cls(name: String): Class<*> =
        XposedHelpers.findClass(name, classLoader)

    private fun callString(target: Any, methodName: String): String =
        runCatching { XposedHelpers.callMethod(target, methodName) as? String }
            .getOrDefault("")
            .orEmpty()

    private fun callLong(target: Any, methodName: String): Long =
        runCatching { (XposedHelpers.callMethod(target, methodName) as? Number)?.toLong() ?: 0L }
            .getOrDefault(0L)

    private fun isRemoteUrl(value: String): Boolean {
        val lower = value.trim().lowercase(Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun sourceDialogKey(book: Any, target: ImageTarget): String =
        target.id + ":" + callString(book, "getUuid").ifBlank { System.identityHashCode(book).toString() }

    private data class BookImageContext(
        val book: Any,
        val onUpdateCover: Any,
    )

    private data class AppliedImage(
        val relativePath: String,
        val file: File,
    )

    enum class ImageTarget(
        val id: String,
        val label: String,
        val aiTarget: AiImagePresetTarget,
    ) {
        Cover("cover", "封面", AiImagePresetTarget.Cover),
        Banner("banner", "横幅", AiImagePresetTarget.Banner),
    }

    class DialogColors(context: Context) {
        private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val card: Int = if (dark) Color.rgb(30, 34, 40) else Color.WHITE
        val border: Int = if (dark) Color.rgb(64, 70, 78) else Color.rgb(226, 230, 236)
        val title: Int = if (dark) Color.WHITE else Color.rgb(28, 31, 36)
        val body: Int = if (dark) Color.rgb(190, 198, 208) else Color.rgb(88, 96, 108)
        val field: Int = if (dark) Color.rgb(38, 43, 50) else Color.rgb(246, 248, 251)
        val primary: Int = themeColor(context, android.R.attr.colorAccent, Color.rgb(57, 126, 184))
        val primarySoft: Int = if (dark) Color.rgb(42, 68, 88) else Color.rgb(232, 242, 251)
        val primaryText: Int = if (dark) Color.rgb(184, 221, 250) else Color.rgb(29, 93, 145)
        val neutralSoft: Int = if (dark) Color.rgb(42, 46, 52) else Color.rgb(241, 243, 246)
        val neutralText: Int = if (dark) Color.rgb(218, 223, 230) else Color.rgb(73, 80, 90)
    }

    private companion object {
        private const val LOG_PREFIX = "ReaMicro LSP"
        private const val BOOK_OVERVIEW_ITEMS_CLASS = "app.zhendong.reamicro.ui.home.components.BookOverviewItemsKt"
        private const val BOOK_CLASS = "app.zhendong.reamicro.data.db.entity.Book"
        private const val BOOK_COVER_BANNER_ITEM_METHOD = "BookCoverBannerItem"
        private const val COVER_BOTTOM_SHEET_METHOD = "CoverBottomSheet"
        private const val BANNER_BOTTOM_SHEET_METHOD = "BannerBottomSheet"
        private const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        private const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        private const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
        private const val DEFAULT_COVER_FILE = "book.cover"
        private const val DEFAULT_IMAGE_SIZE = "2k"
        private const val USER_AGENT = "ReaMicro-Extend/book-image"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 25_000
        private const val IMAGE_READ_TIMEOUT_MS = 90_000
        private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        private const val MAX_AI_RESPONSE_BYTES = 36 * 1024 * 1024
        private const val REFRESH_DELAY_MS = 180L
        private val BASE64_KEYS = setOf("b64_json", "image_base64", "base64", "imageData", "image_data")
        private val URL_KEYS = setOf("url", "image_url", "imageUrl")
    }
}

private fun dialogCard(context: Context, colors: BookOverviewImageSelectionHook.DialogColors): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 14))
        background = GradientDrawable().apply {
            setColor(colors.card)
            cornerRadius = dp(context, 22).toFloat()
        }
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

private fun dialogTitle(
    context: Context,
    title: String,
    colors: BookOverviewImageSelectionHook.DialogColors,
): TextView =
    TextView(context).apply {
        text = title
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setTextColor(colors.title)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 6) }
    }

private fun dialogMessage(
    context: Context,
    message: String,
    colors: BookOverviewImageSelectionHook.DialogColors,
): TextView =
    TextView(context).apply {
        text = message
        textSize = 13f
        setTextColor(colors.body)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 8) }
    }

private fun actionRow(
    context: Context,
    title: String,
    subtitle: String,
    colors: BookOverviewImageSelectionHook.DialogColors,
    onClick: () -> Unit,
): TextView =
    TextView(context).apply {
        text = if (subtitle.isBlank()) title else "$title\n$subtitle"
        textSize = if (subtitle.isBlank()) 15f else 14f
        setTextColor(if (subtitle.isBlank()) colors.neutralText else colors.title)
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        background = rounded(colors.neutralSoft, colors.border, dp(context, 12))
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 8) }
    }

private fun fieldLabel(
    context: Context,
    label: String,
    colors: BookOverviewImageSelectionHook.DialogColors,
): TextView =
    TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(colors.body)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 6)
            bottomMargin = dp(context, 6)
        }
    }

private fun editText(context: Context, hintText: String, singleLine: Boolean): EditText =
    EditText(context).apply {
        hint = hintText
        textSize = 14f
        setSingleLine(singleLine)
        minHeight = dp(context, 46)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        background = rounded(
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            ) {
                Color.rgb(38, 43, 50)
            } else {
                Color.rgb(246, 248, 251)
            },
            themeColor(context, android.R.attr.divider, Color.rgb(224, 228, 235)),
            dp(context, 12),
        )
        layoutParams = fieldParams(context)
    }

private fun previewImageView(context: Context, target: BookOverviewImageSelectionHook.ImageTarget): ImageView =
    ImageView(context).apply {
        visibility = View.GONE
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = rounded(
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            ) {
                Color.rgb(38, 43, 50)
            } else {
                Color.rgb(246, 248, 251)
            },
            themeColor(context, android.R.attr.divider, Color.rgb(224, 228, 235)),
            dp(context, 12),
        )
        val height = if (target.name == "Banner") dp(context, 180) else dp(context, 260)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        ).apply {
            topMargin = dp(context, 10)
            bottomMargin = dp(context, 10)
        }
    }

private fun statusText(
    context: Context,
    colors: BookOverviewImageSelectionHook.DialogColors,
): TextView =
    TextView(context).apply {
        text = ""
        textSize = 13f
        setTextColor(colors.body)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 10) }
    }

private fun horizontalActions(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 4) }
    }

private fun compactButton(
    context: Context,
    title: String,
    colors: BookOverviewImageSelectionHook.DialogColors,
    neutral: Boolean = false,
): TextView =
    TextView(context).apply {
        text = title
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (neutral) colors.neutralText else colors.primaryText)
        setPadding(dp(context, 8), dp(context, 11), dp(context, 8), dp(context, 11))
        background = rounded(if (neutral) colors.neutralSoft else colors.primarySoft, colors.border, dp(context, 12))
    }

private fun imageDialog(activity: Activity): Dialog =
    Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)

private fun showDialog(dialog: Dialog, content: View, activity: Activity, widthRatio: Float) {
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(content)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.46f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setGravity(Gravity.CENTER)
    }
    dialog.show()
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.46f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.setPadding(0, 0, 0, 0)
        window.findViewById<View>(android.R.id.content)?.setPadding(0, 0, 0, 0)
        window.setGravity(Gravity.CENTER)
        val attributes = window.attributes
        attributes.width = (activity.resources.displayMetrics.widthPixels * widthRatio).toInt()
        attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT
        attributes.gravity = Gravity.CENTER
        window.attributes = attributes
        window.setLayout(attributes.width, attributes.height)
    }
}

private fun scroll(card: LinearLayout, context: Context): ScrollView =
    ScrollView(context).apply {
        isFillViewport = false
        addView(card)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

private fun fieldParams(context: Context): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(context, 10) }

private fun centeredWrapParams(context: Context): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(context, 8)
    }

private fun actionWeightParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = 4
        rightMargin = 4
    }

private fun rounded(fill: Int, stroke: Int, radiusPx: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusPx.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
    }

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(attr, value, true)) value.data else fallback
}

private fun Activity.toast(message: String) {
    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
