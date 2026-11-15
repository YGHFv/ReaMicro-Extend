package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.reamicro.fix.discover.DiscoverKind
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.hook.settings.SettingsDialogButtonRole
import com.reamicro.fix.online.EnhancedOnlineParser
import com.reamicro.fix.online.EnhancedSourceActionBook
import com.reamicro.fix.online.EnhancedSourceActionSelection
import com.reamicro.fix.online.OnlineParserMode
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceTask
import com.reamicro.fix.online.addBookAction
import com.reamicro.fix.online.searchAction
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask

/** Source UI actions use the host's existing settings/book panels and import transaction. */
internal object HostSourceActions {
    private val main by lazy { ModuleResources.handler(Looper.getMainLooper()) }
    private val panels = linkedSetOf<ActionPanel>()
    private val executor by lazy {
        ModuleResources.pool(2) { task -> Thread(task, "ReaMicroSourceAction").apply { isDaemon = true } }
    }

    fun dispatch(caller: OnlineSourceEntry, name: String, url: String?, title: String?, origin: String?) {
        OnlineSourceStore.requireCurrent(caller)
        val generation = OnlineParserMode.generation
        check(OnlineParserMode.isEnhanced && ModuleResources.accepting) { "增强解析已关闭" }
        check(name in setOf("login", "explore", "search", "searchBook", "addBook")) {
            "阅微未提供对应的阅读应用页面：$name"
        }
        check(main.post {
            if (!OnlineSourceStore.isCurrent(caller) || generation != OnlineParserMode.generation) return@post
            val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return@post
            val activity = hook.activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@post
            if (name == "login" && panels.any { it.isLoginFor(activity, caller) }) {
                hook.showToast("已在登录界面")
                return@post
            }
            if (panels.size >= 3) {
                hook.showToast("请先关闭一个书源操作面板")
                return@post
            }
            var panel: ActionPanel? = null
            runCatching {
                ActionPanel(hook, activity, caller, generation, name, url, title, origin).also {
                    panel = it
                    it.open()
                }
            }.onFailure {
                panel?.abort()
                HookRuntime.logError("ReaMicro source action failed", it)
                hook.showToast(it.message ?: "无法打开书源操作")
            }
        }) { "宿主书源操作主线程已退出" }
    }

    private class ActionPanel(
        private val hook: ReaMicroSettingsHook,
        private val activity: Activity,
        private val caller: OnlineSourceEntry,
        private val generation: Long,
        private val action: String,
        private val url: String?,
        private val title: String?,
        private val origin: String?,
    ) {
        private val dialog = hook.settingsDialog(activity)
        private val colors = hook.SettingsDialogColors(activity)
        private val card = hook.settingsDialogCard(activity, colors)
        private val status = hook.settingsDialogHint(activity, "正在读取书源…", colors)
        private val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val next = hook.settingsDialogButton(activity, "加载下一页", colors).apply { visibility = View.GONE }
        private val close = hook.settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral)
        private val entries = linkedMapOf<String, EnhancedSourceActionBook>()
        private var page = 0
        private var epoch = 0
        private var closed = false
        private var pending: FutureTask<Unit>? = null
        private var operation: OnlineSourceTask? = null
        private var sourceObserver: AutoCloseable? = null
        private var modeObserver: AutoCloseable? = null
        private var parentPage: AutoCloseable? = null
        private var allSources: List<OnlineSourceEntry> = emptyList()
        private var enabledSources: List<OnlineSourceEntry> = emptyList()
        private var selectedSources: List<OnlineSourceEntry> = emptyList()
        private var exploreKind: DiscoverKind? = null
        private var keyword = title.orEmpty()
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(owner: Activity) {
                if (owner === activity) { dialog.dismiss(); release() }
            }
            override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(owner: Activity) = Unit
            override fun onActivityResumed(owner: Activity) = Unit
            override fun onActivityPaused(owner: Activity) = Unit
            override fun onActivityStopped(owner: Activity) = Unit
            override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
        }

        private fun current(): Boolean = !closed && ModuleResources.accepting && !activity.isFinishing &&
            !activity.isDestroyed && generation == OnlineParserMode.generation && OnlineSourceStore.isCurrent(caller)

        fun abort() { dialog.dismiss(); release() }
        fun isLoginFor(owner: Activity, source: OnlineSourceEntry): Boolean =
            current() && action == "login" && activity === owner && caller.id == source.id

        fun open() {
            panels += this
            parentPage = HostSourceBrowserBridge.suspendForUi(activity, caller.sourceUrl)
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            sourceObserver = OnlineSourceStore.observeChanges {
                if (!OnlineSourceStore.isCurrent(caller)) main.post { dialog.dismiss() }
            }
            modeObserver = OnlineParserMode.observeChanges { main.post { dialog.dismiss() } }
            val weak = WeakReference(this)
            ModuleResources.cleanup { weak.get()?.let { owner ->
                HostGeneration.onMain { owner.dialog.dismiss(); owner.release() }
            } }
            with(hook) {
                card.addView(settingsDialogTitle(activity, when (action) {
                    "login" -> "书源登录"
                    "addBook" -> "读取书籍链接"
                    "explore" -> title?.takeIf(String::isNotBlank) ?: caller.name
                    else -> "搜索：$keyword"
                }, colors))
                card.addView(status)
                card.addView(results)
                card.addView(next)
                card.addView(close)
                close.setOnClickListener { dialog.dismiss() }
                next.setOnClickListener { loadPage(page + 1) }
                dialog.setOnDismissListener { release() }
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }
            submit({
                val all = OnlineSourceStore.list(activity.applicationContext)
                all to enabledOnlineCompletionSources(activity.applicationContext)
            }) { (all, enabled) ->
                allSources = all
                enabledSources = enabled
                when (action) {
                    "login" -> openLogin()
                    "addBook" -> submit({
                        EnhancedOnlineParser.addBookAction(requireNotNull(url) { "url不能为空" }, all, enabled)
                    }) { entry ->
                        showBooks(listOf(entry))
                        status.text = "已读取书籍详情，请选择下载方式。"
                        showBook(entry)
                    }
                    "explore" -> {
                        val selected = EnhancedSourceActionSelection.source(caller, all, origin)
                        selectedSources = listOf(selected)
                        val provided = url?.takeIf(String::isNotBlank)
                        if (provided == null) {
                            submit({ EnhancedOnlineParser.kinds(selected) }) { kinds ->
                                hook.openEnhancedDiscoverControls(DiscoverSource(selected, kinds))
                                status.text = "已打开该源发现分类。"
                            }
                        } else {
                            exploreKind = DiscoverKind(title ?: selected.name, provided)
                            loadPage(1)
                        }
                    }
                    "search", "searchBook" -> {
                        val scope = if (action == "searchBook") origin else origin?.let { target ->
                            all.firstOrNull { it.sourceUrl == target }
                                ?.let { "${it.name.replace(":", "")}::${it.sourceUrl}" }
                        }
                        selectedSources = EnhancedSourceActionSelection.searchSources(all, enabled, scope)
                        check(keyword.isNotBlank()) { "搜索词不能为空" }
                        check(selectedSources.isNotEmpty()) { "没有可搜索的已启用书源" }
                        loadPage(1)
                    }
                }
            }
        }

        private fun openLogin() {
            val source = EnhancedSourceActionSelection.source(caller, allSources, origin)
            if (source.loginUrl.isBlank()) {
                status.text = "源未配置登录"
                return
            }
            status.text = "请在登录面板中完成操作。"
            hook.openEnhancedOnlineSourceLogin(source, enableFlow = false) { success ->
                if (current()) status.text = if (success) "登录操作已完成。" else "登录操作已结束。"
            }
        }

        private fun loadPage(requestedPage: Int) {
            next.isEnabled = false
            status.text = if (action == "explore") "正在读取第 $requestedPage 页…" else "正在搜索第 $requestedPage 页…"
            val selected = selectedSources.toList()
            val known = entries.keys.toSet()
            submit({
                val found = arrayListOf<EnhancedSourceActionBook>()
                val failures = arrayListOf<String>()
                selected.forEach { source ->
                    TaskCancellation.checkCurrent()
                    runCatching {
                        OnlineSourceTask(source).use { task -> task.run {
                            val kind = exploreKind
                            if (kind != null) EnhancedOnlineParser.explore(source, kind, requestedPage).map {
                                EnhancedSourceActionBook(source, it)
                            } else EnhancedOnlineParser.searchAction(source, keyword, requestedPage)
                        } }
                    }.onSuccess { found += it }.onFailure {
                        TaskCancellation.rethrowIfCancelled(it)
                        failures += "${source.name}：${it.message ?: "解析失败"}"
                    }
                }
                found to failures
            }) { (found, failures) ->
                // A failed source must retry the same page instead of skipping it.
                // Results already received from other sources are identity-deduplicated.
                page = if (failures.isEmpty()) requestedPage else requestedPage - 1
                showBooks(found)
                val added = entries.keys.count { it !in known }
                status.text = buildString {
                    append("已加载 ${entries.size} 本")
                    if (added == 0) append("，本页没有新增结果")
                    if (failures.isNotEmpty()) append("\n").append(failures.joinToString("\n"))
                }
                next.visibility = if (added > 0 || failures.isNotEmpty()) View.VISIBLE else View.GONE
                next.text = if (failures.isEmpty()) "加载下一页" else "重试第 $requestedPage 页"
                next.isEnabled = true
            }
        }

        private fun showBooks(books: List<EnhancedSourceActionBook>) {
            books.forEach { entry ->
                if (!OnlineSourceStore.isCurrent(entry.source)) return@forEach
                val key = "${entry.source.id}|${entry.book.key}"
                if (entries.putIfAbsent(key, entry) != null) return@forEach
                val label = listOf(entry.book.name, entry.book.author, entry.source.name)
                    .filter(String::isNotBlank).joinToString(" · ")
                results.addView(hook.settingsDialogButton(activity, label, colors).apply {
                    setOnClickListener {
                        if (current() && OnlineSourceStore.isCurrent(entry.source)) showBook(entry)
                        else hook.showToast("书源或解析方式已变化，请重新打开")
                    }
                })
            }
        }

        private fun showBook(entry: EnhancedSourceActionBook) {
            // The existing panel offers on-demand/full download and then invokes
            // the established host import transaction, including overwrite checks.
            hook.openDiscoverBookDialog(activity, DiscoverSource(entry.source, emptyList()), entry.book)
        }

        private fun <T> submit(work: () -> T, publish: (T) -> Unit) {
            if (!current()) return
            val ticket = ++epoch
            operation?.close()
            pending?.cancel(true)
            val guard = OnlineSourceTask(caller)
            operation = guard
            val task = FutureTask<Unit> {
                val result = runCatching { guard.run(work) }
                main.post {
                    if (!current() || ticket != epoch || !guard.isCurrent) return@post
                    result.onSuccess {
                        runCatching { publish(it) }.onFailure(::showError)
                    }.onFailure(::showError)
                }
            }
            pending = task
            runCatching { executor.execute(task) }.onFailure(::showError)
        }

        private fun showError(error: Throwable) {
            status.text = error.message ?: "书源操作失败"
            next.isEnabled = true
        }

        private fun release() {
            if (closed) return
            closed = true
            epoch++
            operation?.close(); operation = null
            pending?.cancel(true); pending = null
            sourceObserver?.close(); sourceObserver = null
            modeObserver?.close(); modeObserver = null
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            parentPage?.close(); parentPage = null
            executor.purge()
            entries.clear()
            panels -= this
        }
    }
}
