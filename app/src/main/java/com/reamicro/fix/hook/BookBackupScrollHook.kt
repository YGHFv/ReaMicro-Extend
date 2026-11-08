package com.reamicro.fix.hook

import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.core.HostProfile
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiMethodByArity
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Adds one native scroll viewport around the host's complete backup card column. */
class BookBackupScrollHook(
    private val loader: ClassLoader,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val scope = BookBackupScrollScope()
    private val handles = ArrayList<XposedInterface.HookHandle>()
    private val registered = AtomicBoolean()
    private val appliedLogged = AtomicBoolean()
    private val failureLogged = AtomicBoolean()
    @Volatile private var closed = false
    private var installed = false
    private var lastFailedProfile: HostProfile.Parsed? = null

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

    /** Fresh scan roles are read directly; HostSymbols may still contain the old cache. */
    @Synchronized private fun installWhenResolved() {
        if (closed || installed || HostSymbols.profileId != "2.3.4-b1") return
        val profile = HostAutoScanner.snapshot()?.profile ?: return
        if (listOf("rememberState", "verticalScrollDefault").any {
                profile.methodOf(SCROLL_ROLE, it) == null || profile.methodOwners["$SCROLL_ROLE#$it"] == null
            }) return
        if (lastFailedProfile === profile) return
        val pending = ArrayList<XposedInterface.HookHandle>()
        runCatching {
            val composerType = loader.hostClass(HostClasses.Compose.COMPOSER)
            val modifierType = loader.hostClass(HostClasses.Compose.MODIFIER)
            val content = loader.hostClass(HostClasses.Host.BOOK_BACKUP_SCREEN)
                .hostMethodsNamed("BookBackupContent")
                .single { it.parameterCount == 9 && it.parameterTypes[7] == composerType }
            val materialize = loader.hostClass("ReaMicroHost.Materialize")
                .hostAbiMethodByArity("h", 2).apply { isAccessible = true }
            check(materialize.parameterTypes.contentEquals(arrayOf(composerType, modifierType)))
            val rememberState = scanMethod(profile, "rememberState", composerType)
            val verticalScroll = scanMethod(
                profile, "verticalScrollDefault", modifierType, rememberState.returnType,
                Int::class.javaPrimitiveType,
            )
            // These are the already verified group operations used by B2NativeUi.column.
            val startGroup = composerType.hostAbiMethod("b0", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val endGroup = composerType.hostAbiMethod("p", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val ui = B2NativeUi(loader)
            val fillSize = ui.fillSize(ui.modifier())

            // MT: BookBackupContent materializes this FillElement before any card. Deoptimize
            // that verified caller so ART cannot bypass the materialization interception.
            val deoptimized = XposedBridge.deoptimize(content)
            pending += XposedBridge.hookMethod(content, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (closed || !settingsProvider().moduleEnabled) return
                    val composer = param.args?.getOrNull(7) ?: return
                    param.setObjectExtra(FRAME_KEY, scope.enter(composer))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val frame = param.getObjectExtra(FRAME_KEY) as? BookBackupScrollScope.Frame ?: return
                    scope.leave(frame)
                }
            })
            pending += XposedBridge.hookMethod(materialize, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (closed) return
                    val composer = param.args?.getOrNull(0) ?: return
                    if (!scope.claimOuterLayout(composer)) return
                    val modifier = param.args?.getOrNull(1) ?: return
                    if (modifier !== fillSize) {
                        reportRenderFailure(IllegalStateException("Backup outer modifier changed"))
                        return
                    }
                    runCatching {
                        startGroup.invoke(composer, SCROLL_GROUP)
                        try {
                            val state = requireNotNull(rememberState.invoke(null, composer))
                            // Native verticalScroll default mask: bit 2 enables scrolling;
                            // orientation is vertical and reverse scrolling is false.
                            param.args?.set(1, requireNotNull(verticalScroll.invoke(null, modifier, state, 2)))
                        } finally {
                            endGroup.invoke(composer, false)
                        }
                        if (appliedLogged.compareAndSet(false, true)) {
                            XposedBridge.logAlways("$LOG_PREFIX applied to complete backup column")
                        }
                    }.onFailure(::reportRenderFailure)
                }
            })
            handles += pending
            installed = true
            HookInstallReport.record("BookBackupScrollHook", "outerColumn", true)
            XposedBridge.logAlways("$LOG_PREFIX installed, content deoptimized=$deoptimized")
        }.onFailure { error ->
            pending.asReversed().forEach { runCatching { it.unhook() } }
            lastFailedProfile = profile
            HookInstallReport.record("BookBackupScrollHook", "outerColumn", false, error)
            XposedBridge.logError("$LOG_PREFIX installation deferred after binding error", error)
        }
    }

    private fun scanMethod(profile: HostProfile.Parsed, name: String, vararg types: Class<*>?): Method {
        val owner = requireNotNull(profile.methodOwners["$SCROLL_ROLE#$name"])
        val actualName = requireNotNull(profile.methodOf(SCROLL_ROLE, name))
        return Class.forName(owner, false, loader).getDeclaredMethod(actualName, *types).also {
            check(Modifier.isStatic(it.modifiers)) { "Expected static backup scroll factory" }
            it.isAccessible = true
        }
    }

    @Synchronized fun closeForReload() {
        closed = true
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
    }

    private fun reportRenderFailure(error: Throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            XposedBridge.logError("$LOG_PREFIX could not attach outer scroll modifier", error)
        }
    }

    private companion object {
        const val SCROLL_ROLE = "ReaMicroHost.BackupScroll"
        const val FRAME_KEY = "ReaMicro.BackupScroll.frame"
        const val SCROLL_GROUP = 0x524d6273
        const val LOG_PREFIX = "ReaMicro backup scroll"
    }
}
