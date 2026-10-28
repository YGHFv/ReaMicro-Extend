package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.cloud.webdav.WebDavImportSource
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.util.WeakHashMap

/** B2 网盘 UI 的角色/分支桥：不修改百度或 115 的真实账号状态。 */
internal class B2StorageUiBridge(private val drive: WebDavDriveHook) {
    private val loader = drive.classLoader
    private val ui by lazy { B2NativeUi(loader) }
    private data class ImportContext(val callback: Any)
    private val importContext = ThreadLocal<ImportContext?>()
    private val accountKind = ThreadLocal<Int?>()
    private val accountVm = ThreadLocal<Any?>()
    private val boundAccounts = WeakHashMap<Any, Int>()
    private val accountStates = WeakHashMap<Any, Int>()
    private val accountFlows = WeakHashMap<Any, Int>()
    private val storageConstructing = ThreadLocal<Int?>()
    private val importSource = ThreadLocal<WebDavImportSource?>()
    private val workerSources = java.util.Collections.synchronizedMap(WeakHashMap<Any, WebDavImportSource>())

    private fun enterAccount(param: XC_MethodHook.MethodHookParam, kind: Int, vm: Any? = null) {
        param.setObjectExtra("reamicro.storage.previousKind", accountKind.get())
        param.setObjectExtra("reamicro.storage.previousVm", accountVm.get())
        param.setObjectExtra("reamicro.storage.accountScope", true)
        accountKind.set(kind)
        if (vm != null) accountVm.set(vm)
    }

    private fun leaveAccount(param: XC_MethodHook.MethodHookParam) {
        if (param.getObjectExtra("reamicro.storage.accountScope") != true) return
        accountKind.set(param.getObjectExtra("reamicro.storage.previousKind") as? Int)
        accountVm.set(param.getObjectExtra("reamicro.storage.previousVm"))
    }

    private fun clearAccounts() {
        boundAccounts.clear()
        accountStates.clear()
        accountFlows.clear()
        drive.clearWebDavAccountContext()
        drive.clearLocalLibraryAccountContext()
    }

    fun install() {
        B2StorageOperations(drive).install()
        HookInstallReport.install(FEATURE_ID, "b2ImportProvenance") {
            val repository = loader.hostClass(BOOKSHELF_REPOSITORY_CLASS)
            val entry = repository.declaredMethods.single { it.name == "n" && it.parameterCount == 9 }
            XposedBridge.hookMethod(entry, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val file = param.args[1] ?: return
                    val source = drive.platformFileKeys(file).firstNotNullOfOrNull { drive.pendingWebDavImports[it] }
                        ?: importSource.get() ?: return
                    param.args[4] = source.sourceUrl
                    if (source.sourceSize != null) param.args[5] = source.sourceSize
                    val mask = param.args[8] as Int
                    param.args[8] = mask and 8.inv() and (if (source.sourceSize != null) 16.inv() else -1)
                    XposedBridge.logAlways("ReaMicro B2 import source bound defaultMask=${param.args[8]} size=${source.sourceSize ?: 0}")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result ?: return
                    if (result.javaClass.name != loader.hostClass(KOTLIN_COROUTINE_SINGLETONS_CLASS).name) {
                        val file = param.args[1] ?: return
                        drive.platformFileKeys(file).forEach { drive.pendingWebDavImports.remove(it) }
                    }
                }
            })
            XposedBridge.deoptimize(entry)
            repository.hostMethodsNamed(BOOKSHELF_IMPORT_BOOK_METHOD).single { it.parameterCount == 7 }
                .let(XposedBridge::deoptimize)
            val workerClass = loader.hostClass("ReaMicroHost.NativeImportWorker")
            val workerInvoke = workerClass.getDeclaredMethod("v", Any::class.java)
            XposedBridge.hookMethod(workerInvoke, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val worker = param.thisObject ?: return
                    val file = workerClass.getDeclaredField("N").get(worker) ?: return
                    val source = workerSources[worker] ?: drive.platformFileKeys(file)
                        .firstNotNullOfOrNull { drive.pendingWebDavImports[it] } ?: return
                    workerSources[worker] = source
                    param.setObjectExtra("reamicro.storage.workerSource", true)
                    param.setObjectExtra("reamicro.storage.previousSource", importSource.get())
                    importSource.set(source)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("reamicro.storage.workerSource") != true) return
                    importSource.set(param.getObjectExtra("reamicro.storage.previousSource") as? WebDavImportSource)
                    if (param.throwable != null || param.result === drive.targetUnit()) {
                        val worker = param.thisObject ?: return
                        workerSources.remove(worker)
                        val file = workerClass.getDeclaredField("N").get(worker) ?: return
                        drive.platformFileKeys(file).forEach { drive.pendingWebDavImports.remove(it) }
                    }
                }
            })
            XposedBridge.deoptimize(workerInvoke)
            val update = repository.declaredMethods.single { it.name == "o" && it.parameterCount == 5 }
            XposedBridge.hookMethod(update, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val source = importSource.get() ?: return
                    // TXT 先转 ePub，重新导入又走 update；来源只能跟随原生工作协程，不能按新文件猜。
                    param.args[2] = source.sourceUrl
                    if (source.sourceSize != null) param.args[3] = source.sourceSize
                    XposedBridge.logAlways("ReaMicro B2 reimport source bound size=${source.sourceSize ?: 0}")
                }
            })
            XposedBridge.deoptimize(update)
            repository.declaredMethods.single { it.name == "p" && it.parameterCount == 4 }.let(XposedBridge::deoptimize)
        }
        HookInstallReport.install(FEATURE_ID, "b2ImportEntries") { installImport() }
        HookInstallReport.install(FEATURE_ID, "b2AccountRoutes") { installAccountRoutes() }
        HookInstallReport.install(FEATURE_ID, "b2AccountAuthProjection") { installAuthProjection() }
        HookInstallReport.install(FEATURE_ID, "b2AccountSettings") { installAccountSettings() }
        HookInstallReport.install(FEATURE_ID, "b2StorageDataProjection") { installStorageProjection() }
        HookInstallReport.install(FEATURE_ID, "b2StorageTitle") {
            val topBar = loader.hostClass(APP_TOP_BAR_CLASS).hostMethodsNamed("AppTopBar-1YH7lEI")
                .single { it.parameterCount == 11 }
            XposedBridge.hookMethod(topBar, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val title = when {
                        (drive.localLibraryCloudTitleDepth.get() ?: 0) > 0 -> LOCAL_LIBRARY_TITLE
                        (drive.webDavCloudTitleDepth.get() ?: 0) > 0 -> WEBDAV_TITLE
                        else -> return
                    }
                    param.args[0] = title
                    param.args[9] = ((param.args[9] as Int) and 1.inv()) or 6
                    param.args[10] = (param.args[10] as Int) and 1.inv()
                }
            })
            XposedBridge.deoptimize(topBar)
            loader.hostClass(CLOUD_STORAGE_SCREEN_CLASS).hostMethodsNamed(CLOUD_STORAGE_BAR_METHOD)
                .single { it.parameterCount == 5 }.let(XposedBridge::deoptimize)
        }
    }

    private fun installImport() {
        val content = loader.hostClass("ReaMicroHost.ImportCardContent")
        val invoke = content.getDeclaredMethod("r", Any::class.java, Any::class.java, Any::class.java)
        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.thisObject ?: return
                if (content.getDeclaredField("a").getByte(callback).toInt() != 0) return
                importContext.set(ImportContext(callback))
                param.setObjectExtra("reamicro.storage.importScope", true)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra("reamicro.storage.importScope") == true) importContext.remove()
            }
        })
        val local = loader.hostClass(BOOK_LIBRARY_SHEET_CLASS).hostMethodsNamed(BOOK_LIBRARY_LOCAL_METHOD)
            .single { it.parameterCount == 3 }
        XposedBridge.hookMethod(local, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val context = importContext.get() ?: return
                val composer = param.args?.getOrNull(1) ?: return
                if (drive.canShowWebDavEntry()) {
                    row("WebDAV", if (hasWebDavLogin(drive.currentContext())) "浏览网盘文件" else "配置 WebDAV 账号", composer) {
                        if (hasWebDavLogin(drive.currentContext())) openImport(context, BACKUP_TYPE_WEBDAV)
                        else drive.showWebDavLoginPage()
                    }
                }
                if (drive.canShowLocalLibraryEntry()) {
                    row(LOCAL_LIBRARY_TITLE, drive.localLibraryFolderSummary(), composer) {
                        openImport(context, BACKUP_TYPE_LOCAL_LIBRARY)
                    }
                }
                XposedBridge.logAlways("ReaMicro B2 storage import entries rendered WebDAV=${drive.canShowWebDavEntry()} local=${drive.canShowLocalLibraryEntry()}")
            }
        })
        XposedBridge.deoptimize(invoke)
    }

    private fun openImport(context: ImportContext, kind: Int) {
        val callback = context.callback
        val native = loader.hostClass("ReaMicroHost.ImportRowClick")
        // 沿原生导航和关 sheet 协程，不自行改 Home UI 状态。
        val scope = callback.javaClass.getDeclaredField("c").get(callback)
        val coroutine = callback.javaClass.getDeclaredField("d").get(callback)
        val sheet = callback.javaClass.getDeclaredField("e").get(callback)
        val receiver = callback.javaClass.getDeclaredField("f").get(callback)
        val click = native.declaredConstructors.single { it.parameterCount == 6 }
            .newInstance(scope, kind, coroutine, sheet, receiver, 1.toByte())
        native.getDeclaredMethod("d").invoke(click)
    }

    private fun installAccountRoutes() {
        val content = loader.hostClass("ReaMicroHost.RouteContent")
        val method = content.getDeclaredMethod("y", Any::class.java, Any::class.java, Any::class.java, Any::class.java)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.thisObject ?: return
                val branch = content.getDeclaredField("a").getByte(callback).toInt()
                if (branch != 20) return
                val entry = param.args?.getOrNull(1) ?: return
                val routeType = loader.hostClass(ROUTE_THIRD_ACCOUNT_CLASS)
                val reflection = loader.hostClass("ReaMicroHost.Reflection")
                val kClass = reflection.getDeclaredMethod("a", Class::class.java).invoke(null, routeType)
                val decode = loader.hostClass("ReaMicroHost.RouteDecoder").declaredMethods
                    .single { it.name == "e" && it.parameterCount == 2 }
                val route = decode.invoke(null, entry, kClass) ?: return
                val kind = route.javaClass.hostField("type").getInt(route)
                if (kind !in setOf(BACKUP_TYPE_WEBDAV, BACKUP_TYPE_LOCAL_LIBRARY)) {
                    drive.clearWebDavAccountContext()
                    drive.clearLocalLibraryAccountContext()
                    return
                }
                val scope = content.getDeclaredField("b").get(callback) ?: return
                val composer = param.args?.getOrNull(2) ?: return
                enterAccount(param, kind)
                try {
                    val rendered = if (kind == BACKUP_TYPE_WEBDAV) {
                        drive.clearLocalLibraryAccountContext()
                        drive.renderWebDavAccountRoute(scope, composer)
                    } else {
                        drive.clearWebDavAccountContext()
                        drive.renderLocalLibraryAccountRoute(scope, composer)
                    }
                    check(rendered) { "B2 account route did not render: $kind" }
                    param.result = drive.targetUnit()
                    XposedBridge.logAlways("ReaMicro B2 storage account route rendered kind=$kind")
                } finally { leaveAccount(param) }
            }
        })
        XposedBridge.deoptimize(method)
        val scope = loader.hostClass(NAV_GRAPH_SCOPE_CLASS)
        XposedBridge.hookMethod(scope.getDeclaredMethod("a", scope, Any::class.java), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 每次显式导航先清理，原生账户的重组不能继承上次 WebDAV 作用域。
                clearAccounts()
            }
        })
        XposedBridge.deoptimize(scope.getDeclaredMethod("a", scope, Any::class.java))
        val controller = loader.hostClass("ReaMicroHost.NavController")
        XposedBridge.hookMethod(controller.getDeclaredMethod("a"), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result == true) {
                    clearAccounts()
                }
            }
        })
    }

    private fun installAuthProjection() {
        for ((role, branches, kind) in listOf(
            Triple("ReaMicroHost.BaiduAccountContent", setOf(4, 5), BACKUP_TYPE_WEBDAV),
            Triple("ReaMicroHost.Y115AccountContent", setOf(10, 11), BACKUP_TYPE_LOCAL_LIBRARY),
        )) {
            val type = loader.hostClass(role)
            val constructor = type.getDeclaredConstructor(Any::class.java, Any::class.java, Byte::class.javaPrimitiveType)
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (accountKind.get() != kind || (param.args[2] as Number).toInt() !in branches) return
                    val vm = param.args[0] ?: return
                    if (!loader.hostClass(if (kind == BACKUP_TYPE_WEBDAV) BAIDU_VIEW_MODEL_CLASS else Y115_VIEW_MODEL_CLASS).isInstance(vm)) return
                    boundAccounts[vm] = kind
                    vm.javaClass.getDeclaredField("g").get(vm)?.let { accountFlows[it] = kind }
                    XposedBridge.logAlways("ReaMicro B2 account content captured kind=$kind branch=${param.args[2]}")
                }
            })
            val screen = loader.hostClass(if (kind == BACKUP_TYPE_WEBDAV) BAIDU_ACCOUNT_SCREEN_CLASS else Y115_ACCOUNT_SCREEN_CLASS)
            screen.declaredMethods.single { it.name == (if (kind == BACKUP_TYPE_WEBDAV) "a" else "e") && it.parameterCount == 3 }
                .let(XposedBridge::deoptimize)
            val invoke = type.getDeclaredMethod("r", Any::class.java, Any::class.java, Any::class.java)
            XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.thisObject ?: return
                    if (type.getDeclaredField("a").getByte(callback).toInt() !in branches) return
                    val vm = type.getDeclaredField("b").get(callback) ?: return
                    if (accountKind.get() == kind) boundAccounts[vm] = kind
                    if (boundAccounts[vm] != kind) return
                    enterAccount(param, kind, vm)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    leaveAccount(param)
                }
            })
            XposedBridge.deoptimize(invoke)
        }
        val collect = loader.hostClass("ReaMicroHost.CollectState").declaredMethods
            .single { it.name == "d" && it.parameterCount == 5 }
        XposedBridge.hookMethod(collect, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val input = param.args?.firstOrNull() ?: return
                val vm = accountVm.get()
                val scopedKind = if (vm != null && vm.javaClass.getDeclaredField("g").get(vm) === input) accountKind.get() else null
                val kind = scopedKind ?: accountFlows[input] ?: return
                // 只替换此处 collect 的输入，不写原生账号 repository/VM 的 final 字段。
                param.args[0] = if (kind == BACKUP_TYPE_WEBDAV) drive.webDavAccountAuthFlow()
                    else drive.localLibraryAccountAuthFlow()
                param.setObjectExtra("reamicro.storage.projectedKind", kind)
                XposedBridge.logAlways("ReaMicro B2 storage account auth projected kind=$kind")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val kind = param.getObjectExtra("reamicro.storage.projectedKind") as? Int ?: return
                param.result?.let { accountStates[it] = kind }
            }
        })
        XposedBridge.deoptimize(collect)
        val folder = loader.hostClass("ReaMicroHost.AccountFolderContent")
        val folderInvoke = folder.getDeclaredMethod("r", Any::class.java, Any::class.java, Any::class.java)
        XposedBridge.hookMethod(folderInvoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val state = folder.getDeclaredField("c").get(param.thisObject) ?: return
                val kind = accountStates[state] ?: return
                enterAccount(param, kind)
            }
            override fun afterHookedMethod(param: MethodHookParam) { leaveAccount(param) }
        })
        XposedBridge.deoptimize(folderInvoke)
    }

    private fun installStorageProjection() {
        val vmType = loader.hostClass(CLOUD_STORAGE_VIEW_MODEL_CLASS)
        val constructor = vmType.declaredConstructors.single()
        XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val kind = param.args[0] as? Int ?: return
                if (kind !in setOf(BACKUP_TYPE_WEBDAV, BACKUP_TYPE_LOCAL_LIBRARY)) return
                param.setObjectExtra("reamicro.storage.previousConstructing", storageConstructing.get())
                param.setObjectExtra("reamicro.storage.constructing", true)
                storageConstructing.set(kind)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra("reamicro.storage.constructing") == true) {
                    storageConstructing.set(param.getObjectExtra("reamicro.storage.previousConstructing") as? Int)
                }
            }
        })
        XposedBridge.deoptimize(constructor)
        val cache = loader.hostClass("ReaMicroHost.PagingCache").declaredMethods.single { it.name == "b" && it.parameterCount == 2 }
        XposedBridge.hookMethod(cache, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val kind = storageConstructing.get() ?: return
                param.args[0] = if (kind == BACKUP_TYPE_WEBDAV) drive.webDavLibraryFlow() else drive.localLibraryFlow()
                XposedBridge.logAlways("ReaMicro B2 storage paging projected kind=$kind")
            }
        })
        XposedBridge.deoptimize(cache)
        val collector = loader.hostClass("ReaMicroHost.StorageCollector")
        val collect = loader.hostClass("ReaMicroHost.CollectLatest").declaredMethods.single { it.name == "a" && it.parameterCount == 3 }
        XposedBridge.hookMethod(collect, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val continuation = param.args[2] ?: return
                if (!collector.isInstance(continuation)) return
                val vm = collector.getDeclaredField("g").get(continuation) ?: return
                val kind = vmType.hostField("type").getInt(vm)
                if (kind !in setOf(BACKUP_TYPE_WEBDAV, BACKUP_TYPE_LOCAL_LIBRARY)) return
                val phase = collector.getDeclaredField("e").getByte(continuation).toInt()
                param.args[0] = if (phase == 0) {
                    if (kind == BACKUP_TYPE_WEBDAV) drive.webDavAccountAuthFlow() else drive.localLibraryAccountAuthFlow()
                } else {
                    drive.flowOf(if (kind == BACKUP_TYPE_WEBDAV) drive.webDavCloudUserInfo() else drive.localLibraryCloudUserInfo())
                }
                XposedBridge.logAlways("ReaMicro B2 storage metadata projected kind=$kind phase=$phase")
            }
        })
        XposedBridge.deoptimize(collector.getDeclaredMethod("v", Any::class.java))
        XposedBridge.deoptimize(collect)
    }

    private fun installAccountSettings() {
        fun hook(className: String, methodName: String, kind: Int, action: (XC_MethodHook.MethodHookParam) -> Unit) {
            val method = loader.hostClass(className).hostMethodsNamed(methodName).single()
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (accountKind.get() == kind) action(param)
                }
            })
            XposedBridge.deoptimize(method)
        }
        hook(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_DEFAULT_FOLDER_METHOD, BACKUP_TYPE_WEBDAV) { param ->
            param.args[0] = drive.currentWebDavBackupDir()
            param.args[1] = drive.functionProxy("B2WebDavBackupDirectory", FUNCTION0_CLASS) {
                val scope = drive.webDavAccountNavGraphScopeStrong
                if (scope != null) drive.navigateCloudFolder(scope)
                drive.targetUnit()
            }
        }
        for ((name, orderBy) in listOf(BAIDU_ACCOUNT_QUERY_ORDER_BY_METHOD to true, BAIDU_ACCOUNT_QUERY_ORDER_DIRECTION_METHOD to false)) {
            hook(BAIDU_ACCOUNT_SCREEN_CLASS, name, BACKUP_TYPE_WEBDAV) { param ->
                param.args[0] = if (orderBy) drive.currentWebDavOrderBy() else drive.currentWebDavOrderDirection()
                param.args[1] = drive.functionProxy("B2WebDavQuery", FUNCTION1_CLASS) { args ->
                    val value = args?.firstOrNull() as? String ?: return@functionProxy drive.targetUnit()
                    if (orderBy) drive.saveWebDavOrderBy(value) else drive.saveWebDavOrderDirection(value)
                    drive.updateWebDavAccountAuthFlow()
                    drive.targetUnit()
                }
            }
        }
        hook(BAIDU_ACCOUNT_SCREEN_CLASS, BAIDU_ACCOUNT_LOGOUT_METHOD, BACKUP_TYPE_WEBDAV) { param ->
            param.args[0] = drive.functionProxy("B2WebDavLogout", FUNCTION0_CLASS) {
                drive.clearWebDavLogin()
                drive.updateWebDavAccountAuthFlow()
                drive.webDavAccountNavGraphScopeStrong?.let(HostNavigation::pop)
                drive.targetUnit()
            }
        }
        hook(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_DEFAULT_FOLDER_METHOD, BACKUP_TYPE_LOCAL_LIBRARY) { param ->
            param.args[0] = drive.localLibraryPickerDir()
            param.args[1] = drive.functionProxy("B2LocalLibraryDirectory", FUNCTION0_CLASS) {
                drive.launchLocalLibraryFolderPicker()
                drive.targetUnit()
            }
        }
        hook(Y115_ACCOUNT_SCREEN_CLASS, Y115_ACCOUNT_LOGOUT_METHOD, BACKUP_TYPE_LOCAL_LIBRARY) { param ->
            param.result = drive.targetUnit()
        }
    }

    private fun row(title: String, subtitle: String, composer: Any, click: () -> Unit) {
        val headline = ui.lambda(title.hashCode(), drive.functionProxy("B2StorageTitle", FUNCTION2_CLASS) { args ->
            text(title, args?.firstOrNull() ?: return@functionProxy drive.targetUnit())
            drive.targetUnit()
        })
        val supporting = ui.lambda(title.hashCode() xor 0x3131, drive.functionProxy("B2StorageSubtitle", FUNCTION2_CLASS) { args ->
            text(subtitle, args?.firstOrNull() ?: return@functionProxy drive.targetUnit())
            drive.targetUnit()
        })
        val callback = drive.functionProxy("B2StorageRowClick", FUNCTION0_CLASS) { click(); drive.targetUnit() }
        val modifier = ui.clickable(ui.fillWidth(ui.modifier()), callback)
        ui.listItem(arrayOf(headline, modifier, null, supporting, null, null, ui.listColors(composer), 0f, 0f,
            composer, 0, 4 or 16 or 32 or 128 or 256))
    }

    private fun text(value: String, composer: Any) {
        val scheme = ui.theme(composer, "getColorScheme")
        val typography = ui.theme(composer, "getTypography")
        val color = XposedHelpers.callMethod(scheme, "getOnBackground")
        val style = XposedHelpers.callMethod(typography, "getLabelLarge")
        val method = loader.hostClass(MATERIAL3_TEXT_CLASS).hostMethodsNamed(MATERIAL3_TEXT_METHOD)
            .single { it.parameterCount == 20 }
        method.invoke(null, value, null, color, 0L, null, null, 0L, null, null, 0L, 0, false, 0, 0, null,
            style, composer, 0, 0, 131066)
    }
}
