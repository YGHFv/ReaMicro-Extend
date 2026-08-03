package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.R
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 改造阅微阅读页「主题」面板的「背景」区（host `ReaderThemesKt.ReaderBackground-3xixttE`）：
 * 在其下方注入一行模块管理的多图缩略图，按当前深浅模式只显示对应组；
 * 单击切换背景、长按移除、双击当前项回退默认；用户经 host 原选图入口新增的图自动纳入当前组。
 *
 * 深浅背景由模块独立保存；正文只在宿主背景绘制 lambda 读取 State 时按真实主题返回对应 URI，
 * 并通过 Compose snapshot 版本 State 触发当前阅读页即时重组，不写入宿主全局 BACKGROUND。
 */
class ReaderBackgroundHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
    private val settingsProvider: () -> ModuleSettingsSnapshot = settings::snapshot,
) {
    private val thumbCache = ConcurrentHashMap<String, Bitmap>()
    private val backgroundStateThemes = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val backgroundStateHostValues = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val backgroundExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ReaMicroReaderBackground").apply { isDaemon = true }
    }
    @Volatile private var currentReaderDark: Boolean = false
    @Volatile private var pendingPickDark: Boolean = false
    @Volatile private var activityResultHooked: Boolean = false
    @Volatile private var renderingDarkThemeContent: Boolean = false
    @Volatile private var renderingEpubBackground: Boolean = false
    @Volatile private var activeBackgroundBranchDark: Boolean? = null
    @Volatile private var visibleArea: WeakReference<LinearLayout>? = null
    @Volatile private var selectionVersionState: Any? = null
    @Volatile private var lightHostBackgroundState: Any? = null
    @Volatile private var darkHostBackgroundState: Any? = null
    @Volatile private var lightSelectionOverride: String? = null
    @Volatile private var darkSelectionOverride: String? = null
    @Volatile private var builtInBackgroundsReady: Boolean = false
    private val imagePickInProgress = AtomicBoolean(false)
    private val darkThemeContentHitLogged = AtomicBoolean(false)
    private val darkEpubBackgroundHitLogged = AtomicBoolean(false)

    fun install() {
        runCatching {
            val clazz = XposedHelpers.findClass(READER_THEMES_KT_CLASS, classLoader)
            val bgMethod = clazz.declaredMethods.firstOrNull { m ->
                m.name.startsWith(READER_BACKGROUND_PREFIX) &&
                    !m.name.contains('$') &&
                    m.parameterTypes.size >= 6 &&
                    m.parameterTypes.getOrNull(0)?.name == SESSION_CLASS &&
                    m.parameterTypes.getOrNull(1)?.name == USER_STORAGE_CLASS
            }?.apply { isAccessible = true } ?: error("ReaderBackground 方法未找到")
            // 末两参固定为 (Composer, int)，Composer 在倒数第 2 位。
            val composerIdx = bgMethod.parameterTypes.size - 2
            XposedBridge.hookMethod(bgMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    val args = param.args ?: return
                    args.getOrNull(0) ?: return
                    args.getOrNull(1) ?: return
                    val composer = args.getOrNull(composerIdx) ?: return
                    val dark = renderingDarkThemeContent
                    currentReaderDark = dark
                    // 接管整个「背景」区：模块画标题 + 一行多图，替代 host 单图大条。
                    val ok = runCatching { emitBackgroundArea(composer, dark) }
                        .onFailure { XposedBridge.log("$LOG_PREFIX emit area failed: ${it.stackTraceToString()}") }
                        .isSuccess
                    if (ok) param.result = null // 跳过 host 原大条渲染
                }
            })
            hookDarkThemeContent()
            hookEpubBackground()
            hookActivityResult()
            XposedBridge.log("$LOG_PREFIX reader background hook installed: ${bgMethod.name}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook reader background: ${it.stackTraceToString()}")
        }
    }

    private fun hookDarkThemeContent() {
        runCatching {
            val singletonsClass = cls(READER_THEMES_SINGLETONS_CLASS)
            val darkContentMethod = singletonsClass.declaredMethods.firstOrNull {
                it.name == DARK_THEME_CONTENT_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.getOrNull(0)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("深色主题月亮内容方法未找到")
            val readerThemesClass = cls(READER_THEMES_KT_CLASS)
            val readerThemesContent = readerThemesClass.declaredMethods.firstOrNull {
                it.name == READER_THEMES_CONTENT_ACCESS_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.getOrNull(0)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("主题背景内容访问方法未找到")

            XposedBridge.hookMethod(darkContentMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    val composer = param.args?.getOrNull(0) ?: return
                    val changed = (param.args?.getOrNull(1) as? Number)?.toInt() ?: 0
                    renderingDarkThemeContent = true
                    try {
                        readerThemesContent.invoke(null, composer, changed and 0x1)
                        param.result = targetUnit()
                        if (darkThemeContentHitLogged.compareAndSet(false, true)) {
                            XposedBridge.log(
                                "$LOG_PREFIX replaced reader moon animation with dark background content",
                            )
                        }
                    } catch (error: InvocationTargetException) {
                        throw error.targetException ?: error
                    } finally {
                        renderingDarkThemeContent = false
                    }
                }
            })
            XposedBridge.log(
                "$LOG_PREFIX dark theme content replacement hook installed: ${darkContentMethod.name}",
            )
        }.onFailure {
            XposedBridge.log(
                "$LOG_PREFIX hook dark theme content replacement failed: ${it.stackTraceToString()}",
            )
        }
    }

    private fun hookEpubBackground() {
        runCatching {
            val epubContainerClass = cls(EPUB_CONTAINER_KT_CLASS)
            val epubBackgroundMethod = epubContainerClass.declaredMethods.firstOrNull {
                it.name == EPUB_BACKGROUND_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.getOrNull(0)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("EpubBackground 方法未找到")
            val epubContainerMethod = epubContainerClass.declaredMethods.firstOrNull {
                it.name == EPUB_CONTAINER_METHOD &&
                    it.parameterTypes.size == 16 &&
                    it.parameterTypes.getOrNull(EPUB_DRAW_BACKGROUND_ARG_INDEX) == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.getOrNull(EPUB_CONTAINER_COMPOSER_ARG_INDEX)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("EpubContainer 方法未找到")
            val darkThemeMethod = cls(DYNAMIC_THEME_CONTENT_KT_CLASS).declaredMethods.firstOrNull {
                it.name == REMEMBER_READER_DARK_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.getOrNull(0)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("阅读主题状态方法未找到")
            val backgroundSingletons = cls(EPUB_CONTAINER_SINGLETONS_CLASS)
            val singletonInstance = backgroundSingletons.getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .get(null)
                ?: error("EpubContainer singleton unavailable")
            val backgroundLambdaGetter = backgroundSingletons.declaredMethods.firstOrNull {
                it.name == EPUB_BACKGROUND_LAMBDA_GETTER && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }
                ?: error("正文背景绘制 lambda 未找到")
            val hostBackgroundLambda = backgroundLambdaGetter.invoke(singletonInstance)
                ?: error("正文背景绘制 lambda unavailable")
            val lightBackgroundLambda = backgroundComposableLambda(hostBackgroundLambda, dark = false)
            val darkBackgroundLambda = backgroundComposableLambda(hostBackgroundLambda, dark = true)
            val themeToggleMethod = cls(DYNAMIC_THEME_CONTENT_KT_CLASS).declaredMethods.firstOrNull {
                it.name == THEME_TOGGLE_CONTENT_METHOD && it.parameterTypes.size == 5
            }?.apply { isAccessible = true }
                ?: error("ThemeToggleContent 方法未找到")
            val backgroundValueMethod = backgroundSingletons.declaredMethods.firstOrNull {
                it.name == EPUB_BACKGROUND_VALUE_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.returnType == String::class.java
            }?.apply { isAccessible = true }
                ?: error("正文背景状态读取方法未找到")

            XposedBridge.hookMethod(epubContainerMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    if (
                        selectedBackgroundUri(dark = false).isNotBlank() ||
                        selectedBackgroundUri(dark = true).isNotBlank()
                    ) {
                        param.args[EPUB_DRAW_BACKGROUND_ARG_INDEX] = true
                    }
                }
            })
            XposedBridge.hookMethod(epubBackgroundMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    renderingEpubBackground = true
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    renderingEpubBackground = false
                }
            })
            XposedBridge.hookMethod(darkThemeMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    currentReaderDark = param.result as? Boolean ?: return
                }
            })
            XposedBridge.hookMethod(themeToggleMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!renderingEpubBackground || !settingsProvider().canUseReaderBackground) return
                    param.args[0] = darkBackgroundLambda
                    param.args[1] = lightBackgroundLambda
                    // 宿主原调用使用默认参数 darkContent=null（掩码 bit 0）。只改 args[0] 会在函数体内再次被清空。
                    val defaultMask = param.args?.getOrNull(THEME_TOGGLE_DEFAULT_MASK_ARG_INDEX) as? Int ?: 0
                    param.args[THEME_TOGGLE_DEFAULT_MASK_ARG_INDEX] =
                        defaultMask and THEME_TOGGLE_DARK_CONTENT_DEFAULT_MASK.inv()
                    if (darkEpubBackgroundHitLogged.compareAndSet(false, true)) {
                        XposedBridge.log("$LOG_PREFIX enabled reader background drawing for dark theme")
                    }
                }
            })
            XposedBridge.hookMethod(backgroundValueMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    val state = param.args?.getOrNull(0) ?: return
                    val dark = activeBackgroundBranchDark ?: backgroundStateThemes[state] ?: currentReaderDark
                    backgroundStateThemes[state] = dark
                    backgroundStateHostValues[state] = (param.result as? String).orEmpty()
                    if (dark) darkHostBackgroundState = state else lightHostBackgroundState = state
                    // 必须先让宿主 State.getValue() 正常执行，Compose 才会登记读取依赖；
                    // 然后仅替换本次返回的 URI，避免把深浅背景写回宿主全局 BACKGROUND。
                    param.result = selectedBackgroundUri(dark)
                }
            })
            XposedBridge.log("$LOG_PREFIX epub background theme isolation hooks installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX hook epub background failed: ${it.stackTraceToString()}")
        }
    }

    private fun emitBackgroundArea(composer: Any, dark: Boolean) {
        observeSelectionVersion()
        val factory = functionProxy { fnArgs ->
            val ctx = fnArgs?.getOrNull(0) as? Context ?: activityProvider() ?: error("no context")
            buildBackgroundArea(ctx, dark)
        }
        val modifier = fillMaxWidthModifier()
        cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(null, factory, modifier, null, composer, 0, 4)
    }

    private fun buildBackgroundArea(ctx: Context, dark: Boolean): View {
        ensureBuiltInBackgrounds(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        visibleArea = WeakReference(root)
        renderBackgroundArea(root, dark)
        return root
    }

    private fun renderBackgroundArea(root: LinearLayout, dark: Boolean) {
        val ctx = root.context
        val images = settingsProvider().readerBgImages(dark)
            .filter { File(it).isFile }
            .distinctBy(::canonicalPathOrSelf)
        val density = ctx.resources.displayMetrics.density
        val itemW = (54 * density).toInt()
        val itemH = (80 * density).toInt()
        root.removeAllViews()
        root.setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
        root.addView(TextView(ctx).apply {
            text = "背景"
            textSize = 13f
            setTextColor(if (dark) Color.rgb(150, 150, 150) else Color.rgb(120, 120, 120))
        })
        val rowInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        images.forEach { path ->
            runCatching { rowInner.addView(buildThumb(ctx, path, itemW, itemH, density, dark)) }
        }
        rowInner.addView(buildAddTile(ctx, itemW, itemH, density, dark))
        root.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    rowInner,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun refreshVisibleArea(dark: Boolean) {
        val activity = activityProvider()
        if (activity == null) {
            invalidateHostBackgroundState(dark)
            bumpSelectionVersion()
        } else {
            activity.runOnUiThread {
                invalidateHostBackgroundState(dark)
                bumpSelectionVersion()
            }
        }
        val root = visibleArea?.get() ?: return
        root.post {
            if (currentReaderDark == dark) renderBackgroundArea(root, dark)
        }
    }

    private fun observeSelectionVersion() {
        val state = selectionVersionState ?: mutableState(0).also { selectionVersionState = it }
        state.javaClass.methods.firstOrNull {
            it.name == "getValue" && it.parameterTypes.isEmpty()
        }?.invoke(state)
    }

    private fun bumpSelectionVersion() {
        val state = selectionVersionState ?: return
        val getter = state.javaClass.methods.firstOrNull {
            it.name == "getValue" && it.parameterTypes.isEmpty()
        } ?: return
        val setter = state.javaClass.methods.firstOrNull {
            it.name == "setValue" && it.parameterTypes.size == 1
        } ?: return
        val current = (getter.invoke(state) as? Number)?.toInt() ?: 0
        setter.invoke(state, current + 1)
    }

    private fun invalidateHostBackgroundState(dark: Boolean) {
        val state = if (dark) darkHostBackgroundState else lightHostBackgroundState
        state ?: return
        runCatching {
            val setter = state.javaClass.methods.firstOrNull {
                it.name == "setValue" && it.parameterTypes.size == 1
            } ?: error("宿主背景 State 不可写")
            // 用一次性令牌触发当前 composition 精确失效，再恢复此前捕获的宿主原值；
            // afterHookedMethod 仍只替换 getter 返回值，模块 URI不会残留进宿主 State。
            val hostValue = backgroundStateHostValues[state].orEmpty()
            val refreshToken = "$HOST_REFRESH_TOKEN_PREFIX${System.nanoTime()}"
            setter.invoke(state, refreshToken)
            setter.invoke(state, hostValue)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX invalidate host background state failed dark=$dark: ${it.stackTraceToString()}")
        }
    }

    private fun buildThumb(ctx: Context, path: String, w: Int, h: Int, density: Float, dark: Boolean): View {
        val selected = path == selectedBackgroundPath(dark)
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(decodeThumb(path, w, h))
            clipToOutline = true
            background = roundedRectDrawable(density, dark, selected)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (10 * density).toInt() }
            addView(iv, FrameLayout.LayoutParams(w, h))
        }
        if (selected) {
            val markSize = (22 * density).toInt()
            frame.addView(
                TextView(ctx).apply {
                    text = "✓"
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.rgb(255, 90, 31))
                    }
                    contentDescription = "当前阅读背景"
                },
                FrameLayout.LayoutParams(markSize, markSize, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
                    bottomMargin = (3 * density).toInt()
                    marginStart = (3 * density).toInt()
                },
            )
        }
        val builtIn = isBuiltInBackground(dark, path)
        if (!builtIn) {
            val deleteSize = (22 * density).toInt()
            frame.addView(
                TextView(ctx).apply {
                    text = "×"
                    textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(190, 36, 36, 36))
                    }
                    contentDescription = "移除阅读背景"
                    setOnClickListener { removeImage(dark, path) }
                },
                FrameLayout.LayoutParams(deleteSize, deleteSize, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                    topMargin = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (path != selectedBackgroundPath(dark)) applyBackground(dark, path)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (path == selectedBackgroundPath(dark)) clearBackground(dark)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!builtIn) removeImage(dark, path)
            }
        })
        iv.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        return frame
    }

    private fun buildAddTile(ctx: Context, w: Int, h: Int, density: Float, dark: Boolean): View {
        val label = android.widget.TextView(ctx).apply {
            text = "＋"
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            setTextColor(if (dark) Color.rgb(200, 200, 200) else Color.rgb(120, 120, 120))
        }
        return FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (10 * density).toInt() }
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (dark) Color.rgb(30, 30, 30) else Color.rgb(240, 240, 240))
                setStroke((1.5f * density).toInt(), if (dark) Color.rgb(70, 70, 70) else Color.rgb(205, 205, 205))
            }
            addView(label, FrameLayout.LayoutParams(w, h))
            setOnClickListener { pickImage(dark) }
        }
    }

    private fun decodeThumb(path: String, w: Int, h: Int): Bitmap? {
        thumbCache[path]?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)?.also { thumbCache[path] = it }
        }.getOrNull()
    }

    private fun applyBackground(dark: Boolean, path: String) {
        val file = File(path)
        if (!file.isFile) {
            showToast("背景图片已失效")
            return
        }
        setSelectionOverride(dark, path)
        refreshVisibleArea(dark)
        backgroundExecutor.execute {
            runCatching {
                settings.setReaderBgCurrent(dark, path)
                // 保留进程内覆盖值，避免宿主背景子 composition 持有旧配置快照；后续选择会直接覆盖它。
                refreshVisibleArea(dark)
                XposedBridge.log("$LOG_PREFIX selected isolated reader background dark=$dark path=$path")
            }.onFailure {
                clearSelectionOverride(dark, path)
                refreshVisibleArea(dark)
                showToast("应用阅读背景失败")
                XposedBridge.log("$LOG_PREFIX apply background failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun clearBackground(dark: Boolean) {
        val defaultPath = builtInBackgroundPath(dark)
        setSelectionOverride(dark, defaultPath)
        refreshVisibleArea(dark)
        backgroundExecutor.execute {
            runCatching {
                settings.setReaderBgCurrent(dark, defaultPath)
                // 恢复模块内置的对应主题默认背景，并保留进程内覆盖值保证正文立即更新。
                refreshVisibleArea(dark)
                XposedBridge.log("$LOG_PREFIX restored built-in reader background dark=$dark path=$defaultPath")
            }.onFailure {
                clearSelectionOverride(dark, defaultPath)
                refreshVisibleArea(dark)
                showToast("恢复默认背景失败")
                XposedBridge.log("$LOG_PREFIX clear background failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun removeImage(dark: Boolean, path: String) {
        if (isBuiltInBackground(dark, path)) {
            showToast("内置默认背景不能移除")
            return
        }
        val list = settingsProvider().readerBgImages(dark).toMutableList()
        if (!list.remove(path)) return
        val selected = path == selectedBackgroundPath(dark)
        backgroundExecutor.execute {
            runCatching {
                settings.setReaderBgImages(dark, list)
                if (selected) settings.setReaderBgCurrent(dark, "")
                thumbCache.remove(path)
                refreshVisibleArea(dark)
                deleteModuleBackgroundFile(path)
                showToast(if (selected) "已移除并恢复默认背景" else "已移除背景图片")
                XposedBridge.log("$LOG_PREFIX removed reader background dark=$dark selected=$selected path=$path")
            }.onFailure {
                showToast("移除阅读背景失败")
                XposedBridge.log("$LOG_PREFIX remove background failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun pickImage(dark: Boolean) {
        val activity = activityProvider() ?: return
        if (!imagePickInProgress.compareAndSet(false, true)) return
        pendingPickDark = dark
        runCatching {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            activity.startActivityForResult(Intent.createChooser(intent, "选择阅读背景图片"), IMAGE_REQUEST_CODE)
        }.onFailure {
            imagePickInProgress.set(false)
            XposedBridge.log("$LOG_PREFIX open reader bg picker failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookActivityResult() {
        if (activityResultHooked) return
        activityResultHooked = true
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((param.args?.getOrNull(0) as? Number)?.toInt() != IMAGE_REQUEST_CODE) return
                    if (!imagePickInProgress.compareAndSet(true, false)) return
                    if ((param.args?.getOrNull(1) as? Number)?.toInt() != Activity.RESULT_OK) return
                    val activity = param.thisObject as? Activity ?: return
                    val uri = (param.args?.getOrNull(2) as? Intent)?.data ?: return
                    onImagePicked(activity, uri)
                }
            })
        }.onFailure { XposedBridge.log("$LOG_PREFIX hook activity result failed: ${it.stackTraceToString()}") }
    }

    private fun onImagePicked(activity: Activity, uri: Uri) {
        runCatching {
            val dark = pendingPickDark
            val target = copyUriToDir(activity, uri)
            val list = settingsProvider().readerBgImages(dark)
                .filter { File(it).isFile }
                .distinctBy(::canonicalPathOrSelf)
                .toMutableList()
            list.removeAll { sameFileContent(File(it), target) }
            list.add(target.absolutePath)
            settings.setReaderBgImages(dark, list)
            refreshVisibleArea(dark)
            applyBackground(dark, target.absolutePath)
            XposedBridge.log("$LOG_PREFIX reader bg added dark=$dark path=${target.absolutePath}")
        }.onFailure { XposedBridge.log("$LOG_PREFIX import reader bg failed: ${it.stackTraceToString()}") }
    }

    private fun ensureBuiltInBackgrounds(context: Context) {
        if (builtInBackgroundsReady) return
        synchronized(this) {
            if (builtInBackgroundsReady) return
            runCatching {
                val hostContext = context.applicationContext ?: context
                val moduleContext = if (hostContext.packageName == MODULE_PACKAGE_NAME) {
                    hostContext
                } else {
                    hostContext.createPackageContext(MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
                }
                val dir = File(hostContext.filesDir, BACKGROUND_DIR_NAME).apply { mkdirs() }
                val light = copyBuiltInBackground(
                    moduleContext,
                    R.drawable.reader_background_default_light,
                    File(dir, BUILTIN_LIGHT_FILE_NAME),
                )
                val dark = copyBuiltInBackground(
                    moduleContext,
                    R.drawable.reader_background_default_dark,
                    File(dir, BUILTIN_DARK_FILE_NAME),
                )
                registerBuiltInBackground(dark = false, file = light)
                registerBuiltInBackground(dark = true, file = dark)
                builtInBackgroundsReady = true
                XposedBridge.log("$LOG_PREFIX built-in reader backgrounds ready")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX prepare built-in reader backgrounds failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun copyBuiltInBackground(moduleContext: Context, resourceId: Int, target: File): File {
        val resourceBytes = moduleContext.resources.openRawResource(resourceId).use { it.readBytes() }
        if (!target.isFile || !fileDigest(target).contentEquals(MessageDigest.getInstance("SHA-256").digest(resourceBytes))) {
            target.outputStream().buffered().use { it.write(resourceBytes) }
        }
        return target
    }

    private fun registerBuiltInBackground(dark: Boolean, file: File) {
        val images = settingsProvider().readerBgImages(dark)
            .filter { File(it).isFile }
            .distinctBy(::canonicalPathOrSelf)
            .toMutableList()
        images.removeAll { canonicalPathOrSelf(it) == file.canonicalPath }
        images.add(0, file.absolutePath)
        settings.setReaderBgImages(dark, images)
        if (settingsProvider().readerBgCurrent(dark).isBlank()) {
            settings.setReaderBgCurrent(dark, file.absolutePath)
            setSelectionOverride(dark, file.absolutePath)
        }
    }

    private fun builtInBackgroundPath(dark: Boolean): String {
        val dir = moduleBgDir() ?: return ""
        val name = if (dark) BUILTIN_DARK_FILE_NAME else BUILTIN_LIGHT_FILE_NAME
        return File(dir, name).takeIf(File::isFile)?.absolutePath.orEmpty()
    }

    private fun isBuiltInBackground(dark: Boolean, path: String): Boolean {
        val builtInPath = builtInBackgroundPath(dark)
        return builtInPath.isNotBlank() && canonicalPathOrSelf(path) == canonicalPathOrSelf(builtInPath)
    }

    private fun copyUriToDir(activity: Activity, uri: Uri): File {
        val dir = File(activity.applicationContext.filesDir, BACKGROUND_DIR_NAME).apply { mkdirs() }
        val name = queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty()
        val ext = name.substringAfterLast('.', "png").lowercase()
            .let { if (it.matches(Regex("^[a-z0-9]{1,8}$"))) it else "png" }
        val target = File(dir, "reader_bg_${System.currentTimeMillis()}.$ext")
        activity.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("无法读取图片")
        return target
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String? =
        runCatching {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()

    private fun cls(name: String): Class<*> = XposedHelpers.findClass(name, classLoader)

    private fun functionProxy(block: (Array<Any?>?) -> Any?): Any {
        val fnClass = cls(FUNCTION1_CLASS)
        return Proxy.newProxyInstance(classLoader, arrayOf(fnClass)) { _, method, args ->
            when (method.name) {
                "invoke" -> runCatching { block(args) }.getOrElse { targetUnit() }
                "toString" -> "ReaderBgViewFactory"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        }
    }

    private fun backgroundComposableLambda(hostLambda: Any, dark: Boolean): Any {
        val fnClass = cls(FUNCTION2_CLASS)
        return Proxy.newProxyInstance(classLoader, arrayOf(fnClass)) { _, method, args ->
            when (method.name) {
                "invoke" -> runCatching {
                    currentReaderDark = dark
                    activeBackgroundBranchDark = dark
                    observeSelectionVersion()
                    try {
                        hostLambda.javaClass.methods.first {
                            it.name == "invoke" && it.parameterTypes.size == 2
                        }.invoke(
                            hostLambda,
                            args?.getOrNull(0),
                            Integer.valueOf(0),
                        )
                    } finally {
                        activeBackgroundBranchDark = null
                    }
                }.getOrElse {
                    activeBackgroundBranchDark = null
                    targetUnit()
                }
                "toString" -> "ReaderBgComposable(dark=$dark)"
                "hashCode" -> System.identityHashCode(hostLambda) * 31 + dark.hashCode()
                "equals" -> false
                else -> null
            }
        }
    }

    private fun targetUnit(): Any? =
        runCatching {
            cls("kotlin.Unit").getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()

    private fun emptyModifier(): Any {
        val m = cls(MODIFIER_CLASS)
        return runCatching { m.getField("INSTANCE").get(null) }
            .recoverCatching { m.getField("Companion").get(null) }
            .getOrThrow()
    }

    private fun fillMaxWidthModifier(): Any = runCatching {
        cls(SIZE_KT_CLASS).declaredMethods.first { it.name == "fillMaxWidth" && it.parameterTypes.size == 2 }
            .apply { isAccessible = true }.invoke(null, emptyModifier(), 1f)
    }.getOrElse { emptyModifier() }

    private fun roundedRectDrawable(density: Float, dark: Boolean, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(if (dark) Color.rgb(38, 38, 38) else Color.rgb(238, 238, 238))
            val borderColor = if (selected) Color.rgb(255, 90, 31) else if (dark) Color.rgb(70, 70, 70) else Color.rgb(205, 205, 205)
            setStroke(((if (selected) 2.5f else 1.5f) * density).toInt(), borderColor)
        }

    private fun canonicalPathOrSelf(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    private fun sameFileContent(first: File, second: File): Boolean {
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        return runCatching { fileDigest(first).contentEquals(fileDigest(second)) }.getOrDefault(false)
    }

    private fun fileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun selectedBackgroundPath(dark: Boolean): String =
        (if (dark) darkSelectionOverride else lightSelectionOverride)
            ?: settingsProvider().readerBgCurrent(dark)

    private fun setSelectionOverride(dark: Boolean, path: String) {
        if (dark) darkSelectionOverride = path else lightSelectionOverride = path
    }

    private fun clearSelectionOverride(dark: Boolean, expected: String) {
        if (dark) {
            if (darkSelectionOverride == expected) darkSelectionOverride = null
        } else if (lightSelectionOverride == expected) {
            lightSelectionOverride = null
        }
    }

    private fun selectedBackgroundUri(dark: Boolean): String =
        selectedBackgroundPath(dark)
            .takeIf { it.isNotBlank() && File(it).isFile }
            ?.let { Uri.fromFile(File(it)).toString() }
            .orEmpty()

    private fun mutableState(value: Any): Any =
        cls(SNAPSHOT_STATE_KT_CLASS).declaredMethods.first {
            it.name == MUTABLE_STATE_OF_DEFAULT_METHOD && it.parameterTypes.size == 4
        }.apply { isAccessible = true }.invoke(null, value, null, 2, null)
            ?: error("mutableStateOf unavailable")

    private fun deleteModuleBackgroundFile(path: String) {
        val dir = moduleBgDir() ?: return
        val file = File(path)
        if (!file.isFile) return
        val parentPath = dir.canonicalPath + File.separator
        if (file.canonicalPath.startsWith(parentPath) && !file.delete()) {
            error("无法删除模块背景文件: $path")
        }
    }

    private fun showToast(message: String) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun moduleBgDir(): File? =
        activityProvider()?.applicationContext?.let { File(it.filesDir, BACKGROUND_DIR_NAME) }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
        const val BACKGROUND_DIR_NAME = "reamicro-reader-bg"
        const val BUILTIN_LIGHT_FILE_NAME = "reader_background_default_light.jpg"
        const val BUILTIN_DARK_FILE_NAME = "reader_background_default_dark.png"
        const val HOST_REFRESH_TOKEN_PREFIX = "reamicro-bg-refresh://"
        const val READER_THEMES_KT_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderThemesKt"
        const val READER_THEMES_SINGLETONS_CLASS =
            "app.zhendong.reamicro.ui.reader.compose.ComposableSingletons\$ReaderThemesKt"
        const val DARK_THEME_CONTENT_METHOD = "lambda__1576463935\$lambda\$0"
        const val READER_THEMES_CONTENT_ACCESS_METHOD = "access\$ReaderThemesContent"
        const val READER_BACKGROUND_PREFIX = "ReaderBackground-"
        const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"
        const val SESSION_CLASS = "app.zhendong.reamicro.repository.core.Session"
        const val USER_STORAGE_CLASS = "app.zhendong.reamicro.arch.fs.UserStorage"
        const val EPUB_CONTAINER_KT_CLASS = "app.zhendong.reamicro.ui.reader.components.EpubContainerKt"
        const val EPUB_CONTAINER_SINGLETONS_CLASS =
            "app.zhendong.reamicro.ui.reader.components.ComposableSingletons\$EpubContainerKt"
        const val EPUB_CONTAINER_METHOD = "EpubContainer"
        const val EPUB_BACKGROUND_METHOD = "EpubBackground"
        const val EPUB_DRAW_BACKGROUND_ARG_INDEX = 3
        const val EPUB_CONTAINER_COMPOSER_ARG_INDEX = 12
        const val EPUB_BACKGROUND_LAMBDA_GETTER = "getLambda\$1672513034\$reamicro_composeApp"
        const val EPUB_BACKGROUND_VALUE_METHOD = "lambda_1672513034\$lambda\$0\$0"
        const val DYNAMIC_THEME_CONTENT_KT_CLASS =
            "app.zhendong.reamicro.ui.reader.theme.DynamicThemeContentKt"
        const val REMEMBER_READER_DARK_METHOD = "rememberReaderShouldUseDarkTheme"
        const val THEME_TOGGLE_CONTENT_METHOD = "ThemeToggleContent"
        const val THEME_TOGGLE_DEFAULT_MASK_ARG_INDEX = 4
        const val THEME_TOGGLE_DARK_CONTENT_DEFAULT_MASK = 0x1
        const val ANDROID_VIEW_KT_CLASS = "androidx.compose.ui.viewinterop.AndroidView_androidKt"
        const val ANDROID_VIEW_METHOD = "AndroidView"
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val FUNCTION2_CLASS = "kotlin.jvm.functions.Function2"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val SNAPSHOT_STATE_KT_CLASS = "androidx.compose.runtime.SnapshotStateKt__SnapshotStateKt"
        const val MUTABLE_STATE_OF_DEFAULT_METHOD = "mutableStateOf\$default"
        const val IMAGE_REQUEST_CODE = 47615
    }
}
