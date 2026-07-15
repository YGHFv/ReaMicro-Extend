package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.math.roundToInt

class ReaderAutoPageHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val methodCache = HashMap<String, Method>()
    private val settingsDepth = ThreadLocal.withInitial { 0 }
    private val injectedInCurrentSettings = ThreadLocal.withInitial { false }
    private val settingsRowCount = ThreadLocal.withInitial { 0 }
    private val settingsRowDepth = ThreadLocal.withInitial { 0 }
    private val settingsRowTopLevelStack = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    private val renderingAutoPageSection = ThreadLocal.withInitial { false }

    private var currentIntentReceiverRef: WeakReference<Any>? = null
    private var currentViewModelRef: WeakReference<Any>? = null
    private var runningState: Any? = null
    private var timerHoursState: Any? = null
    private var intervalSecondsState: Any? = null
    private var autoPageRunnable: Runnable? = null
    private var autoPageEndAtMs: Long = Long.MAX_VALUE
    private var autoPageEnabled: Boolean = false
    private var pausedForReaderExit: Boolean = false
    private var pausedRemainingMs: Long = Long.MAX_VALUE

    fun install() {
        hookReaderViewModel()
        hookTapGesturesBox()
        hookReaderSettings()
    }

    private fun hookReaderViewModel() {
        runCatching {
            val cls = cls(READER_VIEW_MODEL_CLASS)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentViewModelRef = WeakReference(param.thisObject)
                    resumeAutoPageAfterReaderReturn()
                }
            })
            XposedBridge.hookAllMethods(cls, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentViewModelRef?.get() === param.thisObject) {
                        currentViewModelRef = null
                        currentIntentReceiverRef = null
                    }
                    pauseAutoPageForReaderExit()
                }
            })
            XposedBridge.log("$LOG_PREFIX Reader auto page ViewModel hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX Reader auto page ViewModel hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookTapGesturesBox() {
        runCatching {
            cls(TAP_GESTURES_BOX_CLASS).declaredMethods
                .filter { it.name == TAP_GESTURES_BOX_METHOD && it.parameterTypes.size == 4 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.getOrNull(0)?.let { currentIntentReceiverRef = WeakReference(it) }
                            resumeAutoPageAfterReaderReturn()
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX Reader auto page TapGesturesBox hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX Reader auto page TapGesturesBox hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookReaderSettings() {
        runCatching {
            val settingsClass = cls(READER_SETTINGS_CLASS)
            val composerClass = cls(COMPOSER_CLASS)
            var scopeHookCount = 0
            settingsClass.declaredMethods
                .filter {
                    isReaderSettingsScopeMethod(it, composerClass)
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val depth = settingsDepth.get()
                            if (depth == 0) {
                                injectedInCurrentSettings.set(false)
                                settingsRowCount.set(0)
                                settingsRowDepth.set(0)
                                settingsRowTopLevelStack.get().clear()
                            }
                            settingsDepth.set(depth + 1)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            settingsDepth.set((settingsDepth.get() - 1).coerceAtLeast(0))
                        }
                    })
                    scopeHookCount++
                }
            if (scopeHookCount == 0) {
                XposedBridge.log("$LOG_PREFIX Reader auto page settings scope hook not found")
            }

            method(TEXT_KT_CLASS, TEXT_METHOD, 22).let { textMethod ->
                XposedBridge.hookMethod(textMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (renderingAutoPageSection.get()) return
                        val text = param.args.getOrNull(0) as? String ?: return
                        if (text !in DISPLAY_TITLE_CANDIDATES) return
                        if (!canRunAutoPage()) return
                        val scopedByHook = settingsDepth.get() > 0
                        if (!scopedByHook && !isReaderSettingsStack()) return
                        if (injectedInCurrentSettings.get()) return
                        val composer = param.args.getOrNull(18) ?: return
                        injectedInCurrentSettings.set(true)
                        renderingAutoPageSection.set(true)
                        try {
                            renderAutoPageSection(composer)
                        } finally {
                            renderingAutoPageSection.set(false)
                        }
                        param.args[1] = sectionTitleModifier(
                            top = NATIVE_TYPE_GROUP_TOP_PADDING,
                            bottom = NATIVE_SECTION_TITLE_BOTTOM_PADDING,
                        )
                    }
                })
            }
            method(ROW_KT_CLASS, ROW_METHOD, 7).let { rowMethod ->
                XposedBridge.hookMethod(rowMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (settingsDepth.get() <= 0) return
                        val depth = settingsRowDepth.get()
                        settingsRowTopLevelStack.get().addLast(depth == 0)
                        settingsRowDepth.set(depth + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (settingsDepth.get() <= 0) return
                        val depth = (settingsRowDepth.get() - 1).coerceAtLeast(0)
                        settingsRowDepth.set(depth)
                        val isTopLevel = settingsRowTopLevelStack.get().removeLastOrNull() == true
                        if (!isTopLevel || injectedInCurrentSettings.get()) return
                        val rowIndex = settingsRowCount.get()
                        settingsRowCount.set(rowIndex + 1)
                        if (rowIndex == 0) {
                            if (!canRunAutoPage()) return
                            val composer = param.args.getOrNull(4) ?: return
                            injectedInCurrentSettings.set(true)
                            renderAutoPageSection(composer)
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX Reader auto page settings UI hook installed ($scopeHookCount scope hooks)")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX Reader auto page settings UI hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun isReaderSettingsScopeMethod(method: Method, composerClass: Class<*>): Boolean {
        val parameterTypes = method.parameterTypes
        // 阅微 2.2.0 起 Settings 的签名从 (Composer, Int) 变为 (Function0, Composer, Int)，
        // ReaderSettings$lambda$2 从 5 参变为 6 参 (…, Composer, Int)。
        // 不再写死参数个数/位置，只要求“末两参为 (Composer, Int)”即可匹配，抗后续版本变动。
        val endsWithComposerInt = parameterTypes.size >= 2 &&
            parameterTypes[parameterTypes.size - 2] == composerClass &&
            parameterTypes[parameterTypes.size - 1] == Int::class.javaPrimitiveType
        if (!endsWithComposerInt) return false
        return method.name == READER_SETTINGS_PRIVATE_METHOD ||
            method.name == READER_SETTINGS_CONTENT_LAMBDA_METHOD
    }

    private fun renderAutoPageSection(composer: Any) {
        if (!canRunAutoPage()) return
        runCatching {
            ensureUiStates()
            log("render auto page section")
            val rowContent = functionProxy("AutoPageRow", FUNCTION3_CLASS) { args ->
                val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
                val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
                renderTimerSlider(rowScope, innerComposer)
                renderStartStopButton(innerComposer)
                renderIntervalSlider(rowScope, innerComposer)
                targetUnit()
            }
            method(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
                null,
                fillMaxWidth(sectionTitleModifier(top = AUTO_PAGE_SECTION_TOP_PADDING, bottom = 0)),
                spacedBy(20),
                alignmentTop(),
                rowContent,
                composer,
                0,
                0,
            )
        }.onFailure {
            log("render auto page section failed: ${it.stackTraceToString()}")
        }
    }

    private fun renderTimerSlider(rowScope: Any, composer: Any) {
        val value = displayedTimerHours()
        val content = functionProxy("AutoPageTimerColumn", FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderCompactLabel("\u5b9a\u65f6", innerComposer)
            renderSlider(
                value = (value / MAX_TIMER_HOURS).coerceIn(0f, 1f),
                label = formatHours(value),
                minText = "0",
                maxText = "5h",
                steps = 49,
                composer = innerComposer,
            ) { normalized ->
                val next = ((normalized.coerceIn(0f, 1f) * MAX_TIMER_HOURS) * 10f).roundToInt() / 10f
                setTimerHours(next)
            }
            targetUnit()
        }
        renderColumn(weightModifier(rowScope), composer, content)
    }

    private fun renderIntervalSlider(rowScope: Any, composer: Any) {
        val value = intervalSeconds()
        val content = functionProxy("AutoPageIntervalColumn", FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderCompactLabel("\u95f4\u9694", innerComposer)
            renderSlider(
                value = ((value - MIN_INTERVAL_SECONDS).toFloat() / (MAX_INTERVAL_SECONDS - MIN_INTERVAL_SECONDS)).coerceIn(0f, 1f),
                label = value.toString(),
                minText = "5s",
                maxText = "30s",
                steps = MAX_INTERVAL_SECONDS - MIN_INTERVAL_SECONDS - 1,
                composer = innerComposer,
            ) { normalized ->
                val next = MIN_INTERVAL_SECONDS + (normalized.coerceIn(0f, 1f) * (MAX_INTERVAL_SECONDS - MIN_INTERVAL_SECONDS)).roundToInt()
                setIntervalSeconds(next)
            }
            targetUnit()
        }
        renderColumn(weightModifier(rowScope), composer, content)
    }

    private fun renderStartStopButton(composer: Any) {
        val running = isRunningForUi()
        val content = functionProxy("AutoPageButtonBox", FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderIcon(
                imageVector = autoPageIcon(running),
                composer = innerComposer,
                modifier = sizeModifier(modifierInstance(), AUTO_PAGE_BUTTON_SIZE),
                color = onBackgroundVariant(innerComposer),
            )
            targetUnit()
        }
        method(BOX_KT_CLASS, BOX_METHOD, 7).invoke(
            null,
            circleButtonModifier(composer),
            alignmentCenter(),
            false,
            content,
            composer,
            3072,
            0,
        )
    }

    private fun renderColumn(modifier: Any, composer: Any, content: Any) {
        method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
            null,
            modifier,
            arrangementTop(),
            alignmentStart(),
            content,
            composer,
            0,
            0,
        )
    }

    private fun renderCompactLabel(text: String, composer: Any) {
        renderText(
            text = text,
            composer = composer,
            modifier = sectionTitleModifier(top = 0, bottom = 6),
            styleName = "getBodySmall",
            color = onBackgroundVariant(composer),
        )
    }

    private fun renderSlider(
        value: Float,
        label: String,
        minText: String,
        maxText: String,
        steps: Int,
        composer: Any,
        onChange: (Float) -> Unit,
    ) {
        val onValueChange = functionProxy("AutoPageSliderChange", FUNCTION1_CLASS) { args ->
            val raw = args?.getOrNull(0) as? Float ?: return@functionProxy targetUnit()
            onChange(raw)
            targetUnit()
        }
        val onFinished = functionProxy("AutoPageSliderFinished", FUNCTION0_CLASS) {
            targetUnit()
        }
        method(TYPE_SETTING_SLIDER_CLASS, TYPE_SETTING_SLIDER_METHOD, 20).invoke(
            null,
            value,
            onValueChange,
            fillMaxWidth(modifierInstance()),
            false,
            null,
            steps,
            onFinished,
            udp(34),
            minText,
            maxText,
            label,
            udp(16.5),
            sliderColors(composer),
            null,
            false,
            false,
            composer,
            1573248,
            0,
            57368,
        )
    }

    private fun renderIcon(imageVector: Any, composer: Any, modifier: Any?, color: Long) {
        iconMethod(imageVector).invoke(
            null,
            imageVector,
            null,
            modifier,
            color,
            composer,
            48,
            0,
        )
    }

    private fun renderText(text: String, composer: Any, modifier: Any?, styleName: String, color: Long) {
        method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
            null,
            text,
            modifier,
            color,
            null,
            0L,
            null,
            null,
            null,
            0L,
            null,
            null,
            0L,
            0,
            false,
            0,
            0,
            null,
            typography(composer).method0(styleName),
            composer,
            0,
            0,
            TEXT_DEFAULT_MASK,
        )
    }

    private fun ensureUiStates() {
        val prefs = prefs()
        if (timerHoursState == null) {
            timerHoursState = mutableState(prefs.getFloat(KEY_TIMER_HOURS, DEFAULT_TIMER_HOURS))
        }
        if (intervalSecondsState == null) {
            intervalSecondsState = mutableState(prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS))
        }
        if (runningState == null) {
            runningState = mutableState(false)
        }
    }

    private fun timerHours(): Float =
        ((timerHoursState?.method0("getValue") as? Float) ?: prefs().getFloat(KEY_TIMER_HOURS, DEFAULT_TIMER_HOURS))
            .coerceIn(0f, MAX_TIMER_HOURS)

    private fun intervalSeconds(): Int =
        ((intervalSecondsState?.method0("getValue") as? Int) ?: prefs().getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS))
            .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

    private fun isRunning(): Boolean =
        autoPageEnabled

    private fun isRunningForUi(): Boolean =
        runningState?.method0("getValue") as? Boolean ?: autoPageEnabled

    private fun displayedTimerHours(): Float {
        if (!autoPageEnabled) return timerHours()
        val remainingMs = when {
            pausedForReaderExit && pausedRemainingMs != Long.MAX_VALUE -> pausedRemainingMs
            autoPageEndAtMs != Long.MAX_VALUE -> (autoPageEndAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            else -> Long.MAX_VALUE
        }
        if (remainingMs == Long.MAX_VALUE) return 0f
        return ((remainingMs.toFloat() / (60f * 60f * 1000f)) * 10f).roundToInt() / 10f
    }

    private fun setTimerHours(value: Float) {
        val next = value.coerceIn(0f, MAX_TIMER_HOURS)
        setState(timerHoursState, next)
        prefs().edit().putFloat(KEY_TIMER_HOURS, next).apply()
        if (isRunning()) startAutoPage(next, intervalSeconds(), showToast = false, closeSettings = false)
    }

    private fun setIntervalSeconds(value: Int) {
        val next = value.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        setState(intervalSecondsState, next)
        prefs().edit().putInt(KEY_INTERVAL_SECONDS, next).apply()
        if (isRunning()) startAutoPage(timerHours(), next, showToast = false, closeSettings = false)
    }

    private fun toggleAutoPage() {
        if (isRunning()) {
            log("auto page stop by toggle")
            stopAutoPage(updateUiState = true, showToast = true)
        } else {
            if (!canRunAutoPage()) {
                activityProvider()?.let {
                    Toast.makeText(it, "\u8bf7\u5148\u5728\u9605\u5fae\u8865\u5168\u8bbe\u7f6e\u4e2d\u542f\u7528\u81ea\u52a8\u9605\u8bfb", Toast.LENGTH_SHORT).show()
                }
                return
            }
            log("auto page start by toggle timer=${timerHours()} interval=${intervalSeconds()}")
            startAutoPage(timerHours(), intervalSeconds(), showToast = true, closeSettings = true)
        }
    }

    private fun startAutoPage(
        timerHours: Float,
        intervalSeconds: Int,
        showToast: Boolean,
        closeSettings: Boolean,
    ) {
        if (!canRunAutoPage()) {
            stopAutoPage(updateUiState = true, showToast = false)
            return
        }
        mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
        autoPageRunnable = null
        autoPageEnabled = true
        pausedForReaderExit = false
        pausedRemainingMs = Long.MAX_VALUE
        autoPageEndAtMs = if (timerHours > 0f) {
            System.currentTimeMillis() + (timerHours * 60f * 60f * 1000f).toLong()
        } else {
            Long.MAX_VALUE
        }
        setState(runningState, true)
        scheduleNextTurn(intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS))
        if (closeSettings) closeReaderSettings()
        if (showToast) {
            activityProvider()?.let {
                Toast.makeText(it, "\u5df2\u5f00\u59cb\u81ea\u52a8\u7ffb\u9875", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scheduleNextTurn(intervalSeconds: Int) {
        val runnable = Runnable {
            if (!isRunning() || pausedForReaderExit) return@Runnable
            if (!canRunAutoPage()) {
                log("auto page stopped because module setting is disabled")
                stopAutoPage(updateUiState = true, showToast = false)
                return@Runnable
            }
            if (System.currentTimeMillis() >= autoPageEndAtMs) {
                log("auto page timer ended")
                stopAutoPage(updateUiState = true, showToast = false)
                return@Runnable
            }
            log("auto page interval tick")
            triggerNextPage()
            if (isRunning()) scheduleNextTurn(intervalSeconds())
        }
        autoPageRunnable = runnable
        mainHandler.postAtTime(runnable, AUTO_PAGE_TOKEN, SystemClock.uptimeMillis() + intervalSeconds * 1000L)
    }

    private fun stopAutoPage(updateUiState: Boolean, showToast: Boolean = false) {
        mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
        autoPageRunnable = null
        autoPageEndAtMs = Long.MAX_VALUE
        autoPageEnabled = false
        pausedForReaderExit = false
        pausedRemainingMs = Long.MAX_VALUE
        if (updateUiState) setState(runningState, false)
        if (showToast) {
            activityProvider()?.let {
                Toast.makeText(it, "\u5df2\u505c\u6b62\u81ea\u52a8\u7ffb\u9875", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerNextPage() {
        val intent = cls(TAP_DIRECTION_CLASS)
            .getConstructor(Long::class.javaPrimitiveType)
            .newInstance(System.currentTimeMillis())
        if (!dispatchReaderIntent(intent, "page turn")) {
            XposedBridge.log("$LOG_PREFIX auto page skipped: no reader intent receiver")
            stopAutoPage(updateUiState = true)
        }
    }

    private fun closeReaderSettings() {
        runCatching {
            val status = staticObject(UI_STATUS_READER_CLASS, "INSTANCE")
            val intent = cls(UPDATE_UI_STATUS_CLASS)
                .getConstructor(cls(UI_STATUS_CLASS))
                .newInstance(status)
            dispatchReaderIntent(intent, "settings close")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX auto page close settings failed: ${it.stackTraceToString()}")
        }
    }

    private fun dispatchReaderIntent(intent: Any, action: String): Boolean {
        val target = currentIntentReceiverRef?.get() ?: currentViewModelRef?.get() ?: return false
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == "intent" && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(intent.javaClass)
            } ?: target.javaClass.methods.firstOrNull {
                it.name == "onIntent" && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(intent.javaClass)
            } ?: error("intent/onIntent not found on ${target.javaClass.name}")
            method.invoke(target, intent)
            log("auto page $action dispatched via ${target.javaClass.name}.${method.name}")
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX auto page $action failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    private fun pauseAutoPageForReaderExit() {
        if (!autoPageEnabled) return
        pausedRemainingMs = if (autoPageEndAtMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            (autoPageEndAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        pausedForReaderExit = true
        mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
        autoPageRunnable = null
        log("auto page paused for reader exit remaining=$pausedRemainingMs")
    }

    private fun resumeAutoPageAfterReaderReturn() {
        if (!autoPageEnabled || !pausedForReaderExit) return
        if (!canRunAutoPage()) {
            stopAutoPage(updateUiState = true, showToast = false)
            return
        }
        pausedForReaderExit = false
        autoPageEndAtMs = if (pausedRemainingMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() + pausedRemainingMs
        }
        pausedRemainingMs = Long.MAX_VALUE
        setState(runningState, true)
        scheduleNextTurn(intervalSeconds().coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS))
        log("auto page resumed after reader return interval=${intervalSeconds()}")
    }

    private fun setState(state: Any?, value: Any) {
        state?.javaClass?.methods?.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
    }

    private fun canRunAutoPage(): Boolean =
        settingsProvider().canRunReaderAutoPage

    private fun mutableState(value: Any): Any =
        method(SNAPSHOT_STATE_KT_CLASS, MUTABLE_STATE_OF_DEFAULT_METHOD, 4).invoke(null, value, null, 2, null)

    private fun circleButtonModifier(composer: Any): Any {
        val positioned = method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
            null,
            modifierInstance(),
            0f,
            udp(AUTO_PAGE_BUTTON_TOP_PADDING),
            0f,
            0f,
            13,
            null,
        )
        return method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, 9).invoke(
            null,
            sizeModifier(positioned, AUTO_PAGE_BUTTON_SIZE),
            null,
            null,
            false,
            null,
            null,
            functionProxy("AutoPageToggle", FUNCTION0_CLASS) {
                toggleAutoPage()
                targetUnit()
            },
            28,
            null,
        )
    }

    private fun sectionTitleModifier(top: Int, bottom: Int): Any =
        method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
            null,
            modifierInstance(),
            0f,
            udp(top),
            0f,
            udp(bottom),
            5,
            null,
        )

    private fun weightModifier(rowScope: Any): Any =
        method(ROW_SCOPE_CLASS, WEIGHT_DEFAULT_METHOD, 6).invoke(null, rowScope, modifierInstance(), 1.0f, false, 2, null)

    private fun sizeModifier(modifier: Any, value: Int): Any =
        method(SIZE_KT_CLASS, SIZE_METHOD, 2).invoke(null, modifier, udp(value))

    private fun fillMaxWidth(modifier: Any): Any =
        method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(null, modifier, 0f, 1, null)

    private fun sliderColors(composer: Any): Any {
        val defaults = staticObject(MATERIAL_SLIDER_DEFAULTS_CLASS, "INSTANCE")
        return method(MATERIAL_SLIDER_DEFAULTS_CLASS, SLIDER_DEFAULT_COLORS_METHOD, 14).invoke(
            defaults,
            sliderBrushColor(backgroundBright(composer)),
            null,
            sliderBrushColor(borderVariant(composer)),
            null,
            sliderBrushColor(backgroundDim(composer)),
            null,
            sliderBrushColor(transparentColor()),
            sliderBrushColor(transparentColor()),
            null,
            null,
            composer,
            0,
            6,
            810,
        )
    }

    private fun colorScheme(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
    }

    private fun typography(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getTypography", 2).invoke(materialTheme, composer, stable)
    }

    private fun onBackgroundVariant(composer: Any): Long =
        method(THEME_KT_CLASS, ON_BACKGROUND_VARIANT_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun backgroundDim(composer: Any): Long =
        method(THEME_KT_CLASS, BACKGROUND_DIM_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun backgroundBright(composer: Any): Long =
        method(THEME_KT_CLASS, BACKGROUND_BRIGHT_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun borderVariant(composer: Any): Long =
        method(THEME_KT_CLASS, BORDER_VARIANT_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun transparentColor(): Long {
        val color = staticObject(COLOR_CLASS, "INSTANCE")
        return color.javaClass.methods.first {
            it.parameterTypes.isEmpty() &&
                it.name.normalizedMethodToken().contains(TRANSPARENT_COLOR_METHOD.normalizedMethodToken())
        }.invoke(color) as Long
    }

    private fun sliderBrushColor(color: Long): Any =
        cls(SLIDER_BRUSH_COLOR_CLASS).declaredConstructors.first {
            it.parameterTypes.size == 3 && it.parameterTypes[0] == Long::class.javaPrimitiveType
        }.apply {
            isAccessible = true
        }.newInstance(color, null, null)

    private fun autoPageIcon(running: Boolean): Any {
        val evaIcons = staticObject(EVA_ICONS_CLASS, "INSTANCE")
        val fillGroup = method(EVA_FILL_KT_CLASS, EVA_FILL_METHOD, 1).invoke(null, evaIcons)
        return if (running) {
            method(EVA_PAUSE_CIRCLE_KT_CLASS, EVA_PAUSE_CIRCLE_METHOD, 1).invoke(null, fillGroup)
        } else {
            method(EVA_PLAY_CIRCLE_KT_CLASS, EVA_PLAY_CIRCLE_METHOD, 1).invoke(null, fillGroup)
        }
    }

    private fun spacedBy(value: Int): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").javaClass.methods.first {
            it.name == SPACED_BY_METHOD && it.parameterTypes.size == 1
        }.invoke(staticObject(ARRANGEMENT_CLASS, "INSTANCE"), udp(value))

    private fun arrangementTop(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getTop") ?: error("Arrangement.Top not found")

    private fun alignmentStart(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getStart") ?: error("Alignment.Start not found")

    private fun alignmentCenter(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getCenter") ?: error("Alignment.Center not found")

    private fun alignmentCenterVertically(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getCenterVertically") ?: error("Alignment.CenterVertically not found")

    private fun alignmentTop(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getTop") ?: error("Alignment.Top not found")

    private fun modifierInstance(): Any =
        staticObject(MODIFIER_CLASS, "INSTANCE")

    private fun prefs() =
        (activityProvider() ?: error("Activity unavailable"))
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun udp(value: Int): Float =
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun udp(value: Double): Float =
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Double::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun staticObject(className: String, fieldName: String): Any {
        val targetClass = cls(className)
        readStaticField(targetClass, fieldName)?.let { return it }
        readStaticField(targetClass, "Companion")?.let { return it }
        runCatching {
            val companionClass = cls("$className\$Companion")
            readStaticField(companionClass, fieldName) ?: readStaticField(companionClass, "\$\$INSTANCE")
        }.getOrNull()?.let { return it }

        val fields = targetClass.declaredFields.joinToString { it.name }
        error("$className.$fieldName not found; fields=[$fields]")
    }

    private fun staticInt(className: String, fieldName: String): Int =
        readStaticField(cls(className), fieldName) as? Int
            ?: error("$className.$fieldName not found")

    private fun readStaticField(targetClass: Class<*>, fieldName: String): Any? =
        targetClass.fields.firstOrNull { it.name == fieldName }?.get(null)
            ?: targetClass.declaredFields.firstOrNull { it.name == fieldName }?.let {
                it.isAccessible = true
                it.get(null)
            }

    private fun Any.method0(name: String): Any? =
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(this)

    private fun Any.longMethod(name: String): Long =
        method0(name) as Long

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = cls(functionClassName)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching {
                    block(args)
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed in $name callback: ${it.stackTraceToString()}")
                }.getOrElse { targetUnit() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun targetUnit(): Any =
        staticObject(UNIT_CLASS, "INSTANCE")

    private fun isReaderSettingsStack(): Boolean =
        Thread.currentThread().stackTrace.any {
            it.className == READER_SETTINGS_CLASS &&
                (it.methodName == READER_SETTINGS_PRIVATE_METHOD ||
                    it.methodName == READER_SETTINGS_CONTENT_LAMBDA_METHOD ||
                    it.methodName.startsWith("ReaderSettings"))
        }

    private fun log(message: String) {
        XposedBridge.log("$LOG_PREFIX $message")
        Log.i(ANDROID_LOG_TAG, message)
    }

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                val normalizedName = methodName.normalizedMethodToken()
                cls(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                } ?: cls(className).declaredMethods.firstOrNull {
                    it.parameterTypes.size == parameterCount &&
                        it.name.normalizedMethodToken().contains(normalizedName)
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun iconMethod(imageVector: Any): Method =
        cls(ICON_KT_CLASS).declaredMethods.firstOrNull {
            it.parameterTypes.size == 7 && it.parameterTypes[0].isAssignableFrom(imageVector.javaClass)
        }?.apply { isAccessible = true } ?: error("$ICON_KT_CLASS Icon(ImageVector)/7 not found")

    private fun String.normalizedMethodToken(): String =
        filter { it.isLetterOrDigit() }

    private fun formatHours(value: Float): String =
        String.format(Locale.US, "%.1f", value)

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val ANDROID_LOG_TAG = "ReaMicroAutoPage"

        const val READER_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
        const val READER_SETTINGS_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderSettingsKt"
        const val READER_SETTINGS_PRIVATE_METHOD = "Settings"
        const val READER_SETTINGS_CONTENT_LAMBDA_METHOD = "ReaderSettings\$lambda\$2"
        const val TAP_GESTURES_BOX_CLASS = "app.zhendong.reamicro.ui.reader.components.TapGesturesBoxKt"
        const val TAP_GESTURES_BOX_METHOD = "TapGesturesBox"
        const val TAP_DIRECTION_CLASS = "app.zhendong.reamicro.ui.reader.ReaderUiIntent\$TapDirection"
        const val UPDATE_UI_STATUS_CLASS = "app.zhendong.reamicro.ui.reader.ReaderUiIntent\$UpdateUIStatus"
        const val UI_STATUS_CLASS = "app.zhendong.reamicro.ui.reader.UiStatus"
        const val UI_STATUS_READER_CLASS = "app.zhendong.reamicro.ui.reader.UiStatus\$Reader"

        const val TEXT_KT_CLASS = "androidx.compose.material3.TextKt"
        const val TEXT_METHOD = "Text-Nvy7gAk"
        const val ROW_KT_CLASS = "androidx.compose.foundation.layout.RowKt"
        const val ROW_METHOD = "Row"
        const val ROW_SCOPE_CLASS = "androidx.compose.foundation.layout.RowScope"
        const val WEIGHT_DEFAULT_METHOD = "weight\$default"
        const val COLUMN_KT_CLASS = "androidx.compose.foundation.layout.ColumnKt"
        const val COLUMN_METHOD = "Column"
        const val BOX_KT_CLASS = "androidx.compose.foundation.layout.BoxKt"
        const val BOX_METHOD = "Box"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val SIZE_METHOD = "size-3ABfNKs"
        const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
        const val HEIGHT_METHOD = "height-3ABfNKs"
        const val PADDING_KT_CLASS = "androidx.compose.foundation.layout.PaddingKt"
        const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val CLICKABLE_KT_CLASS = "androidx.compose.foundation.ClickableKt"
        const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
        const val BACKGROUND_KT_CLASS = "androidx.compose.foundation.BackgroundKt"
        const val BACKGROUND_DEFAULT_METHOD = "background-bw27NRU\$default"
        const val CLIP_KT_CLASS = "androidx.compose.ui.draw.ClipKt"
        const val CLIP_METHOD = "clip"
        const val SHAPE_KT_CLASS = "app.zhendong.reamicro.arch.components.ShapeKt"
        const val ROUNDED_SHAPE_METHOD = "getRoundedSmallShape"
        const val FOUNDATION_SHAPE_KT_CLASS = "androidx.compose.foundation.shape.RoundedCornerShapeKt"
        const val CIRCLE_SHAPE_METHOD = "getCircleShape"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val ICON_KT_CLASS = "androidx.compose.material3.IconKt"
        const val ARRANGEMENT_CLASS = "androidx.compose.foundation.layout.Arrangement"
        const val SPACED_BY_METHOD = "spacedBy-0680j_4"
        const val ALIGNMENT_CLASS = "androidx.compose.ui.Alignment"
        const val THEME_KT_CLASS = "app.zhendong.reamicro.arch.theme.ThemeKt"
        const val ON_BACKGROUND_VARIANT_METHOD = "getOnBackgroundVariant"
        const val BACKGROUND_DIM_METHOD = "getBackgroundDim"
        const val BACKGROUND_BRIGHT_METHOD = "getBackgroundBright"
        const val BORDER_VARIANT_METHOD = "getBorderVariant"
        const val COLOR_CLASS = "androidx.compose.ui.graphics.Color"
        const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"
        const val UDP_METHOD = "getUdp"
        const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"
        const val SNAPSHOT_STATE_KT_CLASS = "androidx.compose.runtime.SnapshotStateKt__SnapshotStateKt"
        const val MUTABLE_STATE_OF_DEFAULT_METHOD = "mutableStateOf\$default"
        const val TYPE_SETTING_SLIDER_CLASS = "app.zhendong.reamicro.ui.reader.components.TypeSettingSliderKt"
        const val TYPE_SETTING_SLIDER_METHOD = "TypeSettingSlider-S6oqaS4"
        const val SLIDER_BRUSH_COLOR_CLASS = "app.zhendong.reamicro.arch.components.slider.SliderBrushColor"
        const val MATERIAL_SLIDER_DEFAULTS_CLASS = "app.zhendong.reamicro.arch.components.slider.MaterialSliderDefaults"
        const val SLIDER_DEFAULT_COLORS_METHOD = "defaultColors"
        const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val FUNCTION3_CLASS = "kotlin.jvm.functions.Function3"
        const val UNIT_CLASS = "kotlin.Unit"
        const val EVA_ICONS_CLASS = "compose.icons.EvaIcons"
        const val EVA_FILL_KT_CLASS = "compose.icons.evaicons.__FillKt"
        const val EVA_FILL_METHOD = "getFill"
        const val EVA_PLAY_CIRCLE_KT_CLASS = "compose.icons.evaicons.fill.PlayCircleKt"
        const val EVA_PLAY_CIRCLE_METHOD = "getPlayCircle"
        const val EVA_PAUSE_CIRCLE_KT_CLASS = "compose.icons.evaicons.fill.PauseCircleKt"
        const val EVA_PAUSE_CIRCLE_METHOD = "getPauseCircle"
        const val TRANSPARENT_COLOR_METHOD = "getTransparent-0d7_KjU"
        const val TEXT_DEFAULT_MASK = 131066

        const val PREFS_NAME = "reamicro_reader_auto_page"
        const val KEY_TIMER_HOURS = "timer_hours"
        const val KEY_INTERVAL_SECONDS = "interval_seconds"
        const val DEFAULT_TIMER_HOURS = 0f
        const val DEFAULT_INTERVAL_SECONDS = 10
        const val MAX_TIMER_HOURS = 5f
        const val MIN_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 30
        const val NATIVE_TYPE_GROUP_TOP_PADDING = 14
        const val AUTO_PAGE_SECTION_TOP_PADDING = 22
        const val NATIVE_SECTION_TITLE_BOTTOM_PADDING = 6
        const val AUTO_PAGE_BUTTON_TOP_PADDING = 22
        const val AUTO_PAGE_BUTTON_SIZE = 34
        val AUTO_PAGE_TOKEN = Any()
        val DISPLAY_TITLE_CANDIDATES = setOf("\u663e\u793a", "\u93c4\u5267\u305a")
    }
}
