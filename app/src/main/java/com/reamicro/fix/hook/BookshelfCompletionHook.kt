package com.reamicro.fix.hook


import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.core.HostHomeBindings
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.notification.BookshelfUpdateNotifications
import com.reamicro.fix.notification.BookshelfUpdateReport
import com.reamicro.fix.online.download.OnlineChapterUpdateOutcome
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

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
        const val KEY_LAST_AUTO_UPDATE_STARTED_AT = "last_auto_update_started_at"
        const val KEY_LAST_BATCH_UPDATE_COMPLETED_AT = "last_batch_update_completed_at"
    }

    private val main = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
    private var home = WeakReference<Any>(null)
    @Volatile private var bookshelfGroups: List<String> = emptyList()
    private var bookshelfPager = WeakReference<Any>(null)
    private val bookshelfContent = ThreadLocal<Boolean>()
    @Volatile private var showingShelf: Boolean? = null
    private val startupClaimed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val lastAutomaticSkipReason = AtomicReference<String?>()
    private val startupUpdate = Runnable {
        if (!startBatch("startup")) startupClaimed.set(false)
    }
    private val batchCancellation = AtomicReference<TaskCancellation?>()
    private val ui = HostNativeUi(loader)
    private val symbols get() = requireNotNull(HostHomeBindings.bookshelf) { "书架动态契约尚未就绪" }
    private val preferences get() = requireNotNull(HostHomeBindings.preferences) { "宿主偏好动态契约尚未就绪" }
    private fun stateValue(state: Any): Any? = symbols.method("stateGet").invoke(state)
    private fun setState(state: Any, value: Any?) { symbols.method("stateSet").invoke(state, value) }
    private val styleChanging = AtomicBoolean()
    private val dexKitListenerRegistered = AtomicBoolean()
    private val dexKitScanGeneration = AtomicInteger()
    private val dexKitGateGeneration = AtomicInteger()
    private var headerHook: BookshelfHeaderHook? = null
    // Fail installation before callbacks can run when the native UI contract is unavailable.
    private val styleState = ui.mutableState(1)
    private val settingsState = ui.mutableState(settingsProvider())
    private var observedPreferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ModuleSettings.KEY_MODULE_ENABLED || key?.startsWith("shelf_") == true) {
            main.post {
                val settings = settingsProvider()
                if (!settings.moduleEnabled || !settings.shelfBatchUpdate) batchCancellation.get()?.cancel()
                setState(settingsState, settings)
            }
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
                val blocked = DexKitUpdateScan.automaticUpdateBlockReason() != null
                // 在通知当下记录扫描，避免主线程处理回调时短暂扫描已经结束。
                if (DexKitUpdateScan.isBusy()) dexKitScanGeneration.incrementAndGet()
                if (blocked) dexKitGateGeneration.incrementAndGet()
                main.post {
                    if (blocked) {
                        main.removeCallbacks(startupUpdate)
                        startupClaimed.set(false)
                    } else {
                        scheduleStartup()
                    }
                }
            }
        }
        setState(settingsState, settingsProvider())
        scheduleStartup()
    }

    fun closeForReload() {
        batchCancellation.getAndSet(null)?.cancel()
        headerHook?.closeForReload()
        observedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        observedPreferences = null
        main.removeCallbacksAndMessages(null)
    }

    fun install() {
        val homeType = symbols.type("home")
        preferences
        HookRuntime.hookAllConstructors(homeType, object : HookCallback() {
            override fun after(param: Call) {
                home = WeakReference(param.thisObject)
                refreshStyle()
                scheduleStartup()
            }
        })
        val homeScreen = symbols.method("screen")
        HookRuntime.hookMethod(homeScreen, object : HookCallback() {
            override fun before(param: Call) {
                val model = param.args[symbols.index("screen.home")] ?: return
                if (home.get() !== model) {
                    home = WeakReference(model)
                    refreshStyle()
                    drive.rememberHomeViewModelDependencies(model)
                }
            }
        })
        val shelfPage = symbols.method("page")
        HookRuntime.hookMethod(shelfPage, object : HookCallback() {
            override fun before(param: Call) {
                bookshelfContent.set(true)
                bookshelfGroups = (param.args[symbols.index("page.groups")] as? List<*>)?.filterIsInstance<String>().orEmpty()
            }
            override fun after(param: Call) { bookshelfContent.remove() }
        })
        val pagerFactory = symbols.method("pagerFactory")
        HookRuntime.hookMethod(pagerFactory, object : HookCallback() {
            override fun after(param: Call) {
                if (bookshelfContent.get() == true) bookshelfPager = WeakReference(param.result)
            }
        })
        HookRuntime.deoptimize(shelfPage)
        headerHook = BookshelfHeaderHook(
            loader = loader,
            drive = drive,
            settings = { stateValue(settingsState) as ModuleSettingsSnapshot },
            grid = { (stateValue(styleState) as Number).toInt() == 0 },
            onPage = { showingShelf = it },
            update = { startBatch("manual") },
            toggleStyle = ::toggleStyle,
        ).also { it.install() }
    }

    private fun session(): Any? = home.get()?.let(symbols::session)
    private fun firstValue(flow: Any): Any? = preferences.first(flow)
    private fun readStyle(session: Any): Int = (preferences.read(session, "bookshelf_type") as? Number)?.toInt() ?: 0
    private fun refreshStyle() {
        val session = session() ?: return
        com.reamicro.fix.reload.ModuleResources.thread({
            runCatching { readStyle(session) }.onSuccess { value ->
                main.post { setState(styleState, value) }
            }.onFailure { HookRuntime.logError("ReaMicro shelf style read failed: $it", it) }
        }, "ReaMicroShelfStyleRead").start()
    }

    private fun toggleStyle() {
        val session = session() ?: return toast("书架设置暂未就绪")
        if (!styleChanging.compareAndSet(false, true)) return
        com.reamicro.fix.reload.ModuleResources.thread({
            runCatching {
                val current = readStyle(session)
                val next = if (current == 1) 0 else 1
                preferences.update(session, "bookshelf_type", next)
                check(readStyle(session) == next) { "书架样式回读不一致" }
                main.post { setState(styleState, next) }
                HookRuntime.logAlways("ReaMicro shelf style changed=$next")
            }.onFailure { HookRuntime.logError("ReaMicro shelf style failed: $it", it); toast("切换书架样式失败") }
                .also { styleChanging.set(false) }
        }, "ReaMicroShelfStyle").start()
    }

    private fun scheduleStartup() {
        if (!canStartAutomaticUpdate()) return
        if (!startupClaimed.compareAndSet(false, true)) return
        if (!main.postDelayed(startupUpdate, 2000)) startupClaimed.set(false)
    }

    private fun logAutomaticSkip(reason: String, detail: String = reason) {
        if (lastAutomaticSkipReason.getAndSet(reason) != reason) {
            HookRuntime.logAlways("ReaMicro shelf auto update skipped: $detail")
        }
    }

    private fun canStartAutomaticUpdate(context: Context? = activityProvider()?.applicationContext): Boolean {
        if (context == null) return false
        val settings = settingsProvider()
        if (!BookshelfCompletionPolicy.autoUpdate(settings)) return false
        DexKitUpdateScan.automaticUpdateBlockReason()?.let { reason ->
            logAutomaticSkip(reason)
            return false
        }
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val lastStartedAt = prefs.getLong(KEY_LAST_AUTO_UPDATE_STARTED_AT, 0L)
        // 兼容旧版完成时间；手动完整检查书架后同样推迟下一次自动检查。
        val lastCompletedAt = maxOf(
            prefs.getLong(KEY_LAST_AUTO_UPDATE_COMPLETED_AT, 0L),
            prefs.getLong(KEY_LAST_BATCH_UPDATE_COMPLETED_AT, 0L),
        )
        val now = System.currentTimeMillis()
        val allowed = BookshelfCompletionPolicy.canStartAutomatic(
            settings,
            now,
            lastCompletedAt,
            dexKitScanning = false,
            lastStartedAt = lastStartedAt,
        )
        if (!allowed) {
            logAutomaticSkip("更新间隔未满1小时",
                "更新间隔未满1小时 lastStarted=$lastStartedAt lastCompleted=$lastCompletedAt " +
                    "remainingMs=${BookshelfCompletionPolicy.AUTO_UPDATE_INTERVAL_MS - (now - maxOf(lastStartedAt, lastCompletedAt))}")
        }
        return allowed
    }

    private fun claimAutomaticUpdate(context: Context, scanGeneration: Int): Boolean {
        DexKitUpdateScan.validateAutomaticUpdateInstallation(context)?.let { reason ->
            logAutomaticSkip(reason)
            return false
        }
        if (dexKitScanGeneration.get() != scanGeneration) {
            logAutomaticSkip("扫描状态已变化，跳过本次自动更新")
            return false
        }
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        return synchronized(prefs) {
            // 已取得批次运行锁；重新读取时间，避免刚结束的手动更新被旧判断绕过。
            if (!canStartAutomaticUpdate(context)) return@synchronized false
            val startedAt = System.currentTimeMillis()
            if (!prefs.edit().putLong(KEY_LAST_AUTO_UPDATE_STARTED_AT, startedAt).commit()) {
                logAutomaticSkip("自动更新开始时间保存失败")
                return@synchronized false
            }
            // 开始前同步落盘，失败、取消、进程退出和热重载都保留本次限频。
            lastAutomaticSkipReason.set(null)
            HookRuntime.logAlways("ReaMicro shelf auto update started: at=$startedAt " +
                "intervalMs=${BookshelfCompletionPolicy.AUTO_UPDATE_INTERVAL_MS} scanValidated=true")
            true
        }
    }

    private fun startBatch(reason: String): Boolean {
        val gateGeneration = dexKitGateGeneration.get()
        val settings = settingsProvider()
        if (!settings.moduleEnabled || !settings.shelfBatchUpdate) return false
        if (reason == "startup" && !canStartAutomaticUpdate()) return false
        if (!running.compareAndSet(false, true)) return false
        val scanGeneration = dexKitScanGeneration.get()
        val cancellation = TaskCancellation()
        val control = com.reamicro.fix.online.download.OnlineDownloadControl(cancellation)
        val taskKey = "bookshelf-${UUID.randomUUID()}"
        batchCancellation.set(cancellation)
        drive.onlineCompletionRunningUpdates[taskKey] = cancellation
        drive.onlineCompletionUpdateControls[taskKey] = control
        var interruptRegistration: AutoCloseable? = null
        try {
        val thread = com.reamicro.fix.reload.ModuleResources.thread({
            val results = ArrayList<OnlineChapterUpdateOutcome>()
            var total = 0
            var errorMessage: String? = null
            var completed = false
            var batchStarted = reason != "startup"
            var notifier: WebDavDriveHook.OnlineCompletionProgressNotifier? = null
            try {
                cancellation.run {
                val context = drive.currentContext()?.applicationContext ?: error("书架更新缺少 Context")
                if (reason == "startup") {
                    batchStarted = claimAutomaticUpdate(context, scanGeneration)
                    if (!batchStarted) return@run
                    DexKitUpdateScan.automaticUpdateBlockReason()?.let { blockReason ->
                        logAutomaticSkip(blockReason)
                        batchStarted = false
                        return@run
                    }
                }
                drive.ensureOnlineCompletionCancelReceiver(context)
                val notificationId = drive.onlineCompletionNotificationIds.incrementAndGet()
                drive.onlineCompletionUpdateNotificationIds[taskKey] = notificationId
                notifier = drive.OnlineCompletionProgressNotifier(
                    context, notificationId, "update:$taskKey",
                    true, "书架批量更新", null, null,
                )
                notifier?.running(0, "正在读取书架", force = true)
                val repository = home.get()?.let { symbols.field("home.repository").get(it) }
                    ?: drive.awaitBookshelfRepository() ?: error("书架服务未就绪")
                val uid = symbols.uid(repository) ?: error("当前账号未就绪")
                val currentGroup = if (settings.shelfCurrentOnly) {
                    val viewModel = home.get() ?: error("当前书架尚未就绪")
                    if (showingShelf == false) {
                        // 首页的当前阅读流跟随用户当前书籍，不能使用上次书架分页位置代替。
                        val readingFlow = symbols.field("home.reading").get(viewModel)
                        val readingBook = firstValue(requireNotNull(readingFlow)) ?: error("当前没有正在阅读的图书")
                        symbols.field("book.group").get(readingBook)?.toString().orEmpty().trim().ifBlank { "默认" }
                    } else {
                    check(showingShelf == true) { "当前主页尚未显示，暂不猜测更新分组" }
                    val groups = bookshelfGroups.ifEmpty {
                        // 自动更新可早于顶部首帧：从同一HomeUiState取分组，再复用宿主的默认分组选择。
                        val state = symbols.method("current").invoke(viewModel) ?: error("当前书架状态未就绪")
                        (symbols.field("state.groups").get(state) as? List<*>)
                            ?.filterIsInstance<String>().orEmpty()
                    }
                    if (groups.isEmpty()) error("当前书架分组尚未就绪")
                    val pager = bookshelfPager.get() ?: error("当前书架分页尚未就绪")
                    val index = symbols.method("pagerCurrent").invoke(pager) as Int
                    groups.getOrNull(index) ?: error("当前书架分组不可用")
                    }
                } else null
                HookRuntime.logAlways("ReaMicro shelf batch scope=${currentGroup ?: "all"} page=${showingShelf} source=$reason")
                val dao = symbols.field("shelf.dao").get(repository)
                    ?: error("无法读取当前书架")
                val flow = symbols.method("books").invoke(dao, uid)
                val books = (firstValue(requireNotNull(flow)) as? Iterable<*>)
                    ?: error("无法读取当前书架")
                val uniqueBooks = books
                    .filterNotNull().filter { number(it, "uid") == uid }
                    .filter {
                        val group = symbols.field("book.group").get(it)?.toString().orEmpty().trim().ifBlank { "默认" }
                        currentGroup == null || group == currentGroup
                    }
                    .distinctBy { number(it, "id") }
                val online = uniqueBooks.mapNotNull { book -> drive.onlineImportedBookSourceInfo(book)?.let { book to it } }
                total = online.size
                for ((index, item) in online.withIndex()) {
                    if (control.shouldPause || control.isCancelled) break
                    cancellation.check()
                    val (book, info) = item
                    // 切换账号或关闭开关后停止下一本，已启动的单书操作沿用原锁与校验。
                    if (!settingsProvider().shelfBatchUpdate || !settingsProvider().moduleEnabled ||
                        symbols.uid(repository) != uid ||
                        (reason == "startup" && (!settingsProvider().shelfAutoUpdate ||
                            DexKitUpdateScan.automaticUpdateBlockReason() != null ||
                            dexKitScanGeneration.get() != scanGeneration))) break
                    val finished = CountDownLatch(1)
                    val title = symbols.field("book.title").get(book)?.toString().orEmpty()
                    var outcome = OnlineChapterUpdateOutcome(title, error = "章节更新未完成")
                    runCatching {
                        drive.startOnlineCompletionChapterUpdate(
                            book, info, silent = true, skipCompleted = settings.shelfSkipCompleted,
                            parentControl = control,
                            onProgress = { progress, message ->
                                notifier?.running((index * 100 + progress) / total.coerceAtLeast(1),
                                    "${index + 1}/$total 本 · $title · $message")
                            },
                        ) { result ->
                            outcome = result
                            finished.countDown()
                        }
                    }.onFailure {
                        HookRuntime.logError("ReaMicro shelf batch single book failed", it)
                        outcome = OnlineChapterUpdateOutcome(title, error = it.message ?: "章节更新失败")
                        finished.countDown()
                    }
                    // A child may already have entered its irreversible publication gate.
                    // Wait for its actual result before completing the batch stop notification.
                    while (true) {
                        try {
                            finished.await()
                            break
                        } catch (interrupted: InterruptedException) {
                            if (!cancellation.isCancelled) throw interrupted
                        }
                    }
                    results += outcome
                    notifier?.running((results.size * 100) / total.coerceAtLeast(1),
                        "已检查 ${results.size}/$total 本")
                    HookRuntime.logAlways("ReaMicro shelf batch book=${number(book, "id")} success=${outcome.success} added=${outcome.added} source=$reason")
                    if (control.shouldPause || control.isCancelled) break
                }
                completed = results.size == total && !control.shouldPause && !control.isCancelled
                }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                HookRuntime.logError("ReaMicro shelf batch failed", error)
                errorMessage = when {
                    control.shouldPause && !control.isCancelled -> null
                    cancellation.isCancelled -> "书架更新已取消"
                    else -> error.message ?: "书架更新失败"
                }
            } finally {
                interruptRegistration?.close()
                val report = BookshelfUpdateReport(results, total, reason == "startup", errorMessage)
                runCatching {
                    val message = when {
                        control.shouldPause && !control.isCancelled -> "已暂停后续更新，${report.summary}，可长按更新继续"
                        cancellation.isCancelled -> "已取消后续更新，已提交结果保留，${report.summary}"
                        else -> report.summary
                    }
                    notifier?.finish(message, null,
                        success = completed && errorMessage == null && results.all { it.success })
                }.onFailure { HookRuntime.logError("ReaMicro shelf progress completion failed", it) }
                drive.onlineCompletionRunningUpdates.remove(taskKey, cancellation)
                drive.onlineCompletionUpdateControls.remove(taskKey, control)
                drive.onlineCompletionUpdateNotificationIds.remove(taskKey)?.let {
                    drive.onlineCompletionProgressNotifiers.remove(it)
                }
                batchCancellation.compareAndSet(cancellation, null)
                control.finish()
                cancellation.cancel()
                if (completed && errorMessage == null) {
                    drive.currentContext()?.applicationContext
                        ?.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                        ?.let { prefs ->
                            synchronized(prefs) {
                                prefs.edit().putLong(KEY_LAST_BATCH_UPDATE_COMPLETED_AT, System.currentTimeMillis()).apply()
                            }
                        }
                }
                if (batchStarted) runCatching {
                    val context = drive.currentContext()?.applicationContext ?: error("书架更新通知缺少 Context")
                    BookshelfUpdateNotifications.send(
                        context, report,
                        settingsProvider().conciseLogEnabled,
                    )
                }.onFailure { HookRuntime.logError("ReaMicro shelf report failed: $it", it) }
                running.set(false)
                if (reason == "startup" && !batchStarted) startupClaimed.set(false)
                if (reason == "startup" && (dexKitScanGeneration.get() != scanGeneration ||
                        (!batchStarted && dexKitGateGeneration.get() != gateGeneration))) {
                    startupClaimed.set(false)
                    main.post { scheduleStartup() }
                }
            }
        }, "ReaMicroShelfBatch")
        interruptRegistration = cancellation.register(thread::interrupt)
        thread.start()
        } catch (error: Throwable) {
            interruptRegistration?.close()
            drive.onlineCompletionRunningUpdates.remove(taskKey, cancellation)
            drive.onlineCompletionUpdateControls.remove(taskKey, control)
            drive.onlineCompletionUpdateNotificationIds.remove(taskKey)?.let { drive.onlineCompletionProgressNotifiers.remove(it) }
            batchCancellation.compareAndSet(cancellation, null)
            control.finish()
            cancellation.cancel()
            running.set(false)
            HookRuntime.logError("ReaMicro shelf batch could not start", error)
            return false
        }
        return true
    }

    private fun number(book: Any, name: String): Long = (symbols.field("book.$name").get(book) as Number).toLong()
    private fun toast(message: String) = main.post {
        activityProvider()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }.let { Unit }
}
