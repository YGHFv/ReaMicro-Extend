package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
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
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 改造阅微阅读页「主题」面板的完整混搭背景区：
 * 「内置」保留阅微原生的颜色、材质与背景选择；「侧载」显示模块默认背景、用户背景和添加入口。
 * 单击切换背景、长按移除、双击当前项回退模块默认；深浅模式分别保存。
 *
 * 正文只在宿主背景绘制 lambda 读取 State 时按真实主题返回对应 URI，
 * 同时持有实际读取该值的 RecomposeScope，选择变化后直接失效当前正文 composition，避免返回书架才刷新。
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
    @Volatile private var lightContainerRecomposeScope: Any? = null
    @Volatile private var darkContainerRecomposeScope: Any? = null
    @Volatile private var lightBackgroundRecomposeScope: Any? = null
    @Volatile private var darkBackgroundRecomposeScope: Any? = null
    @Volatile private var lightPanelRecomposeScope: Any? = null
    @Volatile private var darkPanelRecomposeScope: Any? = null
    @Volatile private var lightSelectionOverride: String? = null
    @Volatile private var darkSelectionOverride: String? = null
    @Volatile private var builtInBackgroundsReady: Boolean = false
    @Volatile private var backgroundHostContext: Context? = null
    @Volatile private var showNativeBackgrounds: Boolean = true
    @Volatile private var currentSession: Any? = null
    @Volatile private var panelRootRecomposeScope: WeakReference<Any>? = null
    @Volatile private var backgroundPreferenceKey: Any? = null
    @Volatile private var themePreferenceKey: Any? = null
    @Volatile private var mipmapPreferenceKey: Any? = null
    @Volatile private var sessionUpdateMethod: Method? = null
    @Volatile private var currentHostBackgroundValue: String = ""
    @Volatile private var globalUiTypeface: Typeface? = null
    @Volatile private var lightThemePrimaryColor: Int = DEFAULT_THEME_PRIMARY_COLOR
    @Volatile private var darkThemePrimaryColor: Int = DEFAULT_THEME_PRIMARY_COLOR
    private val renderingReaderThemesContent = ThreadLocal.withInitial { 0 }
    private val moduleSessionUpdate = ThreadLocal.withInitial { false }
    private val imagePickInProgress = AtomicBoolean(false)
    // 分支包装的固定实例（0=浅色 1=深色），宿主每次传来的绘制内容存进 hosts 供其调用。
    private val backgroundBranchLambdas = arrayOfNulls<Any>(2)
    private val backgroundBranchHosts = arrayOfNulls<Any>(2)
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
            // ReaderBackground 位于阅微颜色/材质混搭区之后：内置模式保留宿主内容并在末尾加开关，
            // 侧载模式则在同一位置绘制模块背景区并跳过宿主原生背景选择。
            val composerIdx = bgMethod.parameterTypes.size - 2
            XposedBridge.hookMethod(bgMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    val args = param.args ?: return
                    val session = args.getOrNull(0) ?: return
                    currentSession = session
                    args.getOrNull(1) ?: return
                    val composer = args.getOrNull(composerIdx) ?: return
                    val dark = renderingDarkThemeContent
                    currentReaderDark = dark
                    cacheThemePrimaryColor(composer, dark)
                    observeSelectionVersion()
                    capturePanelRecomposeScope(composer, dark)
                    if (showNativeBackgrounds) {
                        // 内置页只保留阅微自己的颜色与材质混搭；跳过原生“背景/选取相册图片”区，
                        // 在同一位置绘制固定尺寸的来源页签。
                        val ok = runCatching { emitBackgroundSourceSwitch(composer, dark) }
                            .onFailure { XposedBridge.log("$LOG_PREFIX emit native source switch failed: ${it.stackTraceToString()}") }
                            .isSuccess
                        if (ok) param.result = null
                    } else {
                        val ok = runCatching { emitBackgroundArea(composer, dark) }
                            .onFailure { XposedBridge.log("$LOG_PREFIX emit side-loaded area failed: ${it.stackTraceToString()}") }
                            .isSuccess
                        if (ok) param.result = null
                    }
                }
            })
            hookNativeMixVisibility()
            hookSessionBackgroundUpdates()
            hookDarkThemeContent()
            hookEpubBackground()
            hookActivityResult()
            XposedBridge.log("$LOG_PREFIX reader background hook installed: ${bgMethod.name}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook reader background: ${it.stackTraceToString()}")
        }
    }

    private fun hookNativeMixVisibility() {
        runCatching {
            val animatedVisibility = cls(ANIMATED_VISIBILITY_KT_CLASS).declaredMethods.firstOrNull {
                it.name == ANIMATED_VISIBILITY_METHOD &&
                    it.parameterTypes.size == 10 &&
                    it.parameterTypes.getOrNull(1) == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.getOrNull(7)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("AnimatedVisibility 方法未找到")
            XposedBridge.hookMethod(animatedVisibility, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground || !showNativeBackgrounds) return
                    if ((renderingReaderThemesContent.get() ?: 0) > 0) param.args[1] = true
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX hook native mix visibility failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookSessionBackgroundUpdates() {
        runCatching {
            val prefKeysClass = cls(PREF_KEYS_CLASS)
            val prefKeys = prefKeysClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            backgroundPreferenceKey = prefKeysClass.getDeclaredMethod("getBACKGROUND").invoke(prefKeys)
            themePreferenceKey = prefKeysClass.getDeclaredMethod("getTHEME").invoke(prefKeys)
            mipmapPreferenceKey = prefKeysClass.getDeclaredMethod("getMIPMAP").invoke(prefKeys)
            val sessionClass = cls(SESSION_CLASS)
            val updateMethod = sessionClass.declaredMethods.firstOrNull {
                it.name == "update" && it.parameterTypes.size == 3
            }?.apply { isAccessible = true } ?: error("Session.update 方法未找到")
            sessionUpdateMethod = updateMethod
            XposedBridge.hookMethod(updateMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    currentSession = param.thisObject
                    if (moduleSessionUpdate.get() == true) return
                    val key = param.args?.getOrNull(0) ?: return
                    val value = param.args?.getOrNull(1)
                    if (key === backgroundPreferenceKey) {
                        currentHostBackgroundValue = value as? String ?: ""
                        clearSelectionOverrides()
                        refreshVisibleArea(currentReaderDark)
                    } else if (key === themePreferenceKey || key === mipmapPreferenceKey) {
                        // 具体选择阅微颜色/材质时才退出侧载来源，并清除模块历史勾选。
                        clearSelectionOverrides()
                        backgroundExecutor.execute {
                            runCatching {
                                settings.setReaderBgCurrent(dark = currentReaderDark, path = "")
                                updateHostPreference(backgroundPreferenceKey, "")
                                currentHostBackgroundValue = ""
                                refreshVisibleArea(currentReaderDark)
                            }.onFailure {
                                XposedBridge.log("$LOG_PREFIX clear side-loaded selection failed: ${it.stackTraceToString()}")
                            }
                        }
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX hook Session background updates failed: ${it.stackTraceToString()}")
        }
    }

    private fun collapsedNativeBackgroundHeight(): Float = 38f

    private fun updateHostBackground(value: String) {
        updateHostPreference(backgroundPreferenceKey, value)
        currentHostBackgroundValue = value
    }

    private fun updateHostPreference(key: Any?, value: String) {
        val session = currentSession ?: error("Session 尚未捕获")
        val resolvedKey = key ?: error("宿主设置 key 尚未捕获")
        val method = sessionUpdateMethod ?: error("Session.update 尚未捕获")
        moduleSessionUpdate.set(true)
        try {
            invokeSuspendMethod(method, session, resolvedKey, value)
        } finally {
            moduleSessionUpdate.set(false)
        }
    }

    private fun invokeSuspendMethod(method: Method, target: Any, vararg args: Any?): Any? {
        val continuationClass = cls(KOTLIN_CONTINUATION_CLASS)
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> cls(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS).getDeclaredField("INSTANCE").get(null)
                "resumeWith" -> targetUnit()
                "toString" -> "ReaMicroReaderBackgroundContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        return method.invoke(target, *(args.toList() + continuation).toTypedArray())
    }

    private fun updateHostPreferencesInOrder(
        updates: List<Pair<Any?, String>>,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        fun updateAt(index: Int) {
            if (index >= updates.size) {
                onSuccess()
                return
            }
            val (key, value) = updates[index]
            updateHostPreferenceAsync(
                key = key,
                value = value,
                onSuccess = { updateAt(index + 1) },
                onFailure = onFailure,
            )
        }
        updateAt(0)
    }

    private fun updateHostPreferenceAsync(
        key: Any?,
        value: String,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val session = currentSession
        val resolvedKey = key
        val method = sessionUpdateMethod
        if (session == null || resolvedKey == null || method == null) {
            onFailure(IllegalStateException("宿主背景状态尚未初始化"))
            return
        }
        val completed = AtomicBoolean(false)
        fun finish(result: Any?) {
            if (!completed.compareAndSet(false, true)) return
            val error = kotlinResultFailure(result)
            if (error == null) onSuccess() else onFailure(error)
        }
        val continuationClass = cls(KOTLIN_CONTINUATION_CLASS)
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> cls(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS).getDeclaredField("INSTANCE").get(null)
                "resumeWith" -> {
                    finish(proxyArgs?.getOrNull(0))
                    targetUnit()
                }
                "toString" -> "ReaMicroReaderBackgroundContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        moduleSessionUpdate.set(true)
        try {
            val result = method.invoke(session, resolvedKey, value, continuation)
            if (!isCoroutineSuspended(result)) finish(result)
        } catch (error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                onFailure((error as? InvocationTargetException)?.targetException ?: error)
            }
        } finally {
            moduleSessionUpdate.set(false)
        }
    }

    private fun isCoroutineSuspended(result: Any?): Boolean =
        result?.javaClass?.name == KOTLIN_COROUTINE_SINGLETONS_CLASS &&
            (result as? Enum<*>)?.name == KOTLIN_COROUTINE_SUSPENDED_NAME

    private fun kotlinResultFailure(result: Any?): Throwable? {
        if (result?.javaClass?.name != KOTLIN_RESULT_FAILURE_CLASS) return null
        return runCatching {
            result.javaClass.getDeclaredField("exception")
                .apply { isAccessible = true }
                .get(result) as? Throwable
        }.getOrNull()
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
            val nativeReaderThemesContent = readerThemesClass.declaredMethods.firstOrNull {
                it.name == NATIVE_READER_THEMES_CONTENT_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.getOrNull(0)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("主题背景实际内容方法未找到")
            XposedBridge.hookMethod(nativeReaderThemesContent, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    val dark = renderingDarkThemeContent
                    currentReaderDark = dark
                    val composer = param.args?.getOrNull(0)
                    val rootScope = composer?.let(::currentRecomposeScope)
                    val previousRootScope = panelRootRecomposeScope?.get()
                    if (rootScope != null && rootScope !== previousRootScope) {
                        panelRootRecomposeScope = WeakReference(rootScope)
                        showNativeBackgrounds = !dark && !hasSelectedSideLoadedBackground(dark)
                    }
                    if (dark) showNativeBackgrounds = false
                    renderingReaderThemesContent.set((renderingReaderThemesContent.get() ?: 0) + 1)
                    observeSelectionVersion()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    renderingReaderThemesContent.set(((renderingReaderThemesContent.get() ?: 0) - 1).coerceAtLeast(0))
                }
            })

            val nativeMixContentMethod = readerThemesClass.declaredMethods.firstOrNull {
                it.name == NATIVE_MIX_CONTENT_METHOD &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes.getOrNull(0)?.name == MUTABLE_STATE_CLASS &&
                    it.parameterTypes.getOrNull(1)?.name == MUTABLE_STATE_CLASS &&
                    it.parameterTypes.getOrNull(3)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("阅微混搭背景内容方法未找到")

            XposedBridge.hookMethod(nativeMixContentMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    val composer = param.args?.getOrNull(3) ?: return
                    val dark = renderingDarkThemeContent
                    currentReaderDark = dark
                    if (dark) showNativeBackgrounds = false
                    observeSelectionVersion()
                    capturePanelRecomposeScope(composer, dark)
                    if (!showNativeBackgrounds) param.result = targetUnit()
                }
            })

            val nativeReaderMipmapMethod = readerThemesClass.declaredMethods.firstOrNull {
                it.name == NATIVE_READER_MIPMAP_METHOD &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes.getOrNull(0) == String::class.java &&
                    it.parameterTypes.getOrNull(1) == String::class.java &&
                    it.parameterTypes.getOrNull(3)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("阅微材质选择方法未找到")
            val nativeReaderThemeMethod = readerThemesClass.declaredMethods.firstOrNull {
                it.name == NATIVE_READER_THEME_METHOD &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes.getOrNull(0) == String::class.java &&
                    it.parameterTypes.getOrNull(2)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("阅微颜色选择方法未找到")
            val clearNativeSelectionHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    if ((renderingReaderThemesContent.get() ?: 0) <= 0 ||
                        !hasSelectedSideLoadedBackground(renderingDarkThemeContent)
                    ) return
                    // 侧载背景生效时，颜色与材质仍可作为候选展示，但不能继续显示“当前使用”勾选。
                    param.args[0] = SIDE_LOADED_SELECTION_SENTINEL
                }
            }
            XposedBridge.hookMethod(nativeReaderMipmapMethod, clearNativeSelectionHook)
            XposedBridge.hookMethod(nativeReaderThemeMethod, clearNativeSelectionHook)

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
            // 2.3.0 beta 新构建给 EpubBackground/EpubContainer 都加了 EpubBackgroundState 参数，
            // Composer 的位置随之后移。这里一律按「方法名 + 含 Composer 参数」定位，不再写死参数个数与下标。
            val epubBackgroundMethod = epubContainerClass.declaredMethods.firstOrNull {
                it.name == EPUB_BACKGROUND_METHOD && it.composerParameterIndex() >= 0
            }?.apply { isAccessible = true }
                ?: error("EpubBackground 方法未找到")
            val epubContainerMethod = epubContainerClass.declaredMethods.firstOrNull {
                it.name == EPUB_CONTAINER_METHOD &&
                    it.composerParameterIndex() >= 0 &&
                    it.parameterTypes.getOrNull(EPUB_DRAW_BACKGROUND_ARG_INDEX) == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
                ?: error("EpubContainer 方法未找到")
            val epubContainerComposerIndex = epubContainerMethod.composerParameterIndex()
            val darkThemeMethod = cls(DYNAMIC_THEME_CONTENT_KT_CLASS).declaredMethods.firstOrNull {
                it.name == REMEMBER_READER_DARK_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.getOrNull(0)?.name == COMPOSER_CLASS
            }?.apply { isAccessible = true }
                ?: error("阅读主题状态方法未找到")
            val themeToggleMethod = cls(DYNAMIC_THEME_CONTENT_KT_CLASS).declaredMethods.firstOrNull {
                it.name == THEME_TOGGLE_CONTENT_METHOD && it.parameterTypes.size == 5
            }?.apply { isAccessible = true }
                ?: error("ThemeToggleContent 方法未找到")
            // 背景值以前从 ComposableSingletons 的 lambda 里读，新版改由 EpubBackgroundState 承载。
            // 两条路径都尝试挂上，谁在就用谁。
            val backgroundValueMethod = backgroundStateValueMethod() ?: legacyBackgroundValueMethod()
                ?: error("正文背景状态读取方法未找到")

            XposedBridge.hookMethod(epubContainerMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settingsProvider().canUseReaderBackground) return
                    param.args?.getOrNull(epubContainerComposerIndex)?.let { composer ->
                        captureContainerRecomposeScope(composer, currentReaderDark)
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
                    val dark = param.result as? Boolean ?: return
                    val changed = currentReaderDark != dark
                    currentReaderDark = dark
                    if (changed) onReaderThemeChanged(dark)
                }
            })
            XposedBridge.hookMethod(themeToggleMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!renderingEpubBackground || !settingsProvider().canUseReaderBackground) return
                    // 宿主只给浅色分支传了绘制背景的内容，深色分支为空（走月亮动画）。
                    // 两个分支都包上同一份内容：深色因此也能画背景，包装同时记录当前渲染的是哪个分支，
                    // 供背景值读取点按主题返回对应的图。实例固定，避免 changedInstance 触发重组风暴。
                    val lightContent = param.args?.getOrNull(1) ?: return
                    param.args[0] = backgroundBranchLambda(lightContent, dark = true)
                    param.args[1] = backgroundBranchLambda(lightContent, dark = false)
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
                    // 新版是 EpubBackgroundState.getBackground()（无参实例方法），旧版是 singleton lambda(State)。
                    val state = param.args?.getOrNull(0) ?: param.thisObject ?: return
                    val dark = activeBackgroundBranchDark ?: backgroundStateThemes[state] ?: currentReaderDark
                    backgroundStateThemes[state] = dark
                    val hostValue = (param.result as? String).orEmpty()
                    backgroundStateHostValues[state] = hostValue
                    currentHostBackgroundValue = hostValue
                    if (dark) darkHostBackgroundState = state else lightHostBackgroundState = state
                    val selectedUri = selectedSideLoadedBackgroundUri(dark)
                    if (selectedUri.isNotBlank()) {
                        param.result = selectedUri
                    } else if (isSideLoadedBackgroundUri(hostValue)) {
                        // 宿主 BACKGROUND 是全局单值；目标主题没有侧载选中时，不能继承另一主题的图片。
                        param.result = ""
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX epub background theme isolation hooks installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX hook epub background failed: ${it.stackTraceToString()}")
        }
    }

    /** Composer 参数在方法签名里的下标；宿主加参数时位置会变，一律动态查找。 */
    private fun Method.composerParameterIndex(): Int =
        parameterTypes.indexOfFirst { it.name == COMPOSER_CLASS }

    /** 2.3.0 beta 新构建：正文背景值改由 EpubBackgroundState.getBackground() 提供。 */
    /**
     * 正文背景值的读取点。
     *
     * A13 及更早：`ComposableSingletons$EpubContainerKt.lambda_1672513034$lambda$0$0(State) -> String`。
     * 新构建把背景搬进 EpubBackgroundState，等价物是 `rememberEpubBackgroundState__93gMUo$lambda$1`——
     * 它的返回值是位图加载 LaunchedEffect 的 key，改它才会真正重新加载背景图。
     * 注意不能改成 `EpubBackgroundState.getBackground()`：那只是普通 getter，绘制时只用于判空，
     * 到处都会被调用，既拿不到主题分支，也不驱动重新加载。
     */
    private fun backgroundStateValueMethod(): Method? =
        runCatching {
            cls(EPUB_CONTAINER_KT_CLASS).declaredMethods.firstOrNull {
                it.name.startsWith(EPUB_BACKGROUND_STATE_VALUE_METHOD) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == COMPOSE_STATE_CLASS &&
                    it.returnType == String::class.java
            }?.apply { isAccessible = true }
        }.getOrNull()

    /** 旧版路径：ComposableSingletons 里的 lambda(State) -> String。 */
    private fun legacyBackgroundValueMethod(): Method? =
        runCatching {
            cls(EPUB_CONTAINER_SINGLETONS_CLASS).declaredMethods.firstOrNull {
                it.name == EPUB_BACKGROUND_VALUE_METHOD &&
                    it.parameterTypes.size == 1 &&
                    it.returnType == String::class.java
            }?.apply { isAccessible = true }
        }.getOrNull()

    private fun emitBackgroundArea(composer: Any, dark: Boolean) {
        emitBackgroundPanel(composer, dark)
    }

    private fun emitBackgroundSourceSwitch(composer: Any, dark: Boolean) {
        emitBackgroundPanel(composer, dark)
    }

    private fun emitBackgroundPanel(composer: Any, dark: Boolean) {
        val factory = functionProxy { fnArgs ->
            val ctx = fnArgs?.getOrNull(0) as? Context ?: activityProvider() ?: error("no context")
            buildBackgroundPanel(ctx, dark)
        }
        val update = functionProxy { fnArgs ->
            val root = fnArgs?.getOrNull(0) as? LinearLayout ?: return@functionProxy targetUnit()
            renderBackgroundPanel(root, dark)
            targetUnit()
        }
        emitAndroidView(composer, factory, update)
    }

    private fun emitAndroidView(composer: Any, factory: Any, update: Any) {
        val modifier = fillMaxWidthModifier()
        cls(ANDROID_VIEW_KT_CLASS).declaredMethods.first {
            it.name == ANDROID_VIEW_METHOD && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(null, factory, modifier, update, composer, 0, 0)
    }

    private fun buildBackgroundPanel(ctx: Context, dark: Boolean): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            renderBackgroundPanel(this, dark)
        }

    private fun renderBackgroundPanel(root: LinearLayout, dark: Boolean) {
        root.removeAllViews()
        if (showNativeBackgrounds) {
            visibleArea = null
            root.setPadding(0, 0, 0, 0)
            root.addView(
                buildBackgroundSourceSwitch(root.context, root.resources.displayMetrics.density, dark),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        } else {
            visibleArea = WeakReference(root)
            renderBackgroundArea(root, dark)
        }
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
        ensureBuiltInBackgrounds(ctx)
        val builtInPath = builtInBackgroundPath(dark)
        val images = buildList {
            if (builtInPath.isNotBlank()) add(builtInPath)
            addAll(settingsProvider().readerBgImages(dark))
        }
            .filter { File(it).isFile }
            .distinctBy(::canonicalPathOrSelf)
        val density = ctx.resources.displayMetrics.density
        val itemW = (54 * density).toInt()
        val itemH = (80 * density).toInt()
        root.removeAllViews()
        root.setPadding(0, (10 * density).toInt(), 0, 0)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        content.addView(TextView(ctx).apply {
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
        content.addView(
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
        root.addView(content)
        root.addView(
            buildBackgroundSourceSwitch(ctx, density, dark),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun refreshVisibleArea(dark: Boolean) {
        val activity = activityProvider()
        if (activity == null) {
            bumpSelectionVersion()
        } else {
            activity.runOnUiThread {
                bumpSelectionVersion()
            }
        }
        val root = visibleArea?.get() ?: return
        root.post {
            if (currentReaderDark == dark) renderBackgroundArea(root, dark)
        }
    }

    private fun captureContainerRecomposeScope(composer: Any, dark: Boolean) {
        val scope = currentRecomposeScope(composer) ?: return
        if (dark) darkContainerRecomposeScope = scope else lightContainerRecomposeScope = scope
    }

    private fun captureBackgroundRecomposeScope(composer: Any, dark: Boolean) {
        val scope = currentRecomposeScope(composer) ?: return
        if (dark) darkBackgroundRecomposeScope = scope else lightBackgroundRecomposeScope = scope
    }

    private fun capturePanelRecomposeScope(composer: Any, dark: Boolean) {
        val scope = currentRecomposeScope(composer) ?: return
        if (dark) darkPanelRecomposeScope = scope else lightPanelRecomposeScope = scope
    }

    private fun currentRecomposeScope(composer: Any): Any? = runCatching {
        composer.javaClass.methods.firstOrNull {
            it.name == "getRecomposeScope" && it.parameterTypes.isEmpty()
        }?.invoke(composer)
    }.getOrNull()

    private fun invalidateBackgroundComposition(dark: Boolean) {
        val scopes = buildList {
            add(if (dark) darkContainerRecomposeScope else lightContainerRecomposeScope)
            add(if (dark) darkBackgroundRecomposeScope else lightBackgroundRecomposeScope)
            add(if (dark) darkPanelRecomposeScope else lightPanelRecomposeScope)
        }.filterNotNull().distinctBy(System::identityHashCode)
        scopes.forEach { scope ->
            runCatching {
                val invalidate = scope.javaClass.methods.firstOrNull {
                    it.name == "invalidate" && it.parameterTypes.isEmpty()
                } ?: error("Compose RecomposeScope 不可失效")
                invalidate.invoke(scope)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX invalidate Compose scope failed dark=$dark: ${it.stackTraceToString()}")
            }
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
        val selected = isSelectedBackgroundPath(dark, path)
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(decodeThumb(path, w, h))
            clipToOutline = true
            background = roundedRectDrawable(density, dark, selected, themePrimaryColor(dark))
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
                        setColor(themePrimaryColor(dark))
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
                if (!isSelectedBackgroundPath(dark, path)) applyBackground(dark, path)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isSelectedBackgroundPath(dark, path)) clearBackground(dark)
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

    private fun buildBackgroundSourceSwitch(ctx: Context, density: Float, dark: Boolean): View {
        val buttonHeight = (36 * density).toInt()
        val topSpacing = (40 * density).toInt()
        val bottomSpacing = (16 * density).toInt()
        val horizontalSpacing = (16 * density).toInt()
        val gap = (8 * density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalSpacing, topSpacing, horizontalSpacing, bottomSpacing)
            addView(
                buildBackgroundSourceButton(
                    ctx = ctx,
                    text = "混搭",
                    selected = showNativeBackgrounds,
                    enabled = !dark,
                    density = density,
                    dark = dark,
                ) {
                    if (!dark && !showNativeBackgrounds) {
                        showNativeBackgrounds = true
                        visibleArea = null
                        recomposePanel(dark)
                    }
                },
                LinearLayout.LayoutParams(0, buttonHeight, 1f).apply { marginEnd = gap / 2 },
            )
            addView(
                buildBackgroundSourceButton(
                    ctx = ctx,
                    text = "背景",
                    selected = !showNativeBackgrounds,
                    enabled = true,
                    density = density,
                    dark = dark,
                ) {
                    if (showNativeBackgrounds) {
                        showNativeBackgrounds = false
                        recomposePanel(dark)
                    }
                },
                LinearLayout.LayoutParams(0, buttonHeight, 1f).apply { marginStart = gap / 2 },
            )
        }
    }

    private fun recomposePanel(dark: Boolean) {
        val action = {
            bumpSelectionVersion()
        }
        activityProvider()?.runOnUiThread(action) ?: action()
    }

    private fun buildBackgroundSourceButton(
        ctx: Context,
        text: String,
        selected: Boolean,
        enabled: Boolean,
        density: Float,
        dark: Boolean,
        onClick: () -> Unit,
    ): View = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        typeface = resolveGlobalUiTypeface(ctx)
        gravity = android.view.Gravity.CENTER
        setTextColor(
            when {
                !enabled -> if (dark) Color.rgb(100, 100, 100) else Color.rgb(170, 170, 170)
                selected -> themePrimaryColor(dark)
                dark -> Color.rgb(210, 210, 210)
                else -> Color.rgb(80, 80, 80)
            },
        )
        background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(if (dark) Color.rgb(30, 30, 30) else Color.rgb(248, 248, 248))
            setStroke(
                ((if (selected && enabled) 2f else 1f) * density).toInt(),
                when {
                    !enabled -> if (dark) Color.rgb(55, 55, 55) else Color.rgb(220, 220, 220)
                    selected -> themePrimaryColor(dark)
                    dark -> Color.rgb(70, 70, 70)
                    else -> Color.rgb(205, 205, 205)
                },
            )
        }
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        if (enabled) setOnClickListener { onClick() }
    }

    private fun cacheThemePrimaryColor(composer: Any, dark: Boolean) {
        runCatching {
            val materialTheme = cls(MATERIAL_THEME_CLASS).getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .get(null)
            val stable = cls(MATERIAL_THEME_CLASS).getDeclaredField("\$stable")
                .apply { isAccessible = true }
                .getInt(null)
            val colorScheme = cls(MATERIAL_THEME_CLASS).declaredMethods.first {
                it.name == "getColorScheme" && it.parameterTypes.size == 2
            }.apply { isAccessible = true }.invoke(materialTheme, composer, stable)
                ?: error("MaterialTheme.colorScheme unavailable")
            val primary = colorScheme.javaClass.methods.first {
                it.name == PRIMARY_COLOR_METHOD && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(colorScheme) as Long
            val argb = composeColorToArgb(primary)
            if (dark) darkThemePrimaryColor = argb else lightThemePrimaryColor = argb
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX resolve host theme primary failed dark=$dark: ${it.stackTraceToString()}")
        }
    }

    private fun themePrimaryColor(dark: Boolean): Int =
        if (dark) darkThemePrimaryColor else lightThemePrimaryColor

    private fun composeColorToArgb(color: Long): Int =
        runCatching {
            cls(COLOR_KT_CLASS).declaredMethods.firstOrNull { method ->
                method.name.contains("toArgb", ignoreCase = true) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true }?.invoke(null, color) as? Int
        }.getOrNull() ?: if ((color and 0x3fL) == 0L) {
            (color ushr 32).toInt()
        } else {
            DEFAULT_THEME_PRIMARY_COLOR
        }

    private fun resolveGlobalUiTypeface(ctx: Context): Typeface {
        globalUiTypeface?.let { return it }
        val loaded = runCatching {
            Typeface.createFromAsset(ctx.assets, GLOBAL_UI_FONT_ASSET)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX load global UI font failed: ${it.stackTraceToString()}")
        }.getOrNull() ?: Typeface.DEFAULT
        globalUiTypeface = loaded
        return loaded
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
        val uri = Uri.fromFile(file).toString()
        val previousHostBackgroundValue = currentHostBackgroundValue
        setSelectionOverride(dark, path)
        currentHostBackgroundValue = uri
        refreshVisibleArea(dark)
        backgroundExecutor.execute {
            // 侧载图片独占正文背景：按宿主异步完成顺序清空颜色、材质，再写入图片 URI。
            updateHostPreferencesInOrder(
                updates = listOf(
                    themePreferenceKey to "",
                    mipmapPreferenceKey to "",
                    backgroundPreferenceKey to uri,
                ),
                onSuccess = {
                    settings.setReaderBgCurrent(dark, path)
                    currentHostBackgroundValue = uri
                    refreshVisibleArea(dark)
                    XposedBridge.log("$LOG_PREFIX selected reader background via host state dark=$dark path=$path")
                },
                onFailure = { error ->
                    currentHostBackgroundValue = previousHostBackgroundValue
                    clearSelectionOverride(dark, path)
                    refreshVisibleArea(dark)
                    showToast("应用阅读背景失败")
                    XposedBridge.log("$LOG_PREFIX apply background failed: ${error.stackTraceToString()}")
                },
            )
        }
    }

    private fun clearBackground(dark: Boolean) {
        val defaultPath = builtInBackgroundPath(dark)
        setSelectionOverride(dark, defaultPath)
        refreshVisibleArea(dark)
        backgroundExecutor.execute {
            runCatching {
                settings.setReaderBgCurrent(dark, defaultPath)
                updateHostBackground(
                    defaultPath.takeIf { it.isNotBlank() && File(it).isFile }
                        ?.let { Uri.fromFile(File(it)).toString() }
                        .orEmpty(),
                )
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
        val selected = isSelectedBackgroundPath(dark, path)
        backgroundExecutor.execute {
            runCatching {
                settings.setReaderBgImages(dark, list)
                if (selected) {
                    settings.setReaderBgCurrent(dark, "")
                    currentHostBackgroundValue = ""
                    updateHostBackground("")
                }
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
                val hostContext = (context.applicationContext ?: context).also {
                    backgroundHostContext = it
                }
                val dir = File(hostContext.filesDir, BACKGROUND_DIR_NAME).apply { mkdirs() }
                val light = ReaderBackgroundAssets.copyTo(
                    dark = false,
                    target = File(dir, BUILTIN_LIGHT_FILE_NAME),
                )
                val dark = ReaderBackgroundAssets.copyTo(
                    dark = true,
                    target = File(dir, BUILTIN_DARK_FILE_NAME),
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

    /**
     * 取得包住宿主背景绘制内容的分支 lambda，深浅各一个且**实例固定**。
     *
     * `ThemeToggleContent` 用 `Composer.changedInstance(darkContent)` 判断参数是否变化，
     * 每次 hook 新建代理会被判定为「变了」而不断重组。实例只建一次，宿主每次传来的内容存进可变引用。
     */
    private fun backgroundBranchLambda(hostLambda: Any, dark: Boolean): Any {
        val index = if (dark) 1 else 0
        backgroundBranchHosts[index] = hostLambda
        backgroundBranchLambdas[index]?.let { return it }
        return backgroundComposableLambda({ backgroundBranchHosts[index] }, dark)
            .also { backgroundBranchLambdas[index] = it }
    }

    /** 把宿主的背景绘制内容包一层，记录当前正在渲染的是哪个主题分支。 */
    private fun backgroundComposableLambda(hostProvider: () -> Any?, dark: Boolean): Any {
        val fnClass = cls(FUNCTION2_CLASS)
        return Proxy.newProxyInstance(classLoader, arrayOf(fnClass)) { _, method, args ->
            when (method.name) {
                "invoke" -> runCatching {
                    val hostLambda = hostProvider() ?: return@runCatching targetUnit()
                    currentReaderDark = dark
                    activeBackgroundBranchDark = dark
                    val composer = args?.getOrNull(0)
                    observeSelectionVersion()
                    composer?.let { captureBackgroundRecomposeScope(it, dark) }
                    try {
                        hostLambda.javaClass.methods.first {
                            it.name == "invoke" && it.parameterTypes.size == 2
                        }.invoke(hostLambda, args?.getOrNull(0), Integer.valueOf(0))
                    } finally {
                        activeBackgroundBranchDark = null
                    }
                }.getOrElse {
                    activeBackgroundBranchDark = null
                    targetUnit()
                }
                "toString" -> "ReaderBgComposable(dark=$dark)"
                "hashCode" -> if (dark) 0x5245414D else 0x5245414C
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

    private fun roundedRectDrawable(
        density: Float,
        dark: Boolean,
        selected: Boolean,
        selectedColor: Int,
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 10 * density
        setColor(if (dark) Color.rgb(38, 38, 38) else Color.rgb(238, 238, 238))
        val borderColor = if (selected) selectedColor
        else if (dark) Color.rgb(70, 70, 70)
        else Color.rgb(205, 205, 205)
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

    private fun isSideLoadedBackgroundUri(uri: String): Boolean {
        val path = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty()
        if (path.isBlank()) return false
        val canonicalPath = canonicalPathOrSelf(path)
        return sideLoadedBackgroundPaths().any { canonicalPathOrSelf(it) == canonicalPath }
    }

    private fun isSelectedBackgroundPath(dark: Boolean, path: String): Boolean {
        if (path.isBlank()) return false
        val selectedPath = selectedBackgroundPath(dark)
        if (selectedPath.isBlank()) return false
        return canonicalPathOrSelf(path) == canonicalPathOrSelf(selectedPath)
    }

    private fun sideLoadedBackgroundPaths(): List<String> = buildList {
        add(builtInBackgroundPath(dark = false))
        add(builtInBackgroundPath(dark = true))
        addAll(settingsProvider().readerBgImages(dark = false))
        addAll(settingsProvider().readerBgImages(dark = true))
    }.filter(String::isNotBlank)

    private fun selectedBackgroundPath(dark: Boolean): String =
        (if (dark) darkSelectionOverride else lightSelectionOverride)
            ?: settingsProvider().readerBgCurrent(dark)

    private fun setSelectionOverride(dark: Boolean, path: String) {
        if (dark) darkSelectionOverride = path else lightSelectionOverride = path
    }

    private fun clearSelectionOverrides() {
        lightSelectionOverride = null
        darkSelectionOverride = null
    }

    private fun clearSelectionOverride(dark: Boolean, expected: String) {
        if (dark) {
            if (darkSelectionOverride == expected) darkSelectionOverride = null
        } else if (lightSelectionOverride == expected) {
            lightSelectionOverride = null
        }
    }

    private fun onReaderThemeChanged(dark: Boolean) {
        val selectedUri = selectedSideLoadedBackgroundUri(dark)
        showNativeBackgrounds = !dark && selectedUri.isBlank()
        invalidateHostBackgroundState(dark)
        invalidateBackgroundComposition(dark)
        recomposePanel(dark)
        XposedBridge.log(
            "$LOG_PREFIX reader theme background switched dark=$dark sideLoaded=${selectedUri.isNotBlank()}",
        )
    }

    private fun hasSelectedSideLoadedBackground(dark: Boolean): Boolean =
        selectedSideLoadedBackgroundUri(dark).isNotBlank()

    private fun selectedSideLoadedBackgroundUri(dark: Boolean): String =
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
        backgroundHostContext
            ?.let { File(it.filesDir, BACKGROUND_DIR_NAME) }
            ?: activityProvider()?.applicationContext?.let {
                backgroundHostContext = it
                File(it.filesDir, BACKGROUND_DIR_NAME)
            }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val BACKGROUND_DIR_NAME = "reamicro-reader-bg"
        const val GLOBAL_UI_FONT_ASSET =
            "composeResources/reamicro.composeapp.generated.resources/font/serif_medium.ttf"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val PRIMARY_COLOR_METHOD = "getPrimary-0d7_KjU"
        const val DEFAULT_THEME_PRIMARY_COLOR = -42465
        const val BUILTIN_LIGHT_FILE_NAME = "reader_background_default_light.jpg"
        const val BUILTIN_DARK_FILE_NAME = "reader_background_default_dark.png"
        const val HOST_REFRESH_TOKEN_PREFIX = "reamicro-bg-refresh://"
        const val READER_THEMES_KT_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderThemesKt"
        const val READER_THEMES_SINGLETONS_CLASS =
            "app.zhendong.reamicro.ui.reader.compose.ComposableSingletons\$ReaderThemesKt"
        const val DARK_THEME_CONTENT_METHOD = "lambda__1576463935\$lambda\$0"
        const val READER_THEMES_CONTENT_ACCESS_METHOD = "access\$ReaderThemesContent"
        const val NATIVE_READER_THEMES_CONTENT_METHOD = "ReaderThemesContent"
        const val NATIVE_MIX_CONTENT_METHOD = "ReaderThemesContent\$lambda\$15\$0"
        const val NATIVE_READER_MIPMAP_METHOD = "ReaderMipmap"
        const val NATIVE_READER_THEME_METHOD = "ReaderThemes"
        const val SIDE_LOADED_SELECTION_SENTINEL = "reamicro://side-loaded"
        const val READER_BACKGROUND_PREFIX = "ReaderBackground-"
        const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"
        const val MUTABLE_STATE_CLASS = "androidx.compose.runtime.MutableState"
        const val ANIMATED_VISIBILITY_KT_CLASS = "androidx.compose.animation.AnimatedVisibilityKt"
        const val ANIMATED_VISIBILITY_METHOD = "AnimatedVisibility"
        const val PREF_KEYS_CLASS = "app.zhendong.reamicro.constants.PrefKeys"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val KOTLIN_COROUTINE_SUSPENDED_NAME = "COROUTINE_SUSPENDED"
        const val KOTLIN_RESULT_FAILURE_CLASS = "kotlin.Result\$Failure"
        const val SESSION_CLASS = "app.zhendong.reamicro.repository.core.Session"
        const val USER_STORAGE_CLASS = "app.zhendong.reamicro.arch.fs.UserStorage"
        const val EPUB_CONTAINER_KT_CLASS = "app.zhendong.reamicro.ui.reader.components.EpubContainerKt"
        const val EPUB_CONTAINER_SINGLETONS_CLASS =
            "app.zhendong.reamicro.ui.reader.components.ComposableSingletons\$EpubContainerKt"
        const val EPUB_CONTAINER_METHOD = "EpubContainer"
        const val EPUB_BACKGROUND_METHOD = "EpubBackground"
        const val EPUB_BACKGROUND_STATE_VALUE_METHOD = "rememberEpubBackgroundState"
        const val COMPOSE_STATE_CLASS = "androidx.compose.runtime.State"
        const val EPUB_DRAW_BACKGROUND_ARG_INDEX = 3
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
