package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.core.HostAndroidViews
import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.settings.InjectedRoute
import com.reamicro.fix.reload.ModuleUiRuntime
import com.reamicro.fix.settings.FontSettingsSnapshot
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookRuntime
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A structure editor belongs to the same saved native destination as the settings pages. */
internal class BookStructurePages(private val hook: ReaMicroSettingsHook) {
    private class Page(
        val route: InjectedRoute.BookStructure,
        val activity: Activity,
        val identity: AccountHostRuntime.Identity,
        var book: Any?,
        val settingsProvider: () -> ModuleSettingsSnapshot,
        val fontSettingsProvider: () -> FontSettingsSnapshot,
    ) {
        var version: Any? = null
        var loading: ModuleUiRuntime.OwnedScope? = null
        var panel: EpubBookPanel? = null
        var view: View? = null
        var root: File? = null
        var failure: String? = null
        var released = false
    }

    private val pages = linkedMapOf<String, Page>()
    private val native get() = requireNotNull(HostUiBindings.current)
    private val ui by lazy { HostNativeUi(hook.classLoader) }
    private val androidView by lazy { HostAndroidViews(hook.classLoader) }
    private var application: Application? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            pages.values.filter { it.activity === activity }.toList().forEach(::release)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun open(
        activity: Activity, book: Any, navScope: Any,
        settingsProvider: () -> ModuleSettingsSnapshot,
        fontSettingsProvider: () -> FontSettingsSnapshot,
    ): Boolean {
        check(Looper.myLooper() === Looper.getMainLooper())
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return false
        val symbols = requireNotNull(HostReaderExtrasBindings.importMetadata)
        val uid = (symbols.read("book.uid", book) as Number).toLong()
        val id = (symbols.read("book.id", book) as Number).toLong()
        val uuid = (symbols.read("book.uuid", book) as? String).orEmpty()
        if (uid != identity.accountId || id <= 0L || !validUuid(uuid)) return false
        val title = (symbols.read("book.title", book) as? String).orEmpty()
        val route = InjectedRoute.BookStructure(uid, id, uuid, title, UUID.randomUUID().toString())
        if (!AccountHostRuntime.isCurrentStorage(identity, activity)) return false
        track(activity)
        val page = Page(route, activity, identity, book, settingsProvider, fontSettingsProvider)
        page.version = ui.mutableState(0)
        pages[route.pageKey] = page
        return try {
            hook.openInjectedRouteViaHostNavigation(route, navScope).also {
                if (it) prepare(page) else release(page)
            }
        } catch (error: Throwable) {
            release(page)
            throw error
        }
    }

    fun render(route: InjectedRoute.BookStructure, composer: Any) {
        val activity = hook.activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val existing = pages[route.pageKey]?.takeIf { !it.released && it.activity === activity }
        val page = existing ?: restore(route, activity)
        if (page == null) {
            renderStatus(route, "账号或图书已变化，请返回后重新打开", composer)
            return
        }
        page.version?.let(native::stateValue)
        if (!AccountHostRuntime.isCurrentStorage(page.identity, activity) || !page.settingsProvider().canUseFileEdit) {
            renderStatus(route, "账号或设置已变化，请返回后重新打开", composer)
            return
        }
        val back = native.callback(native.type("function0")) {
            if (isCurrentPage(page) && page.panel?.handleBack() != true && isCurrentPage(page)) {
                hook.navigateBackFromInjectedRoute()
            }
        }
        ui.backHandler(isCurrentPage(page), back, composer)
        page.failure?.let { renderStatus(route, it, composer); return }
        if (page.book == null || page.root == null) {
            renderStatus(route, "", composer, loading = true)
            return
        }
        val factory = native.function(native.type("function1")) {
            page.view ?: EpubBookPanel(
                activity = activity,
                root = requireNotNull(page.root),
                bookTitle = route.bookTitle,
                book = requireNotNull(page.book),
                accountIdentity = page.identity,
                settingsProvider = page.settingsProvider,
                fontSettingsProvider = page.fontSettingsProvider,
            ).also { page.panel = it }.createEmbeddedView(
                close = { if (isCurrentPage(page)) hook.navigateBackFromInjectedRoute() },
                canAccess = { isCurrentPage(page) && page.settingsProvider().canUseFileEdit },
                isActive = { isCurrentPage(page) },
            ).also { page.view = it }
        }
        val update = native.callback(native.type("function1")) {}
        val onRelease = native.callback(native.type("function1")) { release(page) }
        androidView.render(factory, ui.fillSize(ui.modifier()), composer, update, onRelease)
    }

    private fun isCurrentPage(page: Page): Boolean =
        !page.released && pages[page.route.pageKey] === page &&
            hook.activityProvider() === page.activity &&
            !page.activity.isFinishing && !page.activity.isDestroyed &&
            hook.currentInjectedRoute() == page.route &&
            AccountHostRuntime.isCurrentStorage(page.identity, page.activity)

    private fun restore(route: InjectedRoute.BookStructure, activity: Activity): Page? {
        pages.remove(route.pageKey)?.let(::release)
        val identity = AccountHostRuntime.storageSnapshot(activity)?.takeIf { it.accountId == route.uid } ?: return null
        if (route.bookId <= 0L || !validUuid(route.uuid) || route.pageKey.isBlank()) return null
        track(activity)
        val page = Page(route, activity, identity, null, hook.settings::snapshot, hook.settings::fontSettings)
        page.version = ui.mutableState(0)
        pages[route.pageKey] = page
        prepare(page)
        return page
    }

    /** Opening and restoring share one IO path; no canonical/stat work runs in the navigation frame. */
    private fun prepare(page: Page) {
        val route = page.route
        val activity = page.activity
        val identity = page.identity
        page.loading = ModuleUiRuntime.scope("图书结构恢复").also { tasks ->
            tasks.scope.launch {
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val restored = withContext(ModuleUiRuntime.io) {
                        check(AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号已变化" }
                        val symbols = requireNotNull(HostReaderExtrasBindings.importMetadata)
                        val book = page.book ?: run {
                            val repository = requireNotNull(ReaMicroBookMetadataSync.currentBookshelfRepository()) { "书架暂时不可用" }
                            val dao = requireNotNull(symbols.read("repository.dao", repository))
                            requireNotNull(HostCoroutines.invokeTimed(hook.classLoader, symbols.method("find"), dao, 30_000L, route.bookId)) {
                                "图书已不存在"
                            }
                        }
                        check(symbols.type("book").isInstance(book) &&
                            (symbols.read("book.uid", book) as Number).toLong() == route.uid &&
                            symbols.read("book.uuid", book) == route.uuid) { "图书已变化" }
                        val root = resolveRoot(activity, route)
                        check(AccountHostRuntime.isCurrentStorage(identity, activity)) { "账号已变化" }
                        book to root
                    }
                    if (!page.released && pages[route.pageKey] === page) {
                        page.book = restored.first
                        page.root = restored.second
                        native.setState(requireNotNull(page.version), 1)
                        HookRuntime.log("ReaMicro structure prepare elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    HookRuntime.logError("ReaMicro structure page restore failed", error)
                    if (!page.released && pages[route.pageKey] === page) {
                        page.failure = "图书结构暂时不可用，请返回后重试"
                        native.setState(requireNotNull(page.version), 1)
                    }
                } finally {
                    tasks.close()
                    if (page.loading === tasks) page.loading = null
                }
            }
        }
    }

    private fun renderStatus(route: InjectedRoute.BookStructure, text: String, composer: Any, loading: Boolean = false) {
        val activity = hook.activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val page = pages[route.pageKey]
        val top = native.lambda(route.pageKey.hashCode(), native.callback(native.type("function2")) { args ->
            hook.renderHostTopBar(route.title, requireNotNull(args?.getOrNull(0))) {
                if (hook.activityProvider() === activity && !activity.isFinishing && !activity.isDestroyed &&
                    hook.currentInjectedRoute() == route && pages[route.pageKey] === page) {
                    hook.navigateBackFromInjectedRoute()
                }
            }
        })
        val body = native.lambda(route.pageKey.hashCode() xor 0x524D5354, native.callback(native.type("function3")) { args ->
            val inner = requireNotNull(args?.getOrNull(1))
            val content = native.callback(native.type("function3")) { child ->
                val childComposer = requireNotNull(child?.getOrNull(1))
                if (loading) {
                    val indicator = native.callback(native.type("function3")) { row ->
                        requireNotNull(HostBookUiBindings.backup).call("loading", 0, requireNotNull(row?.getOrNull(1)))
                    }
                    // Keep width before fillSize: the row is 40dp wide and fills the remaining
                    // height. The parent Column centers that row horizontally; CenterVertically
                    // centers the host indicator within it, matching the host's centered Box.
                    // ui.center() is a two-axis Alignment, not a Row Arrangement.
                    ui.row(ui.fillSize(ui.width(ui.modifier(), 40f)), indicator, childComposer,
                        alignment = ui.centerVertically())
                } else hook.renderHostText(text, childComposer)
            }
            val padded = ui.padding(ui.fillSize(ui.modifier()), requireNotNull(args?.firstOrNull()))
            val horizontal = if (loading) 0f else ui.dp(20)
            ui.column(ui.padding(padded, horizontal, ui.dp(16), horizontal, 0f),
                ui.top(), if (loading) ui.centerHorizontally() else ui.start(), content, inner)
        })
        ui.scaffold(top, hook.backgroundDim(composer), body, composer)
    }

    private fun resolveRoot(activity: Activity, route: InjectedRoute.BookStructure): File {
        check(validUuid(route.uuid)) { "图书目录无效" }
        val host = requireNotNull(HostBookUiBindings.metadata)
        val repository = ReaMicroBookMetadataSync.currentBookshelfRepository()?.takeIf(host.type("repository")::isInstance)
        val books = repository?.let { host.field("repository.storage").get(it) }?.let { storage ->
            host.method("books").invoke(storage, route.uid)?.let { host.method("toFile").invoke(it) as? File }
        } ?: File(File(activity.filesDir, route.uid.toString()), "books")
        val parent = books.canonicalFile
        return File(parent, route.uuid).canonicalFile.also {
            check(it.parentFile == parent && it.isDirectory) { "未找到图书文件目录" }
        }
    }

    private fun release(page: Page) {
        if (page.released) return
        page.released = true
        if (pages[page.route.pageKey] === page) pages.remove(page.route.pageKey)
        page.loading?.close()
        page.loading = null
        page.panel?.release()
        page.panel = null
        page.view = null
        page.book = null
        page.root = null
    }

    private fun track(activity: Activity) {
        if (application === activity.application) return
        application?.unregisterActivityLifecycleCallbacks(lifecycle)
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        application = activity.application
    }

    fun synchronize(routes: List<InjectedRoute>) {
        val present = routes.filterIsInstance<InjectedRoute.BookStructure>().map { it.pageKey }.toSet()
        // A mounted outgoing page lives until native AndroidView.onRelease finishes the transition.
        pages.values.filter { it.view == null && it.route.pageKey !in present }.toList().forEach(::release)
    }

    fun clear() {
        pages.values.toList().forEach(::release)
        pages.clear()
        application?.unregisterActivityLifecycleCallbacks(lifecycle)
        application = null
    }

    private fun validUuid(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' }
}
