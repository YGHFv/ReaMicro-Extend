package com.reamicro.fix.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Shader
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Stage 4: paints a configurable solid color OR image over the
 * "avatar top banner" region of the Profile screen (个人中心头像上方色块)
 * without touching the host's toolbar icons, avatars, or click behaviour.
 *
 * Mechanism: the host's ProfileScreen.lambda$0$1 ("ProfileScreen.kt:48"
 * anonymous content lambda) sets a thread-local flag while it executes.
 *
 * Color mode: ColorScheme.getSurfaceContainerHigh is intercepted while the
 * flag is set so the host's `Modifier.background(surfaceContainerHigh)` call
 * at ProfileScreen.kt:51 paints our configured color.
 *
 * Image mode: BackgroundKt.background-bw27NRU$default (the colour-background
 * factory invoked at ProfileScreen.kt:51) is intercepted while the flag is
 * set; the original Modifier argument is re-routed through Brush-based
 * background$default with a BitmapShader brush built from the user's
 * configured image file. The result is patched in afterHookedMethod so the
 * host's downstream Modifier chain receives a brush-painted Modifier instead
 * of the colour-painted one.
 *
 * All other UI elements (gear/back icons in the toolbar, avatars, lists) keep
 * their own colour sources and are untouched.
 */
class ProfileBackgroundHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
    private val settingsProvider: () -> ModuleSettingsSnapshot = settings::snapshot,
) {
    private val inProfileScreen = ThreadLocal<Int>()
    private val inProfileLambda = ThreadLocal<Boolean>()
    private val resolvingHostBackground = ThreadLocal<Boolean>()
    private val backgroundCallCount = ThreadLocal<Int>()
    private val fillMaxSizeCallCount = ThreadLocal<Int>()
    private val methodCache = HashMap<String, Method>()
    private var cachedBitmap: Bitmap? = null
    private var cachedImagePath: String? = null
    private var cachedHeaderBitmap: Bitmap? = null
    private var cachedHeaderBitmapKey: String? = null
    private var cachedScreenBackgroundBitmap: Bitmap? = null
    private var cachedScreenBackgroundBitmapKey: String? = null
    @Volatile private var cachedColorScheme: Any? = null
    @Volatile private var autoRandomRefreshAttempted: Boolean = false
    @Volatile private var randomRefreshRunning: Boolean = false

    fun install() {
        activeInstance = this
        installProfileScreenHook()
        installProfileScreenLambdaHook()
        installThemeBackgroundHooks()
        installColorHook()
        installProfileSurfaceColorHooks()
        installFillMaxSizeHook()
        installBackgroundFactoryHook()
        installProfileDividerHook()
    }

    private fun installProfileScreenHook() {
        runCatching {
            val target = findProfileScreenMethod() ?: run {
                XposedBridge.log("$LOG_PREFIX ProfileScreen not found")
                return
            }
            XposedBridge.hookMethod(
                target,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val snapshot = settingsProvider()
                        if (!canUseProfileImageBackground(snapshot)) return
                        cacheColorSchemeFromArgs(param.args)
                        inProfileScreen.set((inProfileScreen.get() ?: 0) + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val depth = inProfileScreen.get() ?: return
                        if (depth <= 1) {
                            inProfileScreen.remove()
                        } else {
                            inProfileScreen.set(depth - 1)
                        }
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX ProfileScreen hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook ProfileScreen: ${it.stackTraceToString()}")
        }
    }

    private fun installProfileDividerHook() {
        runCatching {
            val dividerClass = XposedHelpers.findClass(DIVIDER_KT_CLASS, classLoader)
            val target = dividerClass.declaredMethods.firstOrNull { m ->
                m.name == SIMPLE_DIVIDER_METHOD && m.parameterTypes.size == 5
            } ?: run {
                XposedBridge.log("$LOG_PREFIX SimpleDivider not found")
                return
            }
            XposedBridge.hookMethod(
                target,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inProfileLambda.get() != true) return
                        if (!settingsProvider().canShowProfileBackground) return
                        // Suppress the divider draw -- it is the white horizontal
                        // rule that the host paints across the screen between the
                        // avatar-top region and the lower content (ProfileScreen.kt:119).
                        param.result = null
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX SimpleDivider hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook SimpleDivider: ${it.stackTraceToString()}")
        }
    }

    private fun installProfileScreenLambdaHook() {
        runCatching {
            val target = findProfileScreenLambdaMethod() ?: run {
                XposedBridge.log("$LOG_PREFIX ProfileScreen\$lambda\$0\$1 not found")
                return
            }
            XposedBridge.hookMethod(
                target,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!canHandleProfileBackground(settingsProvider())) return
                        cacheColorSchemeFromArgs(param.args)
                        inProfileLambda.set(true)
                        backgroundCallCount.set(0)
                        fillMaxSizeCallCount.set(0)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        inProfileLambda.set(false)
                        backgroundCallCount.remove()
                        fillMaxSizeCallCount.remove()
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX ProfileScreen lambda hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook ProfileScreen lambda: ${it.stackTraceToString()}")
        }
    }

    private fun installThemeBackgroundHooks() {
        runCatching {
            val themeKtClass = XposedHelpers.findClass(THEME_KT_CLASS, classLoader)
            var installed = 0
            PROFILE_BACKGROUND_THEME_METHODS.forEach { methodName ->
                val target = themeKtClass.declaredMethods.firstOrNull { method ->
                    method.name == methodName && method.parameterTypes.size == 1
                } ?: return@forEach
                XposedBridge.hookMethod(
                    target,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (resolvingHostBackground.get() == true) return
                            val snapshot = settingsProvider()
                            if (!canUseProfileImageBackground(snapshot)) return
                            val inProfile = (inProfileScreen.get() ?: 0) > 0 || inProfileLambda.get() == true
                            if (!inProfile) return
                            param.args?.firstOrNull()?.let { cachedColorScheme = it }
                            param.result = colorLongFromArgb(PROFILE_BACKGROUND_HOUMO_SURFACE_ARGB)
                        }
                    },
                )
                installed++
            }
            XposedBridge.log("$LOG_PREFIX ThemeKt transparent background hooks installed: $installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook ThemeKt backgrounds: ${it.stackTraceToString()}")
        }
    }

    private fun installColorHook() {
        runCatching {
            val colorSchemeClass = XposedHelpers.findClass(COLOR_SCHEME_CLASS, classLoader)
            val target = colorSchemeClass.declaredMethods.firstOrNull { method ->
                method.name == SURFACE_CONTAINER_HIGH_METHOD && method.parameterTypes.isEmpty()
            } ?: run {
                XposedBridge.log("$LOG_PREFIX $SURFACE_CONTAINER_HIGH_METHOD not found")
                return
            }
            XposedBridge.hookMethod(
                target,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inProfileLambda.get() != true) return
                        if (resolvingHostBackground.get() == true) return
                        // 始终缓存 ColorScheme,不管图片模式还是颜色模式。
                        // 图片模式下 fillMaxSize hook 需要用它读 getBackgroundAuto。
                        cachedColorScheme = param.thisObject
                        val snapshot = settingsProvider()
                        if (!snapshot.canShowProfileBackground) return
                        if (snapshot.profileBackgroundUseImage) return
                        val argb = parseArgb(snapshot.profileBackgroundColor)
                        param.result = colorLongFromArgb(argb)
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX ColorScheme.$SURFACE_CONTAINER_HIGH_METHOD hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook ColorScheme: ${it.stackTraceToString()}")
        }
    }

    private fun installProfileSurfaceColorHooks() {
        runCatching {
            val colorSchemeClass = XposedHelpers.findClass(COLOR_SCHEME_CLASS, classLoader)
            var installed = 0
            PROFILE_BACKGROUND_SURFACE_COLOR_METHODS.forEach { methodName ->
                val target = colorSchemeClass.declaredMethods.firstOrNull { method ->
                    method.name == methodName && method.parameterTypes.isEmpty()
                } ?: return@forEach
                XposedBridge.hookMethod(
                    target,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (inProfileLambda.get() != true) return
                            if (resolvingHostBackground.get() == true) return
                            cachedColorScheme = param.thisObject
                            val snapshot = settingsProvider()
                            if (!snapshot.canShowProfileBackground) return
                            if (!snapshot.profileBackgroundUseImage) return
                            param.result = colorLongFromArgb(PROFILE_BACKGROUND_HOUMO_SURFACE_ARGB)
                        }
                    },
                )
                installed++
            }
            XposedBridge.log("$LOG_PREFIX ColorScheme transparent surface hooks installed: $installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook transparent surface colors: ${it.stackTraceToString()}")
        }
    }

    private fun installBackgroundFactoryHook() {
        runCatching {
            val backgroundKtClass = XposedHelpers.findClass(BACKGROUND_KT_CLASS, classLoader)
            val target = backgroundKtClass.declaredMethods.firstOrNull { method ->
                method.name == COLOR_BACKGROUND_DEFAULT_METHOD && method.parameterTypes.size == 5
            } ?: run {
                XposedBridge.log("$LOG_PREFIX $COLOR_BACKGROUND_DEFAULT_METHOD not found")
                return
            }
            XposedBridge.hookMethod(
                target,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inProfileLambda.get() != true) return
                        val snapshot = settingsProvider()
                        if (!snapshot.canShowProfileBackground) return
                        if (!snapshot.profileBackgroundUseImage) return
                        if (snapshot.profileBackgroundImage.isBlank()) return
                        // 第 2 次及以后是宿主的胶囊按钮和下方卡片。
                        // 直接给主题适配的半透明表面，避免浅色模式被错误压成黑色。
                        val count = (backgroundCallCount.get() ?: 0) + 1
                        if (count < 2) return
                        val colorArg = param.args?.getOrNull(1)
                        if (colorArg is java.lang.Long) {
                            param.args[1] = java.lang.Long.valueOf(
                                colorLongFromArgb(PROFILE_BACKGROUND_HOUMO_SURFACE_ARGB),
                            )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inProfileLambda.get() != true) return
                        val snapshot = settingsProvider()
                        if (!snapshot.canShowProfileBackground) return
                        if (!snapshot.profileBackgroundUseImage) return
                        val path = snapshot.profileBackgroundImage
                        if (path.isBlank()) return
                        val count = (backgroundCallCount.get() ?: 0) + 1
                        backgroundCallCount.set(count)
                        val modifier = param.args?.getOrNull(0) ?: return
                        if (count == 1) {
                            // 第 1 次(顶部 banner):跳过色块,让根布局模糊背景透出
                            param.result = modifier
                            return
                        }
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX BackgroundKt.$COLOR_BACKGROUND_DEFAULT_METHOD hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BackgroundKt: ${it.stackTraceToString()}")
        }
    }

    private fun findProfileScreenLambdaMethod(): Method? {
        val profileScreenClass = XposedHelpers.findClass(PROFILE_SCREEN_CLASS, classLoader)
        return profileScreenClass.declaredMethods.firstOrNull { method ->
            method.name == "ProfileScreen\$lambda\$0\$1"
        }
    }

    private fun findProfileScreenMethod(): Method? {
        val profileScreenClass = XposedHelpers.findClass(PROFILE_SCREEN_CLASS, classLoader)
        return profileScreenClass.declaredMethods.firstOrNull { method ->
            method.name == "ProfileScreen" && method.parameterTypes.size == 3
        }
    }

    private fun cacheColorSchemeFromArgs(args: Array<Any?>?) {
        val composerClass = runCatching { XposedHelpers.findClass(COMPOSER_CLASS, classLoader) }.getOrNull() ?: return
        args
            ?.firstOrNull { arg -> arg != null && composerClass.isInstance(arg) }
            ?.let { composer ->
                materialColorScheme(composer)?.let { scheme ->
                    cachedColorScheme = scheme
                }
            }
    }

    private fun installFillMaxSizeHook() {
        runCatching {
            val sizeKtClass = XposedHelpers.findClass(SIZE_KT_CLASS, classLoader)
            val target = sizeKtClass.declaredMethods.firstOrNull { method ->
                method.name == FILL_MAX_SIZE_DEFAULT_METHOD && method.parameterTypes.size == 4
            } ?: run {
                XposedBridge.log("$LOG_PREFIX $FILL_MAX_SIZE_DEFAULT_METHOD not found")
                return
            }
            XposedBridge.hookMethod(
                target,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inProfileLambda.get() != true) return
                        val snapshot = settingsProvider()
                        val count = (fillMaxSizeCallCount.get() ?: 0) + 1
                        fillMaxSizeCallCount.set(count)
                        if (count != 1) return
                        var patchedModifier = param.result ?: return
                        if (!snapshot.canShowProfileBackground) return
                        if (!snapshot.profileBackgroundUseImage) return
                        val path = snapshot.profileBackgroundImage
                        if (path.isBlank()) return
                        val bitmap = loadBitmap(path) ?: return
                        val imageBrush = profileBackgroundImageBrush(path, bitmap, snapshot) ?: return
                        patchedModifier = invokeBrushBackground(patchedModifier, imageBrush, null) ?: return
                        param.result = patchedModifier
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX SizeKt.$FILL_MAX_SIZE_DEFAULT_METHOD hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook fillMaxSize: ${it.stackTraceToString()}")
        }
    }

    fun refreshRandomImageFor(activity: Activity?, force: Boolean = false) {
        activity ?: return
        val snapshot = settings.snapshot()
        if (!canHandleProfileBackground(snapshot)) return
        val url = profileBackgroundImageUrlOrNull(snapshot.profileBackgroundImageUrl) ?: return
        if (!force && autoRandomRefreshAttempted) return
        if (!force) autoRandomRefreshAttempted = true
        if (randomRefreshRunning) return
        randomRefreshRunning = true
        Thread {
            runCatching {
                val target = downloadRandomProfileBackground(activity, url)
                settings.setProfileBackgroundImage(target.absolutePath)
                settings.setProfileBackgroundUseImage(true)
                settings.setProfileBackgroundEnabled(true)
                val updatedBackground = createActiveProfileBackgroundBitmap(target.absolutePath, settings.snapshot())
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        if (updatedBackground != null) {
                            replaceActiveProfileBackgroundBitmap(updatedBackground)
                        } else {
                            invalidateImageCaches()
                        }
                        activity.window?.decorView?.postInvalidateOnAnimation()
                    }
                }
                XposedBridge.log("$LOG_PREFIX random image refreshed: ${target.name}")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX random image refresh failed: ${it.stackTraceToString()}")
            }
            randomRefreshRunning = false
        }.apply { name = "ReaMicro-ProfileRandomImage" }.start()
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
            val targetDir = File(activity.filesDir, "profile_background").apply { mkdirs() }
            targetDir.listFiles()
                ?.filter { it.name.startsWith(RANDOM_IMAGE_FILE_PREFIX) }
                ?.forEach { it.delete() }
            val extension = imageExtension(connection.contentType, url)
            val target = File(targetDir, "$RANDOM_IMAGE_FILE_PREFIX${System.currentTimeMillis()}.$extension")
            target.writeBytes(bytes)
            return target
        } finally {
            connection.disconnect()
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

    private fun invalidateImageCaches() {
        cachedBitmap = null
        cachedImagePath = null
        cachedHeaderBitmap = null
        cachedHeaderBitmapKey = null
        cachedScreenBackgroundBitmap = null
        cachedScreenBackgroundBitmapKey = null
    }

    private fun bottomEdgeAverageColor(bitmap: Bitmap): Int {
        // Average the bottom ~5% rows (after downscaling) so a single row of
        // oddly-coloured pixels doesn't dominate the slab colour.
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

    private fun verticalGradientBottomEdgeBrush(bitmap: Bitmap, bottomArgb: Int): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val brushCompanionClass = XposedHelpers.findClass(BRUSH_COMPANION_CLASS, classLoader)
            .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val companionClass = XposedHelpers.findClass("$BRUSH_COMPANION_CLASS\$Companion", classLoader)
        val gradientMethod = companionClass.declaredMethods.firstOrNull { m ->
            m.name == VERTICAL_GRADIENT_DEFAULT_METHOD && m.parameterTypes.size == 7
        }?.apply { isAccessible = true } ?: run {
            XposedBridge.log("$LOG_PREFIX $VERTICAL_GRADIENT_DEFAULT_METHOD not found")
            return@runCatching null
        }
        val colorClass = XposedHelpers.findClass(COLOR_CLASS, classLoader)
        val boxMethod = colorClass.getDeclaredMethod(COLOR_BOX_METHOD, java.lang.Long.TYPE).apply { isAccessible = true }
        // The avatar-top banner (ProfileScreen.kt:51) renders the user image
        // at fit-to-width from y=0 down to y=imageBottomY. Above that line the
        // banner itself is what the user sees; below it we want a clean fade
        // from the image's bottom-edge colour into the host's page background.
        // So the gradient does NOT start at y=0 -- it starts at imageBottomY.
        // Above imageBottomY the gradient contributes nothing (the banner
        // paints there). Below imageBottomY we fade from opaque image-bottom
        // colour at the top of the band to fully transparent (same RGB) at the
        // page bottom, letting the host's own page background show through.
        val opaque = boxMethod.invoke(null, colorLongFromArgb(bottomArgb or 0xFF000000.toInt()))
        val transparent = boxMethod.invoke(null, colorLongFromArgb(bottomArgb and 0x00FFFFFF.toInt()))
        val colors = java.util.ArrayList<Any>(2).apply {
            add(opaque)
            add(transparent)
        }
        // Fit-to-width scaling matches banner hook: scale = screenWidth / bitmapW,
        // so scaledImageHeight = scale * bitmapH.
        val screenPx = activity.resources.displayMetrics.widthPixels
        val screenHeight = activity.resources.displayMetrics.heightPixels.toFloat()
        val imageBottomY = (screenPx.toFloat() / bitmap.width.coerceAtLeast(1)) * bitmap.height
        val startY = imageBottomY.coerceIn(0f, screenHeight)
        gradientMethod.invoke(
            null,
            brushCompanionClass,
            colors,
            startY,
            screenHeight,
            0,
            0,
            null,
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX verticalGradientBottomEdgeBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun loadBitmap(path: String): Bitmap? {
        if (cachedImagePath == path && cachedBitmap != null) return cachedBitmap
        val file = File(path)
        if (!file.isFile) {
            XposedBridge.log("$LOG_PREFIX image file not found: $path")
            return null
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(path)
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX decode image failed: ${it.stackTraceToString()}")
            return null
        }
        cachedBitmap = bitmap
        cachedImagePath = path
        return bitmap
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
        val brushKtClass = XposedHelpers.findClass(BRUSH_KT_CLASS, classLoader)
        val factory = brushKtClass.declaredMethods.firstOrNull { m ->
            m.name == SHADER_BRUSH_METHOD && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Shader::class.java
        }?.apply { isAccessible = true } ?: return@runCatching null
        factory.invoke(null, shader)
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX profileBackgroundImageBrush failed: ${it.stackTraceToString()}")
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
        val bitmap = loadBitmap(path) ?: return@runCatching null
        val pageArgb = hostBackgroundArgb() ?: profileBackgroundPageFallbackArgb()
        val key = profileBackgroundBitmapKey(path, active.width, active.height, snapshot, pageArgb)
        createProfileBackgroundBitmap(bitmap, active.width, active.height, snapshot, pageArgb) to key
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX create active profile background failed: ${it.stackTraceToString()}")
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
        val endY = (headerHeight * 0.58f).coerceAtLeast(1f)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                endY,
                intArrayOf(0x26000000, 0x00000000),
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

    private fun bitmapShaderBrush(bitmap: Bitmap): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val matrix = android.graphics.Matrix()
        val screenW = activity.resources.displayMetrics.widthPixels
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        // Fit-to-width: scale so the image's width matches the screen width.
        // Vertical direction uses the same scale, so portrait images extend
        // downward (potentially beyond the screen), landscape images only
        // occupy the top band of the page. Width is always preserved.
        val scale = screenW / bw
        matrix.setScale(scale, scale)
        // DECAL on API 31+ so pixels beyond the image rectangle render as
        // fully transparent -- crucial: this prevents the CLAMP edge-stretch
        // band from leaking through the layers above/below the image's
        // natural height, which was the visible "stretched" area the user
        // reported. Below API 31 we fall back to CLAMP (the visibility is
        // best-effort; modern devices are API 31+).
        val tileMode = if (android.os.Build.VERSION.SDK_INT >= 31) {
            Shader.TileMode.DECAL
        } else {
            Shader.TileMode.CLAMP
        }
        val shader = BitmapShader(bitmap, tileMode, tileMode).apply {
            setLocalMatrix(matrix)
        }
        val brushKtClass = XposedHelpers.findClass(BRUSH_KT_CLASS, classLoader)
        val factory = brushKtClass.declaredMethods.firstOrNull { m ->
            m.name == SHADER_BRUSH_METHOD && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Shader::class.java
        }?.apply { isAccessible = true } ?: return@runCatching null
        factory.invoke(null, shader)
    }.getOrNull()

    private fun headerBitmapShaderBrush(path: String, bitmap: Bitmap): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val screenW = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val cropPosition = settingsProvider().profileBackgroundCropPosition
        val key = "$path|$screenW|$screenH|$cropPosition"
        val headerBitmap = if (cachedHeaderBitmapKey == key && cachedHeaderBitmap != null) {
            cachedHeaderBitmap!!
        } else {
            Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = android.graphics.Canvas(target)
                val paint = android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
                )
                val scale = maxOf(
                    screenW.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f),
                    screenH.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f),
                )
                val scaledW = bitmap.width * scale
                val scaledH = bitmap.height * scale
                val left = (screenW - scaledW) / 2f
                val overflowY = (scaledH - screenH).coerceAtLeast(0f)
                val cropFraction = when (cropPosition) {
                    ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM -> 1f
                    ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER -> 0.5f
                    else -> 0f
                }
                val top = -overflowY * cropFraction
                canvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(left, top, left + scaledW, top + scaledH),
                    paint,
                )
                cachedHeaderBitmap = target
                cachedHeaderBitmapKey = key
            }
        }
        val tileMode = if (android.os.Build.VERSION.SDK_INT >= 31) {
            Shader.TileMode.DECAL
        } else {
            Shader.TileMode.CLAMP
        }
        val shader = BitmapShader(headerBitmap, tileMode, tileMode)
        val brushKtClass = XposedHelpers.findClass(BRUSH_KT_CLASS, classLoader)
        val factory = brushKtClass.declaredMethods.firstOrNull { m ->
            m.name == SHADER_BRUSH_METHOD && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Shader::class.java
        }?.apply { isAccessible = true } ?: return@runCatching null
        factory.invoke(null, shader)
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX headerBitmapShaderBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun verticalGradientFadeToWhiteBrush(bitmap: Bitmap): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val brushCompanionClass = XposedHelpers.findClass(BRUSH_COMPANION_CLASS, classLoader)
            .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val companionClass = XposedHelpers.findClass("$BRUSH_COMPANION_CLASS\$Companion", classLoader)
        val gradientMethod = companionClass.declaredMethods.firstOrNull { m ->
            m.name == VERTICAL_GRADIENT_DEFAULT_METHOD && m.parameterTypes.size == 7
        }?.apply { isAccessible = true } ?: run {
            XposedBridge.log("$LOG_PREFIX $VERTICAL_GRADIENT_DEFAULT_METHOD not found")
            return@runCatching null
        }
        val colorClass = XposedHelpers.findClass(COLOR_CLASS, classLoader)
        val boxMethod = colorClass.getDeclaredMethod(COLOR_BOX_METHOD, java.lang.Long.TYPE).apply { isAccessible = true }
        // Two stops: transparent at the image's bottom edge (so the upper
        // region shows the user image 1:1) fading to ~80% opaque white at the
        // page bottom so the lower content area is a soft wash and any CLAMP
        // stretch band below the image's natural height gets masked.
        val top = boxMethod.invoke(null, colorLongFromArgb(0x00FFFFFF))
        val bottom = boxMethod.invoke(null, colorLongFromArgb(0xCCFFFFFF.toInt()))
        val colors = java.util.ArrayList<Any>(2).apply {
            add(top)
            add(bottom)
        }
        // startY = where the image's natural height ends at fit-to-width scale
        // (so we don't waste gradient range above that -- the image is 100%
        // visible up to that line). endY = page bottom.
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenHeight = activity.resources.displayMetrics.heightPixels.toFloat()
        val imageBottomY = (screenW.toFloat() / bitmap.width.coerceAtLeast(1)) * bitmap.height
        val startY = imageBottomY.coerceIn(0f, screenHeight)
        gradientMethod.invoke(
            null,
            brushCompanionClass,
            colors,
            startY,
            screenHeight,
            0,
            0,
            null,
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX verticalGradientFadeToWhiteBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun verticalGradientWhiteFadeBrush(): Any? = runCatching {
        verticalGradientHostFadeBrush(0xFFFFFFFF.toInt())
    }.getOrNull()

    private fun hostBackgroundArgb(): Int? = runCatching {
        // Resolve the host's page background colour the same way the host does
        // at ProfileScreen.kt:87: ThemeKt.getBackgroundAuto(ColorScheme).
        // The ProfileScreen lambda hook eagerly caches MaterialTheme's
        // ColorScheme before fillMaxSize is patched, so first render and
        // return-from-settings render use the same target background.
        val scheme = cachedColorScheme ?: return@runCatching null
        val themeKtClass = XposedHelpers.findClass(THEME_KT_CLASS, classLoader)
        val method = themeKtClass.declaredMethods.firstOrNull { m ->
            m.name == THEME_GET_BACKGROUND_AUTO_METHOD && m.parameterTypes.size == 1
        }?.apply { isAccessible = true } ?: return@runCatching null
        resolvingHostBackground.set(true)
        val colorLong = try {
            method.invoke(null, scheme) as Long
        } finally {
            resolvingHostBackground.remove()
        }
        colorLongToArgb(colorLong)
    }.getOrNull()

    private fun materialColorScheme(composer: Any): Any? = runCatching {
        val materialThemeClass = XposedHelpers.findClass(MATERIAL_THEME_CLASS, classLoader)
        val materialTheme = staticObject(materialThemeClass, "INSTANCE")
        val stable = materialThemeClass.getDeclaredField("\$stable").apply { isAccessible = true }.getInt(null)
        materialThemeClass.declaredMethods.firstOrNull { method ->
            method.name == "getColorScheme" && method.parameterTypes.size == 2
        }?.apply { isAccessible = true }?.invoke(materialTheme, composer, stable)
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to cache MaterialTheme colorScheme: ${it.stackTraceToString()}")
        null
    }

    private fun profileBackgroundPageFallbackArgb(): Int {
        // 首次进入未缓存 ColorScheme 时的退回路径。
        // 用系统级 configuration 判断夜间模式。
        val nightMode = android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            PROFILE_BACKGROUND_DARK_PAGE_ARGB_FALLBACK
        } else {
            PROFILE_BACKGROUND_LIGHT_PAGE_ARGB_FALLBACK
        }
    }

    private fun colorLongToArgb(colorLong: Long): Int {
        // Compose stores sRGB Color(Int) in the high 32 bits. The low bits hold
        // colour-space metadata, so narrowing the Long directly corrupts ARGB.
        return (colorLong ushr 32).toInt()
    }

    private fun verticalGradientBannerOverlayBrush(backgroundArgb: Int): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val brushCompanionClass = XposedHelpers.findClass(BRUSH_COMPANION_CLASS, classLoader)
            .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val companionClass = XposedHelpers.findClass("$BRUSH_COMPANION_CLASS\$Companion", classLoader)
        val gradientMethod = companionClass.declaredMethods.firstOrNull { m ->
            m.name == VERTICAL_GRADIENT_DEFAULT_METHOD && m.parameterTypes.size == 7
        }?.apply { isAccessible = true } ?: run {
            XposedBridge.log("$LOG_PREFIX $VERTICAL_GRADIENT_DEFAULT_METHOD not found")
            return@runCatching null
        }
        val colorClass = XposedHelpers.findClass(COLOR_CLASS, classLoader)
        val boxMethod = colorClass.getDeclaredMethod(COLOR_BOX_METHOD, java.lang.Long.TYPE).apply { isAccessible = true }
        fun boxedColor(argb: Int): Any =
            boxMethod.invoke(null, colorLongFromArgb(argb)) ?: error("Color.box returned null")

        val colors = java.util.ArrayList<Any>(4).apply {
            add(boxedColor(0x66000000))
            add(boxedColor(0x20000000))
            add(boxedColor(argbWithAlpha(backgroundArgb, 0xCC)))
            add(boxedColor(argbWithAlpha(backgroundArgb, 0xFF)))
        }
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWidth = activity.resources.displayMetrics.widthPixels.toFloat()
        val fadeHeight = (screenWidth * PROFILE_BACKGROUND_BANNER_FADE_WIDTH_RATIO)
            .coerceIn(
                PROFILE_BACKGROUND_BANNER_FADE_MIN_DP * density,
                PROFILE_BACKGROUND_BANNER_FADE_MAX_DP * density,
            )
        gradientMethod.invoke(
            null,
            brushCompanionClass,
            colors,
            0f,
            fadeHeight,
            0,
            0,
            null,
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX verticalGradientBannerOverlayBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun verticalGradientHeaderFadeBrush(backgroundArgb: Int): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val brushCompanionClass = XposedHelpers.findClass(BRUSH_COMPANION_CLASS, classLoader)
            .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val companionClass = XposedHelpers.findClass("$BRUSH_COMPANION_CLASS\$Companion", classLoader)
        val gradientMethod = companionClass.declaredMethods.firstOrNull { m ->
            m.name == VERTICAL_GRADIENT_DEFAULT_METHOD && m.parameterTypes.size == 7
        }?.apply { isAccessible = true } ?: run {
            XposedBridge.log("$LOG_PREFIX $VERTICAL_GRADIENT_DEFAULT_METHOD not found")
            return@runCatching null
        }
        val colorClass = XposedHelpers.findClass(COLOR_CLASS, classLoader)
        val boxMethod = colorClass.getDeclaredMethod(COLOR_BOX_METHOD, java.lang.Long.TYPE).apply { isAccessible = true }
        fun boxedColor(argb: Int): Any =
            boxMethod.invoke(null, colorLongFromArgb(argb)) ?: error("Color.box returned null")

        val colors = java.util.ArrayList<Any>(4).apply {
            add(boxedColor(0x66000000))
            add(boxedColor(0x22000000))
            add(boxedColor(argbWithAlpha(backgroundArgb, 0x88)))
            add(boxedColor(argbWithAlpha(backgroundArgb, 0xFF)))
        }
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val fadeHeight = (PROFILE_BACKGROUND_HEADER_HEIGHT_DP * density).coerceAtLeast(1f)
        gradientMethod.invoke(
            null,
            brushCompanionClass,
            colors,
            0f,
            fadeHeight,
            0,
            0,
            null,
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX verticalGradientHeaderFadeBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun verticalGradientHostFadeBrush(backgroundArgb: Int): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val brushCompanionClass = XposedHelpers.findClass(BRUSH_COMPANION_CLASS, classLoader)
            .getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val companionClass = XposedHelpers.findClass("$BRUSH_COMPANION_CLASS\$Companion", classLoader)
        val gradientMethod = companionClass.declaredMethods.firstOrNull { m ->
            m.name == VERTICAL_GRADIENT_DEFAULT_METHOD && m.parameterTypes.size == 7
        }?.apply { isAccessible = true } ?: run {
            XposedBridge.log("$LOG_PREFIX $VERTICAL_GRADIENT_DEFAULT_METHOD not found")
            return@runCatching null
        }
        val colorClass = XposedHelpers.findClass(COLOR_CLASS, classLoader)
        val boxMethod = colorClass.getDeclaredMethod(COLOR_BOX_METHOD, java.lang.Long.TYPE).apply { isAccessible = true }
        // Top = fully transparent host background colour (lets the user image
        // show 1:1 in the avatar-top region). Bottom = opaque host background
        // colour (covers any clear-image leakage below the natural image
        // height and blends into the host page background colour naturally).
        // Because both ends share the host's `getBackgroundAuto` RGB, the
        // alpha ramp never bleeds into a different hue family -- the wash
        // stays tonally consistent with the host's own page background.
        val transparent = boxMethod.invoke(null, colorLongFromArgb(backgroundArgb and 0x00FFFFFF.toInt()))
        val opaque = boxMethod.invoke(null, colorLongFromArgb(backgroundArgb or 0xFF000000.toInt()))
        val colors = java.util.ArrayList<Any>(2).apply {
            add(transparent)
            add(opaque)
        }
        val screenHeight = activity.resources.displayMetrics.heightPixels.toFloat()
        gradientMethod.invoke(
            null,
            brushCompanionClass,
            colors,
            0f,
            screenHeight,
            0,
            0,
            null,
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX verticalGradientHostFadeBrush failed: ${it.stackTraceToString()}")
        null
    }

    private fun blurredBitmapShaderBrush(bitmap: Bitmap): Any? = runCatching {
        val activity = activityProvider() ?: return@runCatching null
        val screenW = activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val cropPosition = settingsProvider().profileBackgroundCropPosition
        // 先把原图按 centerCrop 裁到屏幕尺寸,再做模糊,这样模糊层和清晰层
        // 像素严格对齐,不会出现错位。
        val cropped = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888).also { target ->
            val canvas = android.graphics.Canvas(target)
            val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
            )
            val scale = maxOf(
                screenW.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f),
                screenH.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f),
            )
            val scaledW = bitmap.width * scale
            val scaledH = bitmap.height * scale
            val left = (screenW - scaledW) / 2f
            val overflowY = (scaledH - screenH).coerceAtLeast(0f)
            val cropFraction = when (cropPosition) {
                ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM -> 1f
                ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER -> 0.5f
                else -> 0f
            }
            val top = -overflowY * cropFraction
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(left, top, left + scaledW, top + scaledH),
                paint,
            )
        }
        // 下采样再 box blur,近似高斯模糊,成本低
        val downscale = 0.25f
        val smallW = (screenW * downscale).toInt().coerceAtLeast(1)
        val smallH = (screenH * downscale).toInt().coerceAtLeast(1)
        var small = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)
        small = boxBlur(small, radius = 12)
        val matrix = android.graphics.Matrix()
        val scale = screenW.toFloat() / smallW.toFloat().coerceAtLeast(1f)
        matrix.setScale(scale, scale)
        val tileMode = if (android.os.Build.VERSION.SDK_INT >= 31) {
            Shader.TileMode.DECAL
        } else {
            Shader.TileMode.CLAMP
        }
        val shader = BitmapShader(small, tileMode, tileMode).apply {
            setLocalMatrix(matrix)
        }
        val brushKtClass = XposedHelpers.findClass(BRUSH_KT_CLASS, classLoader)
        val factory = brushKtClass.declaredMethods.firstOrNull { m ->
            m.name == SHADER_BRUSH_METHOD && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Shader::class.java
        }?.apply { isAccessible = true } ?: return@runCatching null
        factory.invoke(null, shader)
    }.getOrNull()

    private fun blurredBitmapShaderBrushOffset(bitmap: Bitmap, offsetY: Float): Any? = runCatching {
        blurredBitmapShaderBrush(bitmap)
    }.getOrNull()

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
        repeat(3) { // 3 passes ≈ Gaussian
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

    private fun invokeBrushBackground(modifier: Any, brush: Any, shape: Any?): Any? = runCatching {
        val backgroundKtClass = XposedHelpers.findClass(BACKGROUND_KT_CLASS, classLoader)
        val method = backgroundKtClass.declaredMethods.firstOrNull { m ->
            m.name == BRUSH_BACKGROUND_DEFAULT_METHOD && m.parameterTypes.size == 6
        }?.apply { isAccessible = true } ?: return@runCatching null
        val resolvedShape = shape ?: rectangleShape()
        method.invoke(
            null,
            modifier,
            brush,
            resolvedShape,
            1f,
            4, // default mask: skip alpha (idx 3)
            null,
        )
    }.getOrNull()

    private fun rectangleShape(): Any? = runCatching {
        val cls = XposedHelpers.findClass(RECTANGLE_SHAPE_KT_CLASS, classLoader)
        val field = cls.getDeclaredField("RectangleShape").apply { isAccessible = true }
        field.get(null)
    }.getOrNull()

    private fun staticObject(clazz: Class<*>, fieldName: String): Any {
        listOf(fieldName, "Companion").forEach { candidate ->
            val field = runCatching { clazz.getDeclaredField(candidate) }
                .recoverCatching { clazz.getField(candidate) }
                .getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.get(null)?.let { return it }
            }
        }
        clazz.declaredClasses.forEach { inner ->
            val field = inner.declaredFields.firstOrNull { it.name == "\$\$INSTANCE" } ?: return@forEach
            field.isAccessible = true
            field.get(null)?.let { return it }
        }
        error("${clazz.name}.$fieldName static object not found")
    }

    private fun colorLongFromArgb(argb: Int): Long {
        val cacheKey = "$COLOR_KT_CLASS#$COLOR_METHOD"
        val factory = synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                val colorKtClass = XposedHelpers.findClass(COLOR_KT_CLASS, classLoader)
                colorKtClass.declaredMethods.firstOrNull { method ->
                    method.name == COLOR_METHOD &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType
                }?.apply { isAccessible = true }
                    ?: error("Color factory not found")
            }
        }
        return factory.invoke(null, argb) as Long
    }

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

    companion object {
        const val LOG_PREFIX = "ReaMicro LSP profile background"

        const val BACKGROUND_ARGB_FALLBACK = 0x80000000.toInt()
        const val PROFILE_BACKGROUND_DARK_PAGE_ARGB_FALLBACK = 0xFF000000.toInt()
        const val PROFILE_BACKGROUND_LIGHT_PAGE_ARGB_FALLBACK = -1
        const val PROFILE_BACKGROUND_HEADER_HEIGHT_DP = 253f
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

        const val PROFILE_SCREEN_CLASS = "app.zhendong.reamicro.ui.profile.ProfileScreenKt"

        const val THEME_KT_CLASS = "app.zhendong.reamicro.arch.theme.ThemeKt"
        const val THEME_GET_BACKGROUND_AUTO_METHOD = "getBackgroundAuto"
        const val THEME_GET_BACKGROUND_DIM_METHOD = "getBackgroundDim"
        const val THEME_GET_BACKGROUND_BRIGHT_METHOD = "getBackgroundBright"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"

        const val DIVIDER_KT_CLASS = "app.zhendong.reamicro.arch.components.DividerKt"
        const val SIMPLE_DIVIDER_METHOD = "SimpleDivider-iJQMabo"

        const val COLOR_SCHEME_CLASS = "androidx.compose.material3.ColorScheme"
        const val SURFACE_CONTAINER_HIGH_METHOD = "getSurfaceContainerHigh-0d7_KjU"
        val PROFILE_BACKGROUND_SURFACE_COLOR_METHODS = arrayOf(
            "getSurface-0d7_KjU",
            "getSurfaceDim-0d7_KjU",
            "getSurfaceBright-0d7_KjU",
            "getSurfaceContainerLowest-0d7_KjU",
            "getSurfaceContainerLow-0d7_KjU",
            "getSurfaceContainer-0d7_KjU",
            SURFACE_CONTAINER_HIGH_METHOD,
            "getSurfaceContainerHighest-0d7_KjU",
            "getSurfaceVariant-0d7_KjU",
        )
        val PROFILE_BACKGROUND_THEME_METHODS = arrayOf(
            THEME_GET_BACKGROUND_AUTO_METHOD,
            THEME_GET_BACKGROUND_DIM_METHOD,
            THEME_GET_BACKGROUND_BRIGHT_METHOD,
        )

        const val BACKGROUND_KT_CLASS = "androidx.compose.foundation.BackgroundKt"
        const val COLOR_BACKGROUND_DEFAULT_METHOD = "background-bw27NRU\$default"
        const val BRUSH_BACKGROUND_DEFAULT_METHOD = "background\$default"

        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val FILL_MAX_SIZE_DEFAULT_METHOD = "fillMaxSize\$default"

        const val BRUSH_KT_CLASS = "androidx.compose.ui.graphics.BrushKt"
        const val SHADER_BRUSH_METHOD = "ShaderBrush"

        const val BRUSH_COMPANION_CLASS = "androidx.compose.ui.graphics.Brush"
        const val VERTICAL_GRADIENT_DEFAULT_METHOD = "verticalGradient-8A-3gB4\$default"

        const val RECTANGLE_SHAPE_KT_CLASS = "androidx.compose.ui.graphics.RectangleShapeKt"

        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val COLOR_METHOD = "Color"

        const val COLOR_CLASS = "androidx.compose.ui.graphics.Color"
        const val COLOR_BOX_METHOD = "box-impl"
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
