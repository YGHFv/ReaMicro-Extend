package com.reamicro.fix.hook

import com.reamicro.fix.core.ProfileContainerOpacity
import com.reamicro.fix.core.ImageDecodeBudget

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Shader
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.reamicro.fix.reload.ModuleResources

class ProfileBackgroundHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
    private val settingsProvider: () -> ModuleSettingsSnapshot = settings::snapshot,
) {
    private val host get() = requireNotNull(com.reamicro.fix.core.HostProfileBindings.current)
    private data class Frame(
        val active: Boolean,
        val composer: Any?,
        var rootPending: Boolean = true,
        var rootBlocked: Int = 0,
        var cards: Int = 0,
        var community: Int = 0,
        var surfaces: Int = 0,
        var pendingPrivilege: HookCallback.Call? = null,
    )
    private val frames = ThreadLocal<java.util.ArrayDeque<Frame>>()
    private fun frame(): Frame? = frames.get()?.peekLast()?.takeIf { it.active }
    private var cachedBitmap: Bitmap? = null
    private var cachedImagePath: String? = null
    private var cachedScreenBackgroundBitmap: Bitmap? = null
    private var cachedScreenBackgroundBitmapKey: String? = null
    @Volatile private var cachedColorScheme: Any? = null
    @Volatile private var autoRandomRefreshAttempted: Boolean = false
    private val randomRefreshRunning = AtomicBoolean()

    fun install() {
        val symbols = host
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        fun hook(role: String, callback: HookCallback) {
            handles += HookRuntime.hookMethod(symbols.method(role), callback)
        }
        fun enterSurface(param: HookCallback.Call, current: Frame) {
            current.surfaces++
            param.setObjectExtra(PROFILE_SURFACE_SCOPE_EXTRA, current)
        }
        fun leaveSurface(param: HookCallback.Call) {
            val current = param.getObjectExtra(PROFILE_SURFACE_SCOPE_EXTRA) as? Frame ?: return
            param.setObjectExtra(PROFILE_SURFACE_SCOPE_EXTRA, null)
            current.surfaces = (current.surfaces - 1).coerceAtLeast(0)
        }
        try {
            hook("content", object : HookCallback() {
                override fun before(param: Call) {
                    val current = Frame(canHandleProfileBackground(settingsProvider()), param.args.firstOrNull { symbols.type("composer").isInstance(it) })
                    (frames.get() ?: java.util.ArrayDeque<Frame>().also(frames::set)).addLast(current)
                    param.setObjectExtra(PROFILE_FRAME_EXTRA, current)
                    if (current.active) current.composer?.let { composer ->
                        current.rootBlocked++
                        try { cachedColorScheme = symbols.call("colorScheme", composer) } finally { current.rootBlocked-- }
                    }
                }
                override fun after(param: Call) {
                    val current = param.getObjectExtra(PROFILE_FRAME_EXTRA) as? Frame ?: return
                    current.pendingPrivilege?.let(::leaveSurface)
                    val stack = frames.get() ?: return
                    if (stack.peekLast() === current) stack.removeLast() else stack.remove(current)
                    if (stack.isEmpty()) frames.remove()
                }
            })
            hook("systemBars", object : HookCallback() {
                override fun before(param: Call) {
                    val current = frame() ?: return
                    current.rootBlocked++
                    param.setObjectExtra(PROFILE_FRAME_EXTRA, current)
                }
                override fun after(param: Call) {
                    val current = param.getObjectExtra(PROFILE_FRAME_EXTRA) as? Frame ?: return
                    current.rootBlocked--
                }
            })
            hook("materialize", object : HookCallback() {
                override fun before(param: Call) {
                    val current = frame() ?: return
                    current.pendingPrivilege?.let(::leaveSurface)
                    current.pendingPrivilege = null
                }
                override fun after(param: Call) {
                    val current = frame() ?: return
                    if (!current.rootPending || current.rootBlocked != 0 || current.cards != 0 ||
                        param.args[0] !== current.composer || param.throwable != null) return
                    current.rootPending = false
                    val modifier = param.result ?: return
                    val snapshot = settingsProvider()
                    if (canUseProfileImageBackground(snapshot)) {
                        val bitmap = loadBitmap(snapshot.profileBackgroundImage) ?: return
                        val brush = profileBackgroundImageBrush(snapshot.profileBackgroundImage, bitmap, snapshot) ?: return
                        param.result = symbols.background(modifier, brush)
                    } else if (canHandleProfileBackground(snapshot) && !snapshot.profileBackgroundUseImage) {
                        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
                        param.result = symbols.call("background", modifier, symbols.fromArgb(parseArgb(snapshot.profileBackgroundColor)), ui.value("rectangle"))
                    }
                }
            })
            hook("background", object : HookCallback() {
                override fun before(param: Call) {
                    val current = frame() ?: return
                    val snapshot = settingsProvider()
                    if (!canHandleProfileBackground(snapshot)) return
                    if (current.cards > 0 && current.surfaces == 0) {
                        if (current.community > 0) param.args[1] = transparentProfileContainerColor(param.args[1] as Long, snapshot)
                        return
                    }
                    param.args[1] = if (current.surfaces > 0) transparentProfileContainerColor(param.args[1] as Long, snapshot)
                    else symbols.fromArgb(if (snapshot.profileBackgroundUseImage) PROFILE_BACKGROUND_HOUMO_SURFACE_ARGB else parseArgb(snapshot.profileBackgroundColor))
                }
            })
            hook("surface", object : HookCallback() {
                override fun before(param: Call) {
                    val current = frame() ?: return
                    if (current.cards > 0) enterSurface(param, current)
                }
                override fun after(param: Call) = leaveSurface(param)
            })
            for (role in listOf("community", "privilege")) hook(role, object : HookCallback() {
                override fun before(param: Call) {
                    val current = frame() ?: return
                    if (!canUseProfileImageBackground(settingsProvider())) return
                    current.cards++
                    if (role == "community") current.community++
                    else { enterSurface(param, current); current.pendingPrivilege = param }
                    param.setObjectExtra(PROFILE_CARD_SCOPE_EXTRA, current)
                    symbols.useImageCard(role, param.args)
                }
                override fun after(param: Call) {
                    val current = param.getObjectExtra(PROFILE_CARD_SCOPE_EXTRA) as? Frame ?: return
                    param.setObjectExtra(PROFILE_CARD_SCOPE_EXTRA, null)
                    if (current.pendingPrivilege === param) current.pendingPrivilege = null
                    leaveSurface(param)
                    current.cards = (current.cards - 1).coerceAtLeast(0)
                    if (role == "community") current.community = (current.community - 1).coerceAtLeast(0)
                }
            })
            hook("alpha", object : HookCallback() {
                override fun before(param: Call) {
                    if ((frame()?.surfaces ?: 0) <= 0) return
                    val opacity = (100 - settingsProvider().profileBackgroundCardTransparency.coerceIn(0, 100)) / 100f
                    param.args[0] = ((param.args[0] as Float) * opacity).coerceIn(0f, 1f)
                }
            })
            hook("divider", object : HookCallback() {
                override fun before(param: Call) {
                    if (frame() != null && settingsProvider().canShowProfileBackground) param.result = null
                }
            })
            listOf("root", "content", "systemBars", "community", "privilege", "surface", "materialize", "background", "alpha", "divider").forEach {
                HookRuntime.deoptimize(symbols.method(it))
            }
            activeInstance = this
            com.reamicro.fix.reload.ModuleResources.cleanup {
                if (activeInstance === this) activeInstance = null
                frames.remove()
                releaseMemory("module cleanup")
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }

    fun refreshRandomImageFor(activity: Activity?, force: Boolean = false) {
        activity ?: return
        val snapshot = settings.snapshot()
        if (!canHandleProfileBackground(snapshot)) return
        val url = profileBackgroundImageUrlOrNull(snapshot.profileBackgroundImageUrl) ?: return
        if (!force && autoRandomRefreshAttempted) return
        if (!randomRefreshRunning.compareAndSet(false, true)) return
        if (!force) autoRandomRefreshAttempted = true
        try {
            ModuleResources.thread({
                var target: File? = null
                var queued = false
                try {
                    val downloaded = downloadRandomProfileBackground(activity, url)
                    target = downloaded
                    val updated = createActiveProfileBackgroundBitmap(downloaded.absolutePath, settings.snapshot())
                    queueRandomBackground(activity, snapshot, downloaded, updated)
                    queued = true
                } catch (error: Throwable) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    HookRuntime.logError("$LOG_PREFIX random image refresh failed", error)
                } finally {
                    if (!queued) {
                        target?.delete()
                        randomRefreshRunning.set(false)
                    }
                }
            }, "ReaMicro-ProfileRandomImage").start()
        } catch (error: Throwable) {
            randomRefreshRunning.set(false)
            if (!force) autoRandomRefreshAttempted = false
            HookRuntime.logError("$LOG_PREFIX random image refresh could not start", error)
        }
    }

    private fun queueRandomBackground(
        activity: Activity,
        requested: ModuleSettingsSnapshot,
        target: File,
        updated: Pair<Bitmap, String>?,
    ) {
        val lease = requireNotNull(ModuleResources.lease()) { "模块正在重载" }
        val submitted = try {
            ModuleResources.handler(android.os.Looper.getMainLooper()).post {
                var committed = false
                try {
                    val current = settings.snapshot()
                    if (activityProvider() !== activity || activity.isFinishing || activity.isDestroyed ||
                        !canHandleProfileBackground(current) ||
                        current.profileBackgroundImageUrl != requested.profileBackgroundImageUrl ||
                        current.profileBackgroundImage != requested.profileBackgroundImage ||
                        current.profileBackgroundUseImage != requested.profileBackgroundUseImage) return@post
                    settings.setProfileBackgroundImage(target.absolutePath)
                    committed = true
                    settings.setProfileBackgroundUseImage(true)
                    if (updated != null) replaceActiveProfileBackgroundBitmap(updated) else invalidateImageCaches()
                    activity.window?.decorView?.postInvalidateOnAnimation()
                    // Keep the currently selected file until the replacement has actually been published.
                    target.parentFile?.listFiles()?.filter {
                        it != target && it.name.startsWith(RANDOM_IMAGE_FILE_PREFIX)
                    }?.forEach { it.delete() }
                    HookRuntime.log("$LOG_PREFIX random image refreshed: ${target.name}")
                } catch (error: Throwable) {
                    HookRuntime.logError("$LOG_PREFIX random image publication failed", error)
                } finally {
                    if (!committed) target.delete()
                    randomRefreshRunning.set(false)
                    lease.close()
                }
            }
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
        if (!submitted) {
            lease.close()
            error("无法发布随机背景")
        }
    }

    private fun canHandleProfileBackground(snapshot: ModuleSettingsSnapshot): Boolean =
        snapshot.moduleEnabled && snapshot.profileBackgroundEnabled

    private fun canUseProfileImageBackground(snapshot: ModuleSettingsSnapshot): Boolean =
        canHandleProfileBackground(snapshot) &&
            snapshot.profileBackgroundUseImage &&
            snapshot.profileBackgroundImage.isNotBlank()

    private fun profileBackgroundImageUrlOrNull(text: String): String? {
        val candidate = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trim()
            ?.trimEnd('.', ',', ';', '\'', '"', ')', ']', '}', '\u3002', '\uff0c')
            ?: return null
        if (candidate.length > PROFILE_BACKGROUND_IMAGE_URL_MAX_LENGTH) return null
        return candidate.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun downloadRandomProfileBackground(activity: Activity, url: String): File {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = RANDOM_IMAGE_CONNECT_TIMEOUT_MS
            readTimeout = RANDOM_IMAGE_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", RANDOM_IMAGE_USER_AGENT)
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val bytes = connection.inputStream.use { readLimited(it, MAX_RANDOM_IMAGE_BYTES) }
            validateImageBytes(bytes)
            val targetDir = File(activity.filesDir, "profile_background")
            check(targetDir.isDirectory || targetDir.mkdirs()) { "无法创建背景目录" }
            val extension = imageExtension(connection.contentType, url)
            val target = File.createTempFile(RANDOM_IMAGE_FILE_PREFIX, ".$extension", targetDir)
            return try {
                target.writeBytes(bytes)
                target
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("背景下载已取消")
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) error("\u56fe\u7247\u8fc7\u5927")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validateImageBytes(bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) error("\u4e0d\u662f\u6709\u6548\u56fe\u7247")
    }

    private fun imageExtension(contentType: String?, url: String): String {
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        return when {
            "png" in type -> "png"
            "webp" in type -> "webp"
            "gif" in type -> "gif"
            "jpeg" in type || "jpg" in type -> "jpg"
            else -> url.substringBefore('?').substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") }
                ?: "jpg"
        }
    }

    fun releaseMemory(reason: String) {
        invalidateImageCaches()
        cachedColorScheme = null
        HookRuntime.log("$LOG_PREFIX profile background memory released: $reason")
    }

    private fun invalidateImageCaches() {
        cachedBitmap = null
        cachedImagePath = null
        cachedScreenBackgroundBitmap = null
        cachedScreenBackgroundBitmapKey = null
    }

    private fun bottomEdgeAverageColor(bitmap: Bitmap): Int {

        val downscale = 0.25f
        val smallW = (bitmap.width * downscale).toInt().coerceAtLeast(1)
        val smallH = (bitmap.height * downscale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
        val sampleRows = (smallH * 0.05f).toInt().coerceAtLeast(1)
        val startRow = (smallH - sampleRows).coerceAtLeast(0)
        var a = 0; var r = 0; var g = 0; var b = 0
        var count = 0
        for (y in startRow until smallH) {
            for (x in 0 until smallW) {
                val p = small.getPixel(x, y)
                a += (p ushr 24) and 0xff
                r += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
                count++
            }
        }
        if (count == 0) return 0xFF808080.toInt()
        return ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
    }

    private fun loadBitmap(path: String): Bitmap? {
        if (cachedImagePath == path && cachedBitmap != null) return cachedBitmap
        val file = File(path)
        if (!file.isFile) {
            HookRuntime.log("$LOG_PREFIX image file not found: $path")
            return null
        }
        val bitmap = runCatching { decodeProfileBitmap(path) }.getOrElse {
            HookRuntime.log("$LOG_PREFIX decode image failed: ${it.stackTraceToString()}")
            return null
        }
        cachedBitmap = bitmap
        cachedImagePath = path
        return bitmap
    }

    private fun decodeProfileBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageDecodeBudget.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun profileBackgroundImageBrush(
        path: String,
        bitmap: Bitmap,
        snapshot: ModuleSettingsSnapshot,
    ): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val screenW = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val pageArgb = hostBackgroundArgb() ?: profileBackgroundPageFallbackArgb()
        val key = profileBackgroundBitmapKey(path, screenW, screenH, snapshot, pageArgb)
        val brushBitmap = if (cachedScreenBackgroundBitmapKey == key && cachedScreenBackgroundBitmap != null) {
            cachedScreenBackgroundBitmap!!
        } else {
            val composed = createProfileBackgroundBitmap(bitmap, screenW, screenH, snapshot, pageArgb)
            cachedScreenBackgroundBitmap = composed
            cachedScreenBackgroundBitmapKey = key
            composed
        }
        val matrix = android.graphics.Matrix()
        val scaleX = screenW.toFloat() / brushBitmap.width.toFloat().coerceAtLeast(1f)
        val scaleY = screenH.toFloat() / brushBitmap.height.toFloat().coerceAtLeast(1f)
        matrix.setScale(scaleX, scaleY)
        val shader = BitmapShader(brushBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        host.shader(shader)
    }.getOrElse {
        HookRuntime.log("$LOG_PREFIX profileBackgroundImageBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun profileBackgroundBitmapKey(
        path: String,
        screenW: Int,
        screenH: Int,
        snapshot: ModuleSettingsSnapshot,
        pageArgb: Int,
    ): String = listOf(
        "deepink2",
        path,
        screenW,
        screenH,
        snapshot.profileBackgroundCropPosition,
        snapshot.profileBackgroundDisplayMode,
        pageArgb,
    ).joinToString("|")

    private fun createActiveProfileBackgroundBitmap(
        path: String,
        snapshot: ModuleSettingsSnapshot,
    ): Pair<Bitmap, String>? = runCatching {
        val active = cachedScreenBackgroundBitmap ?: return@runCatching null
        if (active.isRecycled) return@runCatching null
        // Preparation runs in a worker; publish the shared image cache only on the UI thread.
        val bitmap = decodeProfileBitmap(path) ?: return@runCatching null
        val pageArgb = hostBackgroundArgb() ?: profileBackgroundPageFallbackArgb()
        val key = profileBackgroundBitmapKey(path, active.width, active.height, snapshot, pageArgb)
        createProfileBackgroundBitmap(bitmap, active.width, active.height, snapshot, pageArgb) to key
    }.getOrElse {
        HookRuntime.log("$LOG_PREFIX create active profile background failed: ${it.stackTraceToString()}")
        null
    }

    private fun replaceActiveProfileBackgroundBitmap(updated: Pair<Bitmap, String>) {
        val active = cachedScreenBackgroundBitmap
        val bitmap = updated.first
        if (active == null || active.isRecycled ||
            active.width != bitmap.width || active.height != bitmap.height
        ) {
            cachedScreenBackgroundBitmap = bitmap
        } else {
            android.graphics.Canvas(active).drawBitmap(bitmap, 0f, 0f, null)
        }
        cachedScreenBackgroundBitmapKey = updated.second
    }

    private fun createProfileBackgroundBitmap(
        bitmap: Bitmap,
        screenW: Int,
        screenH: Int,
        snapshot: ModuleSettingsSnapshot,
        pageArgb: Int,
    ): Bitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888).also { target ->
        val canvas = android.graphics.Canvas(target)
        val imageAlpha = PROFILE_BACKGROUND_HOUMO_IMAGE_ALPHA
        canvas.drawColor(pageArgb or 0xFF000000.toInt())
        val imageRect = destinationRect(
            bitmap = bitmap,
            screenW = screenW,
            screenH = screenH,
            displayMode = snapshot.profileBackgroundDisplayMode,
            cropPosition = snapshot.profileBackgroundCropPosition,
        )
        val blurredBase = profileBackgroundBlurredBaseBitmap(
            bitmap = bitmap,
            screenW = screenW,
            screenH = screenH,
            pageArgb = pageArgb,
            destination = imageRect,
            imageAlpha = imageAlpha,
            blurRadius = PROFILE_BACKGROUND_HOUMO_BLUR_RADIUS,
        )
        canvas.drawBitmap(
            blurredBase,
            null,
            android.graphics.RectF(0f, 0f, screenW.toFloat(), screenH.toFloat()),
            null,
        )
        val activity = activityProvider()
        val headerHeight = if (activity != null) {
            profileBackgroundHeaderHeightPx(activity, screenH)
        } else {
            screenH * 0.32f
        }
        val clearFadeEnd = drawProfileBackgroundClearImage(
            canvas = canvas,
            bitmap = bitmap,
            destination = imageRect,
            screenW = screenW,
            screenH = screenH,
            headerHeight = headerHeight,
            imageAlpha = imageAlpha,
        )
        drawProfileBackgroundTopScrim(canvas, screenW, headerHeight)
        drawProfileBackgroundSurfaceVeil(
            canvas = canvas,
            screenW = screenW,
            screenH = screenH,
            imageBottomY = imageRect.bottom.coerceIn(1f, screenH.toFloat()),
            fogArgb = profileBackgroundFogArgb(
                bitmap = bitmap,
                pageArgb = pageArgb,
                destination = imageRect,
                sampleScreenY = minOf(clearFadeEnd, imageRect.bottom - 1f),
            ),
            pageArgb = pageArgb,
        )
    }

    private fun profileBackgroundBlurredBaseBitmap(
        bitmap: Bitmap,
        screenW: Int,
        screenH: Int,
        pageArgb: Int,
        destination: android.graphics.RectF,
        imageAlpha: Int,
        blurRadius: Int,
    ): Bitmap {
        val base = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888).also { target ->
            val canvas = android.graphics.Canvas(target)
            canvas.drawColor(pageArgb or 0xFF000000.toInt())
            val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
            ).apply {
                alpha = imageAlpha.coerceIn(0, 255)
            }
            canvas.drawBitmap(bitmap, null, destination, paint)
        }
        if (blurRadius <= 0) return base
        val downscale = PROFILE_BACKGROUND_BLUR_DOWNSCALE
        val smallW = (screenW * downscale).toInt().coerceAtLeast(1)
        val smallH = (screenH * downscale).toInt().coerceAtLeast(1)
        return boxBlur(
            Bitmap.createScaledBitmap(base, smallW, smallH, true),
            radius = blurRadius,
        )
    }

    private fun profileBackgroundHeaderHeightPx(activity: Activity, screenH: Int): Float {
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        return (PROFILE_BACKGROUND_HEADER_HEIGHT_DP * density)
            .coerceIn(screenH * 0.24f, screenH * 0.40f)
    }

    private fun drawProfileBackgroundClearImage(
        canvas: android.graphics.Canvas,
        bitmap: Bitmap,
        destination: android.graphics.RectF,
        screenW: Int,
        screenH: Int,
        headerHeight: Float,
        imageAlpha: Int,
    ): Float {
        val fadeStart = (headerHeight * PROFILE_BACKGROUND_HOUMO_CLEAR_FADE_START_RATIO)
            .coerceIn(1f, screenH.toFloat())
        val fadeEnd = (headerHeight * PROFILE_BACKGROUND_HOUMO_CLEAR_FADE_END_RATIO)
            .coerceIn(fadeStart + 1f, screenH.toFloat())
        val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
        ).apply {
            alpha = imageAlpha.coerceIn(0, 255)
        }
        val solidSave = canvas.save()
        canvas.clipRect(0f, 0f, screenW.toFloat(), fadeStart)
        canvas.drawBitmap(bitmap, null, destination, paint)
        canvas.restoreToCount(solidSave)

        val layerBounds = android.graphics.RectF(0f, fadeStart, screenW.toFloat(), fadeEnd)
        val layerSave = canvas.saveLayer(layerBounds, null)
        canvas.drawBitmap(bitmap, null, destination, paint)
        val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f,
                fadeStart,
                0f,
                fadeEnd,
                intArrayOf(0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(layerBounds, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(layerSave)
        return fadeEnd
    }

    private fun drawProfileBackgroundTopScrim(
        canvas: android.graphics.Canvas,
        screenW: Int,
        headerHeight: Float,
    ) {
        val endY = (headerHeight * PROFILE_BACKGROUND_TOP_SCRIM_END_RATIO).coerceAtLeast(1f)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                endY,
                intArrayOf(0x12000000, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, screenW.toFloat(), endY, paint)
    }

    private fun drawProfileBackgroundSurfaceVeil(
        canvas: android.graphics.Canvas,
        screenW: Int,
        screenH: Int,
        imageBottomY: Float,
        fogArgb: Int,
        pageArgb: Int,
    ) {
        val startY = (imageBottomY - screenH * PROFILE_BACKGROUND_HOUMO_VEIL_LEAD_RATIO)
            .coerceIn(0f, screenH.toFloat())
        val settleY = (imageBottomY + screenH * PROFILE_BACKGROUND_HOUMO_VEIL_SETTLE_RATIO)
            .coerceIn(startY + 1f, screenH.toFloat())
        val imageStop = ((imageBottomY - startY) / (screenH - startY).coerceAtLeast(1f))
            .coerceIn(0.08f, 0.68f)
        val settleStop = ((settleY - startY) / (screenH - startY).coerceAtLeast(1f))
            .coerceIn(imageStop + 0.04f, 0.92f)
        val dark = isDarkArgb(pageArgb)
        val edgeAlpha = if (dark) 0xC8 else 0xE8
        val settleAlpha = if (dark) 0xF0 else 0xFA
        val bottomAlpha = if (dark) 0xF8 else 0xFF
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f,
                startY,
                0f,
                screenH.toFloat(),
                intArrayOf(
                    argbWithAlpha(fogArgb, 0x00),
                    argbWithAlpha(blendArgb(pageArgb, fogArgb, 0.46f), edgeAlpha),
                    argbWithAlpha(pageArgb, settleAlpha),
                    argbWithAlpha(pageArgb, bottomAlpha),
                ),
                floatArrayOf(0f, imageStop, settleStop, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, startY, screenW.toFloat(), screenH.toFloat(), paint)
    }

    private fun profileBackgroundFogArgb(
        bitmap: Bitmap,
        pageArgb: Int,
        destination: android.graphics.RectF,
        sampleScreenY: Float,
    ): Int {
        val sample = displayedImageAverageColor(bitmap, destination, sampleScreenY)
            ?: bottomEdgeAverageColor(bitmap)
        val page = pageArgb or 0xFF000000.toInt()
        val sampleWeight = if (isDarkArgb(page)) 0.50f else 0.74f
        return blendArgb(page, sample, sampleWeight) or 0xFF000000.toInt()
    }

    private fun displayedImageAverageColor(
        bitmap: Bitmap,
        destination: android.graphics.RectF,
        sampleScreenY: Float,
    ): Int? {
        val dstW = destination.width().coerceAtLeast(1f)
        val dstH = destination.height().coerceAtLeast(1f)
        val sampleYFraction = ((sampleScreenY - destination.top) / dstH).coerceIn(0f, 1f)
        val centerY = (sampleYFraction * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
        val radiusY = (bitmap.height * PROFILE_BACKGROUND_HOUMO_SAMPLE_BAND_RATIO)
            .toInt()
            .coerceAtLeast(1)
        val startY = (centerY - radiusY).coerceAtLeast(0)
        val endY = (centerY + radiusY).coerceAtMost(bitmap.height - 1)
        val startX = 0
        val endX = bitmap.width - 1
        val stepX = ((endX - startX) / PROFILE_BACKGROUND_HOUMO_SAMPLE_COLUMNS).coerceAtLeast(1)
        val stepY = ((endY - startY) / PROFILE_BACKGROUND_HOUMO_SAMPLE_ROWS).coerceAtLeast(1)
        var r = 0
        var g = 0
        var b = 0
        var count = 0
        var y = startY
        while (y <= endY) {
            var x = startX
            while (x <= endX) {
                val p = bitmap.getPixel(x, y)
                r += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return null
        return (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
    }

    private fun blendArgb(baseArgb: Int, overlayArgb: Int, overlayWeight: Float): Int {
        val weight = overlayWeight.coerceIn(0f, 1f)
        val inverse = 1f - weight
        fun channel(shift: Int): Int {
            val base = (baseArgb ushr shift) and 0xff
            val overlay = (overlayArgb ushr shift) and 0xff
            return ((base * inverse) + (overlay * weight)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    private fun destinationRect(
        bitmap: Bitmap,
        screenW: Int,
        screenH: Int,
        displayMode: String,
        cropPosition: String,
    ): android.graphics.RectF {
        val bitmapW = bitmap.width.toFloat().coerceAtLeast(1f)
        val bitmapH = bitmap.height.toFloat().coerceAtLeast(1f)
        val cropFraction = when (cropPosition) {
            ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM -> 1f
            ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER -> 0.5f
            else -> 0f
        }
        val scale = when (displayMode) {
            ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_WIDTH -> screenW.toFloat() / bitmapW
            ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT -> screenH.toFloat() / bitmapH
            else -> maxOf(screenW.toFloat() / bitmapW, screenH.toFloat() / bitmapH)
        }
        val scaledW = bitmapW * scale
        val scaledH = bitmapH * scale
        val left = (screenW - scaledW) / 2f
        val top = when (displayMode) {
            ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT -> 0f
            else -> {
                val overflowY = (scaledH - screenH).coerceAtLeast(0f)
                -overflowY * cropFraction
            }
        }
        return android.graphics.RectF(left, top, left + scaledW, top + scaledH)
    }

    private fun isDarkArgb(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xff
        val g = (argb ushr 8) and 0xff
        val b = argb and 0xff
        val luminance = (0.299f * r) + (0.587f * g) + (0.114f * b)
        return luminance < 128f
    }

    private fun hostBackgroundArgb(): Int? = runCatching {
        val scheme = cachedColorScheme ?: return@runCatching null
        host.toArgb(host.call("backgroundAuto", scheme) as Long)
    }.getOrNull()

    private fun profileBackgroundPageFallbackArgb(): Int {

        val nightMode = android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            PROFILE_BACKGROUND_DARK_PAGE_ARGB_FALLBACK
        } else {
            PROFILE_BACKGROUND_LIGHT_PAGE_ARGB_FALLBACK
        }
    }

    private fun colorLongToArgb(colorLong: Long): Int = host.toArgb(colorLong)

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
        repeat(3) {
            boxBlurHorizontal(pixels, temp, w, h, radius)
            boxBlurVertical(temp, pixels, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val window = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val x = i.coerceIn(0, w - 1)
                val p = src[row + x]
                a += (p ushr 24) and 0xff
                rr += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / window) shl 24) or ((rr / window) shl 16) or ((g / window) shl 8) or (b / window)
                val outX = (x + r + 1).coerceIn(0, w - 1)
                val inX = (x - r).coerceIn(0, w - 1)
                val pOut = src[row + outX]
                val pIn = src[row + inX]
                a += (pOut ushr 24) and 0xff
                rr += (pOut ushr 16) and 0xff
                g += (pOut ushr 8) and 0xff
                b += pOut and 0xff
                a -= (pIn ushr 24) and 0xff
                rr -= (pIn ushr 16) and 0xff
                g -= (pIn ushr 8) and 0xff
                b -= pIn and 0xff
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val window = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val y = i.coerceIn(0, h - 1)
                val p = src[y * w + x]
                a += (p ushr 24) and 0xff
                rr += (p ushr 16) and 0xff
                g += (p ushr 8) and 0xff
                b += p and 0xff
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / window) shl 24) or ((rr / window) shl 16) or ((g / window) shl 8) or (b / window)
                val outY = (y + r + 1).coerceIn(0, h - 1)
                val inY = (y - r).coerceIn(0, h - 1)
                val pOut = src[outY * w + x]
                val pIn = src[inY * w + x]
                a += (pOut ushr 24) and 0xff
                rr += (pOut ushr 16) and 0xff
                g += (pOut ushr 8) and 0xff
                b += pOut and 0xff
                a -= (pIn ushr 24) and 0xff
                rr -= (pIn ushr 16) and 0xff
                g -= (pIn ushr 8) and 0xff
                b -= pIn and 0xff
            }
        }
    }

    private fun colorLongFromArgb(argb: Int): Long = host.fromArgb(argb)

    private fun parseArgb(hex: String): Int {
        val normalized = hex.trim().removePrefix("#")
        return when (normalized.length) {
            8 -> normalized.toLong(16).toInt()
            6 -> (0xFF shl 24) or normalized.toInt(16)
            else -> BACKGROUND_ARGB_FALLBACK
        }
    }

    private fun argbWithAlpha(argb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)

    private fun transparentProfileContainerColor(color: Long, snapshot: ModuleSettingsSnapshot): Long =
        colorLongFromArgb(
            ProfileContainerOpacity.applyToArgb(
                colorLongToArgb(color),
                snapshot.profileBackgroundCardTransparency,
            ),
        )

    companion object {
        const val PROFILE_FRAME_EXTRA = "reamicro.profileBackgroundFrame"
        const val LOG_PREFIX = "ReaMicro LSP profile background"
        const val PROFILE_CARD_SCOPE_EXTRA = "reamicro.profileBackgroundCardScope"
        const val PROFILE_SURFACE_SCOPE_EXTRA = "reamicro.profileBackgroundSurfaceScope"

        const val BACKGROUND_ARGB_FALLBACK = 0x80000000.toInt()
        const val PROFILE_BACKGROUND_DARK_PAGE_ARGB_FALLBACK = 0xFF000000.toInt()
        const val PROFILE_BACKGROUND_LIGHT_PAGE_ARGB_FALLBACK = -1
        const val PROFILE_BACKGROUND_HEADER_HEIGHT_DP = 253f
        const val PROFILE_BACKGROUND_TOP_SCRIM_END_RATIO = 0.34f
        const val PROFILE_BACKGROUND_BANNER_FADE_WIDTH_RATIO = 0.78f
        const val PROFILE_BACKGROUND_BANNER_FADE_MIN_DP = 220f
        const val PROFILE_BACKGROUND_BANNER_FADE_MAX_DP = 360f
        const val PROFILE_BACKGROUND_BLUR_DOWNSCALE = 0.25f
        const val PROFILE_BACKGROUND_HOUMO_SURFACE_ARGB = 0x00FFFFFF
        const val PROFILE_BACKGROUND_HOUMO_IMAGE_ALPHA = 248
        const val PROFILE_BACKGROUND_HOUMO_BLUR_RADIUS = 24
        const val PROFILE_BACKGROUND_HOUMO_CLEAR_FADE_START_RATIO = 0.34f
        const val PROFILE_BACKGROUND_HOUMO_CLEAR_FADE_END_RATIO = 1.46f
        const val PROFILE_BACKGROUND_HOUMO_VEIL_LEAD_RATIO = 0.24f
        const val PROFILE_BACKGROUND_HOUMO_VEIL_SETTLE_RATIO = 0.28f
        const val PROFILE_BACKGROUND_HOUMO_SAMPLE_BAND_RATIO = 0.035f
        const val PROFILE_BACKGROUND_HOUMO_SAMPLE_COLUMNS = 28
        const val PROFILE_BACKGROUND_HOUMO_SAMPLE_ROWS = 6

        const val PROFILE_BACKGROUND_IMAGE_URL_MAX_LENGTH = 2048
        const val RANDOM_IMAGE_CONNECT_TIMEOUT_MS = 12_000
        const val RANDOM_IMAGE_READ_TIMEOUT_MS = 25_000
        const val MAX_RANDOM_IMAGE_BYTES = 24 * 1024 * 1024
        const val RANDOM_IMAGE_FILE_PREFIX = "profile_random_"
        const val RANDOM_IMAGE_USER_AGENT = "ReaMicro-Extend/profile-background"

        @Volatile private var activeInstance: ProfileBackgroundHook? = null

        fun refreshRandomBackgroundNow(activity: Activity?, force: Boolean = false) {
            activeInstance?.refreshRandomImageFor(activity, force)
        }
    }
}
