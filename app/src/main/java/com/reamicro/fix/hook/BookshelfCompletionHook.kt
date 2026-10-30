package com.reamicro.fix.hook

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
import com.reamicro.fix.hook.discover.B4DiscoverVector
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
    private val main = Handler(Looper.getMainLooper())
    private var home = WeakReference<Any>(null)
    @Volatile private var bookshelfGroups: List<String> = emptyList()
    private var bookshelfPager = WeakReference<Any>(null)
    private val bookshelfContent = ThreadLocal<Boolean>()
    private val header = ThreadLocal<Boolean>()
    private val normalHeader = ThreadLocal<Boolean>()
    @Volatile private var showingShelf: Boolean? = null
    private val startupClaimed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val ui = B2NativeUi(loader)
    private val icons = HashMap<String, Any>()
    private val styleChanging = AtomicBoolean()
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
        XposedHelpers.callMethod(settingsState, "setValue", settingsProvider())
        scheduleStartup()
    }

    fun install() {
        check(HostSymbols.profileId == "2.3.3") { "书架动作尚未适配该宿主" }
        val homeType = loader.hostClass("app.zhendong.reamicro.ui.home.HomeViewModel")
        XposedBridge.hookAllConstructors(homeType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                home = WeakReference(param.thisObject)
                refreshStyle()
                scheduleStartup()
            }
        })
        val tabs = loader.hostClass("ReaMicroShelf233.Header")
        val shelf = loader.hostClass("app.zhendong.reamicro.ui.home.BookshelfScreenKt")
        val shelfPage = shelf.hostMethodsNamed("BookshelfPage-NpZTi58").single { it.parameterCount == 11 }
        XposedBridge.hookMethod(shelfPage, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                bookshelfContent.set(true)
                bookshelfGroups = (param.args[2] as? List<*>)?.filterIsInstance<String>().orEmpty()
            }
            override fun afterHookedMethod(param: MethodHookParam) { bookshelfContent.remove() }
        })
        val pagerFactory = loader.hostClass("ReaMicroShelf233.PagerFactory").declaredMethods
            .single { it.name == "b" && it.parameterCount == 5 }
        XposedBridge.hookMethod(pagerFactory, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (bookshelfContent.get() == true) bookshelfPager = WeakReference(param.result)
            }
        })
        XposedBridge.deoptimize(shelfPage)
        val sync = tabs.getDeclaredMethod(
            "c", loader.hostClass("ReaMicroShelf233.SyncState"), loader.hostClass("ReaMicroShelf233.LanState"),
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            loader.hostClass("kotlin.jvm.functions.Function0"),
            loader.hostClass("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType,
        )
        val parent = tabs.declaredMethods.single { it.name == "b" && it.parameterCount == 15 }
        XposedBridge.hookMethod(parent, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 第3号逻辑参数是“书架”标签，不能处理正在阅读或多选工具条。
                header.set(param.args[3] == true && param.args[4] != true)
                normalHeader.set(param.args[4] != true)
                showingShelf = param.args[3] == true
                // 此处args[0]是“正在阅读/书架”首页标签，分组列表只能从BookshelfPage获取。
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                header.remove()
                normalHeader.remove()
            }
        })
        val selectionVector = loader.hostClass("ReaMicroShelf233.SelectionIcon").getDeclaredField("b")
        val vectorIcon = loader.hostClass("androidx.compose.material3.IconKt").hostMethodsNamed("Icon-ww6aTOc")
            .single { it.parameterCount == 7 && it.parameterTypes[0] ==
                loader.hostClass("androidx.compose.ui.graphics.vector.ImageVector") }
        XposedBridge.hookMethod(vectorIcon, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (header.get() != true || param.args[0] !== selectionVector.get(null)) return
                val settings = XposedHelpers.callMethod(settingsState, "getValue") as ModuleSettingsSnapshot
                if (!settings.moduleEnabled ||
                    (!settings.shelfHideSync && !settings.shelfBatchUpdate && !settings.shelfStyleButton)) return
                // 原生勾选槽宽只有36 UDP，其余为44 UDP；外侧补8 UDP，不改变图标或回调。
                val outer = ui.padding(ui.modifier(), 0f, 0f, udp(8), 0f)
                param.args[2] = loader.hostClass("androidx.compose.ui.Modifier")
                    .hostMethod("then", loader.hostClass("androidx.compose.ui.Modifier"))
                    .invoke(outer, param.args[2])
                param.args[5] = (param.args[5] as Int) and 0x380.inv()
            }
        })
        XposedBridge.hookMethod(sync, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (normalHeader.get() != true) return
                val shelfVisible = showingShelf == true
                // 读取 Compose 状态，让从设置返回的既有书架也立即重组，不必切标签或重启。
                val settings = XposedHelpers.callMethod(settingsState, "getValue") as ModuleSettingsSnapshot
                if (!settings.moduleEnabled) return
                if (!shelfVisible && !settings.shelfBatchUpdate) return
                if (!settings.shelfHideSync && !settings.shelfBatchUpdate && !settings.shelfStyleButton) return
                val nativeArgs = param.args.clone()
                val composer = nativeArgs[6]
                val row = ui.lambda(0x524d5342, drive.functionProxy(
                    "BookshelfCompletionActions", "kotlin.jvm.functions.Function3",
                ) { args ->
                    val inner = args?.getOrNull(1) ?: return@functionProxy drive.targetUnit()
                    val current = XposedHelpers.callMethod(settingsState, "getValue") as ModuleSettingsSnapshot
                    if (!shelfVisible || !current.shelfHideSync) sync.invoke(null, *nativeArgs.clone().apply { this[6] = inner })
                    val scale = nativeArgs[4] as Float
                    if (current.shelfBatchUpdate) renderAction("批量更新", updateIcon(), inner, scale) { startBatch("manual") }
                    if (shelfVisible && current.shelfStyleButton) {
                        val grid = (XposedHelpers.callMethod(styleState, "getValue") as Number).toInt() == 0
                        renderAction(if (grid) "切换为列表" else "切换为宫格", styleIcon(grid), inner, scale, ::toggleStyle)
                    }
                    drive.targetUnit()
                })
                // 替换同步节点自身，原同步动效和回调仍在同一原生Row中执行。
                val active = header.get()
                val activeNormal = normalHeader.get()
                header.set(false)
                normalHeader.set(false)
                try {
                    ui.row(ui.modifier(), row, composer)
                    param.result = null
                } finally {
                    header.set(active)
                    normalHeader.set(activeNormal)
                }
            }
        })
        listOf(parent, sync).forEach(XposedBridge::deoptimize)
    }

    private fun udp(value: Int): Float = loader.hostClass("ReaMicroShelf233.Unit")
        .getDeclaredMethod("e", Int::class.javaPrimitiveType).invoke(null, value) as Float

    private fun size(modifier: Any, value: Float): Any =
        requireNotNull(loader.hostClass("ReaMicroHost.Size").hostAbiMethod(
            "o", loader.hostClass("androidx.compose.ui.Modifier"), Float::class.javaPrimitiveType)
            .invoke(null, modifier, value))

    private fun renderAction(label: String, icon: Any, composer: Any, scale: Float, action: () -> Unit) {
        val scheme = ui.theme(composer, "getColorScheme")
        val color = loader.hostClass("app.zhendong.reamicro.arch.theme.ThemeKt")
            .hostMethod("getOnBackgroundVariant", scheme.javaClass).invoke(null, scheme) as Long
        val callback = drive.functionProxy(label, "kotlin.jvm.functions.Function0") { action(); drive.targetUnit() }
        val modifierType = loader.hostClass("androidx.compose.ui.Modifier")
        val scaled = loader.hostClass("ReaMicroShelf233.Scale")
            .getDeclaredMethod("b", modifierType, Float::class.javaPrimitiveType).invoke(null, ui.modifier(), scale)
        // 与原生同步保持同一 UDP 尺寸、末端留白和圆形涟漪，内层图标不扩大点击边界。
        val padded = ui.padding(requireNotNull(scaled), 0f, 0f, udp(8), 0f)
        val circle = loader.hostClass("ReaMicroShelf233.Circle").getDeclaredField("a").get(null)
        val clipped = loader.hostClass("ReaMicroShelf233.Clip")
            .getDeclaredMethod("a", modifierType, loader.hostClass("androidx.compose.ui.graphics.Shape"))
            .invoke(null, size(padded, udp(36)), circle)
        val clickable = ui.clickable(requireNotNull(clipped), callback)
        val iconModifier = size(ui.padding(clickable, udp(8), udp(8), udp(8), udp(8)), udp(20))
        loader.hostClass("androidx.compose.material3.IconKt").hostMethodsNamed("Icon-ww6aTOc")
            .single { it.parameterCount == 7 && it.parameterTypes[0] == icon.javaClass }
            .invoke(null, icon, label, iconModifier, color, composer, 0, 0)
    }

    private fun vector(name: String, path: String): Any = icons.getOrPut(name) {
        B4DiscoverVector.build(loader, name, listOf(0xff000000L to path), 24f, 24f, false)
    }

    private fun updateIcon(): Any = vector("Shelf.Update",
        "M17.65,6.35 L15,9 L22,9 L22,2 L19.07,4.93 C17.26,3.12 14.76,2 12,2 C6.48,2 2,6.48 2,12 L4,12 C4,7.58 7.58,4 12,4 C14.21,4 16.21,4.9 17.65,6.35 Z M6.35,17.65 L9,15 L2,15 L2,22 L4.93,19.07 C6.74,20.88 9.24,22 12,22 C17.52,22 22,17.52 22,12 L20,12 C20,16.42 16.42,20 12,20 C9.79,20 7.79,19.1 6.35,17.65 Z")

    private fun styleIcon(grid: Boolean): Any = if (grid) vector("Shelf.List",
        "M3,4 H21 V7 H3 Z M3,10 H21 V13 H3 Z M3,16 H21 V19 H3 Z")
    else vector("Shelf.Grid", "M3,3 H10 V10 H3 Z M14,3 H21 V10 H14 Z M3,14 H10 V21 H3 Z M14,14 H21 V21 H14 Z")

    private fun session(): Any? = home.get()?.let {
        it.javaClass.hostField("repository").get(it)?.let { shelf -> shelf.javaClass.hostField("session").get(shelf) }
    }

    private fun preferenceKey(): Any =
        loader.hostClass("ReaMicroHost.PreferenceKey").getConstructor(String::class.java)
            .newInstance("bookshelf_type")

    private fun firstValue(flow: Any): Any? {
        val first = loader.hostClass("ReaMicroShelf233.FlowFirst").getDeclaredMethod(
            "c", loader.hostClass("kotlinx.coroutines.flow.Flow"),
            loader.hostClass("kotlin.coroutines.Continuation"))
        return HostCoroutines.invokeTimed(loader, first, null, 15_000L, flow)
    }

    private fun readStyle(session: Any): Int {
        val store = session.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(session)
        val flow = XposedHelpers.callMethod(store, "getData")
        val prefs = firstValue(requireNotNull(flow)) ?: error("阅读设置未就绪")
        val key = preferenceKey()
        return (prefs.javaClass.hostAbiMethod("c", key.javaClass).invoke(prefs, key) as? Number)?.toInt() ?: 0
    }

    private fun refreshStyle() {
        val session = session() ?: return
        Thread({
            runCatching { readStyle(session) }.onSuccess { value ->
                main.post { XposedHelpers.callMethod(styleState, "setValue", value) }
            }.onFailure { XposedBridge.logError("ReaMicro shelf style read failed: $it", it) }
        }, "ReaMicroShelfStyleRead").start()
    }

    private fun toggleStyle() {
        val session = session() ?: return toast("书架设置暂未就绪")
        if (!styleChanging.compareAndSet(false, true)) return
        Thread({
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
        if (!BookshelfCompletionPolicy.autoUpdate(settingsProvider())) return
        if (!startupClaimed.compareAndSet(false, true)) return
        main.postDelayed({ startBatch("startup") }, 2000)
    }

    private fun startBatch(reason: String) {
        val settings = settingsProvider()
        if (!settings.moduleEnabled || !settings.shelfBatchUpdate) return
        if (reason == "startup" && !BookshelfCompletionPolicy.autoUpdate(settings)) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            val results = ArrayList<OnlineChapterUpdateOutcome>()
            var total = 0
            var errorMessage: String? = null
            try {
                val repository = home.get()?.let { it.javaClass.hostField("repository").get(it) }
                    ?: drive.awaitBookshelfRepository() ?: error("书架服务未就绪")
                val uid = drive.currentUserId(repository) ?: error("当前账号未就绪")
                val currentGroup = if (settings.shelfCurrentOnly) {
                    val viewModel = home.get() ?: error("当前书架尚未就绪")
                    if (showingShelf == false) {
                        // 首页的当前阅读流跟随用户当前书籍，不能使用上次书架分页位置代替。
                        val readingFlow = viewModel.javaClass.getDeclaredField("y").apply { isAccessible = true }.get(viewModel)
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
                        drive.currentUserId(repository) != uid) break
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
            } catch (error: Throwable) {
                XposedBridge.logError("ReaMicro shelf batch failed", error)
                errorMessage = error.message ?: "书架更新失败"
            } finally {
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
    }

    private fun number(book: Any, name: String): Long = (XposedHelpers.callMethod(book, name) as Number).toLong()
    private fun toast(message: String) = main.post {
        activityProvider()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }.let { Unit }
}
