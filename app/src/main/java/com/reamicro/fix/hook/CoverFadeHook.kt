package com.reamicro.fix.hook

import com.reamicro.fix.core.HostAutoScanner
import com.reamicro.fix.core.HostProfile
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** One transition decision for shelf, loading, book details and reading banners. */
internal class CoverFadeHook(
    private val loader: ClassLoader,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val knownSources = CoverSources()
    private val handles = ArrayList<XposedInterface.HookHandle>()
    private val registered = AtomicBoolean()
    private val runtimeFailureLogged = AtomicBoolean()
    @Volatile private var closed = false
    private var sourceHooksInstalled = false
    private var transitionInstalled = false
    private var lastFailedProfile: HostProfile.Parsed? = null

    fun install() {
        if (registered.compareAndSet(false, true)) {
            DexKitUpdateScan.addStateListener {
                if (!closed && !DexKitUpdateScan.isBusy()) installWhenResolved()
            }
        }
        installWhenResolved()
    }

    fun onHostStarted() = installWhenResolved()

    @Synchronized private fun installWhenResolved() {
        if (closed) return
        // These existing verified roles can be captured before a cold-cache background scan ends.
        if (!sourceHooksInstalled) {
            val installed = ArrayList<XposedInterface.HookHandle>()
            runCatching {
                val publisher = loader.hostClass("app.zhendong.reamicro.data.res.book.Publisher")
                val constructors = publisher.declaredConstructors.mapNotNull { constructor ->
                    PublisherCoverArgument.index(constructor)?.let { constructor to it }
                }
                check(constructors.isNotEmpty()) { "Publisher cover constructor has changed" }
                constructors.forEach { (constructor, coverIndex) ->
                    installed += XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!closed && param.throwable == null) {
                                knownSources.remember(PublisherCoverArgument.value(coverIndex, param.args))
                            }
                        }
                    })
                }
                val logicalOwner = "app.zhendong.reamicro.ui.home.HomeScreenKt"
                val logicalMethod = "OldBookCover-ziNgDLE"
                val owner = Class.forName(HostSymbols.resolveMethodOwner(logicalOwner, logicalMethod), false, loader)
                val name = HostSymbols.resolveMethodName(logicalOwner, logicalMethod)
                val method = owner.declaredMethods.single {
                    it.name == name && Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.firstOrNull() == String::class.java
                }
                installed += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!closed) knownSources.remember(param.args[0])
                    }
                })
                handles += installed
                sourceHooksInstalled = true
            }.onFailure { error ->
                installed.asReversed().forEach { runCatching { it.unhook() } }
                XposedBridge.logError("ReaMicro cover source hooks deferred", error)
            }
        }
        if (transitionInstalled) return
        val profile = HostAutoScanner.snapshot()?.profile ?: return
        val factoryName = profile.classOf("ReaMicroHost.CoilCrossfadeFactory") ?: return
        val requestName = profile.classOf("ReaMicroHost.CoilImageRequest") ?: return
        if (lastFailedProfile === profile) return
        runCatching {
            val bindings = CoverFadeBindings(
                Class.forName(factoryName, false, loader), Class.forName(requestName, false, loader),
            )
            handles += XposedBridge.hookMethod(bindings.create, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (closed || !CoverFadePolicy.enabled(settingsProvider())) return
                    val result = param.args.getOrNull(1) ?: return
                    runCatching {
                        if (bindings.isCoverResult(result, knownSources)) {
                            param.result = bindings.withoutFade(param.args[0], result)
                        }
                    }.onFailure { error ->
                        if (runtimeFailureLogged.compareAndSet(false, true)) {
                            XposedBridge.logError("ReaMicro cover transition preserved after binding error", error)
                        }
                    }
                }
            })
            transitionInstalled = true
            // MT confirms this Compose state update calls the factory. Undo ART inlining when
            // installing after the background scan, while keeping its request/state logic intact.
            runCatching {
                val painter = Class.forName("coil3.compose.AsyncImagePainter", false, loader)
                val success = Class.forName("coil3.compose.AsyncImagePainter\$State\$Success", false, loader)
                val state = success.interfaces.single()
                val update = painter.declaredMethods.single {
                    it.parameterTypes.contentEquals(arrayOf(state)) && it.returnType == Void.TYPE
                }
                check(XposedBridge.deoptimize(update)) { "AsyncImagePainter state update deoptimization failed" }
            }.onFailure { XposedBridge.logError("ReaMicro cover transition caller deoptimization failed", it) }
            XposedBridge.logAlways("ReaMicro cover transition hook ready (DexKit)")
        }.onFailure { error ->
            lastFailedProfile = profile
            XposedBridge.logError("ReaMicro cover transition hook deferred", error)
        }
    }

    @Synchronized fun closeForReload() {
        closed = true
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
        knownSources.clear()
    }
}
