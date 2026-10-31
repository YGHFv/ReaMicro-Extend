package com.reamicro.fix.reload

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HotReloadSourceContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = root.resolve("java/com/reamicro/fix/$path").readText(Charsets.UTF_8)

    @Test fun requestUsesFrameworkTargetAndNeverKillsOrRecreatesHost() {
        val request = source("reload/HostHotReload.kt") + source("reload/HotReloadService.kt") +
            source("reload/HostGeneration.kt")
        assertTrue(request.contains("service.runningTargets"))
        assertTrue(request.contains("service.hotReloadModule(target"))
        for (forbidden in listOf("killProcess", "System.exit", ".recreate(", "HostRestartActivity")) {
            assertFalse(forbidden, request.contains(forbidden))
        }
    }

    @Test fun newGenerationReinstallsInsteadOfExpectingLifecycleReplay() {
        val entry = source("hook/ReaMicroLibXposedEntry.kt")
        assertTrue(entry.contains("override fun onHotReloading"))
        assertTrue(entry.contains("override fun onHotReloaded"))
        assertTrue(entry.contains("param.oldHookHandles.forEach { it.unhook() }"))
        assertTrue(entry.contains("installPackage(packageName, loader, application.applicationInfo)"))
        assertTrue(entry.contains("HookInstallReport.failures().isEmpty()"))
    }

    @Test fun providerUsesBinderIdentityAndNotCallerSuppliedPid() {
        val provider = source("reload/HotReloadProvider.kt")
        assertTrue(provider.contains("Binder.getCallingUid()"))
        assertTrue(provider.contains("Binder.getCallingPid()"))
        assertTrue(provider.contains("callingPackage"))
        assertFalse(provider.contains("getInt(\"pid"))
    }

    @Test fun restartAndReloadRemainSeparateWithoutReloadConfirmation() {
        val scan = source("hook/ReaMicroSettingsHook.DexKit.kt")
        assertTrue(scan.contains("HostRestart.request(activity)"))
        assertFalse(scan.contains("HostHotReload"))
        assertTrue(source("hook/ReaMicroSettingsHook.SettingsUi.kt").contains("about_completion_hot_reload"))
        assertFalse(source("reload/HostHotReload.kt").contains("AlertDialog"))
        assertTrue(scan.contains("actions.addView(settingsDialogButton(activity, \"重启阅微\""))
    }

    @Test fun reloadKeepsActivityAndNeverLaunchesRelay() {
        val service = source("reload/HotReloadService.kt")
        assertTrue(service.contains("request.lifecycle.submit()"))
        assertTrue(source("reload/HostHotReload.kt").contains("putBoolean(HotReloadContract.IN_PLACE, true)"))
        assertFalse(source("reload/HostHotReload.kt").contains("startIntentSender"))
        val generation = source("reload/HostGeneration.kt")
        assertTrue(generation.contains("activity.hasWindowFocus()"))
        assertFalse(generation.contains("startActivity"))
        assertFalse(generation.contains(".finish()"))
        val manifest = root.resolve("AndroidManifest.xml").readText(Charsets.UTF_8)
        assertFalse(manifest.contains(".reload.HotReloadActivity"))
        assertTrue(source("reload/HostCompositionReload.kt").contains("container.removeView(root)"))
    }

    @Test fun unknownExtensionsAndUnretirableUiCannotSilentlyOptIn() {
        assertTrue(source("hook/ReaMicroHookEntry.kt").contains("feature is com.reamicro.fix.association.provider.ReloadableFeatureProvider"))
        assertTrue(source("hook/ReaMicroHookEntry.kt").contains("HostGeneration.unsupportedExtension(feature.id)"))
        assertTrue(source("hook/ModuleComposeOwner.kt").contains("ModuleUiReloadGuard.markUsed()"))
        assertFalse(root.resolve("resources/META-INF/xposed/module.prop").readText().contains("autoHotReload=true"))
    }
}
