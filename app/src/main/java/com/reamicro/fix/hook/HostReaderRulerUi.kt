package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reader.ReaderRulerRange
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap

/** The host listening ruler keeps its layout, bubble, thumb, gesture and accessibility path. */
internal class HostReaderRulerUi(private val symbols: HostReaderFeatures.Symbols) {
    private data class Frame(
        val name: String, val min: Float, val max: Float, val steps: Int,
        val minText: String, val maxText: String, val label: String,
        val tick: (Float) -> String, val change: (Float) -> Unit,
    ) {
        private val range = ReaderRulerRange(min, max)
        fun native(value: Float): Float = range.native(value)
        fun actual(value: Float): Float = range.actual(value)
    }
    private val frames = ThreadLocal.withInitial { java.util.ArrayDeque<Frame>() }
    private val callbacks = Collections.synchronizedMap(WeakHashMap<Any, Frame>())
    private val captures = Collections.synchronizedMap(WeakHashMap<Any, Frame>())
    private val ui get() = requireNotNull(HostUiBindings.current)

    fun install(register: (Executable, HookCallback) -> Unit) {
        fun enter(param: HookCallback.Call, frame: Frame?) {
            if (frame == null) return
            frames.get().push(frame)
            param.setObjectExtra("reamicro.ruler.frame", true)
        }
        fun leave(param: HookCallback.Call) {
            if (param.getObjectExtra("reamicro.ruler.frame") == true) frames.get().pop()
        }
        register(symbols["voice"], object : HookCallback() {
            override fun before(param: Call) {
                enter(param, param.args.getOrNull(2)?.let(callbacks::get))
            }
            override fun after(param: Call) { leave(param) }
        })
        for (role in listOf("voiceBody", "voiceLabel", "voiceSemantics")) {
            register(symbols.constructor(role), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null) {
                        val frame = frames.get().peek() ?: return
                        param.thisObject?.let { captures[it] = frame }
                    }
                }
            })
            register(symbols[role], object : HookCallback() {
                override fun before(param: Call) {
                    enter(param, param.thisObject?.let(captures::get))
                }
                override fun after(param: Call) { leave(param) }
            })
            HookRuntime.deoptimize(symbols[role])
        }
        register(symbols["voiceSlider"], object : HookCallback() {
            override fun before(param: Call) {
                val frame = frames.get().peek() ?: return
                // The host callback rounds speech speed to tenths; our controls use their own units.
                param.args[1] = ui.callback(symbols["voice"].parameterTypes[2]) { args ->
                    (args?.firstOrNull() as? Number)?.toFloat()?.takeIf { it.isFinite() }?.let { frame.change(frame.actual(it)) }
                }
                param.args[7] = frame.steps
                param.args[12] = (param.args[12] as Int) and ((7 shl 4) or (7 shl 22)).inv()
            }
        })
        register(ui.method("text"), object : HookCallback() {
            override fun before(param: Call) {
                val frame = frames.get().peek() ?: return
                val text = param.args.getOrNull(0) as? String ?: return
                val replacement = when {
                    text.startsWith("语速 ") -> frame.label
                    text == "慢" -> frame.minText
                    text == "快" -> frame.maxText
                    else -> text.toFloatOrNull()?.takeIf { it in .5f..2f }?.let { frame.tick(frame.actual(it)) }
                } ?: return
                param.args[0] = replacement
                param.args[17] = (param.args[17] as Int) and 14.inv()
            }
        })
        register(symbols["voiceDescription"], object : HookCallback() {
            override fun before(param: Call) {
                frames.get().peek()?.let { param.args[1] = it.name }
            }
        })
        for (role in listOf("voice", "voiceSlider", "voiceDescription")) HookRuntime.deoptimize(symbols[role])
        ModuleResources.cleanup { callbacks.clear(); captures.clear(); frames.remove() }
    }

    fun render(name: String, current: Float, min: Float, max: Float, steps: Int,
        minText: String, maxText: String, label: String, composer: Any,
        tick: (Float) -> String, onChange: (Float) -> Unit) {
        require(min.isFinite() && max.isFinite() && max > min && current.isFinite() && steps >= 0)
        val frame = Frame(name, min, max, steps, minText, maxText, label, tick, onChange)
        val callback = ui.callback(symbols["voice"].parameterTypes[2]) { args ->
            (args?.firstOrNull() as? Number)?.toFloat()?.takeIf { it.isFinite() }?.let { frame.change(frame.actual(it)) }
        }
        callbacks[callback] = frame
        // Keep the native 34dp ruler, with room for its label and the next settings row.
        val paddingTop = symbols["udp"].invoke(null, 8)
        val paddingBottom = symbols["udp"].invoke(null, 24)
        val modifier = ui.call("padding", symbols.modifier, 0f, paddingTop, 0f, paddingBottom)
        symbols["voice"].invoke(null, name, frame.native(current), callback, modifier, composer, 0)
    }
}
