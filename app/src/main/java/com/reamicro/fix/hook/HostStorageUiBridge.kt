package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Executable

/** Native import rows retain the host sheet's state, navigation and Compose grouping. */
internal class HostStorageUiBridge(private val drive: WebDavDriveHook) {
    @Volatile private var importScope: WeakReference<Any>? = null
    @Volatile private var importIntentReceiver: WeakReference<Any>? = null
    private val ui = HostNativeUi(drive.classLoader)
    private val contract by lazy { requireNotNull(HostStorageBindings.ui) { "Storage UI contract unavailable" } }
    private val native by lazy { requireNotNull(HostUiBindings.current) { "Native storage UI unavailable" } }
    private val handles = arrayListOf<XposedInterface.HookHandle>()
    private val nativeStorageTitles = setOf("百度网盘", "阿里云盘", "115网盘", "本地存储")
    private fun hook(method: Executable, callback: HookCallback) { handles += HookRuntime.hookMethod(method, callback) }
    fun install() {
        check(handles.isEmpty())
        check(native.composerClass == contract.type("composer"))
        requireNotNull(HostStorageBindings.routes) { "Storage routes unavailable" }
        val records = requireNotNull(HostStorageBindings.records) { "Storage records unavailable" }
        val auth = contract.method("authorized").parameterTypes[1]
        check(auth.isAssignableFrom(records.type("auth")) && auth.isAssignableFrom(records.type("localAuth")))
        for (name in listOf("storageRoutes", "storageOperations", "cloudStorageWebDavData",
            "cloudStorageWebDavRefresh", "cloudStorageWebDavTitle", "cloudStorageWebDavViewModelState",
            "cloudStorageWebDavStrings", "cloudStorageWebDavAuthTips")) check(
            com.reamicro.fix.core.HookInstallReport.snapshot().any { it.feature == FEATURE_ID && it.name == name && it.ok }
        ) { "Storage dependency did not install: $name" }
        try {
            installImportRows()
            installAvailableDriveLabels()
            installStorageTitle()
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            handles.clear()
            throw error
        }
    }
    private fun installImportRows() {
        val content = contract.method("sheet")
        hook(content, object : HookCallback() {
            override fun before(param: Call) {
                importScope = if (param.args[2] == true) WeakReference(param.args[0]) else null
                importIntentReceiver = if (param.args[2] == true) WeakReference(param.args[1]) else null
            }
        })
        val local = contract.method("local")
        hook(local, object : HookCallback() {
            override fun after(param: Call) {
                if (param.throwable != null) return
                val composer = param.args[1] ?: return
                val scope = importScope?.get() ?: return
                native.instance("startGroup", composer, 0x524D7351)
                try {
                    if (drive.canShowLocalLibraryEntry()) {
                        divider(composer)
                        drive.pushLocalLibraryIcon()
                        try {
                            contract.method("authorized").invoke(null, BACKUP_TYPE_LOCAL_LIBRARY, drive.localLibraryAuth(),
                                native.function(contract.type("function0")) {
                                    openImport(scope, BACKUP_TYPE_LOCAL_LIBRARY)
                                    drive.targetUnit()
                                }, composer, 0)
                        } finally { drive.popLocalLibraryIcon() }
                    }
                    if (drive.canShowWebDavEntry()) {
                        divider(composer)
                        val click = native.function(contract.type("function0")) {
                            openImport(scope, BACKUP_TYPE_WEBDAV)
                            drive.targetUnit()
                        }
                        if (hasWebDavLogin(drive.currentContext())) {
                            drive.pushWebDavIcon()
                            try {
                                contract.method("authorized").invoke(null, BACKUP_TYPE_WEBDAV, drive.webDavAuth(), click, composer, 0)
                            } finally { drive.popWebDavIcon() }
                        } else contract.method("unauthorized").invoke(null, BACKUP_TYPE_WEBDAV, click, composer, 0)
                    }
                } finally { native.instance("endGroup", composer, false) }
            }
        })
        HookRuntime.deoptimize(content)
        HookRuntime.deoptimize(local)
    }
    private fun openImport(scope: Any, kind: Int) {
        val receiver = importIntentReceiver?.get()
        importScope = null
        importIntentReceiver = null
        if (receiver != null) {
            check(contract.type("receiver").isInstance(receiver))
            contract.method("dismiss").invoke(receiver, contract.field("hide").get(null))
        }
        drive.navigateStorage(scope, kind)
    }
    private fun divider(composer: Any) {
        ui.divider(ui.padding(ui.modifier(), ui.dp(56), 0f, 0f, 0f), composer)
    }
    private fun installAvailableDriveLabels() {
        val text = native.method("text")
        hook(text, object : HookCallback() {
            override fun before(param: Call) {
                val title = param.args[0] as? String ?: return
                if (title !in nativeStorageTitles) return
                val replacement = when {
                    (drive.webDavIconDepth.get() ?: 0) > 0 || (drive.webDavBackupCardDepth.get() ?: 0) > 0 -> WEBDAV_TITLE
                    (drive.localLibraryIconDepth.get() ?: 0) > 0 -> LOCAL_LIBRARY_TITLE
                    else -> {
                        if (drive.syncAvailableProvider.get() == BACKUP_TYPE_WEBDAV) WEBDAV_TITLE else return
                    }
                }
                param.args[0] = replacement
                param.args[17] = ((param.args[17] as Int) and 14.inv()) or 6
                param.args[19] = (param.args[19] as Int) and 1.inv()
            }
        })
        HookRuntime.deoptimize(text)
    }
    private fun installStorageTitle() {
        val topBar = native.method("topBar")
        hook(topBar, object : HookCallback() {
            override fun before(param: Call) {
                val title = when {
                    (drive.localLibraryCloudTitleDepth.get() ?: 0) > 0 -> LOCAL_LIBRARY_TITLE
                    (drive.webDavCloudTitleDepth.get() ?: 0) > 0 -> WEBDAV_TITLE
                    else -> return
                }
                param.args[0] = title
                param.args[9] = ((param.args[9] as Int) and 14.inv()) or 6
                param.args[10] = (param.args[10] as Int) and 1.inv()
            }
        })
        HookRuntime.deoptimize(topBar)
    }
}
