package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Adds one native scroll viewport around the host's complete backup card column. */
class BookBackupScrollHook(
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val scope = BookBackupScrollScope()
    private val handles = ArrayList<XposedInterface.HookHandle>()
    private val registered = AtomicBoolean()
    private val appliedLogged = AtomicBoolean()
    private val failureLogged = AtomicBoolean()
    @Volatile private var closed = false
    private var installed = false

    fun install() {
        if (closed) return
        if (registered.compareAndSet(false, true)) {
            DexKitUpdateScan.addStateListener {
                if (!closed && !DexKitUpdateScan.isBusy()) installWhenResolved()
            }
        }
        installWhenResolved()
    }

    fun onHostStarted() = installWhenResolved()

    @Synchronized private fun installWhenResolved() {
        if (closed || installed) return
        val host = requireNotNull(com.reamicro.fix.core.HostBookUiBindings.backup) { "Backup scroll contract unavailable" }
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
        val pending = ArrayList<XposedInterface.HookHandle>()
        runCatching {
            val content = host.method("content")
            val materialize = host.method("materialize")
            val rememberState = host.method("rememberScroll")
            val verticalScroll = host.method("verticalScroll")
            val startGroup = ui.method("startGroup")
            val endGroup = ui.method("endGroup")
            val rootModifier = requireNotNull(host.field("rootModifier").get(null))
            check(materialize == ui.method("materialize"))
            // MT: BookBackupContent materializes this FillElement before any card. Deoptimize
            // that verified caller so ART cannot bypass the materialization interception.
            val deoptimized = HookRuntime.deoptimize(content)
            pending += HookRuntime.hookMethod(content, object : HookCallback() {
                override fun before(param: Call) {
                    val settings = settingsProvider()
                    if (closed || !settings.moduleEnabled || !settings.bookBackupScrollEnabled) return
                    val composer = param.args?.getOrNull(7) ?: return
                    param.setObjectExtra(FRAME_KEY, scope.enter(composer))
                }

                override fun after(param: Call) {
                    val frame = param.getObjectExtra(FRAME_KEY) as? BookBackupScrollScope.Frame ?: return
                    scope.leave(frame)
                }
            })
            pending += HookRuntime.hookMethod(materialize, object : HookCallback() {
                override fun before(param: Call) {
                    if (closed) return
                    val composer = param.args?.getOrNull(0) ?: return
                    val modifier = param.args?.getOrNull(1) ?: return
                    if (modifier !== rootModifier || !scope.claimOuterLayout(composer)) return
                    runCatching {
                        startGroup.invoke(composer, SCROLL_GROUP)
                        try {
                            val state = requireNotNull(rememberState.invoke(null, composer))
                            param.args?.set(1, requireNotNull(verticalScroll.invoke(null, modifier, state, host.number("scrollMask"))))
                        } finally {
                            endGroup.invoke(composer, false)
                        }
                        if (appliedLogged.compareAndSet(false, true)) {
                            HookRuntime.logAlways("$LOG_PREFIX applied to complete backup column")
                        }
                    }.onFailure(::reportRenderFailure)
                }
            })
            handles += pending
            installed = true
            HookInstallReport.record("BookBackupScrollHook", "outerColumn", true)
            HookRuntime.logAlways("$LOG_PREFIX installed, content deoptimized=$deoptimized")
        }.onFailure { error ->
            pending.asReversed().forEach { runCatching { it.unhook() } }
            HookInstallReport.record("BookBackupScrollHook", "outerColumn", false, error)
            HookRuntime.logError("$LOG_PREFIX installation deferred after binding error", error)
        }
    }

    @Synchronized fun closeForReload() {
        closed = true
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
    }

    private fun reportRenderFailure(error: Throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            HookRuntime.logError("$LOG_PREFIX could not attach outer scroll modifier", error)
        }
    }

    private companion object {
        const val FRAME_KEY = "ReaMicro.BackupScroll.frame"
        const val SCROLL_GROUP = 0x524d6273
        const val LOG_PREFIX = "ReaMicro backup scroll"
    }
}
