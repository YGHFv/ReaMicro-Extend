package com.reamicro.fix.hook

import com.reamicro.fix.core.HostHighlightUiBindings
import com.reamicro.fix.core.HostHighlightUiQueries
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.hook.reader.READER_RULE_SHEET_HEIGHT_DP
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.util.ArrayDeque

internal class ReaderHighlightUiBridge(private val reader: ReaderHook) {
    private val host get() = requireNotNull(HostHighlightUiBindings.current) { "Highlight UI contract unavailable" }
    private fun deoptimizeBody(role: String) {
        val s = host
        HookRuntime.deoptimize(s.method("root.$role"))
        HookRuntime.deoptimize(s.method("body.$role"))
        for (i in 0 until s.index("body.$role.callers")) HookRuntime.deoptimize(s.method("body.$role.caller.$i"))
    }

    fun installEntry() {
        val s = host
        val handle = HookRuntime.hookMethod(s.method("preset"), object : HookCallback() {
            override fun before(param: Call) {
                val composer = param.args?.getOrNull(s.index("preset.composer")) ?: return
                if (!s.type("composer").isInstance(composer) || reader.renderingHighlightScreenEntry.get() == true) return
                reader.renderingHighlightScreenEntry.set(true)
                try {
                    ReaMicroSettingsHook.renderReaderHighlightScreenEntryCard(
                        bookKey = ReaderHighlightBookContext.bookKey,
                        bookTitle = ReaderHighlightBookContext.bookTitle,
                        composer = composer,
                    ) { reader.openReaderCompletionPlanPage() }
                } finally {
                    reader.renderingHighlightScreenEntry.remove()
                }
            }
        })
        try {
            HookRuntime.deoptimize(s.method("page"))
            for (i in 0 until s.index("preset.callers")) HookRuntime.deoptimize(s.method("preset.caller.$i"))
        } catch (error: Throwable) {
            handle.unhook(); throw error
        }
        ModuleResources.cleanup { reader.renderingHighlightScreenEntry.remove() }
    }

    fun installRules() {
        val s = host
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(s.method("root.EpubFamily"), object : HookCallback() {
                override fun before(param: Call) {
                    if (param.args?.getOrNull(s.index("root.EpubFamily.status")) === s.status("Hidden"))
                        reader.pendingReaderHighlightSheet = null
                }
            })
            handles += HookRuntime.hookMethod(s.method("body.EpubFamily"), object : HookCallback() {
                override fun before(param: Call) {
                    val request = reader.pendingReaderHighlightSheet ?: return
                    val body = param.thisObject ?: return
                    val (state, receiver) = s.capture("EpubFamily", body) ?: return
                    if (state !== s.status("EpubFamily")) return
                    val composer = param.args?.getOrNull(s.index("body.EpubFamily.composer")) ?: return
                    if (!s.type("composer").isInstance(composer)) return
                    reader.updateReaderHighlightBookContext(request.bookKey, request.bookTitle, "highlight sheet render")
                    val rendered = ReaMicroSettingsHook.renderReaderHighlightRulesSheetFromReader(
                        globalRules = request.globalRules,
                        bookKey = request.bookKey,
                        bookTitle = request.bookTitle,
                        composer = composer,
                    ) {
                        reader.pendingReaderHighlightSheet = null
                        reader.sendReaderUiSheetStatus(receiver, "Hidden")
                    }
                    if (rendered) param.result = requireNotNull(HostReaderFeatures.current).unit
                }
            })
            deoptimizeBody("EpubFamily")
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        ModuleResources.cleanup { reader.pendingReaderHighlightSheet = null }
    }

    fun installHeight() {
        val s = host
        class Frame(val height: Int)
        val frames = ThreadLocal<ArrayDeque<Frame>>()
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            for ((bodyMethod, roles) in HostHighlightUiQueries.FAMILIES.groupBy { s.method("body.$it") }) {
                handles += HookRuntime.hookMethod(bodyMethod, object : HookCallback() {
                    override fun before(param: Call) {
                        val body = param.thisObject ?: return
                        val role = roles.firstOrNull { s.capture(it, body) != null } ?: return
                        val frame = Frame(s.index("body.$role.height"))
                        val stack = frames.get() ?: ArrayDeque<Frame>().also(frames::set)
                        stack.addLast(frame); param.setObjectExtra("reamicro.highlight.height", frame)
                    }
                    override fun after(param: Call) {
                        val frame = param.getObjectExtra("reamicro.highlight.height") as? Frame ?: return
                        val stack = frames.get() ?: return
                        if (stack.pollLast() !== frame) stack.clear()
                        if (stack.isEmpty()) frames.remove()
                    }
                })
            }
            handles += HookRuntime.hookMethod(s.method("height"), object : HookCallback() {
                override fun before(param: Call) {
                    val frame = frames.get()?.peekLast() ?: return
                    val args = param.args ?: return
                    val height = args[1] as? Float ?: return
                    val native = (s.method("dpInt").invoke(null, frame.height) as Number).toFloat()
                    if (kotlin.math.abs(height - native) > 0.01f) return
                    args[1] = (s.method("dpInt").invoke(null, READER_RULE_SHEET_HEIGHT_DP) as Number).toFloat()
                }
            })
            HostHighlightUiQueries.FAMILIES.forEach(::deoptimizeBody)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        ModuleResources.cleanup { frames.remove() }
    }
}
