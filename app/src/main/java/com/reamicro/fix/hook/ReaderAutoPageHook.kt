package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.core.HostReaderLifecycleBindings
import com.reamicro.fix.core.HostReaderMenuBindings
import com.reamicro.fix.core.HostReaderPositionBindings
import com.reamicro.fix.reader.ReaderAutoPageSession
import com.reamicro.fix.reader.ReaderAutoPageValues
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class ReaderAutoPageHook(
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
) {
    private val mainHandler = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private var currentViewModelRef: WeakReference<Any>? = null
    private var currentSettingsCloseRef: WeakReference<Any>? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var currentBookKey: String? = null
    private var readerVisible = false
    private var activityResumed = false
    private var autoPageBookKey: String? = null
    private val executionLock = Any()
    private val executionRevision = AtomicLong()
    private var autoPageExecutionRevision: Long? = null
    private var autoPageViewModelRef: WeakReference<Any>? = null
    private val autoPageSession = ReaderAutoPageSession(SystemClock::elapsedRealtime)
    private var pendingStartToast = false
    private var autoPageIdentity: AccountHostRuntime.Identity? = null
    private var host: HostReaderExtrasBindings.Contract? = null
    internal var nativeStateChanged: (() -> Unit)? = null
    private var nativeUiState: Any? = null
    private var nativeUiStateRevision = 0

    fun install() {
        HookInstallReport.install("ReaderAutoPageHook", "receiver") {
            val symbols = requireNotNull(HostReaderFeatures.current) { "Reader native controls unavailable" }
            val contract = requireNotNull(HostReaderExtrasBindings.autoPage) { "Reader auto-page contract unavailable" }
            val lifecycle = requireNotNull(HostReaderLifecycleBindings.current) { "Reader visibility contract unavailable" }
            val menu = requireNotNull(HostReaderMenuBindings.current) { "Reader menu visibility contract unavailable" }
            val position = requireNotNull(HostReaderPositionBindings.current) { "Reader identity contract unavailable" }
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                check(symbols["state"].returnType == contract.type("mutableState")) { "Reader UI state contract mismatch" }
                check(position.field("state#status").type.isAssignableFrom(lifecycle.type("status.reader")) &&
                    lifecycle.type("vm") == contract.type("vm") && position.type("vm") == contract.type("vm"))
                nativeUiState = requireNotNull(symbols["state"].invoke(null, 0))
                nativeUiStateRevision = 0
                contract.constructors("vm.").forEach { constructor ->
                    handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                        override fun after(param: Call) {
                            if (param.throwable != null) return
                            val receiver = param.thisObject ?: return
                            onMain {
                                // Construction is not proof that the reading page is visible.
                                if (currentViewModelRef?.get() == null) currentViewModelRef = WeakReference(receiver)
                            }
                        }
                    })
                }
                handles += HookRuntime.hookMethod(contract.method("dispatch"), object : HookCallback() {
                    override fun before(param: Call) {
                        val receiver = param.thisObject ?: return
                        val intent = param.args.firstOrNull()
                        onMain {
                            if (currentViewModelRef?.get() == null) currentViewModelRef = WeakReference(receiver)
                            if (currentViewModelRef?.get() !== receiver) return@onMain
                            if (intent != null && lifecycle.type("intent.UpdateUIStatus").isInstance(intent)) {
                                val status = lifecycle.field("UpdateUIStatus.status").get(intent)
                                // A Reader intent still waits for the corresponding native composition.
                                if (!lifecycle.type("status.reader").isInstance(status)) readerVisible = false
                                reconcileVisibility()
                            } else if (isRunning() && !ownsAutoPage()) stopAutoPage(showToast = false)
                        }
                    }
                })
                handles += HookRuntime.hookMethod(contract.method("clear"), object : HookCallback() {
                    override fun after(param: Call) {
                        val receiver = param.thisObject ?: return
                        onMain {
                            if (currentViewModelRef?.get() !== receiver) return@onMain
                            stopAutoPage(showToast = false)
                            clearReader()
                        }
                    }
                })
                handles += HookRuntime.hookMethod(menu.method("bottomBar"), object : HookCallback() {
                    override fun before(param: Call) {
                        observeReaderComposition(param.args.getOrNull(0), param.args.getOrNull(1), param.args.getOrNull(2))
                    }
                })
                handles += HookRuntime.hookMethod(menu.method("bottomContent"), object : HookCallback() {
                    override fun before(param: Call) {
                        val callback = param.thisObject ?: return
                        if (!menu.bottomMatches(callback)) return
                        observeReaderComposition(menu.field("bottom.receiver").get(callback),
                            menu.field("bottom.book").get(callback), menu.field("bottom.status").get(callback))
                    }
                })
                for (role in listOf("home", "bookshelf")) {
                    handles += HookRuntime.hookMethod(lifecycle.method(role), object : HookCallback() {
                        override fun before(param: Call) {
                            onMain {
                                readerVisible = false
                                currentSettingsCloseRef = null
                                reconcileVisibility()
                            }
                        }
                    })
                }
                for (member in listOf(menu.method("bottomBar"), menu.method("bottomContent"))) HookRuntime.deoptimize(member)
                host = contract
                check(ReaderPageControlsHook(symbols, activityProvider, this) {
                    val settings = settingsProvider()
                    settings.moduleEnabled && settings.readerPageAnimationEnabled
                }.install()) { "Native reader controls unavailable" }
                activeInstance = this
                com.reamicro.fix.reload.ModuleResources.cleanup {
                    nativeStateChanged = null
                    nativeUiState = null
                    stopAutoPage(showToast = false)
                    mainHandler.removeCallbacksAndMessages(READER_VISIBILITY_TOKEN)
                    clearReader()
                    currentActivityRef = null
                    activityResumed = false
                    if (activeInstance === this) activeInstance = null
                }
            } catch (failure: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() } }
                host = null
                nativeUiState = null
                throw failure
            }
        }
    }
    internal fun nativeAutoPageVisible(): Boolean = canRunAutoPage()
    internal fun nativeModuleEnabled(): Boolean = settingsProvider().moduleEnabled
    internal fun nativeTimerHours(): Float = timerHours()
    internal fun nativeTimerMinutes(): Int = timerMinutes()
    internal fun nativeIntervalSeconds(): Float = intervalSeconds()
    internal fun nativeEnabled(): Boolean {
        if (!canRunAutoPage()) return false
        val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return false
        return runCatching { prefs().getBoolean(enabledKey(identity), false) }.getOrDefault(false)
    }
    internal fun nativeSetEnabled(value: Boolean) {
        if (!canRunAutoPage()) return
        val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return
        AccountHostRuntime.withCurrentStorage(identity, activityProvider()) {
            prefs().edit().putBoolean(enabledKey(identity), value)
                .putInt(KEY_INTERVAL_SECONDS, ReaderAutoPageValues.interval(intervalSeconds()))
                .putInt(KEY_TIMER_MINUTES, timerMinutes()).remove(KEY_TIMER_HOURS).apply()
            if (value) startAutoPage(timerHours(), intervalSeconds(), showToast = true, closeSettings = false)
            else stopAutoPage(showToast = true)
        }
    }
    internal fun nativeRunning(): Boolean {
        if (isRunning() && (!nativeEnabled() || !ownsAutoPage())) stopAutoPage(showToast = false)
        return isRunning()
    }
    internal fun nativeSetTimer(value: Float) = setTimerHours(value)
    internal fun nativeSetTimerMinutes(value: Float) = setTimerHours(ReaderAutoPageValues.minutes(value) / 60f)
    internal fun nativeSetInterval(value: Float) = setIntervalSeconds(value)
    internal fun nativeCaptureClose(callback: Any) {
        currentSettingsCloseRef = WeakReference(callback)
        readerVisible = false
        reconcileVisibility()
    }
    internal fun nativePageMode(mode: Int) {
        // ScrollPager ignores TapDirection; stop an existing timer when its mode is selected.
        val owner = autoPageIdentity
        if (mode == 3 && nativeRunning()) mainHandler.post {
            if (owner === autoPageIdentity && isRunning()) stopAutoPage(showToast = false)
        }
    }
    internal fun nativeSetRunning(value: Boolean) {
        if (value == nativeRunning()) return
        if (value) startAutoPage(timerHours(), intervalSeconds(), showToast = true, closeSettings = false)
        else stopAutoPage(showToast = true)
    }


    private fun observeNativeState() {
        val state = nativeUiState ?: return
        host?.method("stateGet")?.invoke(state)
    }

    private fun notifyNativeStateChanged() {
        val state = nativeUiState
        val contract = host
        if (state != null && contract != null) contract.method("stateSet").invoke(state, ++nativeUiStateRevision)
        nativeStateChanged?.invoke()
    }

    private fun timerMinutes(): Int = if (prefs().contains(KEY_TIMER_MINUTES)) runCatching {
        ReaderAutoPageValues.minutes(prefs().getInt(KEY_TIMER_MINUTES, 0).toFloat())
    }.getOrDefault(0) else ReaderAutoPageValues.legacyHours(runCatching {
        prefs().getFloat(KEY_TIMER_HOURS, DEFAULT_TIMER_HOURS)
    }.recoverCatching { prefs().getInt(KEY_TIMER_HOURS, 0).toFloat() }.getOrDefault(DEFAULT_TIMER_HOURS))
    private fun timerHours(): Float = timerMinutes() / 60f
    private fun intervalSeconds(): Float = normalizeInterval(runCatching {
        prefs().getFloat(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
    }.recoverCatching {
        // Existing accounts and imported backups store this same seconds key as an Int.
        prefs().getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS.toInt()).toFloat()
    }.getOrDefault(DEFAULT_INTERVAL_SECONDS))
    private fun enabledKey(identity: AccountHostRuntime.Identity) = "enabled_account_${identity.accountId}"
    private fun normalizeTimerHours(value: Float): Float =
        ReaderAutoPageValues.legacyHours(value) / 60f
    private fun normalizeInterval(value: Float): Float =
        ReaderAutoPageValues.interval(value).toFloat()
    private fun isRunning() = autoPageSession.requested
    private fun setTimerHours(value: Float) {
        if (!canRunAutoPage()) return
        val next = normalizeTimerHours(value)
        prefs().edit().putInt(KEY_TIMER_MINUTES, ReaderAutoPageValues.legacyHours(next)).remove(KEY_TIMER_HOURS).apply()
        if (isRunning()) startAutoPage(next, intervalSeconds(), showToast = false, closeSettings = false)
    }

    private fun setIntervalSeconds(value: Float) {
        if (!canRunAutoPage()) return
        val next = normalizeInterval(value)
        prefs().edit().putInt(KEY_INTERVAL_SECONDS, ReaderAutoPageValues.interval(next)).apply()
        if (isRunning()) {
            mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
            if (autoPageSession.running) scheduleNextTurn(next)
        }
    }

    private fun toggleAutoPage() {
        if (nativeRunning()) {
            log("auto page stop by toggle")
            stopAutoPage(showToast = true)
        } else {
            if (!nativeEnabled()) {
                activityProvider()?.let {
                    Toast.makeText(it, "\u8bf7\u5148\u5728\u66f4\u591a\u8bbe\u7f6e\u4e2d\u5f00\u542f\u81ea\u52a8\u9605\u8bfb", Toast.LENGTH_SHORT).show()
                }
                return
            }
            log("auto page start by toggle timer=${timerHours()} interval=${intervalSeconds()}")
            startAutoPage(timerHours(), intervalSeconds(), showToast = true, closeSettings = true)
        }
    }

    private fun startAutoPage(
        timerHours: Float,
        intervalSeconds: Float,
        showToast: Boolean,
        closeSettings: Boolean,
    ) {
        val revision = executionRevision.get()
        if (!nativeEnabled()) {
            stopAutoPage(showToast = false)
            return
        }
        val identity = AccountHostRuntime.storageSnapshot(activityProvider())
        if (identity == null) {
            stopAutoPage(showToast = false)
            return
        }
        val receiver = currentViewModelRef?.get()
        val book = currentBookKey ?: receiver?.let(::nativeBookKey)
        if (receiver == null || book == null) {
            stopAutoPage(showToast = false)
            return
        }
        mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
        autoPageIdentity = identity
        autoPageViewModelRef = WeakReference(receiver)
        autoPageBookKey = book
        autoPageExecutionRevision = revision
        pendingStartToast = showToast
        autoPageSession.request(if (timerHours > 0f) {
            (timerHours.toDouble() * 60.0 * 60.0 * 1000.0).roundToLong().coerceAtLeast(1L)
        } else 0L)
        notifyNativeStateChanged()
        if (closeSettings) closeReaderSettings()
        queueVisibilityReconciliation()
    }

    private fun scheduleNextTurn(intervalSeconds: Float) {
        val runnable = Runnable {
            if (!isRunning()) return@Runnable
            if (!nativeEnabled() || !ownsAutoPage()) {
                log("auto page stopped because its account or module setting changed")
                stopAutoPage(showToast = false)
                return@Runnable
            }
            reconcileVisibility()
            if (!autoPageSession.running) return@Runnable
            if (autoPageSession.expired()) {
                log("auto page timer ended")
                stopAutoPage(showToast = false)
                return@Runnable
            }
            log("auto page interval tick")
            triggerNextPage()
            if (isRunning()) scheduleNextTurn(intervalSeconds())
        }
        mainHandler.postAtTime(runnable, AUTO_PAGE_TOKEN, SystemClock.uptimeMillis() + (intervalSeconds.toDouble() * 1000.0).roundToLong())
    }

    private fun stopAutoPage(showToast: Boolean = false) {
        mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
        autoPageSession.stop()
        autoPageIdentity = null
        autoPageViewModelRef = null
        autoPageBookKey = null
        autoPageExecutionRevision = null
        pendingStartToast = false
        notifyNativeStateChanged()
        if (showToast) {
            activityProvider()?.let {
                Toast.makeText(it, "\u5df2\u505c\u6b62\u81ea\u52a8\u7ffb\u9875", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun triggerNextPage() {
        if (!ownsAutoPage()) { stopAutoPage(); return }
        val receiver = currentViewModelRef?.get()
        val contract = host
        val dispatched = synchronized(executionLock) {
            if (receiver == null || contract == null || autoPageExecutionRevision != executionRevision.get() ||
                !settingsProvider().moduleEnabled) false
            else runCatching {
                contract.method("send").invoke(receiver, contract.constructor("tap").newInstance(System.currentTimeMillis()))
            }.onFailure { HookRuntime.logError("$LOG_PREFIX auto-page dispatch failed", it) }.isSuccess
        }
        if (!dispatched) {
            stopAutoPage()
        }
    }
    private fun closeReaderSettings() {
        val callback = currentSettingsCloseRef?.get() ?: return
        val contract = host ?: return
        runCatching {
            require(contract.type("close").isInstance(callback))
            contract.method("close").invoke(callback)
            currentSettingsCloseRef = null
        }.onFailure { HookRuntime.logError("$LOG_PREFIX auto-page settings close failed", it) }
    }

    internal fun onHostActivityCreated(activity: Activity) {
        if (currentActivityRef?.get() === activity) return
        if (currentActivityRef?.get() != null) {
            stopAutoPage(showToast = false)
            clearReader()
        }
        currentActivityRef = WeakReference(activity)
        activityResumed = false
    }

    internal fun onModuleEnabledChanged(enabled: Boolean) {
        if (enabled) return
        onMain {
            stopAutoPage(showToast = false)
            mainHandler.removeCallbacksAndMessages(READER_VISIBILITY_TOKEN)
        }
    }

    internal fun invalidateExecution() {
        synchronized(executionLock) { executionRevision.incrementAndGet() }
    }

    internal fun onHostActivityResumed(activity: Activity) {
        onHostActivityCreated(activity)
        activityResumed = true
        queueVisibilityReconciliation()
    }

    internal fun onHostActivityPaused(activity: Activity) {
        if (currentActivityRef?.get() !== activity) return
        activityResumed = false
        reconcileVisibility()
    }

    internal fun onHostActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() !== activity) return
        stopAutoPage(showToast = false)
        clearReader()
        activityResumed = false
        currentActivityRef = null
        mainHandler.removeCallbacksAndMessages(READER_VISIBILITY_TOKEN)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private fun observeReaderComposition(receiver: Any?, book: Any?, status: Any?) {
        val lifecycle = HostReaderLifecycleBindings.current ?: return
        val position = HostReaderPositionBindings.current ?: return
        if (receiver == null || book == null || !lifecycle.type("vm").isInstance(receiver) ||
            !position.type("book").isInstance(book)) return
        onMain {
            val key = bookKey(book)
            // An outgoing animated menu must not revive a book that its ViewModel has replaced.
            if (key != nativeBookKey(receiver)) return@onMain
            if (isRunning() && (autoPageViewModelRef?.get() !== receiver || autoPageBookKey != key)) {
                stopAutoPage(showToast = false)
            }
            if (currentViewModelRef?.get() !== receiver) currentSettingsCloseRef = null
            currentViewModelRef = WeakReference(receiver)
            currentBookKey = key
            readerVisible = lifecycle.type("status.reader").isInstance(status) && nativeReaderContent(receiver)
            if (readerVisible) currentSettingsCloseRef = null
            else reconcileVisibility()
            // Run after this native composition, so its settings disposal has completed first.
            queueVisibilityReconciliation()
        }
    }

    private fun bookKey(book: Any): String {
        val position = requireNotNull(HostReaderPositionBindings.current)
        return "${position.field("book#id").getLong(book)}:${position.field("book#uuid").get(book)}"
    }

    private fun nativeBookKey(receiver: Any): String? = runCatching {
        val position = requireNotNull(HostReaderPositionBindings.current)
        val state = position.method("currentState").invoke(receiver) ?: return@runCatching null
        position.field("state#book").get(state)?.let(::bookKey)
    }.getOrNull()

    private fun nativeReaderContent(receiver: Any): Boolean = runCatching {
        val position = requireNotNull(HostReaderPositionBindings.current)
        val state = position.method("currentState").invoke(receiver) ?: return@runCatching false
        requireNotNull(HostReaderLifecycleBindings.current).type("status.reader")
            .isInstance(position.field("state#status").get(state))
    }.getOrDefault(false)

    private fun queueVisibilityReconciliation() {
        mainHandler.removeCallbacksAndMessages(READER_VISIBILITY_TOKEN)
        mainHandler.postAtTime(Runnable { reconcileVisibility() }, READER_VISIBILITY_TOKEN, SystemClock.uptimeMillis())
    }

    private fun reconcileVisibility() {
        if (!isRunning()) return
        if (!nativeEnabled() || !ownsAutoPage()) {
            stopAutoPage(showToast = false)
            return
        }
        val activity = currentActivityRef?.get()
        val visible = readerVisible && currentSettingsCloseRef?.get() == null &&
            currentViewModelRef?.get()?.let(::nativeReaderContent) == true
        val resumed = activityResumed && activity != null && activityProvider() === activity &&
            !activity.isFinishing && !activity.isDestroyed
        when (autoPageSession.visibility(visible, resumed)) {
            ReaderAutoPageSession.Transition.STARTED, ReaderAutoPageSession.Transition.RESUMED -> {
                scheduleNextTurn(intervalSeconds().coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS))
                if (pendingStartToast) {
                    pendingStartToast = false
                    activity?.let {
                        Toast.makeText(it, "\u5df2\u5f00\u59cb\u81ea\u52a8\u7ffb\u9875", Toast.LENGTH_SHORT).show()
                    }
                }
                log("auto page resumed on visible reader interval=${intervalSeconds()}")
            }
            ReaderAutoPageSession.Transition.PAUSED -> {
                mainHandler.removeCallbacksAndMessages(AUTO_PAGE_TOKEN)
                log("auto page paused while reader hidden remaining=${autoPageSession.remainingMillis()}")
            }
            ReaderAutoPageSession.Transition.FINISHED -> stopAutoPage(showToast = false)
            ReaderAutoPageSession.Transition.NONE -> Unit
        }
    }

    private fun clearReader() {
        readerVisible = false
        currentViewModelRef = null
        currentBookKey = null
        currentSettingsCloseRef = null
    }
    private fun ownsAutoPage(): Boolean = autoPageExecutionRevision == executionRevision.get() && autoPageIdentity?.let {
        AccountHostRuntime.isCurrentStorage(it, activityProvider()) &&
            autoPageViewModelRef?.get()?.let { receiver ->
                receiver === currentViewModelRef?.get() && autoPageBookKey == nativeBookKey(receiver)
            } == true
    } == true

    private fun canRunAutoPage(): Boolean = host != null && settingsProvider().canRunReaderAutoPage
    private fun prefs() = requireNotNull(activityProvider()) { "Activity unavailable" }.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun log(message: String) = HookRuntime.log("$LOG_PREFIX $message")
    private fun icon(running: Boolean): Any? = host?.method(if (running) "pause" else "play")?.invoke(null)
    companion object {
        private const val LOG_PREFIX = "ReaMicro LSP"
        private const val PREFS_NAME = "reamicro_reader_auto_page"
        private const val KEY_TIMER_HOURS = "timer_hours"
        private const val KEY_TIMER_MINUTES = "timer_minutes"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val DEFAULT_TIMER_HOURS = 0f
        private const val DEFAULT_INTERVAL_SECONDS = 10f
        internal const val MAX_TIMER_HOURS = 3f
        internal const val MIN_INTERVAL_SECONDS = 5f
        internal const val MAX_INTERVAL_SECONDS = 60f
        private val AUTO_PAGE_TOKEN = Any()
        private val READER_VISIBILITY_TOKEN = Any()
        @Volatile private var activeInstance: ReaderAutoPageHook? = null
        fun observeAutoPageState() { activeInstance?.observeNativeState() }
        fun isAutoPageEnabled(): Boolean = activeInstance?.nativeEnabled() ?: false
        fun isAutoPageRunning(): Boolean = activeInstance?.nativeRunning() ?: false
        fun toggleAutoPage() { activeInstance?.toggleAutoPage() }
        fun autoPageIcon(running: Boolean): Any? = activeInstance?.runCatching { icon(running) }?.getOrNull()
    }
}
