package com.reamicro.fix.hook

import com.reamicro.fix.core.hostReleaseMethod
import com.reamicro.fix.core.hostReleaseField
import com.reamicro.fix.core.hostReleaseMethodByArity

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.notification.BookshelfUpdateNotifications
import com.reamicro.fix.notification.BookshelfUpdateReport
import com.reamicro.fix.online.download.OnlineChapterUpdateOutcome
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** 复用宿主同步动作、Session 和单书更新；不修改书架页或在线更新算法。 */
internal class BookshelfCompletionHook(
    private val loader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
    private val drive: WebDavDriveHook,
) {
    private companion object {
        const val RUNTIME_PREFS = "reamicro_fix_shelf_runtime"
        const val KEY_LAST_AUTO_UPDATE_COMPLETED_AT = "last_auto_update_completed_at"
    }

    private val main = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private var home = WeakReference<Any>(null)
    @Volatile private var bookshelfGroups: List<String> = emptyList()
    private var bookshelfPager = WeakReference<Any>(null)
    private val bookshelfContent = ThreadLocal<Boolean>()
    @Volatile private var showingShelf: Boolean? = null
    private val startupClaimed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val ui = B2NativeUi(loader)
    private val styleChanging = AtomicBoolean()
    private val dexKitListenerRegistered = AtomicBoolean()
    @Volatile private var cancelAutomaticForDexKit = false
    private var headerHook: BookshelfHeaderHook? = null
    private val styleState by lazy { ui.mutableState(1) }
    private val settingsState by lazy { ui.mutableState(settingsProvider()) }
    private var observedPreferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ModuleSettings.KEY_MODULE_ENABLED || key?.startsWith("shelf_") == true) {
            main.post { XposedHelpers.callMethod(settingsState, "setValue", settingsProvider()) }
        }
    }

    fun onHostStarted() {
        activityProvider()?.let { activity ->
            val prefs = activity.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
            if (observedPreferences !== prefs) {
                observedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
                observedPreferences = prefs
            }
        }
        if (dexKitListenerRegistered.compareAndSet(false, true)) {
            DexKitUpdateScan.addStateListener {
                main.post {
                    if (DexKitUpdateScan.isBusy()) {
                        cancelAutomaticForDexKit = true
                        startupClaimed.set(false)
                    } else {
                        scheduleStartup()
                    }
                }
            }
        }
        XposedHelpers.callMethod(settingsState, "setValue", settingsProvider())
        scheduleStartup()
    }

    fun closeForReload() {
        headerHook?.closeForReload()
        observedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        observedPreferences = null
        main.removeCallbacksAndMessages(null)
    }

    fun install() {
        check(HostSymbols.usesReleaseLayout) { "书架动作尚未适配该宿主" }
        val homeType = loader.hostClass("app.zhendong.reamicro.ui.home.HomeViewModel")
        XposedBridge.hookAllConstructors(homeType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                home = WeakReference(param.thisObject)
                refreshStyle()
                scheduleStartup()
            }
        })
        val homeScreen = loader.hostClass("app.zhendong.reamicro.ui.home.HomeScreenKt")
            .hostMethod("HomeScreen", loader.hostClass("app.zhendong.reamicro.NavGraphScope"),
                homeType, loader.hostClass("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType)
        XposedBridge.hookMethod(homeScreen, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val model = param.args[1] ?: return
                if (home.get() !== model) {
                    home = WeakReference(model)
                    refreshStyle()
                    drive.rememberHomeViewModelDependencies(model)
                }
            }
        })
        val shelf = loader.hostClass("app.zhendong.reamicro.ui.home.BookshelfScreenKt")
        val shelfPage = shelf.hostMethodsNamed("BookshelfPage-NpZTi58").single { it.parameterCount == 11 }
        XposedBridge.hookMethod(shelfPage, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                bookshelfContent.set(true)
                bookshelfGroups = (param.args[2] as? List<*>)?.filterIsInstance<String>().orEmpty()
            }
            override fun afterHookedMethod(param: MethodHookParam) { bookshelfContent.remove() }
        })
        val pagerFactory = loader.hostClass("ReaMicroShelf233.PagerFactory").hostReleaseMethodByArity("b", 5)
        XposedBridge.hookMethod(pagerFactory, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (bookshelfContent.get() == true) bookshelfPager = WeakReference(param.result)
            }
        })
        XposedBridge.deoptimize(shelfPage)
        headerHook = BookshelfHeaderHook(
            loader = loader,
            drive = drive,
            settings = { XposedHelpers.callMethod(settingsState, "getValue") as ModuleSettingsSnapshot },
            grid = { (XposedHelpers.callMethod(styleState, "getValue") as Number).toInt() == 0 },
            onPage = { showingShelf = it },
            update = { startBatch("manual") },
            toggleStyle = ::toggleStyle,
        ).also { it.install() }
    }

    private fun session(): Any? = home.get()?.let {
        it.javaClass.hostField("repository").get(it)?.let { shelf -> shelf.javaClass.hostField("session").get(shelf) }
    }

    private fun preferenceKey(): Any =
        loader.hostClass("ReaMicroHost.PreferenceKey").getConstructor(String::class.java)
            .newInstance("bookshelf_type")

    private fun firstValue(flow: Any): Any? {
        val first = loader.hostClass("ReaMicroShelf233.FlowFirst").hostReleaseMethod(
            "c", loader.hostClass("kotlinx.coroutines.flow.Flow"),
            loader.hostClass("kotlin.coroutines.Continuation"))
        return HostCoroutines.invokeTimed(loader, first, null, 15_000L, flow)
    }

    private fun readStyle(session: Any): Int {
        val store = session.javaClass.hostReleaseField("a").apply { isAccessible = true }.get(session)
        val flow = XposedHelpers.callMethod(store, "getData")
        val prefs = firstValue(requireNotNull(flow)) ?: error("阅读设置未就绪")
        val key = preferenceKey()
        return (prefs.javaClass.hostAbiMethod("c", key.javaClass).invoke(prefs, key) as? Number)?.toInt() ?: 0
    }

    private fun refreshStyle() {
        val session = session() ?: return
        com.reamicro.fix.reload.ModuleResources.thread({
            runCatching { readStyle(session) }.onSuccess { value ->
                main.post { XposedHelpers.callMethod(styleState, "setValue", value) }
            }.onFailure { XposedBridge.logError("ReaMicro shelf style read failed: $it", it) }
        }, "ReaMicroShelfStyleRead").start()
    }

    private fun toggleStyle() {
        val session = session() ?: return toast("书架设置暂未就绪")
        if (!styleChanging.compareAndSet(false, true)) return
        com.reamicro.fix.reload.ModuleResources.thread({
            runCatching {
                val current = readStyle(session)
                val next = if (current == 1) 0 else 1
                val key = preferenceKey()
                val update = session.javaClass.hostAbiMethod("h", key.javaClass, Any::class.java,
                    loader.hostClass("kotlin.coroutines.Continuation"))
                HostCoroutines.invokeTimed(loader, update, session, 15_000L, key, next)
                check(readStyle(session) == next) { "书架样式回读不一致" }
                main.post { XposedHelpers.callMethod(styleState, "setValue", next) }
                XposedBridge.logAlways("ReaMicro shelf style changed=$next")
            }.onFailure { XposedBridge.logError("ReaMicro shelf style failed: $it", it); toast("切换书架样式失败") }
                .also { styleChanging.set(false) }
        }, "ReaMicroShelfStyle").start()
    }

    private fun scheduleStartup() {
        if (!canStartAutomaticUpdate()) return
        if (!startupClaimed.compareAndSet(false, true)) return
        main.postDelayed({
            if (!startBatch("startup")) startupClaimed.set(false)
        }, 2000)
    }

    private fun canStartAutomaticUpdate(): Boolean {
        val context = activityProvider()?.applicationContext ?: return false
        val lastCompletedAt = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AUTO_UPDATE_COMPLETED_AT, 0L)
        return BookshelfCompletionPolicy.canStartAutomatic(
            settingsProvider(),
            System.currentTimeMillis(),
            lastCompletedAt,
            DexKitUpdateScan.isBusy() || isManualDexKitScanning() || cancelAutomaticForDexKit,
        )
    }

    private fun startBatch(reason: String): Boolean {
        val settings = settingsProvider()
        if (!settings.moduleEnabled || !settings.shelfBatchUpdate) return false
        if (reason == "startup" && !canStartAutomaticUpdate()) return false
        if (!running.compareAndSet(false, true)) return false
        com.reamicro.fix.reload.ModuleResources.thread({
            val results = ArrayList<OnlineChapterUpdateOutcome>()
            var total = 0
            var errorMessage: String? = null
            var completed = false
            try {
                val repository = home.get()?.let { it.javaClass.hostField("repository").get(it) }
                    ?: drive.awaitBookshelfRepository() ?: error("书架服务未就绪")
                val uid = drive.currentUserId(repository) ?: error("当前账号未就绪")
                val currentGroup = if (settings.shelfCurrentOnly) {
                    val viewModel = home.get() ?: error("当前书架尚未就绪")
                    if (showingShelf == false) {
                        // 首页的当前阅读流跟随用户当前书籍，不能使用上次书架分页位置代替。
                        val readingFlow = viewModel.javaClass.hostReleaseField("y").apply { isAccessible = true }.get(viewModel)
                        val readingBook = firstValue(requireNotNull(readingFlow)) ?: error("当前没有正在阅读的图书")
                        XposedHelpers.callMethod(readingBook, "getGroup")?.toString().orEmpty().trim().ifBlank { "默认" }
                    } else {
                    check(showingShelf == true) { "当前主页尚未显示，暂不猜测更新分组" }
                    val groups = bookshelfGroups.ifEmpty {
                        // 自动更新可早于顶部首帧：从同一HomeUiState取分组，再复用宿主的默认分组选择。
                        val state = XposedHelpers.callMethod(viewModel, "f") ?: error("当前书架状态未就绪")
                        (state.javaClass.hostField("bookshelfGroups").get(state) as? List<*>)
                            ?.filterIsInstance<String>().orEmpty()
                    }
                    if (groups.isEmpty()) error("当前书架分组尚未就绪")
                    val pager = bookshelfPager.get() ?: error("当前书架分页尚未就绪")
                    val index = XposedHelpers.callMethod(pager, "getCurrentPage") as Int
                    groups.getOrNull(index) ?: error("当前书架分组不可用")
                    }
                } else null
                XposedBridge.logAlways("ReaMicro shelf batch scope=${currentGroup ?: "all"} page=${showingShelf} source=$reason")
                val dao = repository.javaClass.hostField("bookDao").get(repository)
                    ?: error("无法读取当前书架")
                val flow = dao.javaClass.hostMethodsNamed("listByUid").single { it.parameterCount == 1 }
                    .invoke(dao, uid)
                val books = (firstValue(requireNotNull(flow)) as? Iterable<*>)
                    ?: error("无法读取当前书架")
                val uniqueBooks = books
                    .filterNotNull().filter { number(it, "getUid") == uid }
                    .filter {
                        val group = XposedHelpers.callMethod(it, "getGroup")?.toString().orEmpty().trim().ifBlank { "默认" }
                        currentGroup == null || group == currentGroup
                    }
                    .distinctBy { number(it, "getId") }
                val online = uniqueBooks.mapNotNull { book -> drive.onlineImportedBookSourceInfo(book)?.let { book to it } }
                total = online.size
                for ((book, info) in online) {
                    // 切换账号或关闭开关后停止下一本，已启动的单书操作沿用原锁与校验。
                    if (!settingsProvider().shelfBatchUpdate || !settingsProvider().moduleEnabled ||
                        drive.currentUserId(repository) != uid ||
                        (reason == "startup" && (DexKitUpdateScan.isBusy() ||
                            isManualDexKitScanning() || cancelAutomaticForDexKit))) break
                    val finished = CountDownLatch(1)
                    val title = XposedHelpers.callMethod(book, "getTitle")?.toString().orEmpty()
                    var outcome = OnlineChapterUpdateOutcome(title, error = "章节更新未完成")
                    runCatching {
                        drive.startOnlineCompletionChapterUpdate(
                            book, info, silent = true, skipCompleted = settings.shelfSkipCompleted,
                        ) { result ->
                            outcome = result
                            finished.countDown()
                        }
                    }.onFailure {
                        XposedBridge.logError("ReaMicro shelf batch single book failed", it)
                        outcome = OnlineChapterUpdateOutcome(title, error = it.message ?: "章节更新失败")
                        finished.countDown()
                    }
                    finished.await()
                    results += outcome
                    XposedBridge.logAlways("ReaMicro shelf batch book=${number(book, "getId")} success=${outcome.success} added=${outcome.added} source=$reason")
                }
                completed = results.size == total
            } catch (error: Throwable) {
                XposedBridge.logError("ReaMicro shelf batch failed", error)
                errorMessage = error.message ?: "书架更新失败"
            } finally {
                if (reason == "startup" && completed && errorMessage == null) {
                    drive.currentContext()?.applicationContext
                        ?.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                        ?.edit()?.putLong(KEY_LAST_AUTO_UPDATE_COMPLETED_AT, System.currentTimeMillis())?.apply()
                }
                runCatching {
                    val context = drive.currentContext()?.applicationContext ?: error("书架更新通知缺少 Context")
                    BookshelfUpdateNotifications.send(
                        context, BookshelfUpdateReport(results, total, reason == "startup", errorMessage),
                        settingsProvider().conciseLogEnabled,
                    )
                }.onFailure { XposedBridge.logError("ReaMicro shelf report failed: $it", it) }
                running.set(false)
            }
        }, "ReaMicroShelfBatch").start()
        return true
    }

    private fun number(book: Any, name: String): Long = (XposedHelpers.callMethod(book, name) as Number).toLong()
    private fun toast(message: String) = main.post {
        activityProvider()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }.let { Unit }
}
