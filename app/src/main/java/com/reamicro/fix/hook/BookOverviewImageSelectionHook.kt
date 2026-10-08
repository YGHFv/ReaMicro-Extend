package com.reamicro.fix.hook

import android.app.Activity
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import com.reamicro.fix.ai.AiApiConfig
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class BookOverviewImageSelectionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val requestCoverFix: (Any) -> Boolean = { false },
) {

    private val contextStack = ThreadLocal.withInitial { mutableListOf<BookImageContext?>() }
    private val sheetGates = WeakHashMap<Any, BookImageSheetActionGate>()
    private val nativeSheetUi by lazy { BookImageSheetUi(classLoader) }

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
                        val book = param.args?.getOrNull(1)
                        val onUpdateCover = param.args?.getOrNull(2)

                        stack().add(if (book != null && onUpdateCover != null) BookImageContext(book, onUpdateCover) else null)
                        runCatching { EmbeddedHostUi.captureTypography(classLoader, param.args?.getOrNull(3)) }
                        BookImageDialogStyle.capture(classLoader, param.args?.getOrNull(3))
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

            val ui = nativeSheetUi
            val itemsClass = cls(BOOK_OVERVIEW_ITEMS_CLASS)
            val callbackCount = if (target == ImageTarget.Cover) 4 else 3
            val entry = itemsClass.declaredMethods.single { method ->
                method.name == methodName &&
                    method.parameterTypes.map { it.name } == listOf("boolean") +
                    List(callbackCount) { FUNCTION0_CLASS } + listOf(COMPOSER_CLASS, "int")
            }.apply { isAccessible = true }
            val content = itemsClass.declaredMethods.single { method ->
                method.name == "$methodName\$lambda\$2" &&
                    method.parameterTypes.size == callbackCount + 6 &&
                    method.parameterTypes[0].name == "kotlinx.coroutines.CoroutineScope" &&
                    method.parameterTypes[1].name == "androidx.compose.material3.SheetState" &&
                    method.parameterTypes.count { it.name == FUNCTION0_CLASS } == callbackCount &&
                    method.parameterTypes.takeLast(3).map { it.name } ==
                    listOf("androidx.compose.foundation.layout.ColumnScope", COMPOSER_CLASS, "int")
            }.apply { isAccessible = true }

            XposedBridge.hookMethod(content, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val binding = args.firstNotNullOfOrNull(::sheetBinding) ?: return
                    if (binding.target != target) return
                    val activity = activityProvider() ?: return
                    if (activity.isFinishing || activity.isDestroyed) return
                    val scope = args[0] ?: return
                    val sheetState = args[1] ?: return
                    val composer = args[args.size - 2] ?: return
                    val gate = synchronized(sheetGates) {
                        sheetGates.getOrPut(sheetState) { BookImageSheetActionGate() }
                    }

                    ui.render(composer, BookImageSheetActions.items(target == ImageTarget.Cover, binding.hasSecondary)) { id ->
                        if (gate.tryStart()) {
                            runCatching {
                                ui.hide(scope, sheetState) { hidden, error ->
                                    gate.hideFinished(hidden)
                                    if (hidden) {
                                        synchronized(sheetGates) { sheetGates.remove(sheetState) }
                                        activity.runOnUiThread {
                                            if (!activity.isFinishing && !activity.isDestroyed) {
                                                runCatching { binding.execute(id) }.onFailure {
                                                    activity.toast("操作失败，请重试")
                                                    XposedBridge.log("$LOG_PREFIX image action failed: $it")
                                                }
                                            }
                                        }
                                    } else if (error != null) {
                                        XposedBridge.log("$LOG_PREFIX image sheet dismissal cancelled: $error")
                                    }
                                }
                            }.onFailure {
                                gate.hideFinished(false)
                                activity.toast("窗口关闭失败，请重试")
                                XposedBridge.log("$LOG_PREFIX image sheet dismissal failed: $it")
                            }
                        }
                    }
                    param.result = ui.unit
                }
            })
            XposedBridge.hookMethod(entry, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val previous = sheetBinding(args.getOrNull(1))
                    val context = currentBookContext() ?: previous?.context ?: return
                    val callbacks = (1..callbackCount).map { index ->
                        val callback = args[index] ?: return

                        sheetBinding(callback)?.callbacks?.first() ?: callback
                    }
                    val binding = SheetBinding(context, target, args[0] as Boolean, callbacks)
                    args[1] = Proxy.newProxyInstance(classLoader, arrayOf(cls(FUNCTION0_CLASS)), binding)

                    args[args.lastIndex] = (args.last() as Int) and (0x7 shl 4).inv()

                }
            })
            XposedBridge.log("$LOG_PREFIX $methodName unified native content installed")
        }.onFailure {

            XposedBridge.log("$LOG_PREFIX $methodName native menu kept (unsupported ABI): ${it.stackTraceToString()}")
        }
    }

    private fun sheetBinding(value: Any?): SheetBinding? =
        if (value != null && Proxy.isProxyClass(value.javaClass))
            Proxy.getInvocationHandler(value) as? SheetBinding else null

    private inner class SheetBinding(
        val context: BookImageContext,
        val target: ImageTarget,
        val hasSecondary: Boolean,
        val callbacks: List<Any>,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "invoke" -> { invokeFunction0(callbacks.first()); targetUnit() }
            "toString" -> "ReaMicro-NativeImageSheetBinding"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }

        fun execute(id: BookImageSheetActions.Id) {
            fun dismiss() = invokeFunction0(callbacks.last())
            when (id) {
                BookImageSheetActions.Id.Pick -> invokeFunction0(callbacks[0])
                BookImageSheetActions.Id.Save -> if (target == ImageTarget.Cover)
                    invokeFunction0(callbacks[1])
                else { dismiss(); saveCurrentImageToGallery(context, target) }
                BookImageSheetActions.Id.Restore -> if (hasSecondary) invokeFunction0(callbacks[2])
                BookImageSheetActions.Id.Remove -> if (hasSecondary) invokeFunction0(callbacks[1])
                BookImageSheetActions.Id.Online -> { dismiss(); showOnlineImageDialog(context, target) }
                BookImageSheetActions.Id.Generate -> { dismiss(); showAiImageDialog(context, target) }
                BookImageSheetActions.Id.Fix -> {
                    dismiss()
                    if (!requestCoverFix(context.book)) activityProvider()?.toast("当前页面无法执行封面修复")
                }
                BookImageSheetActions.Id.Cancel -> dismiss()
            }
        }
    }

    private fun showOnlineImageDialog(context: BookImageContext, target: ImageTarget) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            HostBookImageDialog(activity, "在线${target.label}", generated = false,
                fetch = { url, _ -> downloadImageBytes(url) },
                applyImage = { bytes ->
                    val applied = saveBookImage(context, target, bytes)
                    val finish: () -> Unit = { finishApply(activity, context, target, applied) }
                    finish
                }).show()
        }
    }

    private fun showAiImageDialog(context: BookImageContext, target: ImageTarget) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            val appContext = activity.applicationContext ?: activity
            val config = AiApiStore.imageApi(appContext)
            val settings = AiApiStore.imageSettings(appContext)
            val preset = AiApiStore.imagePreset(appContext, target.aiTarget,
                if (target == ImageTarget.Cover) settings.coverPresetId else settings.bannerPresetId)
            HostBookImageDialog(activity, "生成${target.label}", generated = true,
                initialPrompt = preset.prompt, model = config?.model?.takeIf { it.isNotBlank() } ?: "未配置",
                fetch = { prompt, size ->
                    val api = requireNotNull(config) { "请先在 API 配置里设置生图 API" }
                    generateImage(api, renderImagePrompt(prompt, context.book),
                        loadReferenceImageBytes(context.book), size, target)
                },
                applyImage = { bytes ->
                    val applied = saveBookImage(context, target, bytes)
                    val finish: () -> Unit = { finishApply(activity, context, target, applied) }
                    finish
                },
                saveImage = { bytes -> saveToGallery(activity, bytes, target); Unit },
            ).show()
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
        referenceBytes: ByteArray?,
        requestedSize: String,
        target: ImageTarget,
    ): ByteArray {
        val sizes = imageSizeCandidates(requestedSize, target)
        val referenceDataUrl = referenceBytes?.let { bytes ->
            val mime = imageExtensionAndMime(bytes).second
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        val isGptImage = config.model.contains("gpt-image", ignoreCase = true)
        var lastError: Throwable? = null

        if (isGptImage && referenceBytes != null) {
            for (size in sizes) {
                val normalizedSize = normalizeImageSize(size)
                val bytes = runCatching {
                    requestImageEditsMultipart(config, prompt, normalizedSize, referenceBytes)
                }.onFailure { lastError = it }.getOrNull()
                if (bytes != null) return bytes
            }
        }

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

    private fun requestImageEditsMultipart(
        config: AiApiConfig,
        prompt: String,
        size: String,
        referenceBytes: ByteArray,
    ): ByteArray {
        val (ext, mime) = imageExtensionAndMime(referenceBytes)
        val boundary = "reamicro-${System.currentTimeMillis()}"
        val parts = mutableListOf<Pair<String, ByteArray?>>()
        parts += "model" to config.model.toByteArray(Charsets.UTF_8)
        parts += "prompt" to prompt.toByteArray(Charsets.UTF_8)
        parts += "n" to "1".toByteArray(Charsets.UTF_8)
        if (size.isNotBlank()) parts += "size" to size.toByteArray(Charsets.UTF_8)
        parts += "response_format" to "b64_json".toByteArray(Charsets.UTF_8)
        val bodyBytes = buildMultipartBytes(boundary, parts, "image", "reference.$ext", mime, referenceBytes)

        val connection = (URL(imageEditsUrl(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = IMAGE_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json,image/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(bodyBytes) }
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
                error("生图(edits)接口未返回有效 JSON")
            }
            extractImageBytes(json) ?: error("生图(edits)接口返回中没有图片数据")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMultipartBytes(
        boundary: String,
        fields: List<Pair<String, ByteArray?>>,
        fileFieldName: String,
        fileName: String,
        fileMime: String,
        fileBytes: ByteArray,
    ): ByteArray {
        val crlf = "\r\n"
        val out = java.io.ByteArrayOutputStream()
        fun writeFormField(name: String, value: ByteArray) {
            out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"$name\"$crlf$crlf".toByteArray(Charsets.UTF_8))
            out.write(value)
            out.write(crlf.toByteArray(Charsets.UTF_8))
        }
        fields.forEach { (name, value) ->
            if (value != null) writeFormField(name, value)
        }

        out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: $fileMime$crlf$crlf".toByteArray(Charsets.UTF_8))
        out.write(fileBytes)
        out.write(crlf.toByteArray(Charsets.UTF_8))
        out.write("--$boundary--$crlf".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun imageEditsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/images/edits", ignoreCase = true) -> base
            base.endsWith("/images/generations", ignoreCase = true) ->
                base.removeSuffix("/images/generations") + "/images/edits"
            base.endsWith("/chat/completions", ignoreCase = true) ->
                base.removeSuffix("/chat/completions") + "/images/edits"
            base.endsWith("/v1", ignoreCase = true) || isVersionedApiBaseUrl(base) -> "$base/images/edits"
            base.isBlank() -> error("Base URL 为空")
            else -> "$base/v1/images/edits"
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

    private fun saveCurrentImageToGallery(context: BookImageContext, target: ImageTarget) {
        val activity = activityProvider() ?: return
        activity.toast("正在保存${target.label}到相册")
        Thread {
            val result = runCatching {
                val bytes = loadCurrentImageBytes(activity, context.book, target)
                    ?: error("未找到${target.label}图片")
                saveToGallery(activity, bytes, target)
            }
            activity.runOnUiThread {
                result
                    .onSuccess { activity.toast("${target.label}已保存到相册") }
                    .onFailure {
                        val text = it.message ?: it.javaClass.simpleName
                        activity.toast(text)
                        XposedBridge.log("$LOG_PREFIX save $target to gallery failed: ${it.stackTraceToString()}")
                    }
            }
        }.apply { name = "ReaMicro-SaveBookImage" }.start()
    }

    private fun loadCurrentImageBytes(activity: Activity, book: Any, target: ImageTarget): ByteArray? =
        when (target) {
            ImageTarget.Cover -> loadReferenceImageBytes(book)
            ImageTarget.Banner -> {
                val bookDir = resolveBookDir(activity, book) ?: return null
                val relative = bannerRelativeForWrite(book)
                readImageFile(childFile(bookDir, relative))
            }
        }

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
        return com.reamicro.fix.epub.editor.EpubBannerVariants.primary(cover, book.javaClass.classLoader)
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

    private fun currentBookContext(): BookImageContext? =
        stack().lastOrNull()

    private fun stack(): MutableList<BookImageContext?> {
        val existing = contextStack.get()
        if (existing != null) return existing
        val created = mutableListOf<BookImageContext?>()
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

    private companion object {
        private const val LOG_PREFIX = "ReaMicro LSP"
        private const val BOOK_OVERVIEW_ITEMS_CLASS = HostClasses.Host.BOOK_OVERVIEW_ITEMS
        private const val BOOK_CLASS = HostClasses.Host.BOOK
        private const val BOOK_COVER_BANNER_ITEM_METHOD = "BookCoverBannerItem"
        private const val COVER_BOTTOM_SHEET_METHOD = "CoverBottomSheet"
        private const val BANNER_BOTTOM_SHEET_METHOD = "BannerBottomSheet"
        private const val COMPOSER_CLASS = HostClasses.Compose.COMPOSER
        private const val FUNCTION0_CLASS = HostClasses.Kotlin.FUNCTION0
        private const val FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
        private const val KOTLIN_UNIT_CLASS = HostClasses.Kotlin.KOTLIN_UNIT
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

private fun Activity.toast(message: String) {
    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
