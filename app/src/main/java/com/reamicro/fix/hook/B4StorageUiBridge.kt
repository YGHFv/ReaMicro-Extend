package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostAbiField
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.hook.webdav.APP_TOP_BAR_CLASS
import com.reamicro.fix.hook.webdav.BACKUP_TYPE_WEBDAV
import com.reamicro.fix.hook.webdav.BACKUP_TYPE_LOCAL_LIBRARY
import com.reamicro.fix.hook.webdav.BOOK_LIBRARY_LOCAL_METHOD
import com.reamicro.fix.hook.webdav.BOOK_LIBRARY_SHEET_CLASS
import com.reamicro.fix.hook.webdav.CLOUD_AUTHORIZED_ROW_METHOD
import com.reamicro.fix.hook.webdav.CLOUD_UNAUTH_ROW_METHOD
import com.reamicro.fix.hook.webdav.FUNCTION0_CLASS
import com.reamicro.fix.hook.webdav.FUNCTION1_CLASS
import com.reamicro.fix.hook.webdav.MATERIAL3_TEXT_CLASS
import com.reamicro.fix.hook.webdav.WEBDAV_TITLE
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.ref.WeakReference

/** B4 已内联旧列表 lambda；在仍稳定的原生行和顶栏边界补回存储入口。 */
internal class B4StorageUiBridge(private val drive: WebDavDriveHook) {
    @Volatile private var importScope: WeakReference<Any>? = null
    @Volatile private var importIntentReceiver: WeakReference<Any>? = null
    @Volatile private var importSheetState: WeakReference<Any>? = null
    private val importSheetCompositionDepth = ThreadLocal<Int>()
    private val ui = B2NativeUi(drive.classLoader)
    private val nativeStorageTitles = setOf("百度网盘", "阿里云盘", "115网盘", "本地存储")

    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        installImportRows()
        installAvailableDriveLabels()
        installStorageTitle()
    }

    private fun installImportRows() {
        val sheet = drive.cls(BOOK_LIBRARY_SHEET_CLASS)
        val content = sheet.hostMethodsNamed("BookLibrarySheet").single { it.parameterCount == 5 }
        XposedBridge.hookMethod(content, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 工作表内容延后组合，外层返回时清 ThreadLocal 会丢失实际导航上下文。
                importScope = if (param.args[2] == true) WeakReference(param.args[0]) else null
                importIntentReceiver = if (param.args[2] == true) WeakReference(param.args[1]) else null
                importSheetCompositionDepth.set((importSheetCompositionDepth.get() ?: 0) + 1)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val next = (importSheetCompositionDepth.get() ?: 0) - 1
                if (next <= 0) importSheetCompositionDepth.remove()
                else importSheetCompositionDepth.set(next)
            }
        })
        val sheetStateClass = drive.classLoader.hostClass("androidx.compose.material3.SheetState")
        val modalSheet = drive.cls("fgd").declaredMethods.single {
            it.name == "b" && it.parameterCount in 17..20 &&
                sheetStateClass.isAssignableFrom(it.parameterTypes[2])
        }
        XposedBridge.hookMethod(modalSheet, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((importSheetCompositionDepth.get() ?: 0) <= 0) return
                val state = param.args?.getOrNull(2) ?: return
                importSheetState = WeakReference(state)
            }
        })
        val local = sheet.hostMethodsNamed(BOOK_LIBRARY_LOCAL_METHOD).single { it.parameterCount == 3 }
        XposedBridge.hookMethod(local, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val composer = param.args?.getOrNull(1) ?: return
                val scope = importScope?.get() ?: return
                if (drive.canShowLocalLibraryEntry()) {
                    divider(composer)
                    drive.pushLocalLibraryIcon()
                    try {
                        drive.method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_AUTHORIZED_ROW_METHOD, 5).invoke(
                            null, BACKUP_TYPE_LOCAL_LIBRARY, drive.localLibraryAuth(),
                            drive.functionProxy("B4LocalLibraryImportOpen", FUNCTION0_CLASS) {
                                openImport(scope, BACKUP_TYPE_LOCAL_LIBRARY)
                                drive.targetUnit()
                            }, composer, 0,
                        )
                    } finally {
                        drive.popLocalLibraryIcon()
                    }
                }
                if (!drive.canShowWebDavEntry()) return
                divider(composer)
                if (hasWebDavLogin(drive.currentContext())) {
                    drive.pushWebDavIcon()
                    try {
                        drive.method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_AUTHORIZED_ROW_METHOD, 5).invoke(
                            null,
                            BACKUP_TYPE_WEBDAV,
                            drive.webDavAuth(),
                            drive.functionProxy("B4WebDavImportOpen", FUNCTION0_CLASS) {
                                openImport(scope, BACKUP_TYPE_WEBDAV)
                                drive.targetUnit()
                            },
                            composer,
                            0,
                        )
                    } finally {
                        drive.popWebDavIcon()
                    }
                } else {
                    drive.method(BOOK_LIBRARY_SHEET_CLASS, CLOUD_UNAUTH_ROW_METHOD, 4).invoke(
                        null,
                        BACKUP_TYPE_WEBDAV,
                        drive.functionProxy("B4WebDavImportLogin", FUNCTION0_CLASS) {
                            openImport(scope, BACKUP_TYPE_WEBDAV)
                            drive.targetUnit()
                        },
                        composer,
                        0,
                    )
                }
                XposedBridge.logAlways("ReaMicro B4 WebDAV import row rendered")
            }
        })
        XposedBridge.deoptimize(content)
        XposedBridge.deoptimize(modalSheet)
        XposedBridge.deoptimize(local)
    }

    private fun openImport(scope: Any, kind: Int) {
        importScope = null
        val receiver = importIntentReceiver?.get()
        importIntentReceiver = null
        val sheetState = currentImportSheetState()
        if (sheetState == null) {
            dismissImportMenu(receiver)
            drive.navigateStorage(scope, kind)
            return
        }
        runCatching {
            val hide = sheetState.javaClass.hostMethod(
                "hide",
                drive.classLoader.hostClass("kotlin.coroutines.Continuation"),
            )
            val suspendHide = drive.functionProxy("B4ImportSheetHide", "kotlin.jvm.functions.Function2") { args ->
                hide.invoke(sheetState, args?.getOrNull(1))
            }
            val builders = drive.cls("wvd")
            val function2 = drive.cls("kotlin.jvm.functions.Function2")
            val starter = builders.declaredMethods.single {
                it.name == "j" && it.parameterCount == 5 &&
                    it.parameterTypes[0].isInstance(scope) &&
                    function2.isAssignableFrom(it.parameterTypes[3]) &&
                    it.parameterTypes[4] == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val job = starter.invoke(null, scope, null, null, suspendHide, 3)
            val completion = drive.functionProxy("B4AfterImportSheetHidden", FUNCTION1_CLASS) { args ->
                val error = args?.firstOrNull() as? Throwable
                if (error != null) {
                    XposedBridge.log("ReaMicro B4 import sheet hide failed: ${error.stackTraceToString()}")
                }
                dismissImportMenu(receiver)
                drive.navigateStorage(scope, kind)
                drive.targetUnit()
            }
            val function1 = drive.cls(FUNCTION1_CLASS)
            val register = job.javaClass.methods.single {
                it.parameterCount == 1 && function1.isAssignableFrom(it.parameterTypes[0])
            }.apply { isAccessible = true }
            register.invoke(job, completion)
        }.onFailure {
            XposedBridge.log("ReaMicro B4 import sheet launch failed: ${it.stackTraceToString()}")
            dismissImportMenu(receiver)
            drive.navigateStorage(scope, kind)
        }
    }

    private fun dismissImportMenu(receiver: Any?) {
        if (receiver == null) return
        runCatching {
            val receiverClass = drive.cls("app.zhendong.reamicro.arch.IntentReceiver")
            val intentClass = drive.cls("app.zhendong.reamicro.arch.UiIntent")
            check(receiverClass.isInstance(receiver))
            val hideIntentClass = drive.cls("sd4")
            check(intentClass.isAssignableFrom(hideIntentClass))
            val hideIntent = hideIntentClass.hostAbiField("a").get(null)
            receiverClass.hostAbiMethod("h", intentClass).invoke(receiver, hideIntent)
        }.onFailure {
            XposedBridge.log("ReaMicro B4 import menu dismiss failed: ${it.stackTraceToString()}")
        }
    }

    private fun currentImportSheetState(): Any? {
        val expected = drive.classLoader.hostClass("androidx.compose.material3.SheetState")
        return importSheetState?.get()?.takeIf(expected::isInstance)
    }

    private fun divider(composer: Any) {
        ui.divider(ui.padding(ui.modifier(), ui.dp(56), 0f, 0f, 0f), composer)
    }

    private fun installAvailableDriveLabels() {
        drive.cls(MATERIAL3_TEXT_CLASS).declaredMethods
            .filter { it.parameterTypes.firstOrNull() == String::class.java }
            .forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val title = param.args?.getOrNull(0) as? String ?: return
                        if (title !in nativeStorageTitles) return
                        if ((drive.webDavIconDepth.get() ?: 0) > 0 ||
                            (drive.webDavBackupCardDepth.get() ?: 0) > 0) {
                            param.args?.set(0, WEBDAV_TITLE)
                            return
                        }
                        if ((drive.localLibraryIconDepth.get() ?: 0) > 0) {
                            param.args?.set(0, "本地书库")
                            return
                        }
                        val types = drive.syncAvailableTypes.get() ?: return
                        val index = drive.syncAvailableRowIndex.get() ?: 0
                        if (types.getOrNull(index) == BACKUP_TYPE_WEBDAV) {
                            param.args?.set(0, WEBDAV_TITLE)
                        }
                        drive.syncAvailableRowIndex.set(index + 1)
                    }
                })
            }
    }

    private fun installStorageTitle() {
        val topBar = drive.cls(APP_TOP_BAR_CLASS).hostMethodsNamed("AppTopBar-1YH7lEI")
            .single { it.parameterCount >= 11 }
        XposedBridge.hookMethod(topBar, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val title = when {
                    (drive.localLibraryCloudTitleDepth.get() ?: 0) > 0 -> "本地书库"
                    (drive.webDavCloudTitleDepth.get() ?: 0) > 0 -> WEBDAV_TITLE
                    else -> return
                }
                val args = param.args ?: return
                args[0] = title
                val changedIndex = args.size - 2
                val defaultIndex = args.size - 1
                args[changedIndex] = ((args[changedIndex] as Int) and 1.inv()) or 6
                args[defaultIndex] = (args[defaultIndex] as Int) and 1.inv()
            }
        })
        XposedBridge.deoptimize(topBar)
    }
}
