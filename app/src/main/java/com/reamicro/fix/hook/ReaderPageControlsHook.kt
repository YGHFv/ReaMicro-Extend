package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reader.ReaderAutoPageValues
import com.reamicro.fix.reader.ReaderAnimationOverrides
import java.lang.ref.WeakReference
import java.util.Locale

/** Insert at the same sibling position after the mode Row, outside the preview's conditional group. */
internal class ReaderPageControlsHook(
    private val symbols: HostReaderFeatures.Symbols,
    private val activity: () -> Activity?,
    private val auto: ReaderAutoPageHook,
    private val animationEnabled: () -> Boolean = { true },
) {
    private class Frame : ReaderSettingsInsertion() { var composer: Any? = null }
    private val frames = ThreadLocal.withInitial { java.util.ArrayDeque<Frame>() }
    private val frameStack get() = requireNotNull(frames.get())
    private val animationDepth = ThreadLocal.withInitial { 0 }
    private val swipeLayoutDepth = ThreadLocal.withInitial { 0 }
    // Fling.equals/hashCode include its changing spec; membership must use object identity.
    private class WeakObjects {
        private val values = ArrayList<WeakReference<Any>>()
        operator fun contains(value: Any?): Boolean = values.any { it.get() === value && value != null }
        fun add(value: Any) {
            values.removeAll { it.get() == null }
            if (value !in this) values.add(WeakReference(value))
        }
    }
    private val readerPagers = WeakObjects()
    private val readerFlings = WeakObjects()
    private val originalFlingSpecs = ReaderAnimationOverrides()
    private val tweens = HashMap<Int, Any>()
    private val speedStates = HashMap<Int, Any>()
    private val availableModes = mutableSetOf<Int>()
    private var installing: MutableList<() -> Unit>? = null
    private var refresh: Any? = null
    private val ruler = HostReaderRulerUi(symbols)
    private fun prefs() = requireNotNull(activity()).getSharedPreferences("reamicro_reader_page_speed", Context.MODE_PRIVATE)
    private fun speed(mode: Int) = if (!animationEnabled() || !ReaderPageSpeed.adjustable(mode)) ReaderPageSpeed.DEFAULT else ReaderPageSpeed.normalize(runCatching {
        prefs().getFloat(ReaderPageSpeed.key(mode), 1f)
    }.getOrDefault(1f))
    private val stateContract get() = requireNotNull(HostReaderExtrasBindings.autoPage)
    private fun state(value: Any) = requireNotNull(symbols["state"].invoke(null, value))
    private fun value(state: Any): Any? = stateContract.method("stateGet").invoke(state)
    private fun set(state: Any, value: Any) = stateContract.method("stateSet").invoke(state, value)
    private fun changed() { refresh?.let { set(it, (value(it) as Number).toInt() + 1) } }

    fun install(): Boolean {
        if (!installPart("controls") {
            check(symbols["state"].returnType == stateContract.type("mutableState"))
            installControls()
        }) return false
        auto.nativeStateChanged = ::changed
        HookRuntime.withFeatureGate(animationEnabled) {
            if (installPart("overlay speed", ::installOverlaySpeed)) availableModes += 1
            if (installPart("swipe speed", ::installSwipeSpeed)) availableModes += 0
            availableModes += ReaderExtraMotionHook(symbols, ::speed).install()
        }
        val restored = installPart("native swipe restoration") {
            val specField = symbols.field("swipeFlingSpec")
            hook("swipeFling", object : HookCallback() {
                override fun before(param: Call) {
                    if (animationEnabled()) return
                    param.thisObject?.let { owner ->
                        originalFlingSpecs.restore(owner) { specField.set(owner, it) }
                    }
                }
            })
        }
        val deoptimized = installPart("deoptimization") {
            listOf("content", "overlayResume", "swipeLayout", "swipeFling").forEach { key ->
                HookRuntime.deoptimize(symbols[key])
            }
        }
        HookRuntime.log("ReaMicro reader page controls installed: native controls; speed modes=$availableModes")
        return restored && deoptimized && availableModes.containsAll(listOf(0, 1, 4))
    }

    private fun hook(key: String, callback: HookCallback) {
        val handle = HookRuntime.hookMethod(symbols[key], callback)
        installing?.add { handle.unhook() }
    }

    private fun installPart(label: String, action: () -> Unit): Boolean {
        val undo = mutableListOf<() -> Unit>()
        installing = undo
        return try {
            action()
            HookInstallReport.record("ReaderPageControlsHook", label, true)
            true
        } catch (error: Throwable) {
            undo.asReversed().forEach { runCatching(it) }
            HookRuntime.logError("ReaMicro reader $label installation failed", error)
            HookInstallReport.record("ReaderPageControlsHook", label, false, error)
            false
        } finally { installing = null }
    }

    private fun installControls() {
        ruler.install { member, callback ->
            val handle = HookRuntime.hookMethod(member, callback)
            installing?.add { handle.unhook() }
        }
        hook("settingsTopBar", object : HookCallback() {
            override fun before(param: Call) {
                if (param.args[0] == "更多设置") param.args[4]?.let(auto::nativeCaptureClose)
            }
        })
        hook("content", object : HookCallback() {
            override fun before(param: Call) { frameStack.push(Frame()) }
            override fun after(param: Call) { frameStack.pop() }
        })
        hook("title", object : HookCallback() {
            override fun after(param: Call) {
                val f = frameStack.peek() ?: return
                if (f.rendering || param.args[0] != "翻页方式") return
                f.composer = param.args[2]
                f.beginModes()
            }
        })
        hook("button", object : HookCallback() {
            override fun before(param: Call) {
                val f = frameStack.peek() ?: return
                f.enterButton(ReaderPageSpeed.names.indexOf(param.args[0]), param.args[1] == true)
            }
            override fun after(param: Call) {
                frameStack.peek()?.exitButton()
            }
        })
        hook("endGroup", object : HookCallback() {
            override fun after(param: Call) {
                val f = frameStack.peek() ?: return
                if (param.throwable == null && param.thisObject === f.composer &&
                    f.endGroup(param.args[0] == true)) insert(f)
            }
        })
    }

    private fun installOverlaySpeed() {
        hook("overlayResume", object : HookCallback() {
            override fun before(param: Call) { animationDepth.set((animationDepth.get() ?: 0) + 1) }
            override fun after(param: Call) { animationDepth.set((animationDepth.get() ?: 0) - 1) }
        })
        hook("tween", object : HookCallback() {
            override fun before(param: Call) {
                if ((animationDepth.get() ?: 0) <= 0 || activity() == null) return
                // Use the same 140ms baseline for drag and tap settling.
                param.args[0] = ReaderPageSpeed.duration(140, speed(1))
            }
        })
    }

    private fun insert(frame: Frame) {
        if (frame.rendering || frame.mode !in ReaderPageSpeed.names.indices || activity() == null) return
        frame.rendering = true
        auto.nativePageMode(frame.mode)
        try { render(requireNotNull(frame.composer), frame.mode) }
        finally { frame.rendering = false }
    }

    private fun swipeTween(): Any {
        val duration = ReaderPageSpeed.duration(180, speed(0))
        return tweens.getOrPut(duration) { requireNotNull(symbols["tween"].invoke(null, duration, 0, null, 6)) }
    }

    private fun installSwipeSpeed() {
        hook("swipeEffects", object : HookCallback() {
            override fun before(param: Call) { param.args[6]?.let { readerPagers.add(it) } }
        })
        hook("swipeAnimate", object : HookCallback() {
            override fun before(param: Call) {
                if (activity() != null && param.thisObject in readerPagers) param.args[1] = swipeTween()
            }
        })
        hook("swipeLayout", object : HookCallback() {
            override fun before(param: Call) { swipeLayoutDepth.set((swipeLayoutDepth.get() ?: 0) + 1) }
            override fun after(param: Call) { swipeLayoutDepth.set((swipeLayoutDepth.get() ?: 0) - 1) }
        })
        hook("swipeFlingFactory", object : HookCallback() {
            override fun before(param: Call) {
                if ((swipeLayoutDepth.get() ?: 0) <= 0 || activity() == null) return
                param.args[0]?.let { readerPagers.add(it) }
                param.args[1]?.let { param.setObjectExtra("ReaMicro.NativeFlingSpec", it) }
                param.args[1] = swipeTween()
            }
            override fun after(param: Call) {
                if ((swipeLayoutDepth.get() ?: 0) > 0) param.result?.let { owner ->
                    readerFlings.add(owner)
                    param.getObjectExtra("ReaMicro.NativeFlingSpec")?.let { originalFlingSpecs.capture(owner, it) }
                }
            }
        })
        val specField = symbols.field("swipeFlingSpec")
        hook("swipeFling", object : HookCallback() {
            override fun before(param: Call) {
                val owner = param.thisObject ?: return
                if (activity() != null && owner in readerFlings) {
                    originalFlingSpecs.capture(owner, requireNotNull(specField.get(owner)))
                    specField.set(owner, swipeTween())
                }
            }
        })
    }

    private fun render(composer: Any, mode: Int) {
        group(composer, 0x524d5053) {
            if (refresh == null) refresh = state(0)
            value(requireNotNull(refresh))
            val speedVisible = animationEnabled() && ReaderPageSpeed.adjustable(mode) && mode in availableModes
            group(composer, 0x524d5350 + mode) {
                if (speedVisible) {
                    val multiplier = speed(mode)
                    val speedState = speedStates.getOrPut(mode) { state(multiplier) }
                    // Account restore changes preferences independently of remembered Compose state.
                    if ((value(speedState) as Number).toFloat() != multiplier) set(speedState, multiplier)
                    val identity = AccountHostRuntime.storageSnapshot(activity())
                    gap(12, composer)
                    title("翻页速度", composer)
                    slider("翻页速度", multiplier, 0.5f, 2f, 14, "慢", "快",
                        String.format(Locale.US, "速度 %.1f", multiplier), composer) {
                        editControls(identity) {
                            val next = ReaderPageSpeed.normalize(it)
                            prefs().edit().putFloat(ReaderPageSpeed.key(mode), next).apply()
                            set(speedState, next)
                        }
                    }
                }
            }
            group(composer, 0x524d4155 + mode) { renderAutoPage(composer, mode, speedVisible) }
        }
    }

    private inline fun group(composer: Any, key: Int, block: () -> Unit) {
        symbols["startGroup"].invoke(composer, key)
        try { block() } finally { symbols["endGroup"].invoke(composer, false) }
    }

    private fun renderAutoPage(composer: Any, mode: Int, afterSpeed: Boolean) {
        val visible = auto.nativeAutoPageVisible() && mode != 3
        group(composer, if (visible) 0x524d4156 else 0x524d4148) {
            if (!visible) return@group
            val identity = AccountHostRuntime.storageSnapshot(activity())
            val enabled = auto.nativeEnabled()
            auto.nativeRunning()
            val toggle = requireNotNull(HostUiBindings.current).callback(symbols["toggle"].parameterTypes[2]) {
                editControls(identity) {
                    auto.nativeSetEnabled(it?.firstOrNull() as? Boolean ?: false)
                    changed()
                }
            }
            // The native mode row already has 12dp below it; its following title adds 12dp.
            // The ruler supplies all 24dp below itself, so do not add a second gap here.
            if (!afterSpeed) gap(12, composer)
            val ui = requireNotNull(HostUiBindings.current)
            check(ui.constructor("lambda").declaringClass == symbols["settingsGroup"].parameterTypes[1])
            val content = ui.lambda(0x524d4143, ui.callback(symbols["settingsGroupFunction"].declaringClass) { args ->
                val currentComposer = args?.getOrNull(1) ?: return@callback
                symbols["toggle"].invoke(null, "自动阅读", enabled, toggle, null, currentComposer, 0, 8)
            })
            // The host's always-on row uses this native settings group, including its 16dp bottom gap.
            val modifier = ui.call("padding", symbols.modifier, 0f, 0f, 0f, symbols["udp"].invoke(null, 16))
            symbols["settingsGroup"].invoke(null, modifier, content, composer, 0, 0)
            group(composer, if (enabled) 0x524d4152 else 0x524d4150) {
                if (!enabled) return@group
                val interval = auto.nativeIntervalSeconds()
                val minutes = auto.nativeTimerMinutes()
                val timerText = ReaderAutoPageValues.durationLabel(minutes)
                gap(12, composer)
                title("翻页间隔", composer)
                slider("翻页间隔", interval, ReaderAutoPageHook.MIN_INTERVAL_SECONDS,
                    ReaderAutoPageHook.MAX_INTERVAL_SECONDS, 0, "5 秒", "60 秒",
                    "${ReaderAutoPageValues.interval(interval)} 秒", composer) {
                    editControls(identity) { auto.nativeSetInterval(it); changed() }
                }
                title("阅读时长", composer)
                slider("阅读时长", minutes.toFloat(), 0f, ReaderAutoPageValues.MAX_TIMER_MINUTES.toFloat(),
                    0, "不限", "3 小时", timerText, composer) {
                    editControls(identity) { auto.nativeSetTimerMinutes(it); changed() }
                }
            }
        }
    }

    private fun editControls(identity: AccountHostRuntime.Identity?, action: () -> Unit) {
        identity ?: return
        if (!auto.nativeModuleEnabled()) return
        if (!AccountHostRuntime.isCurrentStorage(identity, activity())) return
        runCatching { AccountHostRuntime.withCurrentStorage(identity, activity(), action) }.onFailure {
            if (AccountHostRuntime.isCurrentStorage(identity, activity()))
                HookRuntime.logError("ReaMicro reader control update failed", it)
        }
    }

    private fun title(text: String, composer: Any) = symbols["title"].invoke(null, text, null, composer, 0)
    private fun gap(height: Int, composer: Any) {
        val ui = requireNotNull(HostUiBindings.current)
        val modifier = ui.call("height", symbols.modifier, symbols["udp"].invoke(null, height))
        ui.call("box", modifier, composer, 0)
    }

    private fun slider(name: String, current: Float, min: Float, max: Float, steps: Int, minText: String,
        maxText: String, label: String, composer: Any, onChange: (Float) -> Unit) {
        val tick: (Float) -> String = when (name) {
            "翻页速度" -> { value -> String.format(Locale.US, "%.1f", value) }
            else -> ReaderAutoPageValues::tick
        }
        ruler.render(name, current, min, max, steps, minText, maxText, label, composer, tick, onChange)
    }
}
