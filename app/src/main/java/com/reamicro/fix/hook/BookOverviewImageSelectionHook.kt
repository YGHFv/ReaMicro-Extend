package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

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
import com.reamicro.fix.epub.editor.AccountBookImageFiles
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
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
    private val enabledProvider: () -> Boolean = { true },
) {

    private val host get() = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.imageSheet)
    private val contextStack = ThreadLocal<java.util.ArrayDeque<BookImageContext>>()
    private val sheetRenderStack = ThreadLocal<java.util.ArrayDeque<SheetRenderFrame>>()
    private val sheetGates = WeakHashMap<Any, BookImageSheetActionGate>()
    private val nativeSheetUi by lazy { HostBookImageSheetUi(classLoader) }
    private val imageUiHandler = com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper())

    fun install() {
        val symbols = host
        requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        requireNotNull(com.reamicro.fix.core.HostReaderFeatures.current)
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.method("overview"), object : HookCallback() {
                override fun before(param: Call) {
                    val book = param.args.getOrNull(1)?.takeIf(symbols.type("book")::isInstance) ?: return
                    val update = param.args.getOrNull(2)?.takeIf(symbols.type("function1")::isInstance) ?: return
                    val activity = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
                    val identity = AccountHostRuntime.storageSnapshot(activity) ?: return
                    val uuid = bookString(book, "uuid").trim()
                    if (bookUid(book) != identity.accountId || !AccountBookImageFiles.validUuid(uuid) ||
                        !AccountHostRuntime.isCurrentStorage(identity, activity)) return
                    val frame = BookImageContext(book, update, java.lang.ref.WeakReference(activity), identity, uuid)
                    (contextStack.get() ?: java.util.ArrayDeque<BookImageContext>().also(contextStack::set)).addLast(frame)
                    param.setObjectExtra(IMAGE_FRAME, frame)
                    runCatching { EmbeddedHostUi.captureTypography(classLoader, param.args.getOrNull(3)) }
                    BookImageDialogStyle.capture(classLoader, param.args.getOrNull(3))
                }
                override fun after(param: Call) {
                    val frame = param.getObjectExtra(IMAGE_FRAME) as? BookImageContext ?: return
                    val frames = contextStack.get() ?: return
                    if (frames.peekLast() === frame) frames.removeLast() else frames.remove(frame)
                    if (frames.isEmpty()) contextStack.remove()
                }
            })
            for (target in ImageTarget.entries) {
                val role = target.id
                val count = symbols.number("$role.callbacks")
                val entry = symbols.method("$role.entry")
                val content = symbols.method("$role.content")
                handles += HookRuntime.hookMethod(entry, object : HookCallback() {
                    override fun before(param: Call) {
                        val args = param.args
                        val previous = sheetBinding(args.getOrNull(1))
                        val context = contextStack.get()?.peekLast() ?: previous?.context ?: return
                        val callbacks = (1..count).map { index ->
                            val callback = args[index]?.takeIf(symbols.type("function")::isInstance) ?: return
                            sheetBinding(callback)?.callbacks?.first() ?: callback
                        }
                        val binding = SheetBinding(context, target, args[0] as Boolean, callbacks)
                        args[1] = Proxy.newProxyInstance(classLoader, arrayOf(symbols.type("function")), binding)
                        // Compose parameter 1 occupies bits 4..6 in the first changed mask.
                        args[args.lastIndex] = (args.last() as Int) and (14 shl 3).inv()
                    }
                })
                handles += HookRuntime.hookMethod(content, object : HookCallback() {
                    override fun before(param: Call) {
                        val closure = param.thisObject ?: return
                        val binding = sheetBinding(symbols.field("$role.pick").get(closure)) ?: return
                        if (binding.target != target) return
                        val state = symbols.field("$role.state").get(closure)?.takeIf(symbols.type("sheet")::isInstance) ?: return
                        val scope = symbols.field("$role.scope").get(closure)?.takeIf(symbols.type("$role.scope")::isInstance) ?: return
                        val composer = param.args.getOrNull(1)?.takeIf(symbols.type("composer")::isInstance) ?: return
                        val activity = runCatching { requireImageOwner(binding.context) }.getOrNull() ?: return
                        val lease = com.reamicro.fix.reload.ModuleResources.lease() ?: return
                        val frame = SheetRenderFrame(binding, composer, scope, state, activity, lease)
                        (sheetRenderStack.get() ?: java.util.ArrayDeque<SheetRenderFrame>().also(sheetRenderStack::set)).addLast(frame)
                        param.setObjectExtra(IMAGE_SHEET_FRAME, frame)
                        // Render the complete native sheet, including its own card and cancel row.
                    }
                    override fun after(param: Call) {
                        val frame = param.getObjectExtra(IMAGE_SHEET_FRAME) as? SheetRenderFrame ?: return
                        val frames = sheetRenderStack.get()
                        if (frames?.peekLast() === frame) frames.removeLast() else frames?.remove(frame)
                        if (frames?.isEmpty() == true) sheetRenderStack.remove()
                        frame.lease.close()
                    }
                })
                HookRuntime.deoptimize(entry)
                HookRuntime.deoptimize(content)
            }
            handles += HookRuntime.hookMethod(symbols.method("menu.text"), object : HookCallback() {
                override fun after(param: Call) {
                    val frame = sheetRenderStack.get()?.peekLast() ?: return
                    if (!frame.injected && param.throwable == null && param.args.getOrNull(16) === frame.composer &&
                        param.args.firstOrNull() == "选取图片") frame.awaitingArrow = true
                }
            })
            handles += HookRuntime.hookMethod(symbols.method("menu.icon"), object : HookCallback() {
                override fun after(param: Call) {
                    val frame = sheetRenderStack.get()?.peekLast() ?: return
                    if (!frame.injected && frame.awaitingArrow && param.throwable == null &&
                        param.args.getOrNull(4) === frame.composer && param.args.firstOrNull() === symbols.call("menu.next")) {
                        frame.awaitingArrow = false
                        frame.awaitingRowEnd = true
                    }
                }
            })
            handles += HookRuntime.hookMethod(symbols.method("menu.endNode"), object : HookCallback() {
                override fun after(param: Call) {
                    val frame = sheetRenderStack.get()?.peekLast() ?: return
                    if (frame.injected || !frame.awaitingRowEnd || param.throwable != null ||
                        param.thisObject !== frame.composer || param.args.firstOrNull() != true) return
                    frame.awaitingRowEnd = false
                    frame.injected = true
                    runCatching {
                        nativeSheetUi.renderAdditionalRows(frame.composer,
                            BookImageSheetActions.additions(frame.binding.target == ImageTarget.Cover, frame.binding.hasSecondary)) { id ->
                            performSheetAction(frame, id)
                        }
                    }.onFailure { HookRuntime.logError("$LOG_PREFIX native image menu additions failed", it) }
                }
            })
            HookRuntime.deoptimize(symbols.method("overview"))
            com.reamicro.fix.reload.ModuleResources.cleanup {
                contextStack.remove()
                sheetRenderStack.remove()
                synchronized(sheetGates) { sheetGates.clear() }
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    private inner class SheetRenderFrame(
        val binding: SheetBinding,
        val composer: Any,
        val scope: Any,
        val state: Any,
        val activity: Activity,
        val lease: AutoCloseable,
    ) {
        var awaitingArrow = false
        var awaitingRowEnd = false
        var injected = false
    }

    private fun performSheetAction(frame: SheetRenderFrame, id: BookImageSheetActions.Id) {
        if (!isImageOwnerCurrent(frame.binding.context)) return
        val gate = synchronized(sheetGates) { sheetGates.getOrPut(frame.state) { BookImageSheetActionGate() } }
        if (!gate.tryStart()) return
        runCatching {
            nativeSheetUi.hide(frame.binding.target.id, frame.scope, frame.state) { hidden, error ->
                gate.hideFinished(hidden)
                if (hidden) {
                    synchronized(sheetGates) { sheetGates.remove(frame.state) }
                    frame.activity.runOnModuleUiThread {
                        if (isImageOwnerCurrent(frame.binding.context)) runCatching {
                            frame.binding.execute(id)
                        }.onFailure {
                            if (isImageOwnerCurrent(frame.binding.context)) frame.activity.toast("操作失败，请重试")
                            HookRuntime.logError("$LOG_PREFIX image action failed", it)
                        }
                    }
                } else if (error != null) HookRuntime.logError("$LOG_PREFIX image sheet dismissal failed", error)
            }
        }.onFailure {
            gate.hideFinished(false)
            if (isImageOwnerCurrent(frame.binding.context)) frame.activity.toast("窗口关闭失败，请重试")
            HookRuntime.logError("$LOG_PREFIX image sheet dismissal failed", it)
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
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when {
            method == host.method("invoke") -> {
                if (isImageOwnerCurrent(context)) invokeFunction0(callbacks.first())
                targetUnit()
            }
            method.name == "toString" -> "ReaMicro-NativeImageSheetBinding"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === args?.firstOrNull()
            else -> null
        }

        fun execute(id: BookImageSheetActions.Id) {
            val activity = requireImageOwner(context)
            fun dismiss() = invokeFunction0(callbacks.last())
            when (id) {
                BookImageSheetActions.Id.Save -> { dismiss(); saveCurrentImageToGallery(context, target) }
                BookImageSheetActions.Id.Online -> { dismiss(); showOnlineImageDialog(context, target) }
                BookImageSheetActions.Id.Generate -> { dismiss(); showAiImageDialog(context, target) }
                BookImageSheetActions.Id.Fix -> {
                    dismiss()
                    val started = AccountHostRuntime.withCurrentStorage(context.identity, activity) {
                        requireImageOwner(context)
                        requestCoverFix(context.book)
                    }
                    if (!started && isImageOwnerCurrent(context)) activity.toast("当前页面无法执行封面修复")
                }
            }
        }
    }

    private fun showOnlineImageDialog(context: BookImageContext, target: ImageTarget) {
        val activity = requireImageOwner(context)
        activity.runOnModuleUiThread {
            if (!isImageOwnerCurrent(context)) return@runOnModuleUiThread
            HostBookImageDialog(activity, "在线${target.label}", generated = false,
                fetch = { url, _ ->
                    requireImageOwner(context)
                    downloadImageBytes(url).also { requireImageOwner(context) }
                },
                applyImage = { bytes ->
                    val applied = saveBookImage(context, target, bytes)
                    val finish: () -> Unit = { finishApply(activity, context, target, applied) }
                    finish
                }, checkCurrent = { requireImageOwner(context) }).show()
        }
    }

    private fun showAiImageDialog(context: BookImageContext, target: ImageTarget) {
        val activity = requireImageOwner(context)
        activity.runOnModuleUiThread {
            if (!isImageOwnerCurrent(context)) return@runOnModuleUiThread
            val appContext = activity.applicationContext ?: activity
            val config = AiApiStore.imageApi(appContext)
            val settings = AiApiStore.imageSettings(appContext)
            val preset = AiApiStore.imagePreset(appContext, target.aiTarget,
                if (target == ImageTarget.Cover) settings.coverPresetId else settings.bannerPresetId)
            HostBookImageDialog(activity, "生成${target.label}", generated = true,
                initialPrompt = preset.prompt, model = config?.model?.takeIf { it.isNotBlank() } ?: "未配置",
                fetch = { prompt, size ->
                    requireImageOwner(context)
                    val api = requireNotNull(config) { "请先在 API 配置里设置生图 API" }
                    generateImage(api, renderImagePrompt(prompt, context.book),
                        loadReferenceImageBytes(context), size, target).also { requireImageOwner(context) }
                },
                applyImage = { bytes ->
                    val applied = saveBookImage(context, target, bytes)
                    val finish: () -> Unit = { finishApply(activity, context, target, applied) }
                    finish
                },
                saveImage = { bytes ->
                    saveToGallery(activity, bytes, target, checkCurrent = { requireImageOwner(context) },
                        commit = { action -> AccountHostRuntime.withCurrentStorage(context.identity, activity) {
                            requireImageOwner(context); action()
                        } })
                    Unit
                },
                checkCurrent = { requireImageOwner(context) },
            ).show()
        }
    }

    private fun saveBookImage(context: BookImageContext, target: ImageTarget, bytes: ByteArray): AppliedImage {
        val activity = requireImageOwner(context)
        validateImageBytes(bytes)
        val coverPlan = if (target == ImageTarget.Cover) {
            com.reamicro.fix.epub.editor.AccountBookCoverFiles.nativePlan(classLoader,
                bookString(context.book, "cover"), "cover.${imageExtensionAndMime(bytes).first}")
        } else null
        val relative = when (target) {
            ImageTarget.Cover -> requireNotNull(coverPlan).path
            ImageTarget.Banner -> bannerRelativeForWrite(context.book)
        }
        if (coverPlan != null) {
            com.reamicro.fix.epub.editor.AccountBookCoverFiles.prepare(
                AccountBookImageFiles.directory(activity.filesDir, context.identity.accountId, context.uuid), coverPlan,
                checkCurrent = { requireImageOwner(context) },
                commit = { action -> AccountHostRuntime.withCurrentStorage(context.identity, activity) {
                    requireImageOwner(context); action()
                } })
        }
        val file = AccountBookImageFiles.write(activity.filesDir, context.identity.accountId, context.uuid,
            relative, bytes, checkCurrent = { requireImageOwner(context) },
            commit = { action -> AccountHostRuntime.withCurrentStorage(context.identity, activity) {
                requireImageOwner(context); action()
            } })
        return AppliedImage(relative, file)
    }

    private fun finishApply(
        activity: Activity,
        context: BookImageContext,
        target: ImageTarget,
        applied: AppliedImage,
    ) {
        try {
            AccountBookImageFiles.notifyOwner(checkCurrent = { check(requireImageOwner(context) === activity) },
                commit = { action -> AccountHostRuntime.withCurrentStorage(context.identity, activity) { action() } },
                onUpdated = { if (target == ImageTarget.Cover) host.method("update").invoke(context.onUpdateCover, applied.relativePath) })
        } catch (error: Throwable) {
            throw com.reamicro.fix.epub.editor.EpubWriteNotConfirmed("图片文件已写入，但页面更新未完成。请重新打开检查。", error)
        }
        if (!isImageOwnerCurrent(context)) return
        activity.toast("${target.label}已应用")
        imageUiHandler.postDelayed({
            runCatching {
                if (isImageOwnerCurrent(context)) activity.recreate()
            }.onFailure {
                HookRuntime.log("$LOG_PREFIX failed to refresh book overview image: ${it.stackTraceToString()}")
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
                        HookRuntime.log("$LOG_PREFIX failed to fetch generated image url: ${it.stackTraceToString()}")
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
        val title = bookString(book, "title").trim().ifBlank { "当前书籍" }
        val bookText = buildList {
            title.takeIf { it.isNotBlank() }?.let { add(it) }
            bookString(book, "author").trim().takeIf { it.isNotBlank() }?.let { add("作者：$it") }
            bookString(book, "publisher").trim().takeIf { it.isNotBlank() }?.let { add("出版/关联：$it") }
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
        val activity = requireImageOwner(context)
        activity.toast("正在保存${target.label}到相册")
        com.reamicro.fix.reload.ModuleResources.thread {
            val result = runCatching {
                val bytes = loadCurrentImageBytes(context, target) ?: error("未找到${target.label}图片")
                saveToGallery(activity, bytes, target, checkCurrent = { requireImageOwner(context) },
                    commit = { action -> AccountHostRuntime.withCurrentStorage(context.identity, activity) {
                        requireImageOwner(context); action()
                    } })
            }
            activity.runOnModuleUiThread {
                if (!isImageOwnerCurrent(context)) return@runOnModuleUiThread
                result.onSuccess { activity.toast("${target.label}已保存到相册") }
                    .onFailure {
                        activity.toast(it.message ?: it.javaClass.simpleName)
                        HookRuntime.log("$LOG_PREFIX save $target to gallery failed: ${it.stackTraceToString()}")
                    }
            }
        }.apply { name = "ReaMicro-SaveBookImage" }.start()
    }

    private fun loadCurrentImageBytes(context: BookImageContext, target: ImageTarget): ByteArray? {
        val activity = requireImageOwner(context)
        return when (target) {
            ImageTarget.Cover -> loadReferenceImageBytes(context)
            ImageTarget.Banner -> {
                val bookDir = resolveBookDir(activity, context) ?: return null
                val relative = bannerRelativeForWrite(context.book)
                readImageFile(childFile(bookDir, relative)).also { requireImageOwner(context) }
            }
        }
    }

    private fun saveToGallery(
        activity: Activity,
        bytes: ByteArray,
        target: ImageTarget,
        checkCurrent: () -> Unit,
        commit: (() -> Unit) -> Unit,
    ): Uri {
        checkCurrent()
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
        checkCurrent()
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkCurrent()
                    val count = minOf(64 * 1024, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
            } ?: error("无法写入相册文件")
            commit {
                checkCurrent()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) > 0) { "相册文件尚未发布" }
                }
            }
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }.onFailure(error::addSuppressed)
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

    private fun loadReferenceImageBytes(context: BookImageContext): ByteArray? {
        val activity = requireImageOwner(context)
        val cover = bookString(context.book, "cover").trim()
        val direct = when {
            cover.startsWith("data:image/", ignoreCase = true) ->
                runCatching { decodeDataUrl(cover)?.also(::validateImageBytes) }.getOrNull()
            isRemoteUrl(cover) -> runCatching { downloadImageBytes(cover) }.getOrNull()
            cover.startsWith("content://", ignoreCase = true) ->
                runCatching { readImageUri(activity, Uri.parse(cover)) }.getOrNull()
            else -> null
        }
        requireImageOwner(context)
        if (direct != null) return direct
        val bookDir = resolveBookDir(activity, context) ?: return null
        val relatives = listOf(safeRelativePath(cover), DEFAULT_COVER_FILE).filter { it.isNotBlank() }.distinct()
        for (relative in relatives) {
            requireImageOwner(context)
            val bytes = runCatching { readImageFile(childFile(bookDir, relative)) }.getOrNull()
            requireImageOwner(context)
            if (bytes != null) return bytes
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

    private fun bannerRelativeForWrite(book: Any): String {
        val cover = safeRelativePath(bookString(book, "cover"))
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

    private fun childFile(root: File, relativePath: String): File =
        AccountBookImageFiles.child(root, relativePath)

    private fun resolveBookDir(activity: Activity, context: BookImageContext): File? {
        check(requireImageOwner(context) === activity)
        return AccountBookImageFiles.directory(activity.filesDir, context.identity.accountId, context.uuid)
            .takeIf { it.isDirectory }
    }

    private fun requireImageOwner(context: BookImageContext): Activity {
        check(enabledProvider()) { "封面与横幅选择已关闭" }
        val activity = context.activity.get() ?: error("图片页面已经关闭")
        check(activityProvider() === activity && !activity.isFinishing && !activity.isDestroyed &&
            bookUid(context.book) == context.identity.accountId && bookString(context.book, "uuid").trim() == context.uuid &&
            AccountHostRuntime.isCurrentStorage(context.identity, activity)) {
            "账号或图书已变化，请重新打开图片菜单"
        }
        return activity
    }

    private fun isImageOwnerCurrent(context: BookImageContext): Boolean =
        runCatching { requireImageOwner(context); true }.getOrDefault(false)

    private fun extractApiError(text: String): String =
        runCatching {
            val json = JSONObject(text)
            val error = json.optJSONObject("error") ?: return@runCatching ""
            error.optString("message")
        }.getOrDefault("")

    private fun invokeFunction0(callback: Any?) {
        runCatching { host.method("invoke").invoke(callback) }
            .onFailure { HookRuntime.log("$LOG_PREFIX failed to invoke Function0: ${it.stackTraceToString()}") }
    }

    private fun targetUnit(): Any = requireNotNull(com.reamicro.fix.core.HostReaderFeatures.current).unit
    private fun bookString(book: Any, role: String): String = host.field("book.$role").get(book) as? String ?: ""
    private fun bookUid(book: Any): Long = host.field("book.uid").getLong(book)

    private fun isRemoteUrl(value: String): Boolean {
        val lower = value.trim().lowercase(Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private data class BookImageContext(
        val book: Any,
        val onUpdateCover: Any,
        val activity: java.lang.ref.WeakReference<Activity>,
        val identity: AccountHostRuntime.Identity,
        val uuid: String,
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
        private const val IMAGE_FRAME = "ReaMicro.image.context"
        private const val IMAGE_SHEET_FRAME = "ReaMicro.image.sheet.render"
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
    runOnModuleUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
